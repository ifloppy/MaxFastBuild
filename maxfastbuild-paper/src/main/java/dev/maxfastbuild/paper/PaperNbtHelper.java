package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side block-entity NBT handling through a thin, version-tolerant NMS reflection layer.
 * <p>
 * MaxFastBuild never applies a client-supplied CompoundTag verbatim. The security model rests on
 * three invariants:
 * <ol>
 *   <li><b>No free items.</b> Every item stack a tile can actually store is decoded and billed.
 *       Per supported tile we enumerate its item fields ({@code Items} lists, {@code Book},
 *       {@code RecordItem}, {@code item}, {@code sherds}); any tile whose item fields we have not
 *       audited is rejected outright.</li>
 *   <li><b>No {@code LootTable}.</b> {@code LootTable}/{@code LootTableSeed} are rejected at any
 *       nesting depth — the only item-generation path besides {@code Items}.</li>
 *   <li><b>No behavior smuggling.</b> Only audited, safe tile types may carry NBT at all; the
 *       forbidden block types (command blocks, spawners, structure blocks, …) are already blocked
 *       by {@link RestrictedMaterials}. Other state keys are left untouched because a tile's own
 *       {@code load} ignores fields it does not understand.</li>
 * </ol>
 * The whitelist is therefore per-<em>tile-type</em>, not per-key: legitimate state like a
 * furnace's {@code BurnTime}/{@code CookTime}/{@code TransferCooldown} passes, while unknown tile
 * types and dupe vectors are rejected.
 * <p>
 * NMS classes are stable under Mojang mappings on modern Paper/Leaf, but method names differ
 * across versions (e.g. {@code CompoundTag.keySet()} vs {@code getAllKeys()}, {@code ListTag}
 * no longer being a {@link List}, string tags exposing {@code value()} instead of
 * {@code getAsString()}). Every reflective access therefore tries the current and legacy names
 * and caches the resolved {@link Method}s.
 */
final class PaperNbtHelper {
    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";
    private static final String LIST_TAG = "net.minecraft.nbt.ListTag";

    /** When true, {@link #applyNbt} logs the input, the load result, and the tile read back. */
    private static volatile boolean tileReadbackEnabled;

    static void setTileReadbackEnabled(boolean enabled) {
        tileReadbackEnabled = enabled;
    }

    /** Hard cap on decompressed payload bytes (see {@link #parseCompound(String)}). */
    private static final int MAX_SNBT_LENGTH = 64_000;
    /** Hard cap on list length we will inspect. */
    private static final int MAX_LIST_SIZE = 54 * 27;
    /** Hard cap on NBT nesting depth during validation. */
    private static final int MAX_NBT_DEPTH = 12;

    /** Dupe/behavioral vectors rejected at every nesting level. */
    private static final Set<String> GLOBAL_FORBIDDEN_KEYS = Set.of("LootTable", "LootTableSeed");

    /** Keys present in Litematica's schematic NBT that are safe to ignore and stripped before apply. */
    private static final Set<String> STRUCTURAL_KEYS = Set.of("id", "x", "y", "z");

    /** Tile types audited as safe to carry NBT. Anything else rejects the whole paste. */
    private static final Set<Material> SUPPORTED_TILES = EnumSet.noneOf(Material.class);
    /** Per-tile item fields whose contents must be billed. Missing entry = no item contents. */
    private static final Map<Material, List<ItemField>> ITEM_FIELDS = new IdentityHashMap<>();

