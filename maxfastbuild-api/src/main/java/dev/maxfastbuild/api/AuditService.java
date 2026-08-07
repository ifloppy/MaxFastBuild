package dev.maxfastbuild.api;

import java.util.UUID;

public interface AuditService {
    boolean available();

    void record(UUID playerId, String playerName, String world, BlockMutation mutation, OperationKind kind);

    /**
     * @param breakAlreadyLogged true when the platform already notified loggers via a break event
     *                           (skip a second CoreProtect {@code logRemoval})
     */
    default void record(UUID playerId, String playerName, String world, BlockMutation mutation,
                        OperationKind kind, boolean breakAlreadyLogged) {
        record(playerId, playerName, world, mutation, kind);
    }

    /**
     * Called immediately before MaxFastBuild mutates a container block's inventory (chest, barrel,
     * placed shulker box). Backends that snapshot-and-diff container contents (CoreProtect's
     * {@code logContainerTransaction}) use this to capture the change caused by our direct
     * {@code setItem}/{@code addItem} writes, which otherwise fire no inventory events.
     * <p>
     * Implementations that record explicit per-item actions instead (Prism) can leave this as a
     * no-op and materialize events from {@link #recordItemRemoval}.
     */
    default void beforeContainerMutation(UUID playerId, String playerName, String world, BlockPos pos) {}

    /**
     * An item was removed from a container block's inventory by MaxFastBuild's material deduction
     * (either a stack removed from the container directly, or a shulker-box's contents shrunken while
     * the box stays in place). Record it so rollback inspectors (Prism) see the removal.
     */
    default void recordItemRemoval(UUID playerId, String playerName, String world, BlockPos pos,
                                   String materialKey, int amount) {}
}
