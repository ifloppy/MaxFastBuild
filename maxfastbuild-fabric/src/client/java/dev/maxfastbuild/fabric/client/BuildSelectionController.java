package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.*;
import dev.maxfastbuild.core.shape.DefaultShapeGenerator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
final class BuildSelectionController {
    private static final int PREVIEW_LIMIT = 8192;
    private static final int FACE_DRAW_LIMIT = 6000;
    /** Max floating look depth and ray length for selection (Shift+scroll). */
    private static final int MAX_LOOK_DISTANCE = 64;
    private static final int PLACE_STROKE = 0xFF4DE4FF;
    private static final int PLACE_FIRST = 0x664DE4FF;
    private static final int PLACE_FACE = 0x3365E08A;
    private static final int BREAK_STROKE = 0xFFFF8A4D;
    private static final int BREAK_FIRST = 0x66FF8A4D;
    private static final int BREAK_FACE = 0x33E08A65;
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final Direction[] FACE_DIRS = {
            Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH
    };

    private static BuildMode mode = BuildMode.LINE;
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

    private record PreviewFace(BlockPos pos, Direction face) {}

    private BuildSelectionController() {}

    static void selectMode(BuildMode selected) {
        mode = selected;
        first = null;
        hovered = null;
        active = true;
        cachedFull = Set.of();
        cachedFaces = List.of();
        clampLookDistance(Minecraft.getInstance());
        notify(Component.translatable("maxfastbuild.selection.mode_selected", modeName()));
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
     * Shift+scroll adjusts look distance. Plain scroll must not be consumed.
     *
     * @return true if the scroll was consumed (hotbar must not change).
     */
    static boolean onHotbarScroll(double vertical) {
        if (!active || vertical == 0) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.player.isShiftKeyDown()) return false;
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
        refreshHovered(client);
        refreshPreviewCache(client.player);
    }

    static void submitGizmos() {
        if (!active) return;
        Minecraft client = Minecraft.getInstance();
        boolean breaking = client.player != null && isBreakIntent(client.player);
        boolean placing = client.player != null && isPlaceIntent(client.player);
        if (!breaking && !placing) {
            if (hovered != null) {
                net.minecraft.core.BlockPos hp = new net.minecraft.core.BlockPos(hovered.x(), hovered.y(), hovered.z());
                Gizmos.cuboid(hp, GizmoStyle.stroke(0xFFB7C0CC, 1.5f));
            }
            return;
        }
        int stroke = breaking ? BREAK_STROKE : PLACE_STROKE;
        int firstFill = breaking ? BREAK_FIRST : PLACE_FIRST;
        int faceFill = breaking ? BREAK_FACE : PLACE_FACE;
        int boundsStroke = breaking ? 0xFFFFB48E : 0xFF8EE9FF;
        if (first != null) {
            net.minecraft.core.BlockPos fp = new net.minecraft.core.BlockPos(first.x(), first.y(), first.z());
            Gizmos.cuboid(fp, GizmoStyle.strokeAndFill(stroke, 2f, firstFill));
        }
        if (hovered != null && first == null) {
            net.minecraft.core.BlockPos hp = new net.minecraft.core.BlockPos(hovered.x(), hovered.y(), hovered.z());
            Gizmos.cuboid(hp, GizmoStyle.stroke(stroke, 1.5f));
            return;
        }
        if (first == null) return;

        BlockPos end = hovered != null ? hovered : first;
        Bounds b = new Bounds(first, end);
        AABB box = new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x() + 1, b.max().y() + 1, b.max().z() + 1);

        // Axis-aligned cuboid shapes: one outer box only (no per-block grid).
        if (isSimpleCuboidPreview(mode) && !cachedFull.isEmpty()) {
            Gizmos.cuboid(box, GizmoStyle.strokeAndFill(boundsStroke, 2.5f, faceFill));
            return;
        }