    /** Method cache keyed by (declaring class, name, param signature). Misses are cached as null. */
    private static final ConcurrentHashMap<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    static {
        // Group A — containers: the Items list is their only item source.
        List<Material> containers = List.of(
                resolve("CHEST"), resolve("TRAPPED_CHEST"), resolve("BARREL"),
                resolve("HOPPER"), resolve("DROPPER"), resolve("DISPENSER"),
                resolve("FURNACE"), resolve("BLAST_FURNACE"), resolve("SMOKER"),
                resolve("BREWING_STAND"), resolve("CRAFTER"), resolve("CHISELED_BOOKSHELF"),
                resolve("CAMPFIRE"), resolve("SOUL_CAMPFIRE"));
        for (Material material : containers) {
            if (material != null) register(material, new ItemField(FieldKind.LIST, "Items"));
        }
        for (String color : List.of("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK",
                "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK")) {
            Material shulker = resolve(color + "_SHULKER_BOX");
            if (shulker != null) register(shulker, new ItemField(FieldKind.LIST, "Items"));
        }

        // Group B — single-item tiles.
        register(resolve("LECTERN"), new ItemField(FieldKind.SINGLE, "Book"));
        register(resolve("JUKEBOX"), new ItemField(FieldKind.SINGLE, "RecordItem"));
        register(resolve("DECORATED_POT"), new ItemField(FieldKind.SINGLE, "item"),
                new ItemField(FieldKind.SHERDS, "sherds"));

        // Group C — state-only tiles (no item fields).
        for (String wood : List.of("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
                "MANGROVE", "CHERRY", "BAMBOO", "CRIMSON", "WARPED")) {
            addTiles(wood + "_SIGN", wood + "_WALL_SIGN", wood + "_HANGING_SIGN", wood + "_WALL_HANGING_SIGN");
        }
        for (String color : List.of("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK",
                "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK")) {
            addTiles(color + "_BANNER");
        }
        addTiles("SKELETON_SKULL", "SKELETON_WALL_SKULL", "WITHER_SKELETON_SKULL", "WITHER_SKELETON_WALL_SKULL",
                "ZOMBIE_HEAD", "ZOMBIE_WALL_HEAD", "PLAYER_HEAD", "PLAYER_WALL_HEAD",
                "CREEPER_HEAD", "CREEPER_WALL_HEAD", "PIGLIN_HEAD", "PIGLIN_WALL_HEAD",
                "BEACON", "ENCHANTING_TABLE", "CONDUIT", "BELL",
                "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "SCULK_SHRIEKER",
                "BEE_NEST", "BEEHIVE",
                "COMPARATOR", "SCULK_CATALYST");

        // Deliberately unsupported: Vault (unbilled server_data items) and End Gateway (ExitPortal grief).
    }

    /** How an item-bearing field is laid out in the tile's compound. */
    private enum FieldKind { LIST, SINGLE, SHERDS }

    private record ItemField(FieldKind kind, String key) {}

    private static void register(Material material, ItemField... fields) {
        if (material == null) return;
        SUPPORTED_TILES.add(material);
        ITEM_FIELDS.merge(material, List.of(fields), (a, b) -> {
            List<ItemField> merged = new ArrayList<>(a);
            merged.addAll(b);
            return merged;
        });
    }

    private static void addTiles(String... names) {
        for (String name : names) {
            Material material = resolve(name);
            if (material != null) SUPPORTED_TILES.add(material);
        }
    }

