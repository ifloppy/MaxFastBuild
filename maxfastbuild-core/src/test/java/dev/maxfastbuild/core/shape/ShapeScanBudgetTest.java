package dev.maxfastbuild.core.shape;

import dev.maxfastbuild.api.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShapeScanBudgetTest {
    @Test void allowsSmallVolume() {
        assertThatCode(() -> ShapeScanBudget.ensureWithinLimit(
                new ShapeRequest(BuildMode.CUBE, new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 1), 100))
                .doesNotThrowAnyException();
    }

    @Test void rejectsOversizedBoundingVolumeBeforeScan() {
        assertThatThrownBy(() -> ShapeScanBudget.ensureWithinLimit(
                new ShapeRequest(BuildMode.SPHERE, new BlockPos(0, 0, 0), new BlockPos(200, 200, 200), 1), 10_000))
                .isInstanceOf(ShapeLimitException.class);
    }
}
