package dev.maxfastbuild.core.task;

import dev.maxfastbuild.api.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskExecutor {
    private final TaskRepository repository;
    private final WorldAccess world;
    private final AuditService audit;
    private final Clock clock;
    private final int saveInterval;
    /**
     * When true (default), a mutation whose current world state no longer matches its expected
     * state is still applied over whatever is there (replace-by-default). When false the old
     * strict behavior is kept: such mutations are skipped. Both modes continue past failures.
     */
    private final boolean replaceMismatched;
    private final Map<UUID, BuildTask> running = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerActiveCounts = new ConcurrentHashMap<>();
    private int tickCounter;

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock, int saveInterval) {
        this(repository, world, audit, clock, saveInterval, true);
    }

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock) {
        this(repository, world, audit, clock, 1, true);
    }

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock, int saveInterval, boolean replaceMismatched) {
        this.repository = repository; this.world = world; this.audit = audit; this.clock = clock;
        this.saveInterval = Math.max(1, saveInterval);
        this.replaceMismatched = replaceMismatched;
    }

    public void enqueue(BuildTask task) {
        if (task.status() != TaskStatus.QUEUED) throw new IllegalArgumentException("Only queued tasks can be enqueued");
        repository.save(task);
        running.put(task.id(), task);
        playerActiveCounts.merge(task.playerId(), 1, Integer::sum);
    }

    /** Drop memory entry without changing DB status (e.g. after persisting PAUSED_*). */
    public void detach(UUID id) {
        detachSnapshot(id);
    }

    /** Remove and return the latest in-memory task snapshot for durable pause/close handling. */
    public BuildTask detachSnapshot(UUID id) {
        BuildTask removed = running.remove(id);
        if (removed != null) decrementPlayerCount(removed.playerId());
        return removed;
    }

    /** Remove and return every latest in-memory task snapshot owned by a player. */
    public List<BuildTask> detachPlayerSnapshots(UUID playerId) {
        List<BuildTask> removed = new ArrayList<>();
        for (Map.Entry<UUID, BuildTask> entry : running.entrySet()) {
            BuildTask task = entry.getValue();
            if (!task.playerId().equals(playerId)) continue;
            if (running.remove(entry.getKey(), task)) {
                decrementPlayerCount(playerId);
                removed.add(task);
            }
        }
        return List.copyOf(removed);
    }

    /** Latest immutable in-memory snapshot, or null when the task is not active. */
    public BuildTask snapshot(UUID id) {
        return running.get(id);
    }

    /** Snapshot of in-memory task ids (for safe shutdown / PlugMan unload). */
    public Set<UUID> activeIds() {
        return Set.copyOf(running.keySet());
    }

    public void clear() {
        running.clear();
        playerActiveCounts.clear();
    }

    public boolean isActive(UUID id) {
        return running.containsKey(id);
    }

    public int activeCount(UUID playerId) {
        return playerActiveCounts.getOrDefault(playerId, 0);
    }

    /** Remove a running/queued task and return final applied count for settlement. */
    public TickResult abort(UUID id) {
        BuildTask task = running.remove(id);
        if (task == null) throw new IllegalArgumentException("Unknown running task");
        decrementPlayerCount(task.playerId());
        if (task.status() == TaskStatus.QUEUED || task.status() == TaskStatus.RUNNING) {
            task = task.transition(TaskStatus.CANCELLING, clock.instant());
        }
        if (task.status() == TaskStatus.CANCELLING) {
            task = task.transition(TaskStatus.CANCELLED, clock.instant());
        }
        repository.save(task);
        return new TickResult(task, 0, 0, task.appliedCount(), true);
    }

    public TickResult tick(UUID id, int blocksPerStep) {
        return tick(id, blocksPerStep, false);
    }

    public TickResult tick(UUID id, int blocksPerStep, boolean forceSave) {
        BuildTask task = Objects.requireNonNull(running.get(id), "Unknown running task");
        if (task.status() == TaskStatus.QUEUED) {
            task = task.transition(TaskStatus.RUNNING, clock.instant());
            // Keep the in-memory snapshot authoritative even if execution fails before this tick saves.
            running.put(id, task);
        }
        BuildPlan plan = task.plan();
        List<BlockMutation> mutations = plan.mutations();
        int size = mutations.size();
        String worldName = plan.world();
        OperationKind operation = plan.operation();
        UUID playerId = task.playerId();
        String playerName = task.playerName();
        int changed = 0, skipped = 0;
        int applied = task.appliedCount();
        int cursor = task.cursor();
        Set<Integer> skippedIndices = new HashSet<>();
        List<BlockPos> changedPositions = new ArrayList<>();
        Throwable failure = null;
        boolean deferredStarted = false;

        // Litematica-style batch physics: place every block in this step with NO_UPDATE, then run
        // one notification pass over the placed positions so redstone computes against the final
        // layout of the batch instead of a partially-built circuit.
        try {
            deferredStarted = true;
            world.beginDeferredPhysics();
            while (changed + skipped < blocksPerStep && cursor < size) {
                BlockMutation mutation = mutations.get(cursor);
                if (!replaceMismatched) {
                    String current = world.stateAt(worldName, mutation.position());
                    if (!current.equals(mutation.expectedState())) {
                        skipped++;
                        skippedIndices.add(cursor);
                        cursor++;
                        continue;
                    }
                }
                WorldAccess.ValidationResult validation = world.mayMutate(playerId, worldName, mutation, operation);
                if (!validation.allowed()) {
                    // Offline is temporary. Never consume the remaining task as skipped while its
                    // owner is disconnected; Paper will detach it as PAUSED_OFFLINE.
                    if ("player_offline".equals(validation.reason())) break;
                    skipped++;
                    skippedIndices.add(cursor);
                    cursor++;
                    continue;
                }

                WorldAccess.MutationResult result = world.mutate(playerId, worldName, mutation, operation);
                if (!result.changed()) {
                    if ("player_offline".equals(result.reason())) break;
                    skipped++;
                    skippedIndices.add(cursor);
                    cursor++;
                    continue;
                }

                // The world changed successfully. Advance the cursor BEFORE calling audit hooks so
                // an audit/plugin exception cannot make settlement refund or retry an already-applied block.
                changed++;
                applied++;
                changedPositions.add(mutation.position());
                cursor++;
                audit.record(playerId, playerName, worldName, mutation, operation,
                        result.breakAlreadyLogged(), result.placeEventAlreadyLogged());
            }
        } catch (RuntimeException | LinkageError ex) {
            failure = ex;
        } finally {
            if (deferredStarted) {
                try {
                    world.endDeferredPhysics();
                } catch (RuntimeException | LinkageError ex) {
                    if (failure == null) failure = ex;
                    else failure.addSuppressed(ex);
                }
            }
        }

        // Settle blocks that really changed even when a later mutation/audit failed. This keeps the
        // partial build internally consistent before it is refunded and marked FAILED.
        if (!changedPositions.isEmpty()) {
            try {
                world.settlePlacements(worldName, changedPositions);
            } catch (RuntimeException | LinkageError ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (cursor != task.cursor()) {
            task = task.advance(cursor, applied, skippedIndices, clock.instant());
        }

        if (failure != null) {
            Instant now = clock.instant();
            task = task.transition(TaskStatus.FAILED, now).withFailure(failureMessage(failure), now);
            running.remove(id);
            decrementPlayerCount(playerId);
            repository.save(task);
            repository.flush();
            return new TickResult(task, changed, skipped, task.appliedCount(), true);
        }

        boolean finished = cursor == size;
        if (finished) {
            task = task.transition(TaskStatus.COMPLETED, clock.instant());
            running.remove(id);
            decrementPlayerCount(playerId);
            repository.save(task);
            repository.flush();
        } else {
            running.put(id, task);
            tickCounter++;
            if (forceSave || tickCounter % saveInterval == 0) {
                repository.saveProgress(task);
            }
        }
        return new TickResult(task, changed, skipped, task.appliedCount(), finished);
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private void decrementPlayerCount(UUID playerId) {
        playerActiveCounts.computeIfPresent(playerId, (k, v) -> v <= 1 ? null : v - 1);
    }

    public record TickResult(BuildTask task, int changed, int skipped, int totalApplied, boolean finished) {}
}
