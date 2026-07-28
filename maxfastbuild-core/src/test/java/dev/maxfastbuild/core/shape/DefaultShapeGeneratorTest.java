package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefaultShapeGeneratorTest {
    private final DefaultShapeGenerator generator = new DefaultShapeGenerator();

    @Test void createsInclusiveDiagonalLine() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.DIAGONAL_LINE, new BlockPos(0, 0, 0), new BlockPos(4, 2, 0), false), 100);
        assertThat(blocks).contains(new BlockPos(0, 0, 0), new BlockPos(2, 1, 0), new BlockPos(4, 2, 0)).hasSize(5);
    }

    @Test void hollowCubeExcludesInterior() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), true), 100);
        assertThat(blocks).hasSize(26).doesNotContain(new BlockPos(1, 1, 1));
    }

    @Test void rejectsShapesBeyondServerLimit() {
        assertThatThrownBy(() -> generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(10, 10, 10), false), 100))
                .isInstanceOf(ShapeLimitException.class);
    }
}
