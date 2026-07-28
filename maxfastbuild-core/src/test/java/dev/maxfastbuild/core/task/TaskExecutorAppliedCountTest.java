package dev.maxfastbuild.core.task;

import dev.maxfastbuild.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TaskExecutorAppliedCountTest {
    @Test void persistsAppliedCountAcrossDetachAndReenqueue() {
        InMemoryRepo repo = new InMemoryRepo();
        StubWorld world = new StubWorld();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AuditService audit = new AuditService() {
            @Override public boolean available() { return true; }
            @Override public void record(UUID playerId, String playerName, String worldName, BlockMutation mutation, OperationKind kind) {}
        };
        TaskExecutor executor = new TaskExecutor(repo, world, audit, clock);

        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        List<BlockMutation> mutations = List.of(
                new BlockMutation(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:stone"),
                new BlockMutation(new BlockPos(1, 64, 0), "minecraft:air", "minecraft:stone"),
                new BlockMutation(new BlockPos(2, 64, 0), "minecraft:air", "minecraft:stone"));
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE, new Bounds(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)), mutations);
        Instant now = clock.instant();
        BuildTask task = new BuildTask(id, player, "Builder", plan, TaskStatus.QUEUED, 0, 0, null, BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
        executor.enqueue(task);

        TaskExecutor.TickResult mid = executor.tick(id, 2);
        assertThat(mid.totalApplied()).isEqualTo(2);
        assertThat(mid.finished()).isFalse();
        assertThat(repo.find(id).orElseThrow().appliedCount()).isEqualTo(2);
        assertThat(repo.find(id).orElseThrow().cursor()).isEqualTo(2);

        BuildTask paused = repo.find(id).orElseThrow().transition(TaskStatus.PAUSED_SHUTDOWN, now);
        repo.save(paused);
        executor.detach(id);
        assertThat(executor.isActive(id)).isFalse();

        BuildTask resumed = repo.find(id).orElseThrow().transition(TaskStatus.QUEUED, now);
        executor.enqueue(resumed);
        TaskExecutor.TickResult done = executor.tick(id, 10);
        assertThat(done.finished()).isTrue();
        assertThat(done.totalApplied()).isEqualTo(3);
        assertThat(repo.find(id).orElseThrow().appliedCount()).isEqualTo(3);
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
    }

    private static final class InMemoryRepo implements TaskRepository {
        private final Map<UUID, BuildTask> tasks = new HashMap<>();
        @Override public void initialize() {}
        @Override public void save(BuildTask task) { tasks.put(task.id(), task); }
        @Override public Optional<BuildTask> find(UUID id) { return Optional.ofNullable(tasks.get(id)); }
        @Override public List<BuildTask> recoverable() { return List.copyOf(tasks.values()); }
        @Override public int activeCount(UUID playerId) {
            return (int) tasks.values().stream().filter(t -> t.playerId().equals(playerId)
                    && t.status() != TaskStatus.COMPLETED && t.status() != TaskStatus.FAILED && t.status() != TaskStatus.CANCELLED).count();
        }
        @Override public void close() {}
    }

    private static final class StubWorld implements WorldAccess {
        private final Map<String, String> states = new HashMap<>();
        private static String key(String world, BlockPos pos) { return world + ":" + pos.x() + "," + pos.y() + "," + pos.z(); }
        @Override public String stateAt(String world, BlockPos position) {
            return states.getOrDefault(key(world, position), "minecraft:air");
        }
        @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
            return new ValidationResult(true, "");
        }
        @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
            states.put(key(world, mutation.position()), mutation.targetState());
            return new MutationResult(true, "");
        }
    }
}
