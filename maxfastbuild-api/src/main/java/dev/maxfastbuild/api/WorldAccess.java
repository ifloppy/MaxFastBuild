package dev.maxfastbuild.api;

import java.util.UUID;

public interface WorldAccess {
    String stateAt(String world, BlockPos position);
    ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);
    MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind);

    record ValidationResult(boolean allowed, String reason) {}
    record MutationResult(boolean changed, String reason) {}
}
