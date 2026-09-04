package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.Bounds;
import dev.maxfastbuild.api.ShapeRequest;
import dev.maxfastbuild.core.shape.DefaultShapeGenerator;
import dev.maxfastbuild.core.shape.ShapeLimitException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Produces a bounded client-only preview without ever scanning an arbitrarily large region.
 * Small selections keep the exact generated shape. Large selections degrade to a sampled AABB
 * wireframe so setting pos2/mode can never turn preview generation into O(region volume) work.
 */
final class SelectionPreviewSampler {
    private SelectionPreviewSampler() {}

    static Sample sample(ShapeRequest request, int maxPositions) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (maxPositions < 8) throw new IllegalArgumentException("maxPositions must be at least 8");

        boolean exactSafe = false;
        try {
            exactSafe = request.bounds().volume() <= maxPositions;
        } catch (ArithmeticException ignored) {
            // Coordinate extremes can overflow a volume calculation. The bounded outline path below
            // does not multiply dimensions and remains safe.
        }

        if (exactSafe) {
            try {
                Set<BlockPos> exact = new DefaultShapeGenerator().generate(request, maxPositions);
                return new Sample(immutable(exact), false);
            } catch (ShapeLimitException ignored) {
                // Some shapes can still exceed the output budget even with a small AABB. Use the
                // same bounded fallback rather than failing or retrying with a larger limit.
            }
        }

        return new Sample(immutable(sampledBounds(request, maxPositions)), true);
    }

    private static Set<BlockPos> sampledBounds(ShapeRequest request, int maxPositions) {
        Bounds bounds = request.bounds();
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>(Math.min(maxPositions, 256));

        add(out, request.first(), maxPositions);
        add(out, request.second(), maxPositions);
        if (request.third() != null) add(out, request.third(), maxPositions);

        BlockPos min = bounds.min();
        BlockPos max = bounds.max();
        BlockPos[] corners = {
                new BlockPos(min.x(), min.y(), min.z()), new BlockPos(max.x(), min.y(), min.z()),
                new BlockPos(min.x(), max.y(), min.z()), new BlockPos(max.x(), max.y(), min.z()),
                new BlockPos(min.x(), min.y(), max.z()), new BlockPos(max.x(), min.y(), max.z()),
                new BlockPos(min.x(), max.y(), max.z()), new BlockPos(max.x(), max.y(), max.z())
        };
        for (BlockPos corner : corners) add(out, corner, maxPositions);

        int[][] edges = {
                {0,1},{2,3},{4,5},{6,7},
                {0,2},{1,3},{4,6},{5,7},
                {0,4},{1,5},{2,6},{3,7}
        };
        int remaining = Math.max(0, maxPositions - out.size());
        int rounds = (remaining + edges.length - 1) / edges.length;
        for (int i = 1; i <= rounds && out.size() < maxPositions; i++) {
            double t = (double) i / (rounds + 1);
            for (int[] edge : edges) {
                if (out.size() >= maxPositions) break;
                add(out, interpolate(corners[edge[0]], corners[edge[1]], t), maxPositions);
            }
        }
        return out;
    }

    private static BlockPos interpolate(BlockPos a, BlockPos b, double t) {
        return new BlockPos(
                lerp(a.x(), b.x(), t),
                lerp(a.y(), b.y(), t),
                lerp(a.z(), b.z(), t));
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + ((long) b - a) * t);
    }

    private static void add(Set<BlockPos> out, BlockPos pos, int maxPositions) {
        if (pos != null && out.size() < maxPositions) out.add(pos);
    }

    private static Set<BlockPos> immutable(Set<BlockPos> positions) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(positions));
    }

    record Sample(Set<BlockPos> positions, boolean simplified) {}
}
