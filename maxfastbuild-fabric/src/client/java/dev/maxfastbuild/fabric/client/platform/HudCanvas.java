package dev.maxfastbuild.fabric.client.platform;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Version-neutral drawing surface for the selection HUD. */
public interface HudCanvas {
    int guiWidth();

    int guiHeight();

    void fill(int x1, int y1, int x2, int y2, int color);

    void outline(int x, int y, int width, int height, int color);

    void centeredText(Font font, Component text, int x, int y, int color);
}
