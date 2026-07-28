package dev.maxfastbuild.api;

public record Bounds(BlockPos min, BlockPos max) {
    public Bounds {
        BlockPos a = min;
        BlockPos b = max;
        min = new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        max = new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public long sizeX() { return (long) max.x() - min.x() + 1; }
    public long sizeY() { return (long) max.y() - min.y() + 1; }
    public long sizeZ() { return (long) max.z() - min.z() + 1; }
    public long volume() { return Math.multiplyExact(Math.multiplyExact(sizeX(), sizeY()), sizeZ()); }
    public long maximumPlaneArea() {
        return Math.max(sizeX() * sizeY(), Math.max(sizeX() * sizeZ(), sizeY() * sizeZ()));
    }
}
