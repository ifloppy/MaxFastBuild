package dev.maxfastbuild.core.shape;

public final class ShapeLimitException extends RuntimeException {
    public ShapeLimitException(int limit) { super("Shape exceeds configured limit of " + limit + " blocks"); }
}
