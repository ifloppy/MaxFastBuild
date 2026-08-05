package dev.maxfastbuild.fabric.client;

/**
 * User-selected paste filters. Applied client-side before any part leaves the client, so the
 * server never needs to know about them: fluids are dropped, block-entity NBT is stripped from
 * the palette, and (once entity paste lands) entities are filtered too.
 */
public record PasteSettings(boolean skipFluids, boolean skipEntities, boolean skipNbt) {
    public static final PasteSettings DEFAULT = new PasteSettings(false, false, false);
}
