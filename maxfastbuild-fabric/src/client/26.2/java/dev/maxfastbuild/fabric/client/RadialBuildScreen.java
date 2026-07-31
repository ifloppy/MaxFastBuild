package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BuildMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Hold-key radial menu (Effortless-style): open while key is held, close on release.
 * Transparent background — world stays visible behind the ring.
 * Ring size scales to the smaller screen axis so it never overflows odd aspect ratios / GUI scales.
 */
public final class RadialBuildScreen extends Screen {
    private BuildMode hovered;
    /** True after left-click selected a mode; release should not re-select. */
    private boolean selectedByClick;
    /** Current ring diameter in GUI pixels (recomputed every frame from screen size). */
    private int ringPx = RadialLayout.BASE_RING_PX;

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
        ringPx = RadialLayout.computeRingPx(width, height);
        int cx = width / 2;
        int cy = height / 2;
        int left = cx - ringPx / 2;
        int top = cy - ringPx / 2;
        hovered = RadialLayout.hit(mouseX, mouseY, width, height, ringPx);

        blitScaled(graphics, Identifier.fromNamespaceAndPath("maxfastbuild", RadialLayout.BASE_PATH), left, top);
        String hoverPath = RadialLayout.hoverTexturePath(hovered);
        if (hoverPath != null) {
            blitScaled(graphics, Identifier.fromNamespaceAndPath("maxfastbuild", hoverPath), left, top);
        }

        drawIconsAndLabels(graphics, cx, cy, RadialLayout.OUTER,
                RadialLayout.screenRadius((RadialLayout.TEX_OUTER_INNER + RadialLayout.TEX_OUTER_OUTER) / 2.0, ringPx),
                RadialLayout.screenRadius(RadialLayout.TEX_OUTER_OUTER - 12, ringPx));
        drawIconsAndLabels(graphics, cx, cy, RadialLayout.INNER,
                RadialLayout.screenRadius((RadialLayout.TEX_INNER_HOLE + RadialLayout.TEX_INNER_OUTER) / 2.0, ringPx),
                RadialLayout.screenRadius(RadialLayout.TEX_INNER_OUTER - 12, ringPx));

        Component name = hovered == null
                ? Component.translatable("maxfastbuild.radial.move_mouse")
                : Component.translatable("maxfastbuild.mode." + hovered.name().toLowerCase(Locale.ROOT));
        int hubTextY = Math.max(2, (int) Math.round(ringPx * 0.025));
        graphics.centeredText(font, name, cx, cy - hubTextY - 4, hovered == null ? 0xFFB8C2CE : 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.release_select"), cx, cy + hubTextY + 2, 0xFF8593A3);

        int headingY = top - Math.max(12, RadialLayout.HEADING_RESERVE - 4);
        if (headingY < 2) headingY = 2;
        graphics.centeredText(font, Component.translatable("maxfastbuild.radial.heading"), cx, headingY, 0xFF8EE9FF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void blitScaled(GuiGraphicsExtractor graphics, Identifier texture, int left, int top) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                left, top,
                0f, 0f,
                ringPx, ringPx,
                RadialLayout.TEX, RadialLayout.TEX,
                RadialLayout.TEX, RadialLayout.TEX);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) {
            BuildMode selected = RadialLayout.hit(event.x(), event.y(), width, height, ringPx);
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
        double uiScale = ringPx / (double) RadialLayout.BASE_RING_PX;
        int iconSize = Math.max(12, (int) Math.round(24 * uiScale));
        int iconHalf = iconSize / 2;
        int iconTex = 32;
        for (int index = 0; index < modes.length; index++) {
            double angle = -Math.PI / 2 + (index + 0.5) * sector;
            int ix = cx + (int) Math.round(Math.cos(angle) * iconR) - iconHalf;
            int iy = cy + (int) Math.round(Math.sin(angle) * iconR) - iconHalf - (int) Math.round(2 * uiScale);
            graphics.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("maxfastbuild", RadialLayout.iconPath(modes[index])),
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
}
