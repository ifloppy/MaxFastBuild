package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.ShapeRequest;
import java.util.Set;

@FunctionalInterface
public interface ShapeGenerator {
    Set<BlockPos> generate(ShapeRequest request, int limit);
}
