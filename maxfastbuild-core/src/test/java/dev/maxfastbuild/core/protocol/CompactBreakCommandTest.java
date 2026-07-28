package dev.maxfastbuild.core.protocol;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.api.BuildMode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CompactBreakCommandTest {
    @Test void roundTripsTenTokens() {
        CompactBreakCommand.Intent intent = new CompactBreakCommand.Intent(
                BuildMode.WALL, new BlockPos(-17, 75, -41), new BlockPos(-13, 71, -41), false);
        String cmd = CompactBreakCommand.format(intent);
        assertThat(cmd.split(" ").length).isEqualTo(10);
        CompactBreakCommand.Intent parsed = CompactBreakCommand.parse("/" + cmd);
        assertThat(parsed.mode()).isEqualTo(BuildMode.WALL);
        assertThat(parsed.first()).isEqualTo(new BlockPos(-17, 75, -41));
        assertThat(parsed.second()).isEqualTo(new BlockPos(-13, 71, -41));
        assertThat(parsed.hollow()).isFalse();
    }

    @Test void rejectsWrongArity() {
        assertThatThrownBy(() -> CompactBreakCommand.parse("__mfb break floor 0 0 0 1 1 1"))
                .hasMessage("break_arity");
        assertThatThrownBy(() -> CompactBreakCommand.parse("__mfb place floor 0 0 0 1 1 1 0 minecraft:stone"))
                .hasMessage("break_arity");
    }
}
