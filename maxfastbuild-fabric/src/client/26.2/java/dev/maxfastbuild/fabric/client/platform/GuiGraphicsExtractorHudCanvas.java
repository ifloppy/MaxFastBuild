package dev.maxfastbuild.fabric.client.platform;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** 26.2 HUD canvas over the extract-phase {@link GuiGraphicsExtractor}. */
final class GuiGraphicsExtractorHudCanvas implements HudCanvas {
    private final GuiGraphicsExtractor graphics;

    GuiGraphicsExtractorHudCanvas(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    @Override
    public int guiWidth() {
        return graphics.guiWidth();
    }

    @Override
    public int guiHeight() {
        return graphics.guiHeight();
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y2, color);
    }

    @Override
    public void outline(int x, int y, int width, int height, int color) {
        graphics.outline(x, y, width, height, color);
    }

    @Override
    public void centeredText(Font font, Component text, int x, int y, int color) {
        graphics.centeredText(font, text, x, y, color);
    }
}
