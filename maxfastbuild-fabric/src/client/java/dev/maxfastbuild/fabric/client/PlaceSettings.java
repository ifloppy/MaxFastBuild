package dev.maxfastbuild.fabric.client;

/**
 * User-selected placement options for directional blocks (stairs / slabs).
 */
public record PlaceSettings(String direction, boolean slabTop) {
    public static final PlaceSettings DEFAULT_SLAB = new PlaceSettings(null, false);

    public boolean isSlab() {
        return direction == null;
    }

    public boolean isStair() {
        return direction != null;
    }
}
