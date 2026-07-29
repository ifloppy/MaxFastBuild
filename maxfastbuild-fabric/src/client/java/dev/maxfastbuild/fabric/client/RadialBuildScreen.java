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
 * Ring size scales to the smaller screen axis so it never overflows odd aspect ratios / GUI scales.
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
    /** Design size at 1.0 scale (matches authored 512 textures drawn into a 320 box). */
    private static final int BASE_RING_PX = 320;
    private static final int MIN_RING_PX = 140;
    /** Margin from each screen edge so the ring + heading stay inside the viewport. */
    private static final int EDGE_MARGIN = 8;
    private static final int HEADING_RESERVE = 22;

    private static final double TEX_INNER_HOLE = 42 * (512.0 / 320.0);
    private static final double TEX_INNER_OUTER = 91 * (512.0 / 320.0);
    private static final double TEX_OUTER_INNER = 96 * (512.0 / 320.0);
    private static final double TEX_OUTER_OUTER = 154 * (512.0 / 320.0);

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
    /** Current ring diameter in GUI pixels (recomputed every frame from screen size). */
    private int ringPx = BASE_RING_PX;

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
        ringPx = computeRingPx();
        int cx = width / 2;
        int cy = height / 2;
        int left = cx - ringPx / 2;
        int top = cy - ringPx / 2;
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
        int hubTextY = Math.max(2, (int) Math.round(ringPx * 0.025));
        graphics.centeredText(font, name, cx, cy - hubTextY - 4, hovered == null ? 0xFFB8C2CE : 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.release_select"), cx, cy + hubTextY + 2, 0xFF8593A3);

        int headingY = top - Math.max(12, HEADING_RESERVE - 4);
        if (headingY < 2) headingY = 2;
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.heading"), cx, headingY, 0xFF8EE9FF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /**
     * Fit the ring inside the smaller screen axis, leaving room for the heading and a thin margin.
     * Never larger than the design size; never smaller than {@link #MIN_RING_PX} unless the screen is tinier.
     */
    private int computeRingPx() {
        int availW = Math.max(1, width - EDGE_MARGIN * 2);
        int availH = Math.max(1, height - EDGE_MARGIN * 2 - HEADING_RESERVE);
        int fit = Math.min(availW, availH);
        int desired = Math.min(BASE_RING_PX, fit);
        // Prefer at least MIN_RING_PX when the screen can take it; otherwise shrink further to fit.
        if (fit >= MIN_RING_PX) {
            return Math.max(MIN_RING_PX, desired);
        }
        return Math.max(96, fit);
    }

    private void blitScaled(GuiGraphicsExtractor graphics, Identifier texture, int left, int top) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                left, top,
                0f, 0f,
                ringPx, ringPx,
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
        double uiScale = ringPx / (double) BASE_RING_PX;
        int iconSize = Math.max(12, (int) Math.round(24 * uiScale));
        int iconHalf = iconSize / 2;
        int iconTex = 32;
        for (int index = 0; index < modes.length; index++) {
            double angle = -Math.PI / 2 + (index + 0.5) * sector;
            int ix = cx + (int) Math.round(Math.cos(angle) * iconR) - iconHalf;
            int iy = cy + (int) Math.round(Math.sin(angle) * iconR) - iconHalf - (int) Math.round(2 * uiScale);
            graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS.get(modes[index]),
                    ix, iy, 0f, 0f, iconSize, iconSize, iconTex, iconTex);

            int lx = cx + (int) Math.round(Math.cos(angle) * labelR);
            int ly = cy + (int) Math.round(Math.sin(angle) * labelR) - (int) Math.round(4 * uiScale);
            // Keep labels inside the screen; clamp slightly if a short axis would clip text.
            lx = Math.max(font.width("W"), Math.min(width - font.width("W"), lx));
            ly = Math.max(2, Math.min(height - 10, ly));
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

    private double screenRadius(double textureRadius) {
        return textureRadius * (ringPx / (double) TEX);
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
