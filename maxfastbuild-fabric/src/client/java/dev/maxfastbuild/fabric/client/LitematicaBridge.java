package dev.maxfastbuild.fabric.client;

import com.mojang.logging.LogUtils;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Litematica integration, reached entirely via reflection for the {@code fi.dy.masa.*}
 * classes so MaxFastBuild never has a compile-time or hard runtime dependency on Litematica. When
 * Litematica is not installed the bridge is a no-op and the paste hotkey reports {@code NOT_LOADED}.
 * <p>
 * Reads the first enabled {@code SchematicPlacement} through the modern Litematica API
 * ({@code DataManager.getSchematicPlacementManager()} → {@code getAllSchematicsPlacements()} → per
 * sub-region {@code LitematicaBlockStateContainer}), applies the placement + sub-region mirror and
 * rotation exactly like Litematica's own {@code placeBlocksToWorld}, and maps each block to an
 * absolute {@link PasteBlock}. Block-entity NBT is not preserved; the server strips any trailing
 * {@code {...}}.
 * <p>
 * {@link #lastReason()} explains why {@link #collect()} produced nothing, so the caller can show a
 * specific message instead of a generic "no placement".
 */
public final class LitematicaBridge {
    private static final int PROTOCOL_CAP = PasteTransfer.MAX_PARTS * PasteTransfer.MAX_BLOCKS_PER_PART;
    /** Default guard on the per-sub-region bounding-box volume we will iterate (anti client freeze). */
    private static final int DEFAULT_MAX_REGION_VOLUME = 8_000_000;
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-paste collection cap; refreshed from the server hello handshake. */
    private static volatile int maxBlocks = PROTOCOL_CAP;
    /**
     * Bounding-box volume a single sub-region may span while being collected. This is separate from
     * {@link #maxBlocks}: a large build (e.g. a furnace array) occupies a box whose volume far
     * exceeds its block count because of the air in between, so the box is allowed as long as it is
     * not pathologically huge. Refreshed from the server hello handshake.
     */
    private static volatile int maxRegionVolume = DEFAULT_MAX_REGION_VOLUME;

    public enum Reason {
        NOT_LOADED, NO_PLACEMENT, ALL_DISABLED, NO_CONTAINER, ZERO_BLOCKS, TOO_LARGE, API_ERROR
    }

    private static Reason lastReason = Reason.NOT_LOADED;
    /** Entities from the most recent successful {@link #collect()}, empty when none. */
    private static List<PasteEntity> lastEntities = List.of();
    /**
     * When true, the {@code Items} tag is removed from every container block-entity before it is
     * serialized, so the pasted container is empty and the server never bills its contents. Set by
     * {@code PasteController.confirmStart} from the {@code skipContents} filter before collection.
     */
    private static volatile boolean stripContainerItems;

    private LitematicaBridge() {}

    /** Toggle container {@code Items} stripping for the next {@link #collect()}. */
    public static void setStripContainerItems(boolean value) {
        stripContainerItems = value;
    }

    /** Entities collected alongside the most recent {@link #collect()} blocks. */
    public static List<PasteEntity> lastEntities() {
        return lastEntities;
    }

    /** Server-advertised paste limit (clamped to what the transfer protocol can carry). */
    public static void setMaxBlocks(int value) {
        if (value > 0) maxBlocks = Math.min(value, PROTOCOL_CAP);
    }

    /** Server-advertised per-region bounding-volume guard. */
    public static void setMaxRegionVolume(int value) {
        if (value > 0) maxRegionVolume = value;
    }

    public static int maxBlocks() {
        return maxBlocks;
    }

    public static boolean available() {
        try {
            return FabricLoader.getInstance().isModLoaded("litematica");
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    /** Reason for the most recent {@link #collect()} call; never {@code null}. */
    public static Reason lastReason() {
        return lastReason;
    }

    /**
     * @return absolute block list of the first non-empty enabled placement, empty when Litematica is
     * absent or nothing usable is placed. Never throws.
     */
    public static List<PasteBlock> collect() {
        if (!available()) {
            lastReason = Reason.NOT_LOADED;
            lastEntities = List.of();
            return List.of();
        }
        try {
            lastReason = Reason.NO_PLACEMENT;
            Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object manager = invokeStatic(dataManager, "getSchematicPlacementManager");
            if (manager == null) return List.of();
            Object placements = invoke(manager, "getAllSchematicsPlacements");
            if (!(placements instanceof Collection<?> collection)) return List.of();
            boolean sawEnabled = false;
            for (Object placement : collection) {
                if (!Boolean.TRUE.equals(invoke(placement, "isEnabled"))) continue;
                sawEnabled = true;
                List<PasteEntity> entities = new ArrayList<>();
                List<PasteBlock> blocks = collectPlacement(placement, entities);
                if (!blocks.isEmpty()) {
                    lastEntities = entities;
                    LOGGER.info("[MaxFastBuild] collected blocks=" + blocks.size() + " entities=" + entities.size());
                    return blocks;
                }
            }
            if (!sawEnabled) lastReason = Reason.ALL_DISABLED;
            lastEntities = List.of();
            return List.of();
        } catch (TooLargeException ex) {
            lastReason = Reason.TOO_LARGE;
            lastEntities = List.of();
            return List.of();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            lastReason = Reason.API_ERROR;
            lastEntities = List.of();
            LOGGER.warn("[MaxFastBuild] Failed to read the Litematica placement", ex);
            return List.of();
        }
    }

    private static List<PasteBlock> collectPlacement(Object placement, List<PasteEntity> entitiesOut)
            throws ReflectiveOperationException {
        Object schematic = invoke(placement, "getSchematic");
        Object originObj = invoke(placement, "getOrigin");
        if (schematic == null || originObj == null) return List.of();
        BlockPos origin = (BlockPos) originObj;
        Mirror placementMirror = (Mirror) invoke(placement, "getMirror");
        Rotation placementRotation = (Rotation) invoke(placement, "getRotation");

        Object subRegions = invoke(placement, "getEnabledRelativeSubRegionPlacements");
        if (!(subRegions instanceof Map<?, ?> map) || map.isEmpty()) {
            lastReason = Reason.NO_CONTAINER;
            return List.of();
        }
        List<PasteBlock> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object container = invoke(schematic, "getSubRegionContainer", new Class<?>[]{String.class}, entry.getKey());
            if (container == null) continue;
            collectSubRegion(result, entitiesOut, schematic, entry.getKey(), entry.getValue(), container, origin,
                    placementMirror, placementRotation);
        }
        if (result.isEmpty()) lastReason = Reason.ZERO_BLOCKS;
        if (result.size() > maxBlocks) throw new TooLargeException();
        return result;
    }

    private static void collectSubRegion(List<PasteBlock> result, List<PasteEntity> entitiesOut, Object schematic,
            Object name, Object subRegion, Object container, BlockPos origin, Mirror placementMirror,
            Rotation placementRotation) throws ReflectiveOperationException {
        Mirror subMirror = (Mirror) invoke(subRegion, "getMirror");
        Rotation subRotation = (Rotation) invoke(subRegion, "getRotation");
        BlockPos subPos = (BlockPos) invoke(subRegion, "getPos");

        Vec3i size = (Vec3i) invoke(schematic, "getAreaSize", new Class<?>[]{String.class}, name);
        if (size == null) size = (Vec3i) invoke(container, "getSize");
        int w = Math.abs(size.getX());
        int h = Math.abs(size.getY());
        int l = Math.abs(size.getZ());
        if (w == 0 || h == 0 || l == 0) return;
        // Bounding-box volume guard is independent of the block cap: large builds occupy a box far
        // bigger than their block count (air in between). Only stop truly pathological boxes that
        // would freeze the client; the real block cap is enforced while iterating below.
        if ((long) w * h * l > maxRegionVolume) throw new TooLargeException();

        BlockPos end = subPos.offset(relativeEnd(size));
        BlockPos minCorner = minCorner(subPos, end);
        BlockPos boxT = getTransformedBlockPos(subPos, placementMirror, placementRotation);
        Rotation combined = placementRotation.getRotated(subRotation);
        Mirror subMirrorAdj = adjustedSubMirror(subMirror, placementRotation);

        Map<String, Object> tileNbt = indexBlockEntityNbt(schematic, name);

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    Object stateObj = invoke(container, "get", new Class<?>[]{int.class, int.class, int.class}, x, y, z);
                    if (!(stateObj instanceof BlockState state)) continue;
                    if (state.getBlock() == Blocks.STRUCTURE_VOID || state.isAir()) continue;

                    BlockPos relative = new BlockPos(
                            minCorner.getX() + x - subPos.getX(),
                            minCorner.getY() + y - subPos.getY(),
                            minCorner.getZ() + z - subPos.getZ());
                    BlockPos transformed = getTransformedBlockPos(
                            getTransformedBlockPos(relative, placementMirror, placementRotation), subMirror, subRotation);
                    BlockPos worldPos = origin.offset(boxT).offset(transformed);

                    BlockState out = state;
                    if (placementMirror != Mirror.NONE) out = out.mirror(placementMirror);
                    if (subMirrorAdj != Mirror.NONE) out = out.mirror(subMirrorAdj);
                    if (combined != Rotation.NONE) out = out.rotate(combined);

                    String blockData = BlockStateParser.serialize(out);
                    if (blockData == null || blockData.isBlank()) continue;
                    // Tile-entity lookup uses the RAW container coordinates (x, y, z), the same space
                    // the schematic's TileEntities are keyed by — NOT the minCorner-shifted `relative`
                    // (which is only the world-transform basis). For negative signed sizes the two
                    // differ and the old lookup missed every tile.
                    String nbtSnbt = readBlockEntityNbt(tileNbt, new BlockPos(x, y, z), worldPos, out);
                    if (nbtSnbt != null) {
                        blockData += nbtSnbt;
                    }
                    try {
                        result.add(new PasteBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ(), blockData));
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (result.size() > maxBlocks) throw new TooLargeException();
                }
            }
        }
        collectEntities(entitiesOut, schematic, name, subRegion, origin, placementMirror, placementRotation);
    }

    /**
     * Collects the sub-region's entities (minecarts, boats, armor stands, mobs, …) via Litematica's
     * {@code getEntityListForRegion}, transforms their positions like {@code placeEntitiesToWorld},
     * strips {@code id}/{@code Pos}/{@code UUID} from the NBT, and appends them as {@link PasteEntity}.
     * No-op when the installed Litematica exposes no such API.
     */
    private static void collectEntities(List<PasteEntity> out, Object schematic, Object name, Object subRegion,
            BlockPos origin, Mirror placementMirror, Rotation placementRotation) {
        Object list;
        try {
            list = invoke(schematic, "getEntityListForRegion", new Class<?>[]{String.class}, name);
        } catch (ReflectiveOperationException ignored) {
            return;
        }
        if (!(list instanceof Collection<?> collection)) return;
        Mirror subMirror = (Mirror) invokeQuiet(subRegion, "getMirror");
        Rotation subRotation = (Rotation) invokeQuiet(subRegion, "getRotation");
        BlockPos subPos = (BlockPos) invokeQuiet(subRegion, "getPos");
        if (subMirror == null || subRotation == null || subPos == null) return;
        Rotation combined = placementRotation.getRotated(subRotation);
        Mirror subMirrorAdj = adjustedSubMirror(subMirror, placementRotation);
        BlockPos boxT = getTransformedBlockPos(subPos, placementMirror, placementRotation);
        for (Object info : collection) {
            Vec3 pos = asVec3(invokeQuiet(info, "getPosition"));
            if (pos == null) pos = asVec3(invokeQuiet(info, "getPos"));
            if (pos == null) pos = asVec3(invokeQuiet(info, "posVec"));
            if (pos == null) pos = asVec3(getFieldQuiet(info, "posVec"));
            if (pos == null) pos = asVec3(getFieldQuiet(info, "pos"));
            if (pos == null) pos = asVec3(getFieldQuiet(info, "position"));
            Object nbt = invokeQuiet(info, "getNbt");
            if (nbt == null) nbt = invokeQuiet(info, "nbt");
            if (nbt == null) nbt = getFieldQuiet(info, "nbt");
            if (pos == null || nbt == null) continue;
            String type = ClientPlatform.instance().entityType(nbt);
            if (type == null || !type.contains(":")) continue;
            String snbt = ClientPlatform.instance().entityNbtToSnbt(nbt);
            if (snbt == null || snbt.isBlank()) continue;
            Vec3 transformed = getTransformedVec3(
                    getTransformedVec3(pos, placementMirror, placementRotation), subMirrorAdj, combined);
            double wx = boxT.getX() + origin.getX() + transformed.x();
            double wy = boxT.getY() + origin.getY() + transformed.y();
            double wz = boxT.getZ() + origin.getZ() + transformed.z();
            try {
                out.add(new PasteEntity(type, wx, wy, wz, snbt));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** Mirrors/rotates a fractional position like {@link #getTransformedBlockPos} for block coordinates. */
    private static Vec3 getTransformedVec3(Vec3 pos, Mirror mirror, Rotation rotation) {
        double x = pos.x();
        double y = pos.y();
        double z = pos.z();
        switch (mirror.ordinal()) {
            case 1: z = -z; break; // LEFT_RIGHT
            case 2: x = -x; break; // FRONT_BACK
            default: break;
        }
        switch (rotation.ordinal()) {
            case 1: return new Vec3(-z, y, x);
            case 2: return new Vec3(-x, y, -z);
            case 3: return new Vec3(z, y, -x);
            default: return new Vec3(x, y, z);
        }
    }

    private static Vec3 asVec3(Object value) {
        if (value instanceof Vec3 vec) return vec;
        return null;
    }

    private static Object getFieldQuiet(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Replicates Litematica's {@code PositionUtils.getRelativeEndPositionFromAreaSize}: per axis
     * {@code size < 0 ? size + 1 : size - 1}. {@code subRegionSizes} is signed, so a negative
     * extent means the box is stored with its maximum corner as {@code pos1}.
     */
    private static BlockPos relativeEnd(Vec3i size) {
        return new BlockPos(
                size.getX() < 0 ? size.getX() + 1 : size.getX() - 1,
                size.getY() < 0 ? size.getY() + 1 : size.getY() - 1,
                size.getZ() < 0 ? size.getZ() + 1 : size.getZ() - 1);
    }

    /**
     * Indexes the sub-region's block-entity NBT via Litematica's
     * {@code LitematicaSchematic.getBlockEntityMapForRegion(String)} (present in released
     * {@code fi.dy.masa.litematica.schematic.LitematicaSchematic}), keyed by the region's
     * container-local coordinates — the exact keys Litematica's own {@code placeBlocksToWorld}
     * uses. Returns an empty map when the installed version exposes no such method; block-entity
     * NBT is then simply not captured.
     */
    private static Map<String, Object> indexBlockEntityNbt(Object schematic, Object regionName) {
        Map<String, Object> index = new HashMap<>();
        Object map;
        try {
            map = invoke(schematic, "getBlockEntityMapForRegion", new Class<?>[]{String.class}, regionName);
        } catch (ReflectiveOperationException ignored) {
            return index;
        }
        if (!(map instanceof Map<?, ?> tileMap)) return index;
        for (Map.Entry<?, ?> entry : tileMap.entrySet()) {
            Object key = entry.getKey();
            Object nbt = entry.getValue();
            if (key == null || nbt == null) continue;
            Integer tx = intOrNull(invokeQuiet(key, "getX"));
            Integer ty = intOrNull(invokeQuiet(key, "getY"));
            Integer tz = intOrNull(invokeQuiet(key, "getZ"));
            if (tx == null || ty == null || tz == null) continue;
            index.put(tx + "," + ty + "," + tz, nbt);
        }
        return index;
    }

    /**
     * Block-entity NBT for the block at {@code relative} (container-local coords, the same value
     * Litematica computes as its {@code posMutable}), serialized to SNBT, or {@code null} when the
     * block has none. The server applies the NBT to the correct absolute position itself.
     * <p>
     * Lectern fallback: Litematica 26.2 can save a lectern with {@code has_book=true} but without
     * its {@code Book} content (or no tile at all). When the schematic supplies no {@code Book},
     * the block's tile data is re-read from the client world at the absolute placement position —
     * the common build-then-paste-in-place workflow — so a pasted lectern keeps its readable book.
     */
    private static String readBlockEntityNbt(Map<String, Object> tileNbt, BlockPos relative, BlockPos worldPos, BlockState out) {
        boolean lectern = out.getBlock() == Blocks.LECTERN;
        Object nbt = (tileNbt == null) ? null : tileNbt.get(relative.getX() + "," + relative.getY() + "," + relative.getZ());
        String snbt;
        if (stripContainerItems && nbt != null && ClientPlatform.instance().nbtHasKey(nbt, "Items")) {
            snbt = ClientPlatform.instance().nbtToSnbtWithoutKey(nbt, "Items");
        } else {
            snbt = nbt == null ? null : ClientPlatform.instance().nbtToSnbt(nbt);
        }
        boolean usable = snbt != null && !snbt.isBlank() && !"{}".equals(snbt);
        // A lectern's schematic NBT counts as usable only when it actually carries a Book;
        // Litematica 26.2 can save has_book=true with no book content ({components:{}}).
        if (usable && (!lectern || ClientPlatform.instance().nbtHasKey(nbt, "Book"))) {
            return snbt;
        }
        if (lectern) {
            String worldSnbt = ClientPlatform.instance().blockEntityNbtAt(worldPos, Blocks.LECTERN);
            LOGGER.info("[MaxFastBuild] lectern fallback {} usable={} world={}", worldPos, usable, worldSnbt);
            if (worldSnbt != null && !worldSnbt.isBlank() && !"{}".equals(worldSnbt)) return worldSnbt;
        }
        return usable ? snbt : null;
    }

    private static Object invokeQuiet(Object target, String name) {
        try {
            return invoke(target, name);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BlockPos minCorner(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    /**
     * Replicates Litematica's {@code PositionUtils.getTransformedBlockPos}: mirror first, then
     * rotate, keyed by {@code Mirror}/{@code Rotation} ordinals (identical in every supported
     * version). 26.2's {@code Rotation.rotate(int,int)} semantics differ from 1.21.x, so the
     * shared code must not rely on those methods.
     */
    private static BlockPos getTransformedBlockPos(BlockPos pos, Mirror mirror, Rotation rotation) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        switch (mirror.ordinal()) {
            case 1: z = -z; break; // LEFT_RIGHT
            case 2: x = -x; break; // FRONT_BACK
            default: break;
        }
        switch (rotation.ordinal()) {
            case 1: return new BlockPos(-z, y, x);   // CLOCKWISE_90
            case 2: return new BlockPos(-x, y, -z);  // CLOCKWISE_180
            case 3: return new BlockPos(z, y, -x);   // COUNTERCLOCKWISE_90
            default: return new BlockPos(x, y, z);
        }
    }

    /**
     * Litematica swaps the sub-region mirror axes when the placement itself is rotated by 90° so the
     * mirrored axis flips meaning (see {@code placeBlocksToWorld}).
     */
    private static Mirror adjustedSubMirror(Mirror subMirror, Rotation placementRotation) {
        if (subMirror == Mirror.NONE) return subMirror;
        if (placementRotation == Rotation.CLOCKWISE_90 || placementRotation == Rotation.COUNTERCLOCKWISE_90) {
            return subMirror == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        }
        return subMirror;
    }

    private static Object invokeStatic(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = type.getMethod(name);
        return method.invoke(null);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        return target.getClass().getMethod(name, parameterTypes).invoke(target, args);
    }

    private static final class TooLargeException extends RuntimeException {
        private TooLargeException() {}
    }
}
