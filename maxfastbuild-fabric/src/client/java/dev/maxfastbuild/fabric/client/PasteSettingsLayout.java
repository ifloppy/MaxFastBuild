package dev.maxfastbuild.fabric.client;

/**
 * Geometry for the paste-settings screen. Version-independent — shared by every version's
 * {@code PasteSettingsScreen} so the layout and hit-testing stay pixel-identical.
 */
final class PasteSettingsLayout {
    record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** Checkbox order matches the settings screen's {@code checked[]} array. */
    enum Option { FLUIDS, ENTITIES, MOBS, DROPS, CONTENTS, NBT, INSTANT }

    static final int TITLE_Y = 42;
    static final int ROWS_START = 66;
    static final int ROW_SPACING = 20;
    static final int ROW_HEIGHT = 18;
    static final int METRICS_Y = ROWS_START + Option.values().length * ROW_SPACING + 4;
    static final int METRICS_SECOND_Y = METRICS_Y + 12;
    /** Y of the red redstone warning line. */
    static final int WARNING_Y = METRICS_SECOND_Y + 17;
    static final int BOX = 14;
    static final int BUTTON_W = 76;
    static final int BUTTON_H = 20;
    static final int BUTTON_Y = WARNING_Y + 22;
    static final int BUTTON_GAP = 8;

    private PasteSettingsLayout() {}

    static int rowY(int index) {
        return ROWS_START + index * ROW_SPACING;
    }

    static Rect row(int index, int width) {
        return new Rect(width / 2 - 160, rowY(index), 320, ROW_HEIGHT);
    }

    static Rect checkbox(int index, int width) {
        return new Rect(width / 2 - 150, rowY(index) + (ROW_HEIGHT - BOX) / 2, BOX, BOX);
    }

    static int labelX(int width) {
        return width / 2 - 124;
    }

    static int labelCenterY(int index) {
        return rowY(index) + ROW_HEIGHT / 2 - 4;
    }

    static Rect confirm(int width) {
        return new Rect(width / 2 - BUTTON_W - BUTTON_GAP / 2, BUTTON_Y, BUTTON_W, BUTTON_H);
    }

    static Rect cancel(int width) {
        return new Rect(width / 2 + BUTTON_GAP / 2, BUTTON_Y, BUTTON_W, BUTTON_H);
    }

    /** Index of the option row under the pointer, or -1 when over no row. */
    static int hitRow(int width, double x, double y) {
        for (int i = 0; i < Option.values().length; i++) {
            if (row(i, width).contains(x, y)) return i;
        }
        return -1;
    }
}
