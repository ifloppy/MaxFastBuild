package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefaultShapeGeneratorTest {
    private final DefaultShapeGenerator generator = new DefaultShapeGenerator();

    @Test void createsInclusiveDiagonalLine() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.DIAGONAL_LINE, new BlockPos(0, 0, 0), new BlockPos(4, 2, 0), 0), 100);
        assertThat(blocks).contains(new BlockPos(0, 0, 0), new BlockPos(2, 1, 0), new BlockPos(4, 2, 0)).hasSize(5);
    }

    @Test void solidCubeIsFull() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 0), 100);
        assertThat(blocks).hasSize(27);
    }

    @Test void shell1CubeExcludesInterior() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 1), 100);
        assertThat(blocks).hasSize(26).doesNotContain(new BlockPos(1, 1, 1));
    }

    @Test void shellThicknessIsAutoCapped() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 10), 100);
        assertThat(blocks).hasSize(26);
    }

    @Test void shell2CubeLeavesSmallerHollow() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(4, 4, 4), 2), 300);
        assertThat(blocks).hasSize(125 - 1).doesNotContain(new BlockPos(2, 2, 2));
    }

    @Test void slopeFloorSmoothSurface() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.SLOPE_FLOOR, new BlockPos(0, 0, 0), new BlockPos(4, 4, 4), 0), 200);
        assertThat(blocks).hasSize(25).contains(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4));
        assertThat(blocks).noneMatch(b -> b.y() < 0 || b.y() > 4);
    }

    @Test void slopeFloorFlatWhenSameHeight() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.SLOPE_FLOOR, new BlockPos(0, 0, 0), new BlockPos(4, 0, 4), 0), 200);
        assertThat(blocks).hasSize(25).allMatch(b -> b.y() == 0);
    }

    @Test void slopeStairAxisX() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.SLOPE_FLOOR, new BlockPos(0, 0, 0), new BlockPos(4, 4, 4), 1), 200);
        assertThat(blocks).hasSize(25);
        for (int x = 0; x <= 4; x++) {
            int xf = x;
            int expectedY = x;
            assertThat(blocks.stream().filter(b -> b.x() == xf)).allMatch(b -> b.y() == expectedY);
        }
    }

    @Test void slopeStairAxisZ() {
        var blocks = generator.generate(new ShapeRequest(BuildMode.SLOPE_FLOOR, new BlockPos(0, 0, 0), new BlockPos(4, 4, 4), 2), 200);
        assertThat(blocks).hasSize(25);
        for (int z = 0; z <= 4; z++) {
            int zf = z;
            int expectedY = z;
            assertThat(blocks.stream().filter(b -> b.z() == zf)).allMatch(b -> b.y() == expectedY);
        }
    }

    @Test void rejectsShapesBeyondServerLimit() {
        assertThatThrownBy(() -> generator.generate(new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(10, 10, 10), 0), 100))
                .isInstanceOf(ShapeLimitException.class);
    }

    @Test void rejectsHollowCuboidWhenBoundingVolumeExceedsLimit() {
        assertThatThrownBy(() -> generator.generate(
                new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(50, 50, 50), 1), 1000))
                .isInstanceOf(ShapeLimitException.class);
    }
}
