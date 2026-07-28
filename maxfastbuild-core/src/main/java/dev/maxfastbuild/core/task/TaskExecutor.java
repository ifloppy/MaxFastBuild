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
    private final Map<UUID, BuildTask> running = new ConcurrentHashMap<>();
    /** Successful mutations applied per task (for partial refunds). */
    private final Map<UUID, Integer> applied = new ConcurrentHashMap<>();

    public TaskExecutor(TaskRepository repository, WorldAccess world, AuditService audit, Clock clock) {
        this.repository = repository; this.world = world; this.audit = audit; this.clock = clock;
    }

    public void enqueue(BuildTask task) {
        if (task.status() != TaskStatus.QUEUED) throw new IllegalArgumentException("Only queued tasks can be enqueued");
        repository.save(task);
        running.put(task.id(), task);
        applied.putIfAbsent(task.id(), 0);
    }

    public int appliedCount(UUID id) {
        return applied.getOrDefault(id, 0);
    }

    /** Remove a running/queued task and return final applied count for settlement. */
    public TickResult abort(UUID id) {
        BuildTask task = running.remove(id);
        if (task == null) throw new IllegalArgumentException("Unknown running task");
        int totalApplied = applied.getOrDefault(id, 0);
        applied.remove(id);
        if (task.status() == TaskStatus.QUEUED || task.status() == TaskStatus.RUNNING) {
            task = task.transition(TaskStatus.CANCELLING, clock.instant());
        }
        if (task.status() == TaskStatus.CANCELLING) {
            task = task.transition(TaskStatus.CANCELLED, clock.instant());
        }
        repository.save(task);
        return new TickResult(task, 0, 0, totalApplied, true);
    }

    public TickResult tick(UUID id, int blocksPerStep) {
        BuildTask task = Objects.requireNonNull(running.get(id), "Unknown running task");
        if (task.status() == TaskStatus.QUEUED) task = task.transition(TaskStatus.RUNNING, clock.instant());
        int changed = 0, skipped = 0;
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
                        applied.merge(id, 1, Integer::sum);
                        audit.record(task.playerId(), task.playerName(), task.plan().world(), mutation, task.plan().operation());
                    } else skipped++;
                }
            }
            task = task.advance(task.cursor() + 1, clock.instant());
        }
        boolean finished = task.cursor() == task.plan().mutations().size();
        if (finished) {
            task = task.transition(TaskStatus.COMPLETED, clock.instant());
            running.remove(id);
        } else running.put(id, task);
        repository.save(task);
        int totalApplied = applied.getOrDefault(id, 0);
        if (finished) applied.remove(id);
        return new TickResult(task, changed, skipped, totalApplied, finished);
    }

    public record TickResult(BuildTask task, int changed, int skipped, int totalApplied, boolean finished) {}
}
