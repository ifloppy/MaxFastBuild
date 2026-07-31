package dev.maxfastbuild.fabric.client;

/**
 * Neutral absolute block entry extracted from a Litematica placement.
 * {@code blockData} is a vanilla block-state string the Paper server can parse
 * (NBT block-entity data is intentionally dropped by the bridge).
 */
public record PasteBlock(int x, int y, int z, String blockData) {
    public PasteBlock {
        if (blockData == null || blockData.isBlank()) throw new IllegalArgumentException("blank block data");
    }
}
