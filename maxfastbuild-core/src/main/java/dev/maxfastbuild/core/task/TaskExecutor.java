package dev.maxfastbuild.core.task;

import dev.maxfastbuild.api.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskExecutor {
    private final TaskRepository repository;
    private final WorldAccess world;
    private final AuditService audit;
    private final Clock clock;
    private final int saveInterval;
    private final Map<UUID, BuildTask> running = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerActiveCounts = new ConcurrentHashMap<>();
    private int tickCounter;

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock, int saveInterval) {
        this.repository = repository; this.world = world; this.audit = audit; this.clock = clock;
        this.saveInterval = Math.max(1, saveInterval);
    }

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock) {
        this(repository, world, audit, clock, 1);
    }

    public void enqueue(BuildTask task) {
        if (task.status() != TaskStatus.QUEUED) throw new IllegalArgumentException("Only queued tasks can be enqueued");
        repository.save(task);
        running.put(task.id(), task);
        playerActiveCounts.merge(task.playerId(), 1, Integer::sum);
    }

    /** Drop memory entry without changing DB status (e.g. after persisting PAUSED_*). */
    public void detach(UUID id) {
        BuildTask removed = running.remove(id);
        if (removed != null) decrementPlayerCount(removed.playerId());
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
        if (task.status() == TaskStatus.QUEUED) task = task.transition(TaskStatus.RUNNING, clock.instant());
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
        while (changed + skipped < blocksPerStep && cursor < size) {
            BlockMutation mutation = mutations.get(cursor);
            String current = world.stateAt(worldName, mutation.position());
            if (!current.equals(mutation.expectedState())) {
                skipped++;
            } else {
                WorldAccess.ValidationResult validation = world.mayMutate(playerId, worldName, mutation, operation);
                if (!validation.allowed()) skipped++;
                else {
                    WorldAccess.MutationResult result = world.mutate(playerId, worldName, mutation, operation);
                    if (result.changed()) {
                        changed++;
                        applied++;
                        audit.record(playerId, playerName, worldName, mutation, operation, result.breakAlreadyLogged());
                    } else skipped++;
                }
            }
            cursor++;
        }
        if (cursor != task.cursor()) {
            task = task.advance(cursor, applied, clock.instant());
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

    private void decrementPlayerCount(UUID playerId) {
        playerActiveCounts.computeIfPresent(playerId, (k, v) -> v <= 1 ? null : v - 1);
    }

    public record TickResult(BuildTask task, int changed, int skipped, int totalApplied, boolean finished) {}
}
