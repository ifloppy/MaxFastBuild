package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;

/**
 * Rejects shapes whose bounding volume would force an O(volume) scan larger than the block limit.
 * Hollow shapes still iterate the full AABB, so volume — not surface size — must be capped first.
 */
public final class ShapeScanBudget {
    private ShapeScanBudget() {}

    public static void ensureWithinLimit(ShapeRequest request, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        long volume;
        try {
            volume = request.bounds().volume();
        } catch (ArithmeticException ex) {
            throw new ShapeLimitException(limit);
        }
        if (volume > limit) throw new ShapeLimitException(limit);
    }
}
