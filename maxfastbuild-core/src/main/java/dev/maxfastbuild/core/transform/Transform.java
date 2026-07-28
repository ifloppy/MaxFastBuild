package dev.maxfastbuild.core.transform;

import dev.maxfastbuild.api.BlockPos;
import java.util.Set;

@FunctionalInterface
public interface Transform { Set<BlockPos> apply(Set<BlockPos> input, int limit); }