        // General shapes: only outward faces (no shared faces between neighbors).
        GizmoStyle faceStyle = GizmoStyle.fill(faceFill);
        int drawn = 0;
        for (PreviewFace face : cachedFaces) {
            if (drawn++ >= FACE_DRAW_LIMIT) break;
            drawExposedFace(face.pos(), face.face(), faceStyle);
        }
        Gizmos.cuboid(box, GizmoStyle.stroke(boundsStroke, 2.5f));
    }

    private static boolean isSimpleCuboidPreview(BuildMode m) {
        return m == BuildMode.CUBE || m == BuildMode.FLOOR || m == BuildMode.SINGLE;
    }

    private static void drawExposedFace(BlockPos pos, Direction face, GizmoStyle style) {
        Vec3 min = new Vec3(pos.x(), pos.y(), pos.z());
        Vec3 max = new Vec3(pos.x() + 1, pos.y() + 1, pos.z() + 1);
        Gizmos.rect(min, max, face, style);
    }

    static void renderHud(GuiGraphicsExtractor graphics) {
        if (!active) return;
        Minecraft client = Minecraft.getInstance();
        boolean breaking = client.player != null && isBreakIntent(client.player);
        boolean placing = client.player != null && isPlaceIntent(client.player);
        int center = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() - 84;
        int outline = !breaking && !placing ? 0xCCB7C0CC : (breaking ? 0xCCFF8A4D : 0xCC65D9FF);
        int titleColor = !breaking && !placing ? 0xFFB7C0CC : (breaking ? 0xFFFF8A4D : 0xFF8EE9FF);
        graphics.fill(center - 170, y - 5, center + 170, y + 52, 0xB018202A);
        graphics.outline(center - 170, y - 5, 340, 57, outline);
        Component title = !breaking && !placing
                ? Component.translatable("maxfastbuild.selection.title_none", modeName())
                : Component.translatable(
                        breaking ? "maxfastbuild.selection.title_break" : "maxfastbuild.selection.title", modeName());
        graphics.centeredText(client.font, title, center, y, titleColor);
        if (!breaking && !placing) {
            graphics.centeredText(client.font, Component.translatable("maxfastbuild.selection.hold_block_or_tool"),
                    center, y + 12, 0xFFFFFFFF);
        } else if (first == null) {
            graphics.centeredText(client.font, Component.translatable("maxfastbuild.selection.pick_first"), center, y + 12, 0xFFFFFFFF);
        } else {
            String second = hovered == null ? "-" : coordinates(hovered);
            graphics.centeredText(client.font, Component.translatable("maxfastbuild.selection.pick_second", coordinates(first), second),
                    center, y + 12, 0xFFFFFFFF);
        }
        graphics.centeredText(client.font, Component.translatable(
                        "maxfastbuild.selection.aim_hud",
                        lookDistance, MAX_LOOK_DISTANCE),
                center, y + 24, 0xFFB7C0CC);
        graphics.centeredText(client.font, Component.translatable(
                        breaking ? "maxfastbuild.selection.cancel_hint_break"
                                : (placing ? "maxfastbuild.selection.cancel_hint" : "maxfastbuild.selection.cancel_hint_none")),
                center, y + 36, 0xFFB7C0CC);
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
                    new ShapeRequest(mode, first, end, false), PREVIEW_LIMIT);
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
        int count;
        try {
            count = new DefaultShapeGenerator().generate(new ShapeRequest(mode, first, second, false), PREVIEW_LIMIT).size();
        } catch (RuntimeException ex) {
            notify(Component.translatable("maxfastbuild.selection.too_large"));
            return;
        }
        String modeKey = mode.name().toLowerCase(Locale.ROOT);
        if (breaking) {
            if (!isBreakIntent(player)) {
                notify(Component.translatable("maxfastbuild.selection.hold_tool"));
                return;
            }
            ClientSession.sendBreak(modeKey, first.x(), first.y(), first.z(), second.x(), second.y(), second.z(), false);
            notify(Component.translatable("maxfastbuild.selection.submitted_break", count));
        } else {
            String material = material(player);
            if (material == null) {
                notify(Component.translatable("maxfastbuild.selection.hold_block"));
                return;
            }
            ClientSession.sendPlace(modeKey, first.x(), first.y(), first.z(), second.x(), second.y(), second.z(), false, material);
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
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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

    private static Component modeName() {
        return Component.translatable("maxfastbuild.mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static String coordinates(BlockPos pos) {
        return pos.x() + ", " + pos.y() + ", " + pos.z();
    }

    private static void notify(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendOverlayMessage(message);
    }
}
