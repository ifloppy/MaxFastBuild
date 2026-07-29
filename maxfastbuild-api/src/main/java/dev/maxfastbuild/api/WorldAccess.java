package dev.maxfastbuild.api;

import java.util.UUID;

public interface WorldAccess {
    String stateAt(String world, BlockPos position);
    ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);
    MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);

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
