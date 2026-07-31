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
    }

    /** Drop memory entry without changing DB status (e.g. after persisting PAUSED_*). */
    public void detach(UUID id) {
        running.remove(id);
    }

    /** Snapshot of in-memory task ids (for safe shutdown / PlugMan unload). */
    public Set<UUID> activeIds() {
        return Set.copyOf(running.keySet());
    }

    public void clear() {
        running.clear();
    }

    public boolean isActive(UUID id) {
        return running.containsKey(id);
    }

    /** Remove a running/queued task and return final applied count for settlement. */
    public TickResult abort(UUID id) {
        BuildTask task = running.remove(id);
        if (task == null) throw new IllegalArgumentException("Unknown running task");
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
        int changed = 0, skipped = 0;
        int applied = task.appliedCount();
        while (changed + skipped < blocksPerStep && task.cursor() < task.plan().mutations().size()) {
            BlockMutation mutation = task.plan().mutations().get(task.cursor());
            String current = world.stateAt(task.plan().world(), mutation.position());
            if (!current.equals(mutation.expectedState())) {
                skipped++;
            } else {
                WorldAccess.ValidationResult validation = world.mayMutate(task.playerId(), task.plan().world(), mutation, task.plan().operation());
                if (!validation.allowed()) skipped++;
                else {
                    WorldAccess.MutationResult result = world.mutate(task.playerId(), task.plan().world(), mutation, task.plan().operation());
                    if (result.changed()) {
                        changed++;
                        applied++;
                        audit.record(
                                task.playerId(),
                                task.playerName(),
                                task.plan().world(),
                                mutation,
                                task.plan().operation(),
                                result.breakAlreadyLogged());
                    } else skipped++;
                }
            }
            task = task.advance(task.cursor() + 1, applied, clock.instant());
        }
        boolean finished = task.cursor() == task.plan().mutations().size();
        if (finished) {
            task = task.transition(TaskStatus.COMPLETED, clock.instant());
            running.remove(id);
            repository.save(task);
        } else {
            running.put(id, task);
            tickCounter++;
            if (forceSave || tickCounter % saveInterval == 0) {
                repository.saveProgress(task);
            }
        }
        return new TickResult(task, changed, skipped, task.appliedCount(), finished);
    }

    public record TickResult(BuildTask task, int changed, int skipped, int totalApplied, boolean finished) {}
}
