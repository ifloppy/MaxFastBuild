package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BuildMode;

import java.util.Locale;

/**
 * Radial menu geometry and texture tables. Version-independent — shared by every
 * version's {@code RadialBuildScreen} implementation so the ring stays pixel-identical.
 */
final class RadialLayout {
    static final BuildMode[] OUTER = {
            BuildMode.SINGLE, BuildMode.LINE, BuildMode.WALL, BuildMode.FLOOR,
            BuildMode.CUBE, BuildMode.DIAGONAL_LINE, BuildMode.CIRCLE, BuildMode.CYLINDER
    };
    static final BuildMode[] INNER = {
            BuildMode.SPHERE, BuildMode.DIAGONAL_WALL, BuildMode.SLOPE_FLOOR,
            BuildMode.PYRAMID, BuildMode.CONE
    };

    static final int TEX = 512;
    /** Design size at 1.0 scale (matches authored 512 textures drawn into a 320 box). */
    static final int BASE_RING_PX = 320;
    static final int MIN_RING_PX = 140;
    /** Margin from each screen edge so the ring + heading stay inside the viewport. */
    static final int EDGE_MARGIN = 8;
    static final int HEADING_RESERVE = 22;

    static final double TEX_INNER_HOLE = 42 * (512.0 / 320.0);
    static final double TEX_INNER_OUTER = 91 * (512.0 / 320.0);
    static final double TEX_OUTER_INNER = 96 * (512.0 / 320.0);
    static final double TEX_OUTER_OUTER = 154 * (512.0 / 320.0);

    /** Relative texture path (namespace "maxfastbuild"); the version module wraps it into its Identifier type. */
    static final String BASE_PATH = "textures/gui/radial_base.png";

    private RadialLayout() {}

    /**
     * Fit the ring inside the smaller screen axis, leaving room for the heading and a thin margin.
     * Never larger than the design size; never smaller than {@link #MIN_RING_PX} unless the screen is tinier.
     */
    static int computeRingPx(int width, int height) {
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

    static double screenRadius(double textureRadius, int ringPx) {
        return textureRadius * (ringPx / (double) TEX);
    }

    static BuildMode hit(double mouseX, double mouseY, int width, int height, int ringPx) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        double radius = Math.sqrt(dx * dx + dy * dy);
        if (radius >= screenRadius(TEX_OUTER_INNER, ringPx) && radius <= screenRadius(TEX_OUTER_OUTER, ringPx)) {
            return sector(OUTER, dx, dy);
        }
        if (radius >= screenRadius(TEX_INNER_HOLE, ringPx) && radius <= screenRadius(TEX_INNER_OUTER, ringPx)) {
            return sector(INNER, dx, dy);
        }
        return null;
    }

    static String iconPath(BuildMode mode) {
        return "textures/gui/modes/" + mode.name().toLowerCase(Locale.ROOT) + ".png";
    }

    static String hoverTexturePath(BuildMode mode) {
        if (mode == null) return null;
        int oi = indexOf(OUTER, mode);
        if (oi >= 0) return "textures/gui/radial_hover/outer_" + oi + ".png";
        int ii = indexOf(INNER, mode);
        if (ii >= 0) return "textures/gui/radial_hover/inner_" + ii + ".png";
        return null;
    }

    private static BuildMode sector(BuildMode[] modes, double dx, double dy) {
        double sector = Math.PI * 2 / modes.length;
        // (int) truncation breaks negative angles (left half of the ring): it both
        // crashes (ArrayIndexOutOfBounds) and highlights the wrong sector. Use floor.
        int index = Math.floorMod((int) Math.floor((Math.atan2(dy, dx) + Math.PI / 2) / sector), modes.length);
        return modes[index];
    }

    private static int indexOf(BuildMode[] modes, BuildMode mode) {
        for (int i = 0; i < modes.length; i++) if (modes[i] == mode) return i;
        return -1;
    }
}
