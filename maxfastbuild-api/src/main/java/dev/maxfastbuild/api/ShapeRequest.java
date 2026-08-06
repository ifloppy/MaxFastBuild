package dev.maxfastbuild.api;

import java.util.Objects;

public record ShapeRequest(BuildMode mode, BlockPos first, BlockPos second, int hollow) {
    public ShapeRequest {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
    }

    public Bounds bounds() { return new Bounds(first, second); }
}
