package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Server-side entity paste: type whitelist, item billing for item/vehicle entities, LootTable
 * rejection, living-mob classification (gated by bypass permission in survival), and NMS spawning
 * from the client-sent NBT ({@code id}/{@code Pos} re-added, {@code UUID} auto-generated).
 */
final class PaperEntityHelper {
    /** Non-living, non-item decoration entities that may be spawned free of charge. */
    private static final Set<String> DECOR = Set.of(
            "minecraft:text_display", "minecraft:block_display", "minecraft:item_display",
            "minecraft:interaction", "minecraft:marker");

    private PaperEntityHelper() {}

    /** A validated, billable-or-otherwise-allowable entity from a paste. */
    record EntityData(String type, Object compound, Material billableItem, boolean mob,
                      List<PaperNbtHelper.ItemInstance> contents) {}

    /** Outcome of spawning: {@code added} mirrors the {@code addFreshEntity} call, {@code entity}
     *  is the Bukkit wrapper (may be null when the wrapper could not be reflected). */
    record SpawnResult(boolean added, org.bukkit.entity.Entity entity) {
        static final SpawnResult FAILED = new SpawnResult(false, null);
    }

    static final class EntityRejectException extends RuntimeException {
        final String reason;

        EntityRejectException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /**
     * Validate a client-sent entity. The type id is passed separately (the client strips it from
     * the NBT it sends), the SNBT carries the remaining entity data. Returns the classified
     * entity, or throws {@link EntityRejectException} (unparseable, forbidden keys, unknown type,
     * forbidden items).
     */
    static EntityData validate(String type, String snbt, Object registryAccess) {
        if (type == null || !type.contains(":")) throw new EntityRejectException("missing_entity_id");
        Object compound = PaperNbtHelper.parseCompound(snbt);
        if (compound == null) throw new EntityRejectException("unparseable_entity_nbt");
        String forbidden = PaperNbtHelper.validateEntityKeys(compound);
        if (forbidden != null) throw new EntityRejectException(forbidden);
        Material item = billableItem(type);
        boolean decor = item == null && DECOR.contains(type);
        boolean mob = item == null && !decor && isAliveMob(type);
        if (item == null && !mob && !decor) throw new EntityRejectException("unsupported_entity_type:" + type);
        List<PaperNbtHelper.ItemInstance> contents =
                item != null ? PaperNbtHelper.decodeEntityItems(compound, registryAccess) : List.of();
        if (contents == null) throw new EntityRejectException("forbidden_item_in_entity");
        return new EntityData(type, compound, item, mob, contents);
    }

    /** The craftable item a pasted entity consumes, or null for mobs/decor. */
    static Material billableItem(String type) {
        String id = type.substring(type.indexOf(':') + 1);
        return switch (id) {
            case "minecart" -> Material.MINECART;
            case "chest_minecart" -> Material.CHEST_MINECART;
            case "hopper_minecart" -> Material.HOPPER_MINECART;
            case "furnace_minecart" -> Material.FURNACE_MINECART;
            case "tnt_minecart" -> Material.TNT_MINECART;
            case "command_block_minecart" -> Material.COMMAND_BLOCK_MINECART;
            case "armor_stand" -> Material.ARMOR_STAND;
            case "item_frame" -> Material.ITEM_FRAME;
            case "glow_item_frame" -> Material.GLOW_ITEM_FRAME;
            case "painting" -> Material.PAINTING;
            case "leash_knot" -> Material.LEAD;
            default -> id.endsWith("_boat") ? boatItem(id) : null;
        };
    }

    private static Material boatItem(String id) {
        String wood = id.substring(0, id.length() - "_boat".length()).toUpperCase();
        try {
            return Material.valueOf(wood + "_BOAT");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isAliveMob(String type) {
        String id = type.substring(type.indexOf(':') + 1);
        EntityType bukkit = org.bukkit.Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id));
        return bukkit != null && bukkit.isAlive();
    }

    /**
     * Spawn the entity from its validated NBT at the given absolute position by re-adding
     * {@code id} + {@code Pos} and loading it via NMS (26.2: {@code EntityType.loadEntityRecursive},
     * older: {@code EntityType.create(CompoundTag, Level)}) then {@code addFreshEntity}.
     * Returns the spawn outcome including the Bukkit wrapper so the caller can fire the Bukkit
     * spawn/place events that audit plugins (CoreProtect/Prism) listen to.
     */
    static SpawnResult spawn(World world, EntityData data, double x, double y, double z) {
        try {
            Object nmsWorld = world.getClass().getMethod("getHandle").invoke(world);
            Object compound = PaperNbtHelper.cloneCompound(data.compound());
            if (compound == null) compound = data.compound();
            PaperNbtHelper.putString(compound, "id", data.type());
            PaperNbtHelper.putDoubleList(compound, "Pos", x, y, z);
            Object entity = loadEntity(compound, nmsWorld);
            if (entity == null) return SpawnResult.FAILED;
            Method add = nmsWorld.getClass().getMethod("addFreshEntity",
                    Class.forName("net.minecraft.world.entity.Entity"));
            add.invoke(nmsWorld, entity);
            org.bukkit.entity.Entity bukkit = null;
            try {
                Object wrapper = entity.getClass().getMethod("getBukkitEntity").invoke(entity);
                if (wrapper instanceof org.bukkit.entity.Entity cast) bukkit = cast;
            } catch (ReflectiveOperationException ignored) {
            }
            return new SpawnResult(true, bukkit);
        } catch (ReflectiveOperationException | LinkageError e) {
            return SpawnResult.FAILED;
        }
    }

    private static Object loadEntity(Object compound, Object level) throws ReflectiveOperationException {
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
        // 26.2: EntityType.loadEntityRecursive(CompoundTag, Level, EntitySpawnRequest, EntityProcessor)
        try {
            Class<?> requestType = Class.forName("net.minecraft.world.entity.EntitySpawnRequest");
            Class<?> reasonType = Class.forName("net.minecraft.world.entity.EntitySpawnReason");
            Object reason = reasonType.getField("COMMAND").get(null);
            Object request = requestType.getConstructor(reasonType, boolean.class).newInstance(reason, false);
            Class<?> processorType = Class.forName("net.minecraft.world.entity.EntityProcessor");
            Object processor = processorType.getField("NOP").get(null);
            Method load = entityType.getMethod("loadEntityRecursive",
                    Class.forName("net.minecraft.nbt.CompoundTag"), Class.forName("net.minecraft.world.level.Level"),
                    requestType, processorType);
            return load.invoke(null, compound, level, request, processor);
        } catch (NoSuchMethodException | ClassNotFoundException ignored) {
            // 1.21.x fallback: EntityType.create(CompoundTag, Level)
            Method create = entityType.getMethod("create",
                    Class.forName("net.minecraft.nbt.CompoundTag"), Class.forName("net.minecraft.world.level.Level"));
            return create.invoke(null, compound, level);
        }
    }
}
