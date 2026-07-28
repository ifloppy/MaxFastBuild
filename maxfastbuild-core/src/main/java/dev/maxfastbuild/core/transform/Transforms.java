package dev.maxfastbuild.core.transform;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.core.shape.ShapeLimitException;
import java.util.*;

public final class Transforms {
    private Transforms() {}

    public static Transform mirror(BlockPos origin, boolean x, boolean y, boolean z) {
        return (input, limit) -> mapCopies(input, limit, pos -> new BlockPos(
                x ? origin.x() * 2 - pos.x() : pos.x(),
                y ? origin.y() * 2 - pos.y() : pos.y(),
                z ? origin.z() * 2 - pos.z() : pos.z()));
    }

    public static Transform array(int dx, int dy, int dz, int copies) {
        if (copies < 1) throw new IllegalArgumentException("copies must be positive");
        return (input, limit) -> {
            LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
            for (int i = 0; i < copies; i++) for (BlockPos pos : input) checkedAdd(out, pos.add(dx * i, dy * i, dz * i), limit);
            return Set.copyOf(out);
        };
    }

    public static Transform radial(BlockPos origin, int copies) {
        if (copies < 1) throw new IllegalArgumentException("copies must be positive");
        return (input, limit) -> {
            LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
            for (int i = 0; i < copies; i++) {
                double angle = Math.PI * 2 * i / copies, sin = Math.sin(angle), cos = Math.cos(angle);
                for (BlockPos pos : input) {
                    int x = pos.x() - origin.x(), z = pos.z() - origin.z();
                    checkedAdd(out, new BlockPos(origin.x() + (int) Math.round(x * cos - z * sin), pos.y(), origin.z() + (int) Math.round(x * sin + z * cos)), limit);
                }
            }
            return Set.copyOf(out);
        };
    }

    private static Set<BlockPos> mapCopies(Set<BlockPos> input, int limit, java.util.function.UnaryOperator<BlockPos> mapper) {
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>(input);
        for (BlockPos pos : input) checkedAdd(out, mapper.apply(pos), limit);
        return Set.copyOf(out);
    }

    private static void checkedAdd(Set<BlockPos> output, BlockPos pos, int limit) {
        output.add(pos);
        if (output.size() > limit) throw new ShapeLimitException(limit);
    }
}
