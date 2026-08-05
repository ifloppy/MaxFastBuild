package dev.maxfastbuild.fabric.client;

/**
 * User-selected paste filters. Applied client-side before any part leaves the client, so the
 * server never needs to know about them: fluids are dropped, living mobs (villagers/animals) are
 * dropped when {@code skipMobs}, dropped item entities are dropped when {@code skipDrops},
 * container {@code Items} NBT is stripped when {@code skipContents}, all entities are dropped
 * when {@code skipEntities}, and block-entity NBT is stripped from the palette when
 * {@code skipNbt}.
 */
public record PasteSettings(boolean skipFluids, boolean skipEntities, boolean skipMobs,
                            boolean skipDrops, boolean skipContents, boolean skipNbt) {
    public static final PasteSettings DEFAULT = new PasteSettings(false, false, false, false, false, false);
}
