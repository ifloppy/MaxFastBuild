package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import dev.maxfastbuild.api.ShapeRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionPreviewSamplerTest {
    @Test
    void smallSelectionKeepsExactShape() {
        ShapeRequest request = new ShapeRequest(BuildMode.CUBE,
                new BlockPos(0, 0, 0), new BlockPos(2, 2, 2), 0);

        SelectionPreviewSampler.Sample sample = SelectionPreviewSampler.sample(request, 2048);

        assertThat(sample.simplified()).isFalse();
        assertThat(sample.positions()).hasSize(27)
                .contains(new BlockPos(1, 1, 1));
    }

    @Test
    void watchdogRegressionLargeCubeUsesBoundedOutline() {
        ShapeRequest request = new ShapeRequest(BuildMode.CUBE,
                new BlockPos(-380, 64, 622), new BlockPos(-473, 110, 726), 0);

        SelectionPreviewSampler.Sample sample = SelectionPreviewSampler.sample(request, 2048);

        assertThat(sample.simplified()).isTrue();
        assertThat(sample.positions().size()).isLessThanOrEqualTo(2048);
        assertThat(sample.positions())
                .contains(new BlockPos(-380, 64, 622), new BlockPos(-473, 110, 726));
        assertThat(sample.positions()).allMatch(pos -> {
            int boundaryAxes = 0;
            if (pos.x() == -473 || pos.x() == -380) boundaryAxes++;
            if (pos.y() == 64 || pos.y() == 110) boundaryAxes++;
            if (pos.z() == 622 || pos.z() == 726) boundaryAxes++;
            return boundaryAxes >= 2;
        });
    }

    @Test
    void extremeCoordinatesDoNotOverflowIntoFullGeneration() {
        ShapeRequest request = new ShapeRequest(BuildMode.CUBE,
                new BlockPos(Integer.MIN_VALUE, -64, Integer.MIN_VALUE),
                new BlockPos(Integer.MAX_VALUE, 319, Integer.MAX_VALUE), 0);

        SelectionPreviewSampler.Sample sample = SelectionPreviewSampler.sample(request, 256);

        assertThat(sample.simplified()).isTrue();
        assertThat(sample.positions().size()).isLessThanOrEqualTo(256);
        assertThat(sample.positions()).contains(request.first(), request.second());
    }
}
