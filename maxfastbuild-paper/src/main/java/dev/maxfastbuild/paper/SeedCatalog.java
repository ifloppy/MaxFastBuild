package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Set;

/**
 * Seed-farm material catalysis: holding a complete "seed" set lets a player place that material
 * in unlimited quantity without consuming anything. Each farm is toggled via
 * {@code inventory.seed-farms.<farm>} and only counts catalysts from the same sources a normal
 * paste would search (player inventory, carried shulker boxes, and — when container search is on —
 * nearby chests/barrels/shulkers).
 * <p>
 * Today only the carpet farm exists: {@value #CATALYST_CARPET} of the target carpet color +
 * {@value #CATALYST_STICKY_PISTON} + {@value #CATALYST_SLIME_BLOCK} + {@value #CATALYST_OBSERVER}
 * makes that carpet placeable in any quantity (blocks, container NBT contents and entity item
 * contents all count as free). Catalysts are never consumed; the player may build from the held
 * carpet itself by carrying one as part of the seed set.
 */
final class SeedCatalog {
    private static final Material CATALYST_CARPET = Material.WHITE_CARPET;
    private static final Material CATALYST_STICKY_PISTON = Material.STICKY_PISTON;
    private static final Material CATALYST_SLIME_BLOCK = Material.SLIME_BLOCK;
    private static final Material CATALYST_OBSERVER = Material.OBSERVER;

    private static volatile boolean carpetEnabled = true;

    private SeedCatalog() {}

    static void reload(FileConfiguration config) {
        carpetEnabled = config.getBoolean("inventory.seed-farms.carpet", true);
    }

    /** Whether {@code material} supports seed catalysis given current config. */
    static boolean isSeeded(Material material) {
        if (!carpetEnabled || material == null) return false;
        return material.name().endsWith("_CARPET") && material.isItem();
    }

    /** Fixed non-target catalysts needed when admin tooling provisions a seeded material. */
    static Set<Material> supplementalCatalysts(Material target) {
        if (!isSeeded(target)) return Set.of();
        return Set.of(CATALYST_STICKY_PISTON, CATALYST_SLIME_BLOCK, CATALYST_OBSERVER);
    }

    /**
     * Whether {@code sources} contain the complete seed set for {@code target} carpet (the target
     * carpet itself + sticky piston + slime block + observer).
     */
    static boolean ownsSeeds(List<PaperInventoryHelper.ItemSource> sources, Material target) {
        if (!isSeeded(target) || sources == null || sources.isEmpty()) return false;
        long carpet = count(sources, target);
        long piston = count(sources, CATALYST_STICKY_PISTON);
        long slime = count(sources, CATALYST_SLIME_BLOCK);
        long observer = count(sources, CATALYST_OBSERVER);
        return carpet >= 1 && piston >= 1 && slime >= 1 && observer >= 1;
    }

    private static long count(List<PaperInventoryHelper.ItemSource> sources, Material material) {
        long total = 0;
        for (PaperInventoryHelper.ItemSource source : sources) {
            total += source.countSeed(material);
            if (total >= 1) break;
        }
        return total;
    }
}
