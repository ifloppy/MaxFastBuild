package dev.maxfastbuild.core.task;

import dev.maxfastbuild.api.BuildPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record BuildTask(UUID id, UUID playerId, String playerName, BuildPlan plan, TaskStatus status,
                        int cursor, int appliedCount, Set<Integer> skipped, String escrowId, BigDecimal charged, BigDecimal refunded,
                        Instant createdAt, Instant updatedAt, String failure) {
    public BuildTask {
        Objects.requireNonNull(id); Objects.requireNonNull(playerId); Objects.requireNonNull(playerName);
        Objects.requireNonNull(plan); Objects.requireNonNull(status); Objects.requireNonNull(charged);
        Objects.requireNonNull(refunded); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (cursor < 0 || cursor > plan.mutations().size()) throw new IllegalArgumentException("Invalid task cursor");
        if (appliedCount < 0 || appliedCount > plan.mutations().size()) throw new IllegalArgumentException("Invalid applied count");
        skipped = skipped == null ? Set.of() : Set.copyOf(skipped);
        for (int index : skipped) {
            if (index < 0 || index >= plan.mutations().size()) throw new IllegalArgumentException("Invalid skipped index");
        }
    }

    public BuildTask transition(TaskStatus next, Instant now) {
        if (!allowed(status, next)) throw new IllegalStateException("Illegal task transition " + status + " -> " + next);
        return new BuildTask(id, playerId, playerName, plan, next, cursor, appliedCount, skipped, escrowId, charged, refunded, createdAt, now, failure);
    }

    public BuildTask advance(int nextCursor, int nextApplied, Instant now) {
        return advance(nextCursor, nextApplied, skipped, now);
    }

    /**
     * Advance the execution cursor, replacing the set of skipped (never-applied) mutation indices.
     * The executor supplies the full accumulated set so settlement can return materials exactly for
     * the mutations that never applied (applied mutations are not necessarily a contiguous prefix).
     */
    public BuildTask advance(int nextCursor, int nextApplied, Set<Integer> nextSkipped, Instant now) {
        if (status != TaskStatus.RUNNING || nextCursor < cursor || nextCursor > plan.mutations().size()) {
            throw new IllegalStateException("Cannot advance task");
        }
        if (nextApplied < appliedCount || nextApplied > plan.mutations().size()) {
            throw new IllegalStateException("Cannot regress applied count");
        }
        Set<Integer> merged = new HashSet<>(skipped);
        if (nextSkipped != null) merged.addAll(nextSkipped);
        for (int index : merged) {
            if (index < 0 || index >= plan.mutations().size()) throw new IllegalArgumentException("Invalid skipped index");
        }
        return new BuildTask(id, playerId, playerName, plan, status, nextCursor, nextApplied, merged, escrowId, charged, refunded, createdAt, now, failure);
    }

    public BuildTask withRefunded(BigDecimal nextRefunded, Instant now) {
        Objects.requireNonNull(nextRefunded);
        return new BuildTask(id, playerId, playerName, plan, status, cursor, appliedCount, skipped, escrowId, charged, nextRefunded, createdAt, now, failure);
    }

    private static boolean allowed(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case VALIDATING -> to == TaskStatus.RESERVING || to == TaskStatus.FAILED;
            case RESERVING -> to == TaskStatus.QUEUED || to == TaskStatus.FAILED || to == TaskStatus.REFUND_PENDING;
            case QUEUED -> to == TaskStatus.RUNNING || to == TaskStatus.CANCELLING || to == TaskStatus.PAUSED_OFFLINE || to == TaskStatus.PAUSED_SHUTDOWN;
            case RUNNING -> Set.of(TaskStatus.QUEUED, TaskStatus.COMPLETED, TaskStatus.CANCELLING, TaskStatus.PAUSED_OFFLINE, TaskStatus.PAUSED_SHUTDOWN, TaskStatus.FAILED, TaskStatus.REFUND_PENDING).contains(to);
            case PAUSED_OFFLINE, PAUSED_SHUTDOWN -> to == TaskStatus.QUEUED || to == TaskStatus.CANCELLING;
            case CANCELLING -> to == TaskStatus.CANCELLED || to == TaskStatus.REFUND_PENDING;
            case REFUND_PENDING -> to == TaskStatus.CANCELLED || to == TaskStatus.FAILED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
