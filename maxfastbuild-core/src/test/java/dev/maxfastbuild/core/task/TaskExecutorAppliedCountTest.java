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
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE,
                new Bounds(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)), mutations);
        Instant now = clock.instant();
        BuildTask task = new BuildTask(id, player, "Builder", plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
        executor.enqueue(task);

        TaskExecutor.TickResult mid = executor.tick(id, 2);
        assertThat(mid.totalApplied()).isEqualTo(2);
        assertThat(mid.finished()).isFalse();
        assertThat(repo.find(id).orElseThrow().appliedCount()).isEqualTo(2);
        assertThat(repo.find(id).orElseThrow().cursor()).isEqualTo(2);

        BuildTask paused = repo.find(id).orElseThrow().transition(TaskStatus.PAUSED_SHUTDOWN, now);
        repo.save(paused);
        BuildTask snapshot = executor.detachSnapshot(id);
        assertThat(snapshot.cursor()).isEqualTo(2);
        assertThat(snapshot.appliedCount()).isEqualTo(2);
        assertThat(executor.isActive(id)).isFalse();

        BuildTask resumed = repo.find(id).orElseThrow().transition(TaskStatus.QUEUED, now);
        executor.enqueue(resumed);
        TaskExecutor.TickResult done = executor.tick(id, 10);
        assertThat(done.finished()).isTrue();
        assertThat(done.totalApplied()).isEqualTo(3);
        assertThat(repo.find(id).orElseThrow().appliedCount()).isEqualTo(3);
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test void detachesAllPlayerTasksFromLatestMemoryState() {
        InMemoryRepo repo = new InMemoryRepo();
        StubWorld world = new StubWorld();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AuditService audit = new AuditService() {
            @Override public boolean available() { return true; }
            @Override public void record(UUID playerId, String playerName, String worldName, BlockMutation mutation, OperationKind kind) {}
        };
        TaskExecutor executor = new TaskExecutor(repo, world, audit, clock, 100);
        UUID player = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        BuildTask first = task(UUID.randomUUID(), player, clock.instant());
        BuildTask second = task(UUID.randomUUID(), player, clock.instant());
        BuildTask other = task(UUID.randomUUID(), otherPlayer, clock.instant());
        executor.enqueue(first);
        executor.enqueue(second);
        executor.enqueue(other);

        executor.tick(first.id(), 1);
        List<BuildTask> detached = executor.detachPlayerSnapshots(player);

        assertThat(detached).extracting(BuildTask::id).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(detached).filteredOn(t -> t.id().equals(first.id())).singleElement()
                .extracting(BuildTask::cursor, BuildTask::appliedCount).containsExactly(1, 1);
        assertThat(executor.activeCount(player)).isZero();
        assertThat(executor.isActive(first.id())).isFalse();
        assertThat(executor.isActive(second.id())).isFalse();
        assertThat(executor.isActive(other.id())).isTrue();
        assertThat(executor.activeCount(otherPlayer)).isEqualTo(1);
    }

    @Test void offlineValidationDoesNotConsumeTaskProgress() {
        InMemoryRepo repo = new InMemoryRepo();
        StubWorld world = new StubWorld();
        world.offline = true;
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        AuditService audit = new AuditService() {
            @Override public boolean available() { return true; }
            @Override public void record(UUID playerId, String playerName, String worldName, BlockMutation mutation, OperationKind kind) {}
        };
        TaskExecutor executor = new TaskExecutor(repo, world, audit, clock);
        BuildTask task = task(UUID.randomUUID(), UUID.randomUUID(), clock.instant());
        executor.enqueue(task);

        TaskExecutor.TickResult result = executor.tick(task.id(), 10);

        assertThat(result.finished()).isFalse();
        assertThat(result.changed()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.task().cursor()).isZero();
        assertThat(result.task().appliedCount()).isZero();
        assertThat(executor.isActive(task.id())).isTrue();
    }

    @Test void runtimeFailureStopsTaskAndPreservesAppliedProgress() {
        InMemoryRepo repo = new InMemoryRepo();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WorldAccess world = new WorldAccess() {
            int calls;
            @Override public String stateAt(String world, BlockPos position) { return "minecraft:air"; }
            @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
                return new ValidationResult(true, "");
            }
            @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
                if (++calls == 2) throw new IllegalArgumentException("REDSTONE_WIRE isn't an item");
                return new MutationResult(true, "");
            }
        };
        AuditService audit = new AuditService() {
            @Override public boolean available() { return true; }
            @Override public void record(UUID playerId, String playerName, String worldName, BlockMutation mutation, OperationKind kind) {}
        };
        TaskExecutor executor = new TaskExecutor(repo, world, audit, clock);
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        List<BlockMutation> mutations = List.of(
                new BlockMutation(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:chest"),
                new BlockMutation(new BlockPos(1, 64, 0), "minecraft:air", "minecraft:redstone_wire"),
                new BlockMutation(new BlockPos(2, 64, 0), "minecraft:air", "minecraft:stone"));
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE,
                new Bounds(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)), mutations);
        BuildTask task = new BuildTask(id, player, "Builder", plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant(), clock.instant(), null);
        executor.enqueue(task);

        TaskExecutor.TickResult result = executor.tick(id, 10);

        assertThat(result.finished()).isTrue();
        assertThat(result.task().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.task().cursor()).isEqualTo(1);
        assertThat(result.totalApplied()).isEqualTo(1);
        assertThat(result.task().failure()).contains("IllegalArgumentException").contains("REDSTONE_WIRE");
        assertThat(executor.isActive(id)).isFalse();
        assertThat(executor.activeCount(player)).isZero();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test void playerOfflineDoesNotConsumeTaskTail() {
        InMemoryRepo repo = new InMemoryRepo();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        WorldAccess world = new WorldAccess() {
            @Override public String stateAt(String world, BlockPos position) { return "minecraft:air"; }
            @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
                return new ValidationResult(false, "player_offline");
            }
            @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
                throw new AssertionError("mutate must not run while offline");
            }
        };
        TaskExecutor executor = new TaskExecutor(repo, world, new AuditService() {
            @Override public boolean available() { return true; }
            @Override public void record(UUID playerId, String playerName, String worldName, BlockMutation mutation, OperationKind kind) {}
        }, clock);
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        List<BlockMutation> mutations = List.of(
                new BlockMutation(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:stone"),
                new BlockMutation(new BlockPos(1, 64, 0), "minecraft:air", "minecraft:stone"));
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE,
                new Bounds(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)), mutations);
        executor.enqueue(new BuildTask(id, player, "Builder", plan, TaskStatus.QUEUED,
                0, 0, Set.of(), null, BigDecimal.ZERO, BigDecimal.ZERO, clock.instant(), clock.instant(), null));

        TaskExecutor.TickResult result = executor.tick(id, 10);

        assertThat(result.finished()).isFalse();
        assertThat(result.task().cursor()).isZero();
        assertThat(result.totalApplied()).isZero();
        assertThat(executor.isActive(id)).isTrue();
    }

    private static BuildTask task(UUID id, UUID player, Instant now) {
        List<BlockMutation> mutations = List.of(
                new BlockMutation(new BlockPos(0, 64, 0), "minecraft:air", "minecraft:stone"),
                new BlockMutation(new BlockPos(1, 64, 0), "minecraft:air", "minecraft:stone"));
        BuildPlan plan = new BuildPlan("world", OperationKind.PLACE,
                new Bounds(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)), mutations);
        return new BuildTask(id, player, "Builder", plan, TaskStatus.QUEUED, 0, 0, Set.of(), null,
                BigDecimal.ZERO, BigDecimal.ZERO, now, now, null);
    }

    private static final class InMemoryRepo implements TaskRepository {
        private final Map<UUID, BuildTask> tasks = new HashMap<>();
        @Override public void initialize() {}
        @Override public void save(BuildTask task) { tasks.put(task.id(), task); }
        @Override public void saveProgress(BuildTask task) { tasks.put(task.id(), task); }
        @Override public void flush() {}
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
        private boolean offline;
        private static String key(String world, BlockPos pos) { return world + ":" + pos.x() + "," + pos.y() + "," + pos.z(); }
        @Override public String stateAt(String world, BlockPos position) {
            return states.getOrDefault(key(world, position), "minecraft:air");
        }
        @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
            if (offline) return new ValidationResult(false, "player_offline");
            return new ValidationResult(true, "");
        }
        @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
            states.put(key(world, mutation.position()), mutation.targetState());
            return new MutationResult(true, "");
        }
    }
}