    private static Material resolve(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private PaperNbtHelper() {}

    /** One item read from a container's item fields: Bukkit mirror + count (+ optional NMS stack). */
    record ItemInstance(ItemStack bukkit, Object nms, long count) {}

    /** Result of validating an SNBT string before it is applied to the world. */
    sealed interface NbtCheck {
        record Ok(Object compound, Material material, List<ItemInstance> items, boolean hasItems) implements NbtCheck {}
        record Rejected(String reason) implements NbtCheck {}
    }

    /**
     * Parse and validate an SNBT string against the security invariants for {@code material}.
     * Returns {@link NbtCheck.Rejected} when the SNBT is too large, unparseable, belongs to an
     * unaudited tile, contains {@code LootTable}/{@code LootTableSeed}, or carries a forbidden or
     * undecodable item. Otherwise returns the parsed compound plus every billable item.
     */
    static NbtCheck validateForBlock(String snbt, Material material, Object registryAccess) {
        if (snbt == null || snbt.isBlank()) return new NbtCheck.Ok(null, material, List.of(), false);
        if (snbt.length() > MAX_SNBT_LENGTH) {
            return new NbtCheck.Rejected("nbt_too_large");
        }
        Object compound = parseCompound(snbt);
        if (compound == null) return new NbtCheck.Rejected("unparseable_nbt");
        if (!SUPPORTED_TILES.contains(material)) {
            return new NbtCheck.Rejected("nbt_not_supported_for_block");
        }
        String reject = validateKeys(compound, 0);
        if (reject != null) return new NbtCheck.Rejected(reject);

        List<ItemInstance> items = collectBillableItems(compound, material, registryAccess);
        if (items == null) return new NbtCheck.Rejected("forbidden_item_in_nbt");
        return new NbtCheck.Ok(compound, material, items, !items.isEmpty());
    }

    /**
     * Collect every item stack this tile will store, for billing. Returns {@code null} when any
     * item is forbidden or undecodable (the paste must be rejected rather than placed unbilled).
     */
    private static List<ItemInstance> collectBillableItems(Object compound, Material material, Object registryAccess) {
        List<ItemField> fields = ITEM_FIELDS.get(material);
        if (fields == null || fields.isEmpty()) return List.of();
        List<ItemInstance> out = new ArrayList<>();
        for (ItemField field : fields) {
            Object tag = getTag(compound, field.key());
            if (tag == null) continue;
            switch (field.kind()) {
                case LIST -> {
                    if (!tag.getClass().getName().equals(LIST_TAG)) return null;
                    int size = listSize(tag);
                    for (int i = 0; i < size; i++) {
                        Object element = listGet(tag, i);
                        if (element == null || !element.getClass().getName().equals(COMPOUND_TAG)) return null;
                        Decoded decoded = decodeItem(element, registryAccess);
                        if (decoded == null) return null;
                        out.add(new ItemInstance(decoded.bukkit(), decoded.nms(), decoded.count()));
                    }
                }
                case SINGLE -> {
                    if (!tag.getClass().getName().equals(COMPOUND_TAG)) return null;
                    Decoded decoded = decodeItem(tag, registryAccess);
                    if (decoded == null) return null;
                    out.add(new ItemInstance(decoded.bukkit(), decoded.nms(), decoded.count()));
                }
                case SHERDS -> {
                    if (!tag.getClass().getName().equals(LIST_TAG)) return null;
                    int size = listSize(tag);
                    for (int i = 0; i < size; i++) {
                        String id = stringValue(listGet(tag, i));
                        Material sherd = id == null ? null : PaperInventoryHelper.resolveMaterial(id);
                        if (sherd == null || !sherd.isItem() || RestrictedMaterials.isForbiddenItem(sherd)) return null;
                        ItemStack bukkit = new ItemStack(sherd, 1);
                        out.add(new ItemInstance(bukkit, null, 1));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Recursively reject dupe vectors ({@code LootTable}/{@code LootTableSeed}) anywhere in the
     * tree, bounded by depth and list length. All other keys are structural or benign state.
     *
     * @return null if valid, otherwise a short rejection reason code
     */
    private static String validateKeys(Object compound, int depth) {
        if (depth > MAX_NBT_DEPTH) return "nbt_too_deep";
        Set<String> keys = compoundKeys(compound);
        if (keys == null) return "nbt_reflection_error";
        for (String key : keys) {
            if (GLOBAL_FORBIDDEN_KEYS.contains(key)) return "forbidden_nbt_key:" + key;
            if (STRUCTURAL_KEYS.contains(key)) continue;
            Object child = getTag(compound, key);
            String childClass = child == null ? null : child.getClass().getName();
            if (COMPOUND_TAG.equals(childClass)) {
                String inner = validateKeys(child, depth + 1);
                if (inner != null) return inner;
            } else if (LIST_TAG.equals(childClass)) {
                int size = listSize(child);
                if (size > MAX_LIST_SIZE) return "nbt_list_too_large:" + key;
                for (int i = 0; i < size; i++) {
                    Object element = listGet(child, i);
                    if (element != null && element.getClass().getName().equals(COMPOUND_TAG)) {
                        String inner = validateKeys(element, depth + 1);
                        if (inner != null) return inner;
                    }
                }
            }
        }
        return null;
    }

    /** Entity validation: same forbidden-key sweep as {@link #validateKeys}, package-visible. */
    static String validateEntityKeys(Object compound) {
        return validateKeys(compound, 0);
    }

    /** The entity type id string (the compound's {@code id}), or null. */
    static String entityTypeId(Object compound) {
        if (compound == null) return null;
        String value = stringValue(getTag(compound, "id"));
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Decode an entity container's {@code Items} list (minecart/hopper/furnace minecarts) for
     * billing. Returns {@code null} when any entry is forbidden or undecodable (entity must be
     * rejected), otherwise the list of billable stacks.
     */
    static List<ItemInstance> decodeEntityItems(Object compound, Object registryAccess) {
        Object tag = getTag(compound, "Items");
        if (tag == null) return List.of();
        if (!tag.getClass().getName().equals(LIST_TAG)) return null;
        int size = listSize(tag);
        List<ItemInstance> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object element = listGet(tag, i);
            if (element == null || !element.getClass().getName().equals(COMPOUND_TAG)) return null;
            Decoded decoded = decodeItem(element, registryAccess);
            if (decoded == null) return null;
            out.add(new ItemInstance(decoded.bukkit(), decoded.nms(), decoded.count()));
        }
        return out;
    }

    /** Parse SNBT into an NMS {@code CompoundTag}, or {@code null} when unavailable or malformed. */
    static Object parseCompound(String snbt) {
        if (snbt == null || snbt.isBlank()) return null;
        Object parsed = tryParse(snbt, "net.minecraft.nbt.TagParser", "parseCompoundFully");
        if (parsed == null) parsed = tryParse(snbt, "net.minecraft.nbt.TagParser", "parseTag");
        if (parsed == null) parsed = tryParse(snbt, "net.minecraft.nbt.TagParser", "parseCompound");
        if (parsed == null) parsed = tryParseReader(snbt);
        return parsed;
    }

    private static Object tryParse(String snbt, String className, String methodName) {
        try {
            Class<?> parser = Class.forName(className);
            Method method = parser.getMethod(methodName, String.class);
            Object result = method.invoke(null, snbt);
            return isCompound(result) ? result : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Object tryParseReader(String snbt) {
        try {
            Class<?> reader = Class.forName("net.minecraft.nbt.StringNbtReader");
            Object instance = reader.getConstructor(java.io.Reader.class).newInstance(new java.io.StringReader(snbt));
            Object result = reader.getMethod("read").invoke(instance);
            return isCompound(result) ? result : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * Apply a previously-validated SNBT to an already-placed block. The caller must have already
     * run {@link #validateForBlock} so this method does not re-validate; it only writes the data.
     * Returns false if the block is not a tile entity or the NMS API is unavailable.
     */
    static boolean applyNbt(Block block, String snbt) {
        if (snbt == null || snbt.isBlank()) return true;
        Object compound = parseCompound(snbt);
        if (compound == null) return false;
        return applyNbt(block, compound);
    }

    /**
     * Apply a previously-parsed and validated NMS compound to an already-placed block.
     * This is the fast path used during mutation: the compound has already passed tile and
     * item validation, so we only write it and update the tile state.
     */
    static boolean applyNbt(Block block, Object compound) {
        if (compound == null) return true;
        BlockState state = block.getState();
        if (!(state instanceof TileState)) return false;
        boolean readback = tileReadbackEnabled;
        try {
            Object copy = cloneCompound(compound);
            if (copy == null) copy = compound;
            for (String structural : STRUCTURAL_KEYS) {
                removeTag(copy, structural);
            }
            normalizeSignText(block.getType(), copy);
            putInt(copy, "x", block.getX());
            putInt(copy, "y", block.getY());
            putInt(copy, "z", block.getZ());
            Class<?> compoundClass = Class.forName(COMPOUND_TAG);
            Method loadData = state.getClass().getMethod("loadData", compoundClass);
            loadData.invoke(state, copy);
            state.update(true, false);
            if (readback) logReadback(block, copy, null);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (readback) logReadback(block, compound, e);
            return false;
        }
    }

    /** Debug aid: print what was applied and what the server tile now holds. */
    private static void logReadback(Block block, Object compound, Throwable failure) {
        try {
            String readBack = readBackTileNbt(block.getState());
            StringBuilder sb = new StringBuilder();
            sb.append("tile-readback pos=").append(block.getX()).append(',').append(block.getY()).append(',').append(block.getZ())
                    .append(" type=").append(block.getType());
            if (failure != null) {
                sb.append(" LOAD_FAILED=").append(failure);
            } else {
                sb.append(" applied=").append(compound == null ? "<null>" : truncate(compound.toString()));
            }
            sb.append(" serverTile=").append(readBack);
            org.bukkit.Bukkit.getLogger().info("[MaxFastBuild] " + sb);
        } catch (RuntimeException ignored) {
        }
    }

    private static String readBackTileNbt(BlockState state) {
        try {
            Method getSnapshot = method(state.getClass(), "getSnapshotNBT");
            if (getSnapshot != null) {
                Object nbt = getSnapshot.invoke(state);
                return nbt == null ? "<none>" : truncate(nbt.toString());
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        try {
            Method serialize = method(state.getClass(), "serializeNBT");
            if (serialize != null) {
                Object nbt = serialize.invoke(state);
                return nbt == null ? "<none>" : truncate(nbt.toString());
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return "<unavailable>";
    }

    private static String truncate(String value) {
        if (value == null) return "<null>";
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }

    /**
     * Signs store their four lines in a fixed-length {@code messages} list (one entry per line).
     * Schematics often carry only the filled lines, and 26.2's strict decoder rejects a shorter
     * list and silently drops the text. Pad/truncate both faces to exactly four lines so the
     * text is written into the tile.
     */
    private static void normalizeSignText(Material material, Object compound) {
        String name = material == null ? "" : material.name();
        boolean sign = name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN")
                || name.endsWith("_HANGING_SIGN") || name.endsWith("_WALL_HANGING_SIGN");
        if (!sign) return;
        normalizeMessages(compound, "front_text");
        normalizeMessages(compound, "back_text");
    }

    private static void normalizeMessages(Object compound, String sideKey) {
        Object side = getTag(compound, sideKey);
        if (side == null || !side.getClass().getName().equals(COMPOUND_TAG)) return;
        Object messages = getTag(side, "messages");
        if (messages == null || !messages.getClass().getName().equals(LIST_TAG)) return;
        int size = listSize(messages);
        if (size < 4) {
            Object emptyLine = stringTag("");
            for (int i = size; i < 4; i++) {
                listAdd(messages, emptyLine);
            }
        } else if (size > 4) {
            for (int i = size - 1; i >= 4; i--) {
                listRemove(messages, i);
            }
        }
    }

    private static Object stringTag(String value) {
        try {
            Method valueOf = Class.forName("net.minecraft.nbt.StringTag").getMethod("valueOf", String.class);
            return valueOf.invoke(null, value);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static void listAdd(Object list, Object tag) {
        if (tag == null) return;
        try {
            Method add = list.getClass().getMethod("add", int.class, Class.forName("net.minecraft.nbt.Tag"));
            add.invoke(list, listSize(list), tag);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private static void listRemove(Object list, int index) {
        try {
            Method remove = list.getClass().getMethod("remove", int.class);
            remove.invoke(list, index);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    /** Registry lookup for item decoding, from the world's {@code ServerLevel.registryAccess()}. */
    static Object registryAccess(World world) {
        try {
            Object nmsWorld = world.getClass().getMethod("getHandle").invoke(world);
            return nmsWorld.getClass().getMethod("registryAccess").invoke(nmsWorld);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Whether the target material may carry validated NBT at all. */
    static boolean supportsNbt(Material material) {
        return material != null && SUPPORTED_TILES.contains(material);
    }

    /** A decoded container item: Bukkit copy for billing, NMS stack when available, and count. */
    private record Decoded(ItemStack bukkit, Object nms, long count) {}

    private static Decoded decodeItem(Object entry, Object registryAccess) {
        Object nms = decodeItemViaCodec(entry, registryAccess);
        if (nms != null) {
            ItemStack bukkit = asBukkitCopy(nms);
            if (bukkit == null || bukkit.getType().isAir()) return null;
            int count = getCount(nms);
            return new Decoded(bukkit, nms, Math.max(1, count));
        }
        // Manual fallback (codec unavailable): id + Count only. Exact-meta billing degrades to
        // material+count, which still prevents item duplication.
        String id = stringValue(getTag(entry, "id"));
        if (id == null || id.isBlank()) return null;
        Material material = PaperInventoryHelper.resolveMaterial(id);
        if (material == null || !material.isItem() || RestrictedMaterials.isForbiddenItem(material)) return null;
        int count = intFromTag(entry, "Count");
        if (count < 1) count = intFromTag(entry, "count");
        if (count < 1) count = 1;
        return new Decoded(new ItemStack(material, count), null, count);
    }

    private static Object decodeItemViaCodec(Object entry, Object registryAccess) {
        try {
            if (registryAccess == null) return null;
            Object ops = fieldValue("net.minecraft.nbt.NbtOps", "INSTANCE");
            Class<?> registryOps = Class.forName("net.minecraft.resources.RegistryOps");
            Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
            Method create = registryOps.getMethod("create", dynamicOps, provider);
            Object registry = create.invoke(null, ops, registryAccess);
            Object itemCodec = fieldValue("net.minecraft.world.item.ItemStack", "CODEC");
            Class<?> codecType = Class.forName("com.mojang.serialization.Decoder");
            Method parse = codecType.getMethod("parse", dynamicOps, Object.class);
            Object dataResult = parse.invoke(itemCodec, registry, entry);
            Class<?> resultType = Class.forName("com.mojang.serialization.DataResult");
            Method getOrThrow = resultType.getMethod("getOrThrow");
            return getOrThrow.invoke(dataResult);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static ItemStack asBukkitCopy(Object nms) {
        try {
            Method copy = method(nms.getClass(), "asBukkitCopy");
            return copy == null ? null : (ItemStack) copy.invoke(nms);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Object getTag(Object compound, String key) {
        if (compound == null) return null;
        try {
            Method get = method(compound.getClass(), "get", String.class);
            return get == null ? null : get.invoke(compound, key);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** NMS ItemStack count: try the 26.2 {@code count()} then the 1.21.x {@code getCount()}. */
    private static int getCount(Object nms) {
        int count = getInt(nms, "count", -1);
        if (count >= 0) return count;
        return getInt(nms, "getCount", 1);
    }

    private static int getInt(Object nms, String methodName, int fallback) {
        Method get = method(nms.getClass(), methodName);
        if (get == null) return fallback;
        try {
            Object value = get.invoke(nms);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException | LinkageError e) {
            return fallback;
        }
    }

    /** Numeric tag value (ByteTag/IntTag) used by the manual item decode fallback. */
    private static int intFromTag(Object compound, String key) {
        Object tag = getTag(compound, key);
        if (tag == null) return 0;
        Method intValue = method(tag.getClass(), "intValue");
        if (intValue == null) return 0;
        try {
            Object value = intValue.invoke(tag);
            return value instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | LinkageError e) {
            return 0;
        }
    }

    private static void putInt(Object compound, String key, int value) {
        try {
            Method put = method(compound.getClass(), "putInt", String.class, int.class);
            if (put != null) put.invoke(compound, key, value);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    /** Put a string tag (used to re-add an entity's {@code id}). */
    static void putString(Object compound, String key, String value) {
        try {
            Method valueOf = method(Class.forName("net.minecraft.nbt.StringTag"), "valueOf", String.class);
            if (valueOf == null) return;
            putTag(compound, key, valueOf.invoke(null, value));
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    /** Put a {@code [x,y,z]} double list tag (used to re-add an entity's {@code Pos}). */
    static void putDoubleList(Object compound, String key, double x, double y, double z) {
        try {
            Object list = Class.forName(LIST_TAG).getConstructor().newInstance();
            Method valueOf = method(Class.forName("net.minecraft.nbt.DoubleTag"), "valueOf", double.class);
            if (valueOf == null) return;
            listAdd(list, valueOf.invoke(null, x));
            listAdd(list, valueOf.invoke(null, y));
            listAdd(list, valueOf.invoke(null, z));
            putTag(compound, key, list);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private static void putTag(Object compound, String key, Object tag) {
        try {
            Method put = method(compound.getClass(), "put", String.class, Class.forName("net.minecraft.nbt.Tag"));
            if (put != null) put.invoke(compound, key, tag);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private static void removeTag(Object compound, String key) {
        try {
            Method remove = method(compound.getClass(), "remove", String.class);
            if (remove != null) remove.invoke(compound, key);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    static Object cloneCompound(Object compound) {
        Method copy = method(compound.getClass(), "copy");
        if (copy == null) return null;
        try {
            return copy.invoke(compound);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** CompoundTag keys: 26.2 {@code keySet()} then 1.21.x {@code getAllKeys()}. */
    private static Set<String> compoundKeys(Object compound) {
        Method keySet = method(compound.getClass(), "keySet");
        if (keySet != null) {
            try {
                Object result = keySet.invoke(compound);
                if (result instanceof Set<?> set) {
                    @SuppressWarnings("unchecked")
                    Set<String> cast = (Set<String>) (Set<?>) set;
                    return cast;
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        Method getAllKeys = method(compound.getClass(), "getAllKeys");
        if (getAllKeys == null) return null;
        try {
            Object result = getAllKeys.invoke(compound);
            if (result instanceof Set<?> set) {
                @SuppressWarnings("unchecked")
                Set<String> cast = (Set<String>) (Set<?>) set;
                return cast;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }

    private static int listSize(Object tag) {
        if (tag instanceof List<?> list) return list.size();
        Method size = method(tag.getClass(), "size");
        if (size == null) return 0;
        try {
            Object result = size.invoke(tag);
            return result instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | LinkageError e) {
            return 0;
        }
    }

    private static Object listGet(Object tag, int index) {
        if (tag instanceof List<?> list) {
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        Method get = method(tag.getClass(), "get", int.class);
        if (get == null) return null;
        try {
            return get.invoke(tag, index);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** String tag value: 26.2 {@code value()}/{@code asString()}, 1.21.x {@code getAsString()}. */
    private static String stringValue(Object tag) {
        if (tag == null) return null;
        Method value = method(tag.getClass(), "value");
        if (value != null) {
            try {
                Object result = value.invoke(tag);
                if (result instanceof String s) return s;
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        Method asString = method(tag.getClass(), "asString");
        if (asString != null) {
            try {
                Object result = asString.invoke(tag);
                if (result instanceof Optional<?> optional && optional.isPresent() && optional.get() instanceof String s) return s;
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        Method getAsString = method(tag.getClass(), "getAsString");
        if (getAsString != null) {
            try {
                Object result = getAsString.invoke(tag);
                if (result != null) return result.toString();
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
        }
        return tag.toString();
    }

    private static Object fieldValue(String className, String fieldName) {
        try {
            return Class.forName(className).getField(fieldName).get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static boolean isCompound(Object result) {
        return result != null && result.getClass().getName().equals(COMPOUND_TAG);
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        MethodKey key = new MethodKey(owner, name, Arrays.toString(params));
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                return owner.getMethod(name, params);
            } catch (NoSuchMethodException | SecurityException e) {
                return null;
            }
        });
    }

    private record MethodKey(Class<?> owner, String name, String params) {}
}
