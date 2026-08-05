package dev.maxfastbuild.api;

import java.util.List;
import java.util.UUID;

public interface WorldAccess {
    String stateAt(String world, BlockPos position);
    ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);
    MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);

    /**
     * Bulk-paste physics model (matches Litematica's {@code placeBlocksToWorld}): while deferred
     * physics is active, {@link #mutate} places blocks WITHOUT firing immediate neighbor updates
     * ({@code NO_UPDATE}), then {@link #settlePlacements} re-notifies every placed position once.
     * Redstone therefore computes against the final layout instead of a partially-built circuit.
     * Implementations may no-op these methods and keep per-block physics.
     */
    default void beginDeferredPhysics() {}

    default void endDeferredPhysics() {}

    default void settlePlacements(String world, List<BlockPos> positions) {}

    record ValidationResult(boolean allowed, String reason) {}

    /**
     * @param changed whether the world was modified
     * @param reason empty, error code, or flags such as {@code break_logged} when a protect
     *               {@code BlockBreakEvent} already ran (CoreProtect may have recorded removal)
     */
    record MutationResult(boolean changed, String reason) {
        public boolean breakAlreadyLogged() {
            return reason != null && reason.contains("break_logged");
        }
    }
}
