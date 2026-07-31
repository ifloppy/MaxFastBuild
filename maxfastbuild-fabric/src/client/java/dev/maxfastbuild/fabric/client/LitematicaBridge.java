package dev.maxfastbuild.fabric.client;

import com.mojang.logging.LogUtils;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-paste collection cap; refreshed from the server hello handshake. */
    private static volatile int maxBlocks = PROTOCOL_CAP;

    public enum Reason {
        NOT_LOADED, NO_PLACEMENT, ALL_DISABLED, NO_CONTAINER, ZERO_BLOCKS, TOO_LARGE, API_ERROR
    }

    private static Reason lastReason = Reason.NOT_LOADED;

    private LitematicaBridge() {}

    /** Server-advertised paste limit (clamped to what the transfer protocol can carry). */
    public static void setMaxBlocks(int value) {
        if (value > 0) maxBlocks = Math.min(value, PROTOCOL_CAP);
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
                List<PasteBlock> blocks = collectPlacement(placement);
                if (!blocks.isEmpty()) return blocks;
            }
            if (!sawEnabled) lastReason = Reason.ALL_DISABLED;
            return List.of();
        } catch (TooLargeException ex) {
            lastReason = Reason.TOO_LARGE;
            return List.of();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            lastReason = Reason.API_ERROR;
            LOGGER.warn("[MaxFastBuild] Failed to read the Litematica placement", ex);
            return List.of();
        }
    }

    private static List<PasteBlock> collectPlacement(Object placement) throws ReflectiveOperationException {
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
            collectSubRegion(result, schematic, entry.getKey(), entry.getValue(), container, origin,
                    placementMirror, placementRotation);
        }
        if (result.isEmpty()) lastReason = Reason.ZERO_BLOCKS;
        if (result.size() > maxBlocks) throw new TooLargeException();
        return result;
    }

    private static void collectSubRegion(List<PasteBlock> result, Object schematic, Object name, Object subRegion,
            Object container, BlockPos origin, Mirror placementMirror, Rotation placementRotation)
            throws ReflectiveOperationException {
        Mirror subMirror = (Mirror) invoke(subRegion, "getMirror");
        Rotation subRotation = (Rotation) invoke(subRegion, "getRotation");
        BlockPos subPos = (BlockPos) invoke(subRegion, "getPos");

        Vec3i size = (Vec3i) invoke(schematic, "getAreaSize", new Class<?>[]{String.class}, name);
        if (size == null) size = (Vec3i) invoke(container, "getSize");
        int w = Math.abs(size.getX());
        int h = Math.abs(size.getY());
        int l = Math.abs(size.getZ());
        if (w == 0 || h == 0 || l == 0) return;
        if ((long) w * h * l > maxBlocks) throw new TooLargeException();

        BlockPos end = subPos.offset(relativeEnd(size));
        BlockPos minCorner = minCorner(subPos, end);
        BlockPos boxT = getTransformedBlockPos(subPos, placementMirror, placementRotation);
        Rotation combined = placementRotation.getRotated(subRotation);
        Mirror subMirrorAdj = adjustedSubMirror(subMirror, placementRotation);

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
                    try {
                        result.add(new PasteBlock(worldPos.getX(), worldPos.getY(), worldPos.getZ(), blockData));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
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
