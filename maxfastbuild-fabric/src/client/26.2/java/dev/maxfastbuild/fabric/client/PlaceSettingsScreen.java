package dev.maxfastbuild.fabric.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Placement options screen (Minecraft 26.2): direction for stairs, half for slabs.
 */
public final class PlaceSettingsScreen extends Screen {
    private final boolean stair;
    private String selectedDir;
    private boolean selectedTop;

    public PlaceSettingsScreen(String defaultDir, boolean forStair) {
        super(Component.translatable(forStair
                ? "maxfastbuild.place.settings.title.stair"
                : "maxfastbuild.place.settings.title.slab"));
        this.stair = forStair;
        this.selectedDir = defaultDir;
        this.selectedTop = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(font, getTitle(), width / 2, PlaceSettingsLayout.TITLE_Y, 0xFF8EE9FF);
        if (stair) {
            drawDirButtons(graphics, mouseX, mouseY);
        } else {
            drawSlabButtons(graphics, mouseX, mouseY);
        }
        drawButton(graphics, PlaceSettingsLayout.confirm(width), Component.translatable("maxfastbuild.place.settings.confirm"),
                0xFF8EE9FF, mouseX, mouseY);
        drawButton(graphics, PlaceSettingsLayout.cancel(width), Component.translatable("maxfastbuild.place.settings.cancel"),
                0xFFB8C2CE, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawDirButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int i = 0; i < 4; i++) {
            String dir = dirAt(i);
            PlaceSettingsLayout.Rect r = PlaceSettingsLayout.dirButton(i, width);
            boolean hovered = r.contains(mouseX, mouseY);
            boolean selected = dir.equals(selectedDir);
            int fill = selected ? 0xFF3A6FD8 : (hovered ? 0xFF3C4249 : 0xFF2A2E33);
            graphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0xFF9AA5B1);
            graphics.fill(r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + r.h() - 1, fill);
            graphics.centeredText(font, Component.translatable("maxfastbuild.place.settings.direction." + dir),
                    r.x() + r.w() / 2, r.y() + 3, hovered || selected ? 0xFFFFFFFF : 0xFFE7EEF7);
        }
    }

    private void drawSlabButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int t = 0; t < 2; t++) {
            boolean isTop = t == 0;
            PlaceSettingsLayout.Rect r = PlaceSettingsLayout.slabButton(isTop, width);
            boolean hovered = r.contains(mouseX, mouseY);
            boolean selected = isTop == selectedTop;
            int fill = selected ? 0xFF3A6FD8 : (hovered ? 0xFF3C4249 : 0xFF2A2E33);
            graphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0xFF9AA5B1);
            graphics.fill(r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + r.h() - 1, fill);
            graphics.centeredText(font, Component.translatable("maxfastbuild.place.settings.slab." + (isTop ? "top" : "bottom")),
                    r.x() + r.w() / 2, r.y() + 3, hovered || selected ? 0xFFFFFFFF : 0xFFE7EEF7);
        }
    }

    private void drawButton(GuiGraphicsExtractor graphics, PlaceSettingsLayout.Rect r, Component label,
                            int textColor, int mouseX, int mouseY) {
        boolean hovered = r.contains(mouseX, mouseY);
        graphics.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0xFF9AA5B1);
        graphics.fill(r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + r.h() - 1,
                hovered ? 0xFF3C4249 : 0xFF2A2E33);
        graphics.centeredText(font, label, r.x() + r.w() / 2, r.y() + 3,
                hovered ? 0xFFFFFFFF : textColor);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) return super.mouseClicked(event, doubled);
        if (stair) {
            for (int i = 0; i < 4; i++) {
                if (PlaceSettingsLayout.dirButton(i, width).contains(event.x(), event.y())) {
                    selectedDir = dirAt(i);
                    return true;
                }
            }
        } else {
            if (PlaceSettingsLayout.slabButton(true, width).contains(event.x(), event.y())) {
                selectedTop = true;
                return true;
            }
            if (PlaceSettingsLayout.slabButton(false, width).contains(event.x(), event.y())) {
                selectedTop = false;
                return true;
            }
        }
        if (PlaceSettingsLayout.confirm(width).contains(event.x(), event.y())) {
            confirm();
            return true;
        }
        if (PlaceSettingsLayout.cancel(width).contains(event.x(), event.y())) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    private void confirm() {
        PlaceSettings settings = stair
                ? new PlaceSettings(selectedDir, false)
                : new PlaceSettings(null, selectedTop);
        BuildSelectionController.confirmPlaceSettings(settings);
        onClose();
    }

    private static String dirAt(int index) {
        return switch (index) {
            case 0 -> "north";
            case 1 -> "south";
            case 2 -> "west";
            case 3 -> "east";
            default -> "north";
        };
    }
}
