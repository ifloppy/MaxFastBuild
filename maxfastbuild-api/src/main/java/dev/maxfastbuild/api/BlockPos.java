package dev.maxfastbuild.api;

public record BlockPos(int x, int y, int z) implements Comparable<BlockPos> {
    public BlockPos add(int dx, int dy, int dz) { return new BlockPos(x + dx, y + dy, z + dz); }

    @Override
    public int compareTo(BlockPos other) {
        int value = Integer.compare(x, other.x);
        if (value == 0) value = Integer.compare(y, other.y);
        return value == 0 ? Integer.compare(z, other.z) : value;
    }
}
