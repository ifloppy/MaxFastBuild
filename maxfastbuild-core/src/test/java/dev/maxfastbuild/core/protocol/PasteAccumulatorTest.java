package dev.maxfastbuild.core.protocol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PasteAccumulatorTest {
    private static final int[] ORIGIN = {100, 64, -200};
    private static final List<String> PALETTE = List.of("minecraft:stone", "minecraft:oak_planks");

    private static PasteAccumulator newAccumulator() {
        return new PasteAccumulator(Clock.systemUTC(), Duration.ofSeconds(30));
    }

    private static PasteTransfer.Payload part(UUID player, String session, int part, int parts, List<String> blocks) {
        return new PasteTransfer.Payload(session, part, parts, ORIGIN, PALETTE, blocks);
    }

    private static PasteTransfer.Payload part(String session, int part, int parts, List<String> blocks,
                                              boolean skipContents) {
        return new PasteTransfer.Payload(session, part, parts, ORIGIN, PALETTE, blocks,
                false, List.of(), skipContents);
    }

    @Test void assemblesSinglePart() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        PasteTransfer.Payload payload = part(player, "s1", 0, 1, List.of("0,0,0:0", "1,0,0:1"));
        Optional<PasteAccumulator.Assembled> result = accumulator.accept(player, payload);
        assertThat(result).isPresent();
        assertThat(result.get().entries()).hasSize(2);
        assertThat(result.get().palette()).isEqualTo(PALETTE);
        assertThat(result.get().origin()).isEqualTo(ORIGIN);
    }

    @Test void assemblesOutOfOrderParts() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        assertThat(accumulator.accept(player, part(player, "s2", 1, 2, List.of("2,0,0:0")))).isEmpty();
        assertThat(accumulator.accept(player, part(player, "s2", 0, 2, List.of("0,0,0:1")))).isPresent();
    }

    @Test void duplicatePartRejected() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        accumulator.accept(player, part(player, "s3", 0, 2, List.of("0,0,0:0")));
        assertThatThrownBy(() -> accumulator.accept(player, part(player, "s3", 0, 2, List.of("0,0,0:0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void paletteMismatchRejected() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        accumulator.accept(player, part(player, "s4", 0, 2, List.of("0,0,0:0")));
        PasteTransfer.Payload mismatched = new PasteTransfer.Payload("s4", 1, 2, ORIGIN,
                List.of("minecraft:dirt"), List.of("0,0,0:0"));
        assertThatThrownBy(() -> accumulator.accept(player, mismatched)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void skipContentsRoundTripsAndMismatchIsRejected() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        assertThat(accumulator.accept(player, part("skip", 0, 2, List.of("0,0,0:0"), true))).isEmpty();
        assertThatThrownBy(() -> accumulator.accept(player,
                part("skip", 1, 2, List.of("1,0,0:1"), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paste_part_mismatch");

        PasteAccumulator complete = newAccumulator();
        assertThat(complete.accept(player, part("complete", 0, 2, List.of("0,0,0:0"), true))).isEmpty();
        PasteAccumulator.Assembled assembled = complete.accept(player,
                part("complete", 1, 2, List.of("1,0,0:1"), true)).orElseThrow();
        assertThat(assembled.skipContents()).isTrue();
    }

    @Test void paletteIndexOutOfRangeRejectedOnAssembly() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        accumulator.accept(player, part(player, "s5", 0, 2, List.of("0,0,0:9")));
        assertThatThrownBy(() -> accumulator.accept(player, part(player, "s5", 1, 2, List.of("1,0,0:0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidPartRejected() {
        PasteAccumulator accumulator = newAccumulator();
        UUID player = UUID.randomUUID();
        assertThatThrownBy(() -> accumulator.accept(player, new PasteTransfer.Payload("s6", 5, 3, ORIGIN, PALETTE, List.of("0,0,0:0"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accumulator.accept(player, new PasteTransfer.Payload("s6", 0, 1, new int[]{1, 2}, PALETTE, List.of("0,0,0:0"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sessionsAreScopedPerPlayer() {
        PasteAccumulator accumulator = newAccumulator();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertThat(accumulator.accept(alice, part(alice, "shared", 0, 2, List.of("0,0,0:0")))).isEmpty();
        assertThat(accumulator.accept(bob, part(bob, "shared", 0, 2, List.of("0,0,0:1")))).isEmpty();
        assertThat(accumulator.accept(alice, part(alice, "shared", 1, 2, List.of("1,0,0:1")))).isPresent();
        assertThat(accumulator.accept(bob, part(bob, "shared", 1, 2, List.of("1,0,0:0")))).isPresent();
    }
}
