package dev.maxfastbuild.fabric.client;

/**
 * Geometry for the place-settings screen. Version-independent — shared by every version's
 * {@code PlaceSettingsScreen} so the layout and hit-testing stay pixel-identical.
 */
final class PlaceSettingsLayout {
    record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    static final int TITLE_Y = 52;
    static final int DIR_START_Y = 84;
    static final int SLAB_START_Y = 100;
    static final int OPTION_SPACING = 26;
    static final int BUTTON_W = 76;
    static final int BUTTON_H = 20;
    static final int BUTTON_Y = 190;

    private PlaceSettingsLayout() {}

    static Rect dirButton(int index, int width) {
        int gridX = index % 2;
        int gridY = index / 2;
        int cx = width / 2;
        int x = cx + (gridX == 0 ? -62 : 14);
        int y = DIR_START_Y + gridY * 28;
        return new Rect(x, y, 48, 22);
    }

    static Rect slabButton(boolean top, int width) {
        int halfW = 60;
        int x = width / 2 - halfW;
        int y = top ? SLAB_START_Y : SLAB_START_Y + 30;
        return new Rect(x, y, halfW * 2, 22);
    }

    static Rect confirm(int width) {
        return new Rect(width / 2 - 84, BUTTON_Y, BUTTON_W, BUTTON_H);
    }

    static Rect cancel(int width) {
        return new Rect(width / 2 + 8, BUTTON_Y, BUTTON_W, BUTTON_H);
    }
}
