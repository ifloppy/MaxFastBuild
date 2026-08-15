package dev.maxfastbuild.core.limit;

/**
 * Effective limits advertised by the Paper server to clients.
 *
 * <p>The first four values are semantic build limits. The remaining values describe the
 * negotiated bulk-paste transport and entity limits; clients must not invent separate values.
 */
public record ServerLimits(
        long maxRegionBlocks,
        long maxAffectedBlocks,
        long maxSizeX,
        long maxSizeY,
        long maxSizeZ,
        int maxPasteParts,
        int maxBlocksPerPart,
        int maxPasteTotalBlocks,
        int maxPayloadBytes,
        int maxInstantEntities,
        int maxInstantEntitiesPerChunk,
        int maxNormalEntities,
        int maxNormalEntitiesPerChunk) {

    public ServerLimits {
        if (maxRegionBlocks < 1 || maxAffectedBlocks < 1
                || maxSizeX < 1 || maxSizeY < 1 || maxSizeZ < 1
                || maxPasteParts < 1 || maxBlocksPerPart < 1 || maxPasteTotalBlocks < 1
                || maxPayloadBytes < 1
                || maxInstantEntities < 0 || maxInstantEntitiesPerChunk < 0
                || maxNormalEntities < 0 || maxNormalEntitiesPerChunk < 0) {
            throw new IllegalArgumentException("server limits must be positive (entity limits may be zero)");
        }
    }

    public int maxPasteBlockEntries() {
        long total = (long) maxPasteParts * maxBlocksPerPart;
        return (int) Math.min(Integer.MAX_VALUE, Math.min(total, maxPasteTotalBlocks));
    }
}
