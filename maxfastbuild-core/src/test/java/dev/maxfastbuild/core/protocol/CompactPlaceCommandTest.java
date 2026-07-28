package dev.maxfastbuild.core.protocol;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompactPlaceCommandTest {
    @Test void parsesServerLogExamplesWithElevenTokens() {
        String[] logs = {
                "/__mfb place floor -7 71 -34 -13 71 -41 0 minecraft:oak_planks",
                "/__mfb place diagonal_line -11 71 -42 -20 71 -34 0 minecraft:oak_planks",
                "/__mfb place cone -20 75 -30 -20 74 -31 0 minecraft:oak_planks",
                "/__mfb place wall -17 75 -41 -13 71 -41 0 minecraft:oak_planks",
                "/__mfb place floor -13 71 -38 -9 71 -35 0 minecraft:oak_planks"
        };
        for (String log : logs) {
            CompactPlaceCommand.Intent intent = CompactPlaceCommand.parse(log);
            assertThat(intent.material()).isEqualTo("minecraft:oak_planks");
            assertThat(intent.hollow()).isFalse();
            assertThat(CompactPlaceCommand.format(intent).split(" ").length).isEqualTo(11);
        }
        CompactPlaceCommand.Intent wall = CompactPlaceCommand.parse(logs[3]);
        assertThat(wall.mode()).isEqualTo(BuildMode.WALL);
        assertThat(wall.first()).isEqualTo(new BlockPos(-17, 75, -41));
        assertThat(wall.second()).isEqualTo(new BlockPos(-13, 71, -41));
    }

    @Test void rejectsShortCommands() {
        assertThatThrownBy(() -> CompactPlaceCommand.parse("__mfb place floor 0 0 0 1 1 1 0"))
                .hasMessage("place_arity");
    }
}
