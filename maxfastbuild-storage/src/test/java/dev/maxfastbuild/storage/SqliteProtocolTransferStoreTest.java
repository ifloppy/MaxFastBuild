package dev.maxfastbuild.storage;

import dev.maxfastbuild.core.protocol.CommandChunkAssembler;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteProtocolTransferStoreTest {
    @TempDir Path directory;

    private SqliteProtocolTransferStore store() {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("protocol.db"));
        SqliteProtocolTransferStore store = new SqliteProtocolTransferStore(database,
                3, 1_000_000, 2, 2_000_000, 4, 131_072, Duration.ofMinutes(5));
        store.initialize();
        return store;
    }

    @Test void commandChunksAreDurableAndChecksumVerified() {
        SqliteProtocolTransferStore store = store();
        UUID player = UUID.randomUUID();
        String envelope = "5 session 0 payload mac";
        List<String> commands = new CommandChunkAssembler(java.time.Clock.systemUTC(), Duration.ofSeconds(10))
                .splitWithDigest(envelope);
        SqliteProtocolTransferStore.ChunkResult result = null;
        for (String command : commands) {
            String[] fields = command.split(" ", 7);
            result = store.acceptChunk(player, fields[2], Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                    fields[5], fields[6]);
        }
        assertThat(result).isNotNull();
        assertThat(result.envelope()).isEqualTo(envelope);

        // The rows are removed after assembly; an unrelated transfer can use the quota again.
        SqliteProtocolTransferStore.ChunkResult next = store.acceptChunk(player, "abcdef12", 0, 1,
                dev.maxfastbuild.core.protocol.ProtocolDigest.sha256("x"), "x");
        assertThat(next.complete()).isTrue();
    }

    @Test void pastePartsSurviveRetryAndAdmissionIsIdempotent() {
        SqliteProtocolTransferStore store = store();
        UUID player = UUID.randomUUID();
        PasteTransfer.Region region = new PasteTransfer.Region(0, 64, 0, 2, 64, 0);
        List<PasteTransfer.Payload> payloads = PasteTransfer.split("paste", new int[]{0, 64, 0},
                List.of("minecraft:stone"), List.of(
                        new PasteTransfer.Entry(0, 0, 0, 0),
                        new PasteTransfer.Entry(1, 0, 0, 0)),
                List.of(), false, false, List.of(region), 1, 4);
        byte[] first = PasteTransfer.gzip(PasteTransfer.encode(payloads.get(0)));
        byte[] second = PasteTransfer.gzip(PasteTransfer.encode(payloads.get(1)));
        SqliteProtocolTransferStore.PasteResult accepted = store.acceptPastePart(player, payloads.get(0), first);
        assertThat(accepted.received()).isEqualTo(1);
        assertThat(accepted.complete()).isFalse();
        SqliteProtocolTransferStore.PasteResult completed = store.acceptPastePart(player, payloads.get(1), second);
        assertThat(completed.complete()).isTrue();
        assertThat(completed.completedParts()).hasSize(2);

        SqliteProtocolTransferStore.PasteResult retry = store.acceptPastePart(player, payloads.get(1), second);
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.completedParts()).hasSize(2);
        assertThat(store.readyPastes(player)).hasSize(1);

        store.markPasteCompleted(player, payloads.get(0).pasteSessionId());
        SqliteProtocolTransferStore.PasteResult afterAdmission = store.acceptPastePart(player, payloads.get(1), second);
        assertThat(afterAdmission.duplicate()).isTrue();
        assertThat(afterAdmission.completedParts()).isEmpty();
        assertThat(store.readyPastes(player)).isEmpty();

        PasteTransfer.Payload corrupted = new PasteTransfer.Payload(payloads.get(0).pasteSessionId(), 0, 1,
                payloads.get(0).origin(), payloads.get(0).palette(), payloads.get(0).blocks(), false,
                List.of(), false, List.of(region), "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.acceptPastePart(player, corrupted,
                "not-a-gzip".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
