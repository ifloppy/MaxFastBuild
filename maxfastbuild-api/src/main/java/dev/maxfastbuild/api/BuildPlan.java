package dev.maxfastbuild.api;

import java.util.List;
import java.util.Objects;

public record BuildPlan(String world, OperationKind operation, Bounds bounds, List<BlockMutation> mutations) {
    public BuildPlan {
        Objects.requireNonNull(world);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(bounds);
        mutations = List.copyOf(mutations);
    }

    public long blockCount() { return mutations.size(); }
}
