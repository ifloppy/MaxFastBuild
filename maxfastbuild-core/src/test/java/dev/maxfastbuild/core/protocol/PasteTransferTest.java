package dev.maxfastbuild.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PasteTransferTest {
    private static final int[] ORIGIN = {100, 64, -200};

    private static List<PasteTransfer.Entry> entries(int count) {
        List<PasteTransfer.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new PasteTransfer.Entry(i, 1, -i, i % 2));
        }
        return entries;
    }

    @Test void gzipRoundTrips() {
        String repeated = "{\"operation\":\"paste\",\"blocks\":[\"1,2,3:0\"],\"origin\":[100,64,-200]}".repeat(200);
        byte[] raw = repeated.getBytes(StandardCharsets.UTF_8);
        byte[] zipped = PasteTransfer.gzip(raw);
        assertThat(zipped).hasSizeLessThan(raw.length);
        assertThat(PasteTransfer.gunzip(zipped)).isEqualTo(raw);
    }

    @Test void entryFormatRoundTrips() {
        PasteTransfer.Entry entry = new PasteTransfer.Entry(-3, 12, 4095, 7);
        assertThat(PasteTransfer.parseEntry(PasteTransfer.formatEntry(entry))).isEqualTo(entry);
    }

    @Test void malformedEntryRejected() {
        assertThatThrownBy(() -> PasteTransfer.parseEntry("1,2:0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasteTransfer.parseEntry("a,b,c:0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasteTransfer.parseEntry("1,2,3:x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasteTransfer.parseEntry("1,2,3:-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void encodeDecodeRoundTrips() {
        PasteTransfer.Payload payload = new PasteTransfer.Payload("abc123", 0, 2, ORIGIN,
                List.of("minecraft:stone", "minecraft:oak_planks"), List.of("0,0,0:0", "1,0,0:1"));
        PasteTransfer.Payload decoded = PasteTransfer.decode(PasteTransfer.encode(payload));
        assertThat(decoded.pasteSessionId()).isEqualTo(payload.pasteSessionId());
        assertThat(decoded.part()).isEqualTo(payload.part());
        assertThat(decoded.parts()).isEqualTo(payload.parts());
        assertThat(decoded.origin()).isEqualTo(ORIGIN);
        assertThat(decoded.palette()).isEqualTo(payload.palette());
        assertThat(decoded.blocks()).isEqualTo(payload.blocks());
    }

    @Test void splitCapsPerPartAndRepeatsPalette() {
        List<String> palette = List.of("minecraft:stone");
        List<PasteTransfer.Payload> parts = PasteTransfer.split("session", ORIGIN, palette, entries(PasteTransfer.MAX_BLOCKS_PER_PART + 10));
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).palette()).isEqualTo(palette);
        assertThat(parts.get(1).palette()).isEqualTo(palette);
        assertThat(parts.get(0).blocks()).hasSize(PasteTransfer.MAX_BLOCKS_PER_PART);
        assertThat(parts.get(1).blocks()).hasSize(10);
        assertThat(parts.get(0).part()).isZero();
        assertThat(parts.get(1).part()).isEqualTo(1);
        assertThat(parts.get(1).parts()).isEqualTo(2);
        assertThat(parts.get(0).origin()).isEqualTo(ORIGIN);
    }

    @Test void splitRejectsHugePaste() {
        List<String> palette = List.of("minecraft:stone");
        int overLimit = PasteTransfer.MAX_PARTS * PasteTransfer.MAX_BLOCKS_PER_PART;
        assertThatThrownBy(() -> PasteTransfer.split("session", ORIGIN, palette, entries(overLimit + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
