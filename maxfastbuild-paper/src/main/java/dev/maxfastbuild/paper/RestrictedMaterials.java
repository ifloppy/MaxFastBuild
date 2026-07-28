package dev.maxfastbuild.paper;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/** Blocks that mass place/break must never touch even if the player holds the item. */
final class RestrictedMaterials {
    private static final Set<Material> FORBIDDEN_PLACE = EnumSet.noneOf(Material.class);
    private static final Set<Material> FORBIDDEN_BREAK = EnumSet.noneOf(Material.class);

    static {
        addBoth("BEDROCK");
        addBoth("BARRIER");
        addBoth("COMMAND_BLOCK");
        addBoth("CHAIN_COMMAND_BLOCK");
        addBoth("REPEATING_COMMAND_BLOCK");
        addBoth("STRUCTURE_BLOCK");
        addBoth("JIGSAW");
        addBoth("END_PORTAL_FRAME");
        addBoth("END_PORTAL");
        addBoth("NETHER_PORTAL");
        addPlace("STRUCTURE_VOID");
        addPlace("LIGHT");
        addPlace("MOVING_PISTON");
        addPlace("SPAWNER");
        addPlace("TRIAL_SPAWNER");
        addPlace("REINFORCED_DEEPSLATE");
    }

    private RestrictedMaterials() {}

    static boolean isForbiddenPlace(Material material) {
        return material == null || !material.isBlock() || material.isAir() || FORBIDDEN_PLACE.contains(material);
    }

    static boolean isForbiddenBreak(Material material) {
        return material != null && FORBIDDEN_BREAK.contains(material);
    }

    private static void addBoth(String name) {
        addPlace(name);
        addBreak(name);
    }

    private static void addPlace(String name) {
        Material material = resolve(name);
        if (material != null) FORBIDDEN_PLACE.add(material);
    }

    private static void addBreak(String name) {
        Material material = resolve(name);
        if (material != null) FORBIDDEN_BREAK.add(material);
    }

    private static Material resolve(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
