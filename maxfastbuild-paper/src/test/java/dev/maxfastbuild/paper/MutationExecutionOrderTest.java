package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockMutation;
import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.OperationKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MutationExecutionOrderTest {
    private static BlockMutation mutation(int x, int y) {
        return new BlockMutation(new BlockPos(x, y, 0), "minecraft:stone", "minecraft:dirt");
    }

    @Test
    void breakBatchesRunTopDownAndKeepLayerOrderStable() {
        BlockMutation y64a = mutation(1, 64);
        BlockMutation y70 = mutation(2, 70);
        BlockMutation y64b = mutation(3, 64);
        BlockMutation y80 = mutation(4, 80);

        List<BlockMutation> ordered = MaxFastBuildPlugin.orderMutationsForExecution(
                OperationKind.BREAK, List.of(y64a, y70, y64b, y80));

        assertThat(ordered).containsExactly(y80, y70, y64a, y64b);
    }

    @Test
    void placeBatchesRunBottomUpAndKeepLayerOrderStable() {
        BlockMutation y64a = mutation(1, 64);
        BlockMutation y70 = mutation(2, 70);
        BlockMutation y64b = mutation(3, 64);
        BlockMutation y50 = mutation(4, 50);

        List<BlockMutation> ordered = MaxFastBuildPlugin.orderMutationsForExecution(
                OperationKind.PLACE, List.of(y64a, y70, y64b, y50));

        assertThat(ordered).containsExactly(y50, y64a, y64b, y70);
    }
}
