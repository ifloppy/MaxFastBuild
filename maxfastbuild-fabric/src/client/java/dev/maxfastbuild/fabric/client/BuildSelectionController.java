package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.shape.DefaultShapeGenerator;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
import dev.maxfastbuild.fabric.client.platform.HudCanvas;
import dev.maxfastbuild.fabric.client.platform.PreviewSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client selection: hold a block to place, hold a tool to break (empty hand = no mode).
 * <p>
 * Shift+scroll adjusts look-distance up to {@link #MAX_LOOK_DISTANCE}. Right-click confirms,
 * including floating air cells. Preview draws only the outer shell.
 */
public final class BuildSelectionController {
    /** No client-side preview cap; the server enforces {@code execution.max-region-blocks}. */
    private static final int UNBOUNDED = Integer.MAX_VALUE;
    private static final int FACE_DRAW_LIMIT = 6000;
    /** Max floating look depth and ray length for selection (Shift+scroll). */
    private static final int MAX_LOOK_DISTANCE = 64;
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final Direction[] FACE_DIRS = {
            Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH
    };

    private static BuildMode mode = BuildMode.LINE;
    private static int currentHollow;
    private static boolean prevShiftDown;
    private static BlockPos first;
    private static BlockPos hovered;
    private static boolean active;
    /** Blocks along look ray used when the ray misses (floating pick). */
    private static int lookDistance = 5;
    /** Full shape set (for neighbor tests / cuboid detect). */
    private static Set<BlockPos> cachedFull = Set.of();
    /** Exposed faces only: block + outward direction. */
    private static List<PreviewFace> cachedFaces = List.of();
    private static BlockPos cacheFirst;
    private static BlockPos cacheSecond;
    private static BuildMode cacheMode;
    private static boolean cacheBreak;
    /** Pending submit state held while the PlaceSettingsScreen is open. */
    private static BlockPos pendingSecond;
    private static boolean pendingBreaking;
    private static String pendingModeKey;
    private static int pendingCount;

    private BuildSelectionController() {}

    static void selectMode(BuildMode selected) {
        mode = selected;
        currentHollow = 0;
        first = null;
        hovered = null;
        active = true;
        cachedFull = Set.of();
        cachedFaces = List.of();
        clampLookDistance(Minecraft.getInstance());
        notify(Component.translatable("maxfastbuild.selection.mode_selected", modeName()));
        if (submodeCount() > 1) {
            notify(submodeLabel());
        }
    }

    static boolean active() {
        return active;
    }

    static BuildMode mode() {
        return mode;
    }

    static int lookDistance() {
        return lookDistance;
    }

    /**
     * Shift+scroll adjusts look distance. Ctrl+scroll adjusts hollow/submode.
     * Both must return true so hotbar does not change.
     */
    public static boolean onHotbarScroll(double vertical) {
        if (!active || vertical == 0) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        if (ClientPlatform.instance().isCtrlKeyDown()) {
            int delta = vertical > 0 ? 1 : -1;
            if (mode == BuildMode.SLOPE_FLOOR) {
                currentHollow = (currentHollow + delta + 3) % 3;
            } else if (isVolumeMode(mode)) {
                currentHollow = Math.max(0, Math.min(MAX_HOLLOW, currentHollow + delta));
            } else {
                return true;
            }
            cachedFull = Set.of();
            cachedFaces = List.of();
            notify(submodeLabel());
            return true;
        }
        if (!client.player.isShiftKeyDown()) return false;
        int delta = vertical > 0 ? 1 : -1;
        int next = Math.max(1, Math.min(MAX_LOOK_DISTANCE, lookDistance + delta));
        if (next == lookDistance) return true;
        lookDistance = next;
        refreshHovered(client);
        return true;
    }

    static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!active || !level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        return confirmPick(player, level);
    }

    /** Air / miss right-click: floating pick. Skip when crosshair is on a block (UseBlock handles that). */
    static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (!active || !level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        Minecraft client = Minecraft.getInstance();
        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }
        return confirmPick(player, level);
    }

    static boolean cancelOnAttack(Minecraft client, int clickCount) {
        if (!active || clickCount == 0) return false;
        cancel();
        return true;
    }

    static void tick(Minecraft client) {
        if (!active || client.player == null || client.level == null) return;
        clampLookDistance(client);
        boolean shiftDown = client.player.isShiftKeyDown();
        if (shiftDown && !prevShiftDown && !ClientPlatform.instance().isScreenOpen(client)) {
            toggleSubmode();
        }
        prevShiftDown = shiftDown;
        refreshHovered(client);
        refreshPreviewCache(client.player);
    }

    /** Snapshot of the current selection for the version-specific world-space renderer. */
    public static PreviewSnapshot collectPreview() {
        if (!active) return null;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        boolean breaking = isBreakIntent(client.player);
        boolean placing = isPlaceIntent(client.player);
        if (!breaking && !placing && hovered == null) return null;
        BlockPos end = hovered != null ? hovered : first;
        Bounds b = first != null && end != null ? new Bounds(first, end) : null;
        AABB box = b != null
                ? new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1)
                : null;
        return new PreviewSnapshot(first, hovered, box, mode, breaking, placing,
                first != null && isSimpleCuboidPreview(mode) && !cachedFull.isEmpty(), cachedFaces);
    }

    private static boolean isSimpleCuboidPreview(BuildMode m) {
        return m == BuildMode.CUBE || m == BuildMode.FLOOR || m == BuildMode.SINGLE;
    }

    public static void renderHud(HudCanvas canvas) {
        PasteController.renderHud(canvas);
        if (!active) return;
        Minecraft client = Minecraft.getInstance();
        boolean breaking = client.player != null && isBreakIntent(client.player);
        boolean placing = client.player != null && isPlaceIntent(client.player);
        int center = canvas.guiWidth() / 2;
        // Kept clear of the vanilla actionbar area (which sits just above the hotbar and carries
        // MFB feedback such as "preview too large"), so error text is never covered by this box.
        int y = canvas.guiHeight() - 108;
        int outline = !breaking && !placing ? 0xCCB7C0CC : (breaking ? 0xCCFF8A4D : 0xCC65D9FF);
        int titleColor = !breaking && !placing ? 0xFFB7C0CC : (breaking ? 0xFFFF8A4D : 0xFF8EE9FF);
        canvas.fill(center - 170, y - 5, center + 170, y + 65, 0xB018202A);
        canvas.outline(center - 170, y - 5, 340, 70, outline);
        Component title = !breaking && !placing
                ? Component.translatable("maxfastbuild.selection.title_none", modeName())
                : Component.translatable(
                        breaking ? "maxfastbuild.selection.title_break" : "maxfastbuild.selection.title", modeName());
        canvas.centeredText(client.font, title, center, y, titleColor);
        if (!breaking && !placing) {
            canvas.centeredText(client.font, Component.translatable("maxfastbuild.selection.hold_block_or_tool"),
                    center, y + 12, 0xFFFFFFFF);
        } else if (first == null) {
            canvas.centeredText(client.font, Component.translatable("maxfastbuild.selection.pick_first"), center, y + 12, 0xFFFFFFFF);
        } else {
            String second = hovered == null ? "-" : coordinates(hovered);
            canvas.centeredText(client.font, Component.translatable("maxfastbuild.selection.pick_second", coordinates(first), second),
                    center, y + 12, 0xFFFFFFFF);
        }
        canvas.centeredText(client.font, Component.translatable(
                        "maxfastbuild.selection.aim_hud",
                        lookDistance, MAX_LOOK_DISTANCE),
                center, y + 24, 0xFFB7C0CC);
        canvas.centeredText(client.font, Component.translatable(
                        breaking ? "maxfastbuild.selection.cancel_hint_break"
                                : (placing ? "maxfastbuild.selection.cancel_hint" : "maxfastbuild.selection.cancel_hint_none")),
                center, y + 36, 0xFFB7C0CC);
        Component sm = submodeLabel();
        if (sm != null) {
            canvas.centeredText(client.font, sm, center, y + 50, 0xFFA0A0A0);
        }
    }

    private static InteractionResult confirmPick(Player player, Level level) {
        boolean breaking = isBreakIntent(player);
        boolean placing = isPlaceIntent(player);
        if (!breaking && !placing) {
            notify(Component.translatable("maxfastbuild.selection.hold_block_or_tool"));
            return InteractionResult.FAIL;
        }
        BlockPos selected = resolveTarget(player, level, breaking);
        if (selected == null) {
            notify(Component.translatable("maxfastbuild.selection.no_target"));
            return InteractionResult.FAIL;
        }
        if (first == null) {
            first = selected;
            hovered = selected;
            notify(Component.translatable("maxfastbuild.selection.first_set", coordinates(first)));
        } else {
            submit(player, selected, breaking);
        }
        return InteractionResult.FAIL;
    }

    private static void refreshHovered(Minecraft client) {
        if (client.player == null || client.level == null) return;
        boolean breaking = isBreakIntent(client.player);
        hovered = resolveTarget(client.player, client.level, breaking);
    }

    /**
     * Ray up to max(vanilla reach, lookDistance) capped at 64.
     * Hit solid → vanilla place/break cell; miss → floating cell at lookDistance.
     */
    private static BlockPos resolveTarget(Player player, Level level, boolean breaking) {
        double rayLength = selectionRayLength(player);
        Vec3 eye = player.getEyePosition(1f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(rayLength));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            double dist = Math.sqrt(eye.distanceToSqr(hit.getLocation()));
            if (dist <= rayLength + 1.0e-3) {
                if (breaking) return aimedBlock(hit);
                return vanillaPlacementPosition(level, hit);
            }
        }
        double d = Math.max(1, Math.min(lookDistance, MAX_LOOK_DISTANCE));
        Vec3 point = eye.add(look.scale(d));
        net.minecraft.core.BlockPos mc = net.minecraft.core.BlockPos.containing(point);
        return new BlockPos(mc.getX(), mc.getY(), mc.getZ());
    }

    /** Vanilla-like: replaceable → that cell; else adjacent face. */
    private static BlockPos vanillaPlacementPosition(Level level, BlockHitResult hit) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.canBeReplaced()) {
            return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        }
        Direction direction = hit.getDirection();
        net.minecraft.core.BlockPos adjacent = pos.relative(direction);
        return new BlockPos(adjacent.getX(), adjacent.getY(), adjacent.getZ());
    }

    private static BlockPos aimedBlock(BlockHitResult hit) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private static double selectionRayLength(Player player) {
        double vanilla = Math.max(1.0, player.blockInteractionRange());
        return Math.min(MAX_LOOK_DISTANCE, Math.max(vanilla, lookDistance));
    }

    private static void clampLookDistance(Minecraft client) {
        if (lookDistance > MAX_LOOK_DISTANCE) lookDistance = MAX_LOOK_DISTANCE;
        if (lookDistance < 1) lookDistance = 1;
    }

    private static void refreshPreviewCache(Player player) {
        if (first == null || (!isBreakIntent(player) && !isPlaceIntent(player))) {
            cachedFull = Set.of();
            cachedFaces = List.of();
            return;
        }
        BlockPos end = hovered != null ? hovered : first;
        boolean breaking = isBreakIntent(player);
        if (mode == cacheMode && first.equals(cacheFirst) && end.equals(cacheSecond) && breaking == cacheBreak) return;
        cacheMode = mode;
        cacheFirst = first;
        cacheSecond = end;
        cacheBreak = breaking;
        try {
            Set<BlockPos> full = new DefaultShapeGenerator().generate(
                    new ShapeRequest(mode, first, end, currentHollow), UNBOUNDED);
            cachedFull = full instanceof HashSet ? full : new HashSet<>(full);
            // Cuboid modes only need bounds; skip face enumeration.
            if (isSimpleCuboidPreview(mode)) {
                cachedFaces = List.of();
            } else {
                cachedFaces = exposedFaces(cachedFull);
            }
        } catch (RuntimeException ex) {
            cachedFull = Set.of();
            cachedFaces = List.of();
        }
    }

    /** One rect per missing 6-neighbor — no shared interior faces. */
    private static List<PreviewFace> exposedFaces(Set<BlockPos> full) {
        if (full.isEmpty()) return List.of();
        List<PreviewFace> faces = new ArrayList<>(Math.min(full.size() * 3, FACE_DRAW_LIMIT));
        for (BlockPos pos : full) {
            for (int i = 0; i < NEIGHBORS.length; i++) {
                int[] n = NEIGHBORS[i];
                if (!full.contains(new BlockPos(pos.x() + n[0], pos.y() + n[1], pos.z() + n[2]))) {
                    faces.add(new PreviewFace(pos, FACE_DIRS[i]));
                    if (faces.size() >= FACE_DRAW_LIMIT) return faces;
                }
            }
        }
        return faces;
    }

    private static void submit(Player player, BlockPos second, boolean breaking) {
        int count = new DefaultShapeGenerator().generate(new ShapeRequest(mode, first, second, currentHollow), UNBOUNDED).size();
        String modeKey = mode.name().toLowerCase(Locale.ROOT);
        if (breaking) {
            if (!isBreakIntent(player)) {
                notify(Component.translatable("maxfastbuild.selection.hold_tool"));
                return;
            }
            ClientSession.sendBreak(modeKey, first.x(), first.y(), first.z(), second.x(), second.y(), second.z(), currentHollow);
            notify(Component.translatable("maxfastbuild.selection.submitted_break", count));
        } else {
            String material = material(player);
            if (material == null) {
                notify(Component.translatable("maxfastbuild.selection.hold_block"));
                return;
            }
            if (isStair(player)) {
                pendingSecond = second;
                pendingBreaking = false;
                pendingModeKey = modeKey;
                pendingCount = count;
                ClientPlatform.instance().setScreen(new PlaceSettingsScreen(playerFacing(player), true));
                return;
            }
            if (isSlab(player)) {
                pendingSecond = second;
                pendingBreaking = false;
                pendingModeKey = modeKey;
                pendingCount = count;
                ClientPlatform.instance().setScreen(new PlaceSettingsScreen("north", false));
                return;
            }
            ClientSession.sendPlace(modeKey, first.x(), first.y(), first.z(), second.x(), second.y(), second.z(), currentHollow, material);
            notify(Component.translatable("maxfastbuild.selection.submitted", count));
        }
        first = null;
        hovered = null;
        active = false;
        cachedFull = Set.of();
        cachedFaces = List.of();
    }

    private static void cancel() {
        first = null;
        hovered = null;
        active = false;
        cachedFull = Set.of();
        cachedFaces = List.of();
        notify(Component.translatable("maxfastbuild.selection.cancelled"));
    }

    private static int submodeCount() {
        return switch (mode) {
            case SLOPE_FLOOR -> 3;
            case CUBE, CIRCLE, CYLINDER, SPHERE, PYRAMID, CONE -> MAX_HOLLOW + 1;
            default -> 1;
        };
    }

    private static boolean isVolumeMode(BuildMode m) {
        return switch (m) {
            case CUBE, CIRCLE, CYLINDER, SPHERE, PYRAMID, CONE -> true;
            default -> false;
        };
    }

    private static final int MAX_HOLLOW = 10;

    private static Component submodeLabel() {
        if (mode == BuildMode.SLOPE_FLOOR) {
            return slopeLabel();
        }
        if (!isVolumeMode(mode)) return null;
        String value = currentHollow == 0
                ? Component.translatable("maxfastbuild.submode.solid").getString()
                : Component.translatable("maxfastbuild.submode.hollow_shell", currentHollow).getString();
        return Component.literal("▸ " + value);
    }

    private static Component slopeLabel() {
        String[] keys = {"maxfastbuild.submode.smooth", "maxfastbuild.submode.stair_x", "maxfastbuild.submode.stair_z"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i == currentHollow ? "▸ " : "  ");
            sb.append(Component.translatable(keys[i]).getString());
        }
        return Component.literal(sb.toString());
    }

    private static void toggleSubmode() {
        int count = submodeCount();
        if (count <= 1) return;
        currentHollow = (currentHollow + 1) % count;
        cachedFull = Set.of();
        cachedFaces = List.of();
        notify(submodeLabel());
    }

    private static boolean isPlaceIntent(Player player) {
        return player.getMainHandItem().getItem() instanceof BlockItem;
    }

    /**
     * Mining tools only (pick/axe/shovel/hoe/sword/shears). Bows, rods, etc. are not break mode.
     * Empty hand is neither place nor break.
     */
    private static boolean isBreakIntent(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem) return false;
        if (!stack.isDamageableItem()) return false;
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return path.endsWith("_pickaxe")
                || path.endsWith("_axe")
                || path.endsWith("_shovel")
                || path.endsWith("_hoe")
                || path.endsWith("_sword")
                || path.equals("shears");
    }

    private static String material(Player player) {
        if (!(player.getMainHandItem().getItem() instanceof BlockItem blockItem)) return null;
        return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
    }

    private static boolean isStair(Player player) {
        if (!(player.getMainHandItem().getItem() instanceof BlockItem blockItem)) return false;
        return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).getPath().endsWith("_stairs");
    }

    private static boolean isSlab(Player player) {
        if (!(player.getMainHandItem().getItem() instanceof BlockItem blockItem)) return false;
        return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).getPath().endsWith("_slab");
    }

    private static String playerFacing(Player player) {
        Direction dir = player.getDirection();
        return switch (dir) {
            case NORTH -> "north";
            case SOUTH -> "south";
            case EAST -> "east";
            case WEST -> "west";
            default -> "north";
        };
    }

    /** Called by PlaceSettingsScreen when the user confirms placement options. */
    public static void confirmPlaceSettings(PlaceSettings settings) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || pendingModeKey == null) return;
        String material = material(client.player);
        if (material == null) return;
        if (settings.isStair()) {
            material = material + "[facing=" + settings.direction() + ",half=bottom]";
        } else {
            material = material + "[type=" + (settings.slabTop() ? "top" : "bottom") + "]";
        }
        ClientSession.sendPlace(pendingModeKey,
                first.x(), first.y(), first.z(),
                pendingSecond.x(), pendingSecond.y(), pendingSecond.z(),
                currentHollow, material);
        notify(Component.translatable("maxfastbuild.selection.submitted", pendingCount));
        first = null;
        hovered = null;
        active = false;
        pendingSecond = null;
        pendingBreaking = false;
        pendingModeKey = null;
        pendingCount = 0;
        cachedFull = Set.of();
        cachedFaces = List.of();
    }

    private static Component modeName() {
        return Component.translatable("maxfastbuild.mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static String coordinates(BlockPos pos) {
        return pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    private static void notify(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) ClientPlatform.instance().sendOverlayMessage(message);
    }
}
