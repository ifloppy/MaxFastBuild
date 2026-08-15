package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;
import java.util.*;

public final class DefaultShapeGenerator implements ShapeGenerator {
    private static final int[][] NEIGHBORS_6 = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    @Override
    public Set<BlockPos> generate(ShapeRequest request, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        ShapeScanBudget.ensureWithinLimit(request, limit);
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        switch (request.mode()) {
            case SINGLE -> add(result, request.first(), limit);
            case LINE -> line(result, request.first(), request.second(), limit);
            case WALL -> wall(result, request, limit);
            case ARC -> arc(result, request, limit);
            case ARRAY -> array(result, request, limit);
            case SLOPE_FLOOR -> slopeFloor(result, request, limit);
            case FLOOR, CUBE -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                cuboidSolid(solid, request.bounds(), request.mode() == BuildMode.FLOOR, limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
            case CIRCLE -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                ellipseSolid(solid, request.bounds(), false, limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
            case CYLINDER -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                ellipseSolid(solid, request.bounds(), true, limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
            case SPHERE -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                ellipsoidSolid(solid, request.bounds(), limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
            case PYRAMID -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                pyramidSolid(solid, request.bounds(), false, limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
            case CONE -> {
                LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
                pyramidSolid(solid, request.bounds(), true, limit);
                if (request.hollow() > 0) shell(solid, request.bounds(), request.hollow());
                result.addAll(solid);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void line(Set<BlockPos> out, BlockPos a, BlockPos b, int limit) {
        int dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps == 0) { add(out, a, limit); return; }
        for (int i = 0; i <= steps; i++) {
            add(out, new BlockPos(a.x() + Math.round((float) dx * i / steps),
                    a.y() + Math.round((float) dy * i / steps),
                    a.z() + Math.round((float) dz * i / steps)), limit);
        }
    }

    /** Draws the circular arc from the first point through the second point to the third point. */
    private static void arc(Set<BlockPos> out, ShapeRequest request, int limit) {
        BlockPos a = request.first();
        BlockPos through = request.second();
        BlockPos b = request.third();
        if (b == null) throw new IllegalArgumentException("arc requires three points");

        Vec3 start = Vec3.from(a);
        Vec3 middle = Vec3.from(through);
        Vec3 end = Vec3.from(b);
        Vec3 ab = middle.subtract(start);
        Vec3 ac = end.subtract(start);
        Vec3 normal = ab.cross(ac);
        double normalLengthSquared = normal.dot(normal);
        if (normalLengthSquared < 1.0e-9) {
            // A degenerate CAD arc has no unique circle; a straight fallback is deterministic.
            line(out, a, through, limit);
            line(out, through, b, limit);
            return;
        }

        Vec3 centerOffset = ac.cross(normal).scale(ab.dot(ab))
                .add(normal.cross(ab).scale(ac.dot(ac)))
                .scale(1.0 / (2.0 * normalLengthSquared));
        Vec3 center = start.add(centerOffset);
        Vec3 radiusVector = start.subtract(center);
        double radius = Math.sqrt(radiusVector.dot(radiusVector));
        Vec3 axisX = radiusVector.scale(1.0 / radius);
        Vec3 axisY = normal.scale(1.0 / Math.sqrt(normalLengthSquared)).cross(axisX);
        double middleAngle = angle(middle.subtract(center), axisX, axisY);
        double endAngle = angle(end.subtract(center), axisX, axisY);
        double ccwEnd = positiveAngle(endAngle);
        double ccwMiddle = positiveAngle(middleAngle);
        boolean counterClockwise = ccwMiddle <= ccwEnd + 1.0e-9;
        double firstSweep = counterClockwise ? ccwMiddle : -(Math.PI * 2 - ccwMiddle);
        double secondSweep = ccwEnd - ccwMiddle;

        appendArcSegment(out, center, axisX, axisY, radius, 0, firstSweep, a, through, limit);
        appendArcSegment(out, center, axisX, axisY, radius, middleAngle, secondSweep, through, b, limit);
    }

    /** An array is a regular 3D lattice bounded by the two selected corners. */
    private static void array(Set<BlockPos> out, ShapeRequest request, int limit) {
        Bounds bounds = request.bounds();
        for (long x = bounds.min().x(); x <= bounds.max().x(); x += request.spacingX()) {
            for (long y = bounds.min().y(); y <= bounds.max().y(); y += request.spacingY()) {
                for (long z = bounds.min().z(); z <= bounds.max().z(); z += request.spacingZ()) {
                    add(out, new BlockPos((int) x, (int) y, (int) z), limit);
                }
            }
        }
    }

    private static void appendArcSegment(Set<BlockPos> out, Vec3 center, Vec3 axisX, Vec3 axisY,
                                         double radius, double startAngle, double sweep,
                                         BlockPos start, BlockPos end, int limit) {
        double estimatedSamples = Math.abs(sweep) * radius * 2.0;
        if (!Double.isFinite(estimatedSamples)
                || estimatedSamples > (double) limit * 2.0
                || estimatedSamples > Integer.MAX_VALUE) {
            throw new ShapeLimitException(limit);
        }
        int steps = Math.max(1, (int) Math.ceil(estimatedSamples));
        BlockPos previous = start;
        add(out, previous, limit);
        for (int i = 1; i <= steps; i++) {
            double angle = startAngle + sweep * i / steps;
            Vec3 point = center.add(axisX.scale(Math.cos(angle) * radius))
                    .add(axisY.scale(Math.sin(angle) * radius));
            BlockPos current = i == steps ? end : rounded(point);
            line(out, previous, current, limit);
            previous = current;
        }
    }

    private static double angle(Vec3 vector, Vec3 axisX, Vec3 axisY) {
        return Math.atan2(vector.dot(axisY), vector.dot(axisX));
    }

    private static double positiveAngle(double angle) {
        double value = angle % (Math.PI * 2);
        return value < 0 ? value + Math.PI * 2 : value;
    }

    private static BlockPos rounded(Vec3 point) {
        long x = Math.round(point.x), y = Math.round(point.y), z = Math.round(point.z);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("arc coordinate overflow");
        }
        return new BlockPos((int) x, (int) y, (int) z);
    }

    private static void wall(Set<BlockPos> out, ShapeRequest request, int limit) {
        BlockPos a = request.first();
        BlockPos b = new BlockPos(request.second().x(), a.y(), request.second().z());
        LinkedHashSet<BlockPos> base = new LinkedHashSet<>();
        line(base, a, b, limit);
        int minY = Math.min(a.y(), request.second().y());
        int maxY = Math.max(a.y(), request.second().y());
        for (BlockPos pos : base) for (int y = minY; y <= maxY; y++) add(out, new BlockPos(pos.x(), y, pos.z()), limit);
    }

    private static void slopeFloor(Set<BlockPos> out, ShapeRequest request, int limit) {
        int hollow = request.hollow();
        if (hollow == 0) {
            smoothSurface(out, request, limit);
        } else if (hollow == 1) {
            slopeStair(out, request, true, limit);
        } else {
            slopeStair(out, request, false, limit);
        }
    }

    private static void smoothSurface(Set<BlockPos> out, ShapeRequest request, int limit) {
        BlockPos a = request.first();
        BlockPos b = request.second();
        int dx = b.x() - a.x();
        int dz = b.z() - a.z();
        int dy = b.y() - a.y();
        long denom = (long) dx * dx + (long) dz * dz;
        Bounds bounds = request.bounds();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                int y;
                if (denom == 0) {
                    y = a.y();
                } else {
                    long proj = (long) (x - a.x()) * dx + (long) (z - a.z()) * dz;
                    y = a.y() + (int) Math.round((double) proj / denom * dy);
                }
                add(out, new BlockPos(x, y, z), limit);
            }
        }
    }

    private static void slopeStair(Set<BlockPos> out, ShapeRequest request, boolean axisX, int limit) {
        BlockPos a = request.first();
        BlockPos b = request.second();
        int dy = b.y() - a.y();
        int dPrimary = axisX ? (b.x() - a.x()) : (b.z() - a.z());
        Bounds bounds = request.bounds();
        int pStart = axisX ? bounds.min().x() : bounds.min().z();
        int pEnd = axisX ? bounds.max().x() : bounds.max().z();
        int cStart = axisX ? bounds.min().z() : bounds.min().x();
        int cEnd = axisX ? bounds.max().z() : bounds.max().x();
        int aPrimary = axisX ? a.x() : a.z();
        for (int p = pStart; p <= pEnd; p++) {
            int y;
            if (dPrimary == 0) {
                y = a.y();
            } else {
                double ratio = (double) (p - aPrimary) / dPrimary;
                y = a.y() + (int) Math.round(ratio * dy);
            }
            for (int c = cStart; c <= cEnd; c++) {
                int x = axisX ? p : c;
                int z = axisX ? c : p;
                add(out, new BlockPos(x, y, z), limit);
            }
        }
    }

    /**
     * Extract an N-thickness shell from a solid block set via BFS from the surface.
     * Automatically caps thickness so at least one interior block remains hollow.
     */
    static void shell(LinkedHashSet<BlockPos> solid, Bounds bounds, int thickness) {
        if (solid.isEmpty() || thickness < 1) return;
        int maxDim = (int) Math.min(Math.min(bounds.sizeX(), bounds.sizeY()), bounds.sizeZ());
        int maxThickness = Math.min(thickness, (maxDim - 1) / 2);
        if (maxThickness < 1) return;
        Map<BlockPos, Integer> depth = new HashMap<>(solid.size());
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos pos : solid) {
            for (int[] n : NEIGHBORS_6) {
                BlockPos nb = new BlockPos(pos.x() + n[0], pos.y() + n[1], pos.z() + n[2]);
                if (!solid.contains(nb)) {
                    depth.put(pos, 0);
                    queue.add(pos);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.pollFirst();
            int d = depth.get(pos);
            if (d >= maxThickness) continue;
            for (int[] n : NEIGHBORS_6) {
                BlockPos nb = new BlockPos(pos.x() + n[0], pos.y() + n[1], pos.z() + n[2]);
                if (solid.contains(nb) && !depth.containsKey(nb)) {
                    depth.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        solid.retainAll(depth.keySet());
        solid.removeIf(pos -> depth.get(pos) >= maxThickness);
    }

    private static void cuboidSolid(Set<BlockPos> out, Bounds b, boolean floor, int limit) {
        int maxY = floor ? b.min().y() : b.max().y();
        for (int x = b.min().x(); x <= b.max().x(); x++)
            for (int y = b.min().y(); y <= maxY; y++)
                for (int z = b.min().z(); z <= b.max().z(); z++)
                    add(out, new BlockPos(x, y, z), limit);
    }

    private static void ellipseSolid(Set<BlockPos> out, Bounds b, boolean cylinder, int limit) {
        double cx = (b.min().x() + b.max().x() + 1) / 2.0;
        double cz = (b.min().z() + b.max().z() + 1) / 2.0;
        double rx = Math.max(.5, b.sizeX() / 2.0), rz = Math.max(.5, b.sizeZ() / 2.0);
        int maxY = cylinder ? b.max().y() : b.min().y();
        for (int y = b.min().y(); y <= maxY; y++)
            for (int x = b.min().x(); x <= b.max().x(); x++)
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    double d = sq((x + .5 - cx) / rx) + sq((z + .5 - cz) / rz);
                    if (d <= 1.0) add(out, new BlockPos(x, y, z), limit);
                }
    }

    private static void ellipsoidSolid(Set<BlockPos> out, Bounds b, int limit) {
        double cx = (b.min().x() + b.max().x() + 1) / 2.0;
        double cy = (b.min().y() + b.max().y() + 1) / 2.0;
        double cz = (b.min().z() + b.max().z() + 1) / 2.0;
        double rx = Math.max(.5, b.sizeX() / 2.0), ry = Math.max(.5, b.sizeY() / 2.0), rz = Math.max(.5, b.sizeZ() / 2.0);
        for (int x = b.min().x(); x <= b.max().x(); x++)
            for (int y = b.min().y(); y <= b.max().y(); y++)
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    double d = sq((x + .5 - cx) / rx) + sq((y + .5 - cy) / ry) + sq((z + .5 - cz) / rz);
                    if (d <= 1.0) add(out, new BlockPos(x, y, z), limit);
                }
    }

    private static void pyramidSolid(Set<BlockPos> out, Bounds b, boolean round, int limit) {
        int height = Math.max(1, (int) b.sizeY());
        double cx = (b.min().x() + b.max().x()) / 2.0, cz = (b.min().z() + b.max().z()) / 2.0;
        for (int layer = 0; layer < height; layer++) {
            double scale = 1.0 - (double) layer / height;
            int rx = Math.max(0, (int) Math.floor((b.sizeX() - 1) * scale / 2));
            int rz = Math.max(0, (int) Math.floor((b.sizeZ() - 1) * scale / 2));
            int xStart = (int) Math.ceil(cx - rx);
            int xEnd = (int) Math.floor(cx + rx);
            int zStart = (int) Math.ceil(cz - rz);
            int zEnd = (int) Math.floor(cz + rz);
            for (int x = xStart; x <= xEnd; x++)
                for (int z = zStart; z <= zEnd; z++) {
                    double normalized = rx == 0 || rz == 0 ? 0 : sq((x - cx) / rx) + sq((z - cz) / rz);
                    if (!round || normalized <= 1.0)
                        add(out, new BlockPos(x, b.min().y() + layer, z), limit);
                }
        }
    }

    private static double sq(double value) { return value * value; }
    private static void add(Set<BlockPos> out, BlockPos pos, int limit) {
        out.add(pos);
        if (out.size() > limit) throw new ShapeLimitException(limit);
    }

    private record Vec3(double x, double y, double z) {
        static Vec3 from(BlockPos pos) { return new Vec3(pos.x(), pos.y(), pos.z()); }
        Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
        Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 scale(double factor) { return new Vec3(x * factor, y * factor, z * factor); }
        double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        Vec3 cross(Vec3 other) {
            return new Vec3(y * other.z - z * other.y,
                    z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }
    }
}
