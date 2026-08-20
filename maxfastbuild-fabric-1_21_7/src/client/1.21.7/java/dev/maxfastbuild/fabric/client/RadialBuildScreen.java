package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BuildMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
    private boolean selectionOnly;
    /** Current ring diameter in GUI pixels (recomputed every frame from screen size). */
    private int ringPx = RadialLayout.BASE_RING_PX;

    RadialBuildScreen() {
        super(Component.translatable("maxfastbuild.radial.title"));
        selectionOnly = BuildSelectionController.selectionOnly();
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
                BuildSelectionController.setSelectionOnly(selectionOnly);
                BuildSelectionController.selectMode(hovered);
            }
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // No super.render(): it may draw a full-screen background (chunk-update priority NEAR) —
        // keep the world visible behind the ring.
        ringPx = RadialLayout.computeRingPx(width, height);
        int cx = width / 2;
        int cy = height / 2;
        int left = cx - ringPx / 2;
        int top = cy - ringPx / 2;
        hovered = RadialLayout.hit(mouseX, mouseY, width, height, ringPx);

        blitScaled(graphics, tex(RadialLayout.BASE_PATH), left, top);
        String hoverPath = RadialLayout.hoverTexturePath(hovered);
        if (hoverPath != null) {
            blitScaled(graphics, tex(hoverPath), left, top);
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
        graphics.drawCenteredString(font, name, cx, cy - hubTextY - 4, hovered == null ? 0xFFB8C2CE : 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("maxfastbuild.radial.release_select"), cx, cy + hubTextY + 2, 0xFF8593A3);

        int headingY = top - Math.max(12, RadialLayout.HEADING_RESERVE - 4);
        if (headingY < 2) headingY = 2;
        graphics.drawCenteredString(font, Component.translatable("maxfastbuild.radial.heading"), cx, headingY, 0xFF8EE9FF);
        drawActionModeMenu(graphics, mouseX, mouseY);
    }

    private static ResourceLocation tex(String path) {
        return ResourceLocation.fromNamespaceAndPath("maxfastbuild", path);
    }

    private void blitScaled(GuiGraphics graphics, ResourceLocation texture, int left, int top) {
        // 12-arg blit: width/height are screen size; uWidth/vHeight are texture region size.
        // 10-arg overload interprets width/height as the texture region too, which crops the
        // 512 base/hover textures to ringPx and causes the fan disk to appear misaligned.
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
                left, top, 0f, 0f, ringPx, ringPx,
                RadialLayout.TEX, RadialLayout.TEX,
                RadialLayout.TEX, RadialLayout.TEX);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (actionModeButton(0, mouseX, mouseY)) {
                selectionOnly = false;
                BuildSelectionController.setSelectionOnly(false);
                onClose();
                return true;
            }
            if (actionModeButton(1, mouseX, mouseY)) {
                selectionOnly = true;
                BuildSelectionController.setSelectionOnly(true);
                onClose();
                return true;
            }
            BuildMode selected = RadialLayout.hit(mouseX, mouseY, width, height, ringPx);
            if (selected != null) {
                selectedByClick = true;
                BuildSelectionController.setSelectionOnly(selectionOnly);
                BuildSelectionController.selectMode(selected);
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void drawActionModeMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = 8;
        int y = 8;
        graphics.drawString(font, Component.translatable("maxfastbuild.radial.action_mode"), x, y, 0xFF8EE9FF, true);
        drawActionModeButton(graphics, 0, x, y + 12, mouseX, mouseY,
                Component.translatable("maxfastbuild.radial.action_build"));
        drawActionModeButton(graphics, 1, x, y + 34, mouseX, mouseY,
                Component.translatable("maxfastbuild.radial.action_select"));
    }

    private void drawActionModeButton(GuiGraphics graphics, int index, int x, int y,
                                      double mouseX, double mouseY, Component label) {
        int w = 132;
        int h = 18;
        boolean selected = selectionOnly == (index == 1);
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        graphics.fill(x, y, x + w, y + h, selected ? 0xFF674C86 : (hovered ? 0xFF3C4249 : 0xFF2A2E33));
        graphics.renderOutline(x, y, w, h, selected ? 0xFFD1B8FF : 0xFF687481);
        graphics.drawString(font, label, x + 6, y + 5, selected || hovered ? 0xFFFFFFFF : 0xFFE7EEF7, false);
    }

    private boolean actionModeButton(int index, double mouseX, double mouseY) {
        int y = 20 + index * 22;
        return mouseX >= 8 && mouseX < 140 && mouseY >= y && mouseY < y + 18;
    }

    private void drawIconsAndLabels(GuiGraphics graphics, int cx, int cy, BuildMode[] modes, double iconR, double labelR) {
        double sector = Math.PI * 2 / modes.length;
        double uiScale = ringPx / (double) RadialLayout.BASE_RING_PX;
        int iconSize = Math.max(12, (int) Math.round(24 * uiScale));
        int iconHalf = iconSize / 2;
        int iconTex = 32;
        for (int index = 0; index < modes.length; index++) {
            double angle = -Math.PI / 2 + (index + 0.5) * sector;
            int ix = cx + (int) Math.round(Math.cos(angle) * iconR) - iconHalf;
            int iy = cy + (int) Math.round(Math.sin(angle) * iconR) - iconHalf - (int) Math.round(2 * uiScale);
            ResourceLocation iconTexture = tex(RadialLayout.iconPath(modes[index]));
            graphics.blit(RenderPipelines.GUI_TEXTURED, iconTexture,
                    ix, iy, 0f, 0f, iconSize, iconSize,
                    iconTex, iconTex,
                    iconTex, iconTex);

            int lx = cx + (int) Math.round(Math.cos(angle) * labelR);
            int ly = cy + (int) Math.round(Math.sin(angle) * labelR) - (int) Math.round(4 * uiScale);
            // Keep labels inside the screen; clamp slightly if a short axis would clip text.
            lx = Math.max(font.width("W"), Math.min(width - font.width("W"), lx));
            ly = Math.max(2, Math.min(height - 10, ly));
            int color = modes[index] == hovered ? 0xFFFFFFFF : 0xFFE7EEF7;
            graphics.drawCenteredString(font,
                    Component.translatable("maxfastbuild.mode.short." + modes[index].name().toLowerCase(Locale.ROOT)),
                    lx, ly, color);
        }
    }
}
