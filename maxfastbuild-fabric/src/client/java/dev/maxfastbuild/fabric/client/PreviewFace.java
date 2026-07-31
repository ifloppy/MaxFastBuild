package dev.maxfastbuild.fabric.client;

import dev.maxfastbuild.api.BlockPos;
import net.minecraft.core.Direction;

/** One exposed face of a preview block (block + outward direction). */
public record PreviewFace(BlockPos pos, Direction face) {
}
