package dev.maxfastbuild.core.limit;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.Bounds;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLimitValidatorTest {
    private static final ServerLimits LIMITS = new ServerLimits(
            100, 50, 10, 8, 12, 4, 1600, 300_000, 131_072,
            64, 32, 500, 64);

    @Test void regionVolumeIncludesAir() {
        assertThat(RequestLimitValidator.region(
                new Bounds(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4)), LIMITS))
                .isPresent()
                .get()
                .extracting(RequestLimitValidator.Violation::kind,
                        RequestLimitValidator.Violation::actual)
                .containsExactly(RequestLimitValidator.Kind.REGION_BLOCKS, 125L);
    }

    @Test void axisLimitIsIndependentFromVolumeLimit() {
        assertThat(RequestLimitValidator.region(11, 1, 1, 11, LIMITS))
                .isPresent()
                .get()
                .extracting(RequestLimitValidator.Violation::kind,
                        RequestLimitValidator.Violation::axis)
                .containsExactly(RequestLimitValidator.Kind.AXIS, "x");
    }

    @Test void affectedLimitCountsCoordinatesNotOperations() {
        assertThat(RequestLimitValidator.affected(50, LIMITS)).isEmpty();
        assertThat(RequestLimitValidator.affected(51, LIMITS))
                .isPresent()
                .get()
                .extracting(RequestLimitValidator.Violation::kind,
                        RequestLimitValidator.Violation::actual)
                .containsExactly(RequestLimitValidator.Kind.AFFECTED_BLOCKS, 51L);
    }
}
