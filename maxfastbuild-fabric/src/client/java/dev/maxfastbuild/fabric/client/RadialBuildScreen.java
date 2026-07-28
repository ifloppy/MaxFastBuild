package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BuildMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hold-key radial menu (Effortless-style): open while key is held, close on release.
 * Transparent background — world stays visible behind the ring.
 */
final class RadialBuildScreen extends Screen {
    private static final BuildMode[] OUTER = {
            BuildMode.SINGLE, BuildMode.LINE, BuildMode.WALL, BuildMode.FLOOR,
            BuildMode.CUBE, BuildMode.DIAGONAL_LINE, BuildMode.CIRCLE, BuildMode.CYLINDER
    };
    private static final BuildMode[] INNER = {
            BuildMode.SPHERE, BuildMode.DIAGONAL_WALL, BuildMode.SLOPE_FLOOR,
            BuildMode.PYRAMID, BuildMode.CONE
    };

    private static final int TEX = 512;
    private static final double TEX_INNER_HOLE = 42 * (512.0 / 320.0);
    private static final double TEX_INNER_OUTER = 91 * (512.0 / 320.0);
    private static final double TEX_OUTER_INNER = 96 * (512.0 / 320.0);
    private static final double TEX_OUTER_OUTER = 154 * (512.0 / 320.0);
    private static final int RING_PX = 320;

    private static final Identifier BASE = Identifier.fromNamespaceAndPath("maxfastbuild", "textures/gui/radial_base.png");
    private static final Map<BuildMode, Identifier> ICONS = new EnumMap<>(BuildMode.class);
    private static final Identifier[] OUTER_HOVER = new Identifier[OUTER.length];
    private static final Identifier[] INNER_HOVER = new Identifier[INNER.length];

    static {
        for (BuildMode mode : BuildMode.values()) {
            ICONS.put(mode, Identifier.fromNamespaceAndPath("maxfastbuild",
                    "textures/gui/modes/" + mode.name().toLowerCase(Locale.ROOT) + ".png"));
        }
        for (int i = 0; i < OUTER.length; i++) {
            OUTER_HOVER[i] = Identifier.fromNamespaceAndPath("maxfastbuild", "textures/gui/radial_hover/outer_" + i + ".png");
        }
        for (int i = 0; i < INNER.length; i++) {
            INNER_HOVER[i] = Identifier.fromNamespaceAndPath("maxfastbuild", "textures/gui/radial_hover/inner_" + i + ".png");
        }
    }

    private BuildMode hovered;
    /** True after left-click selected a mode; release should not re-select. */
    private boolean selectedByClick;

    RadialBuildScreen() {
        super(Component.translatable("maxfastbuild.radial.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // Physical key: KeyMapping.isDown() is false after setScreen → releaseAll().
        if (!MaxFastBuildClient.isRadialKeyPhysicallyDown()) {
            if (!selectedByClick && hovered != null) {
                BuildSelectionController.selectMode(hovered);
            }
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // No full-screen scrim / no extractTransparentBackground — keep world visible.
        int cx = width / 2;
        int cy = height / 2;
        int left = cx - RING_PX / 2;
        int top = cy - RING_PX / 2;
        hovered = hit(mouseX, mouseY);

        blitScaled(graphics, BASE, left, top);
        Identifier hover = hoverTexture(hovered);
        if (hover != null) {
            blitScaled(graphics, hover, left, top);
        }

        drawIconsAndLabels(graphics, cx, cy, OUTER,
                screenRadius((TEX_OUTER_INNER + TEX_OUTER_OUTER) / 2.0),
                screenRadius(TEX_OUTER_OUTER - 12));
        drawIconsAndLabels(graphics, cx, cy, INNER,
                screenRadius((TEX_INNER_HOLE + TEX_INNER_OUTER) / 2.0),
                screenRadius(TEX_INNER_OUTER - 12));

        Component name = hovered == null
                ? Component.translatable("maxfastbuild.radial.move_mouse")
                : Component.translatable("maxfastbuild.mode." + hovered.name().toLowerCase(Locale.ROOT));
        graphics.centeredText(font, name, cx, cy - 8, hovered == null ? 0xFFB8C2CE : 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.release_select"), cx, cy + 6, 0xFF8593A3);
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.heading"), cx, cy - RING_PX / 2 - 18, 0xFF8EE9FF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static void blitScaled(GuiGraphicsExtractor graphics, Identifier texture, int left, int top) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                left, top,
                0f, 0f,
                RING_PX, RING_PX,
                TEX, TEX,
                TEX, TEX);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            BuildMode selected = hit(event.x(), event.y());
            if (selected != null) {
                selectedByClick = true;
                BuildSelectionController.selectMode(selected);
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private void drawIconsAndLabels(GuiGraphicsExtractor graphics, int cx, int cy, BuildMode[] modes, double iconR, double labelR) {
        double sector = Math.PI * 2 / modes.length;
        for (int index = 0; index < modes.length; index++) {
            double angle = -Math.PI / 2 + (index + 0.5) * sector;
            int ix = cx + (int) Math.round(Math.cos(angle) * iconR) - 12;
            int iy = cy + (int) Math.round(Math.sin(angle) * iconR) - 14;
            graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS.get(modes[index]), ix, iy, 0f, 0f, 24, 24, 32, 32);

            int lx = cx + (int) Math.round(Math.cos(angle) * labelR);
            int ly = cy + (int) Math.round(Math.sin(angle) * labelR) - 4;
            int color = modes[index] == hovered ? 0xFFFFFFFF : 0xFFE7EEF7;
            graphics.centeredText(font,
                    Component.translatable("maxfastbuild.mode.short." + modes[index].name().toLowerCase(Locale.ROOT)),
                    lx, ly, color);
        }
    }

    private Identifier hoverTexture(BuildMode mode) {
        if (mode == null) return null;
        int oi = indexOf(OUTER, mode);
        if (oi >= 0) return OUTER_HOVER[oi];
        int ii = indexOf(INNER, mode);
        if (ii >= 0) return INNER_HOVER[ii];
        return null;
    }

    private BuildMode hit(double mouseX, double mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        double radius = Math.sqrt(dx * dx + dy * dy);
        if (radius >= screenRadius(TEX_OUTER_INNER) && radius <= screenRadius(TEX_OUTER_OUTER)) {
            return sector(OUTER, dx, dy);
        }
        if (radius >= screenRadius(TEX_INNER_HOLE) && radius <= screenRadius(TEX_INNER_OUTER)) {
            return sector(INNER, dx, dy);
        }
        return null;
    }

    private static double screenRadius(double textureRadius) {
        return textureRadius * (RING_PX / (double) TEX);
    }

    private static BuildMode sector(BuildMode[] modes, double dx, double dy) {
        double angle = Math.atan2(dy, dx) + Math.PI / 2;
        if (angle < 0) angle += Math.PI * 2;
        int index = Math.min(modes.length - 1, (int) (angle / (Math.PI * 2 / modes.length)));
        return modes[index];
    }

    private static int indexOf(BuildMode[] modes, BuildMode mode) {
        for (int i = 0; i < modes.length; i++) if (modes[i] == mode) return i;
        return -1;
    }
}
