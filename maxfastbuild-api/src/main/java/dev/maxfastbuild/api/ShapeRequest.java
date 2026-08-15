package dev.maxfastbuild.api;

import java.util.Objects;

public record ShapeRequest(BuildMode mode, BlockPos first, BlockPos second, BlockPos third,
                           int hollow, int spacingX, int spacingY, int spacingZ) {
    public ShapeRequest {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (spacingX < 0 || spacingY < 0 || spacingZ < 0) {
            throw new IllegalArgumentException("array spacing must not be negative");
        }
        // Zero is the omitted value used by older JSON requests; one is the natural default.
        spacingX = spacingX == 0 ? 1 : spacingX;
        spacingY = spacingY == 0 ? 1 : spacingY;
        spacingZ = spacingZ == 0 ? 1 : spacingZ;
    }

    public ShapeRequest(BuildMode mode, BlockPos first, BlockPos second, int hollow) {
        this(mode, first, second, null, hollow, 1, 1, 1);
    }

    public ShapeRequest(BuildMode mode, BlockPos first, BlockPos second, BlockPos third, int hollow) {
        this(mode, first, second, third, hollow, 1, 1, 1);
    }

    public ShapeRequest(BuildMode mode, BlockPos first, BlockPos second, int hollow,
                        int spacingX, int spacingY, int spacingZ) {
        this(mode, first, second, null, hollow, spacingX, spacingY, spacingZ);
    }

    public Bounds bounds() {
        if (third == null) return new Bounds(first, second);
        return new Bounds(
                new BlockPos(Math.min(first.x(), Math.min(second.x(), third.x())),
                        Math.min(first.y(), Math.min(second.y(), third.y())),
                        Math.min(first.z(), Math.min(second.z(), third.z()))),
                new BlockPos(Math.max(first.x(), Math.max(second.x(), third.x())),
                        Math.max(first.y(), Math.max(second.y(), third.y())),
                        Math.max(first.z(), Math.max(second.z(), third.z()))));
    }
}
