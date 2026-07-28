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
            case WALL, DIAGONAL_WALL, SLOPE_FLOOR -> wall(result, request, limit);
            case FLOOR, CUBE -> cuboid(result, request.bounds(), request.hollow(), request.mode() == BuildMode.FLOOR, limit);
            case CIRCLE -> ellipse(result, request.bounds(), request.hollow(), false, limit);
            case CYLINDER -> ellipse(result, request.bounds(), request.hollow(), true, limit);
            case SPHERE -> ellipsoid(result, request.bounds(), request.hollow(), limit);
            case PYRAMID -> pyramid(result, request.bounds(), request.hollow(), false, limit);
            case CONE -> pyramid(result, request.bounds(), request.hollow(), true, limit);
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
            for (int x = (int) Math.ceil(cx - rx); x <= Math.floor(cx + rx); x++)
                for (int z = (int) Math.ceil(cz - rz); z <= Math.floor(cz + rz); z++) {
                    boolean edge = x == Math.ceil(cx - rx) || x == Math.floor(cx + rx) || z == Math.ceil(cz - rz) || z == Math.floor(cz + rz);
                    double normalized = rx == 0 || rz == 0 ? 0 : sq((x - cx) / rx) + sq((z - cz) / rz);
                    if ((!round || normalized <= 1.0) && (!hollow || edge || layer == 0 || layer == height - 1))
                        add(out, new BlockPos(x, b.min().y() + layer, z), limit);
                }
        }
    }

    private static double sq(double value) { return value * value; }
    private static void add(Set<BlockPos> out, BlockPos pos, int limit) {
        out.add(pos);
        if (out.size() > limit) throw new ShapeLimitException(limit);
    }
}
