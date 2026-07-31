package dev.maxfastbuild.fabric.client.platform;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import dev.maxfastbuild.fabric.client.PreviewFace;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Immutable view of the current selection for a version's world-space preview renderer. */
public record PreviewSnapshot(
        BlockPos first,
        BlockPos hovered,
        AABB bounds,
        BuildMode mode,
        boolean breaking,
        boolean placing,
        boolean simpleCuboid,
        List<PreviewFace> faces) {
}
