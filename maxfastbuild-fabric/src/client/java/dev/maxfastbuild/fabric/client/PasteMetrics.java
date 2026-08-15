package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.core.protocol.PasteTransfer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Client-side schematic metrics used for display; the server remains authoritative. */
record PasteMetrics(long sizeX, long sizeY, long sizeZ, long regionBlocks, long candidateBlocks) {
    static PasteMetrics from(List<PasteTransfer.Region> regions, List<PasteBlock> blocks) {
        if (regions == null || regions.isEmpty()) {
            if (blocks == null || blocks.isEmpty()) return new PasteMetrics(0, 0, 0, 0, 0);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (PasteBlock block : blocks) {
                minX = Math.min(minX, block.x());
                minY = Math.min(minY, block.y());
                minZ = Math.min(minZ, block.z());
                maxX = Math.max(maxX, block.x());
                maxY = Math.max(maxY, block.y());
                maxZ = Math.max(maxZ, block.z());
            }
            long sx = (long) maxX - minX + 1;
            long sy = (long) maxY - minY + 1;
            long sz = (long) maxZ - minZ + 1;
            long volume;
            try {
                volume = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
            } catch (ArithmeticException ex) {
                volume = Long.MAX_VALUE;
            }
            return new PasteMetrics(sx, sy, sz, volume, uniqueCoordinates(blocks));
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long volume = 0;
        for (PasteTransfer.Region region : regions) {
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.maxX());
            maxY = Math.max(maxY, region.maxY());
            maxZ = Math.max(maxZ, region.maxZ());
            try {
                volume = Math.addExact(volume, region.volume());
            } catch (ArithmeticException ex) {
                volume = Long.MAX_VALUE;
            }
        }
        return new PasteMetrics((long) maxX - minX + 1, (long) maxY - minY + 1,
                (long) maxZ - minZ + 1, volume, uniqueCoordinates(blocks));
    }

    private static long uniqueCoordinates(List<PasteBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return 0;
        Set<String> coordinates = new HashSet<>();
        for (PasteBlock block : blocks) {
            coordinates.add(block.x() + ":" + block.y() + ":" + block.z());
        }
        return coordinates.size();
    }
}
