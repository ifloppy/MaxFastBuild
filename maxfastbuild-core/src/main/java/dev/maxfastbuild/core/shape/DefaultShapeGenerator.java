package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;
import java.util.*;

public final class DefaultShapeGenerator implements ShapeGenerator {
    @Override
    public Set<BlockPos> generate(ShapeRequest request, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        ShapeScanBudget.ensureWithinLimit(request, limit);
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        switch (request.mode()) {
            case SINGLE -> add(result, request.first(), limit);
            case LINE, DIAGONAL_LINE -> line(result, request.first(), request.second(), limit);
            case WALL, DIAGONAL_WALL -> wall(result, request, limit);
            case SLOPE_FLOOR -> slopeFloor(result, request, limit);
            case FLOOR, CUBE -> cuboid(result, request.bounds(), request.hollow() != 0, request.mode() == BuildMode.FLOOR, limit);
            case CIRCLE -> ellipse(result, request.bounds(), request.hollow() != 0, false, limit);
            case CYLINDER -> ellipse(result, request.bounds(), request.hollow() != 0, true, limit);
            case SPHERE -> ellipsoid(result, request.bounds(), request.hollow() != 0, limit);
            case PYRAMID -> pyramid(result, request.bounds(), request.hollow() != 0, false, limit);
            case CONE -> pyramid(result, request.bounds(), request.hollow() != 0, true, limit);
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

    private static void cuboid(Set<BlockPos> out, Bounds b, boolean hollow, boolean floor, int limit) {
        int maxY = floor ? b.min().y() : b.max().y();
        for (int x = b.min().x(); x <= b.max().x(); x++)
            for (int y = b.min().y(); y <= maxY; y++)
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    boolean edge = x == b.min().x() || x == b.max().x() || y == b.min().y()
                            || y == maxY || z == b.min().z() || z == b.max().z();
                    if (!hollow || edge) add(out, new BlockPos(x, y, z), limit);
                }
    }

    private static void ellipse(Set<BlockPos> out, Bounds b, boolean hollow, boolean cylinder, int limit) {
        double cx = (b.min().x() + b.max().x() + 1) / 2.0;
        double cz = (b.min().z() + b.max().z() + 1) / 2.0;
        double rx = Math.max(.5, b.sizeX() / 2.0), rz = Math.max(.5, b.sizeZ() / 2.0);
        int maxY = cylinder ? b.max().y() : b.min().y();
        for (int y = b.min().y(); y <= maxY; y++)
            for (int x = b.min().x(); x <= b.max().x(); x++)
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    double d = sq((x + .5 - cx) / rx) + sq((z + .5 - cz) / rz);
                    if (d <= 1.0 && (!hollow || d >= .60)) add(out, new BlockPos(x, y, z), limit);
                }
    }

    private static void ellipsoid(Set<BlockPos> out, Bounds b, boolean hollow, int limit) {
        double cx = (b.min().x() + b.max().x() + 1) / 2.0;
        double cy = (b.min().y() + b.max().y() + 1) / 2.0;
        double cz = (b.min().z() + b.max().z() + 1) / 2.0;
        double rx = Math.max(.5, b.sizeX() / 2.0), ry = Math.max(.5, b.sizeY() / 2.0), rz = Math.max(.5, b.sizeZ() / 2.0);
        for (int x = b.min().x(); x <= b.max().x(); x++)
            for (int y = b.min().y(); y <= b.max().y(); y++)
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    double d = sq((x + .5 - cx) / rx) + sq((y + .5 - cy) / ry) + sq((z + .5 - cz) / rz);
                    if (d <= 1.0 && (!hollow || d >= .58)) add(out, new BlockPos(x, y, z), limit);
                }
    }

    private static void pyramid(Set<BlockPos> out, Bounds b, boolean hollow, boolean round, int limit) {
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
            boolean topOrBottom = layer == 0 || layer == height - 1;
            for (int x = xStart; x <= xEnd; x++) {
                boolean xEdge = x == xStart || x == xEnd;
                for (int z = zStart; z <= zEnd; z++) {
                    boolean edge = xEdge || z == zStart || z == zEnd;
                    double normalized = rx == 0 || rz == 0 ? 0 : sq((x - cx) / rx) + sq((z - cz) / rz);
                    if ((!round || normalized <= 1.0) && (!hollow || edge || topOrBottom))
                        add(out, new BlockPos(x, b.min().y() + layer, z), limit);
                }
            }
        }
    }

    private static double sq(double value) { return value * value; }
    private static void add(Set<BlockPos> out, BlockPos pos, int limit) {
        out.add(pos);
        if (out.size() > limit) throw new ShapeLimitException(limit);
    }
}
