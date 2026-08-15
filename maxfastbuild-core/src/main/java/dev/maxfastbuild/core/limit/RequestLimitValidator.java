package dev.maxfastbuild.core.limit;

import dev.maxfastbuild.api.Bounds;

import java.util.Optional;

/** Validates the server-authoritative region and affected-coordinate limits. */
public final class RequestLimitValidator {
    private RequestLimitValidator() {}

    public static Optional<Violation> region(Bounds bounds, ServerLimits limits) {
        try {
            return region(bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(), bounds.volume(), limits);
        } catch (ArithmeticException ex) {
            return Optional.of(new Violation(Kind.REGION_BLOCKS, "volume", Long.MAX_VALUE, limits.maxRegionBlocks()));
        }
    }

    public static Optional<Violation> region(long sizeX, long sizeY, long sizeZ, long volume,
                                             ServerLimits limits) {
        if (sizeX > limits.maxSizeX()) {
            return Optional.of(new Violation(Kind.AXIS, "x", sizeX, limits.maxSizeX()));
        }
        if (sizeY > limits.maxSizeY()) {
            return Optional.of(new Violation(Kind.AXIS, "y", sizeY, limits.maxSizeY()));
        }
        if (sizeZ > limits.maxSizeZ()) {
            return Optional.of(new Violation(Kind.AXIS, "z", sizeZ, limits.maxSizeZ()));
        }
        if (volume > limits.maxRegionBlocks()) {
            return Optional.of(new Violation(Kind.REGION_BLOCKS, "volume", volume, limits.maxRegionBlocks()));
        }
        return Optional.empty();
    }

    public static Optional<Violation> affected(long count, ServerLimits limits) {
        if (count > limits.maxAffectedBlocks()) {
            return Optional.of(new Violation(Kind.AFFECTED_BLOCKS, "affected", count, limits.maxAffectedBlocks()));
        }
        return Optional.empty();
    }

    public enum Kind { AXIS, REGION_BLOCKS, AFFECTED_BLOCKS }

    public record Violation(Kind kind, String axis, long actual, long limit) {}
}
