package dev.maxfastbuild.storage;

import dev.maxfastbuild.core.protocol.CommandChunkAssembler;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import dev.maxfastbuild.core.protocol.ProtocolDigest;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable bounded state for the command transport and bulk paste uploads.
 *
 * <p>The network handler only keeps the current decoded payload on the stack. In-flight command
 * chunks and paste parts live in SQLite, so a player cannot pin the heap by opening many partial
 * transfers and a plugin reload does not lose already uploaded parts.</p>
 */
public final class SqliteProtocolTransferStore {
    private static final int MAX_COMPLETED_PASTE_TOMBSTONES_PER_PLAYER = 32;
    private static final int MAX_PASTE_UPLOAD_RECORDS = 4096;
    private final SqliteDatabase database;
    private final int maxChunkTransfersPerPlayer;
    private final int maxChunkStorageBytes;
    private final int maxPasteUploadsPerPlayer;
    private final int maxPasteStorageBytes;
    private final int maxPasteParts;
    private final int maxPayloadBytes;
    private final long retentionMillis;

    public SqliteProtocolTransferStore(SqliteDatabase database,
                                       int maxChunkTransfersPerPlayer,
                                       int maxChunkStorageBytes,
                                       int maxPasteUploadsPerPlayer,
                                       int maxPasteStorageBytes,
                                       int maxPasteParts,
                                       int maxPayloadBytes,
                                       Duration retention) {
        this.database = database;
        this.maxChunkTransfersPerPlayer = positive(maxChunkTransfersPerPlayer, "max_chunk_transfers");
        this.maxChunkStorageBytes = positive(maxChunkStorageBytes, "max_chunk_storage");
        this.maxPasteUploadsPerPlayer = positive(maxPasteUploadsPerPlayer, "max_paste_uploads");
        this.maxPasteStorageBytes = positive(maxPasteStorageBytes, "max_paste_storage");
        this.maxPasteParts = positive(maxPasteParts, "max_paste_parts");
        this.maxPayloadBytes = positive(maxPayloadBytes, "max_payload_bytes");
        this.retentionMillis = Math.max(1_000L, retention.toMillis());
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException("invalid_" + name);
        return value;
    }

    public void initialize() {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS mfb_protocol_chunk_transfers (
                      player_id TEXT NOT NULL, transfer_id TEXT NOT NULL,
                      total INTEGER NOT NULL, envelope_digest TEXT,
                      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                      PRIMARY KEY(player_id, transfer_id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS mfb_protocol_chunks (
                      player_id TEXT NOT NULL, transfer_id TEXT NOT NULL, part INTEGER NOT NULL,
                      chunk TEXT NOT NULL, created_at INTEGER NOT NULL,
                      PRIMARY KEY(player_id, transfer_id, part)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_mfb_protocol_chunks_updated ON mfb_protocol_chunk_transfers(updated_at)");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS mfb_paste_uploads (
                      player_id TEXT NOT NULL, paste_id TEXT NOT NULL, total INTEGER NOT NULL,
                      metadata_digest TEXT NOT NULL, status TEXT NOT NULL,
                      total_bytes INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                      PRIMARY KEY(player_id, paste_id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS mfb_paste_parts (
                      player_id TEXT NOT NULL, paste_id TEXT NOT NULL, part INTEGER NOT NULL,
                      checksum TEXT NOT NULL, payload BLOB NOT NULL, created_at INTEGER NOT NULL,
                      PRIMARY KEY(player_id, paste_id, part)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_mfb_paste_uploads_updated ON mfb_paste_uploads(updated_at)");
            }
            return null;
        });
    }

    /** Remove abandoned state and its payload rows. Called before quota checks. */
    private void purgeExpired(Connection connection, long cutoff) throws SQLException {
        try (PreparedStatement chunks = connection.prepareStatement("""
                DELETE FROM mfb_protocol_chunks WHERE EXISTS (
                  SELECT 1 FROM mfb_protocol_chunk_transfers t
                  WHERE t.player_id=mfb_protocol_chunks.player_id
                    AND t.transfer_id=mfb_protocol_chunks.transfer_id AND t.updated_at < ?)
                """)) {
            chunks.setLong(1, cutoff);
            chunks.executeUpdate();
        }
        try (PreparedStatement transfers = connection.prepareStatement(
                "DELETE FROM mfb_protocol_chunk_transfers WHERE updated_at < ?")) {
            transfers.setLong(1, cutoff);
            transfers.executeUpdate();
        }
        try (PreparedStatement parts = connection.prepareStatement("""
                DELETE FROM mfb_paste_parts WHERE EXISTS (
                  SELECT 1 FROM mfb_paste_uploads u
                  WHERE u.player_id=mfb_paste_parts.player_id AND u.paste_id=mfb_paste_parts.paste_id
                    AND u.updated_at < ?)
                """)) {
            parts.setLong(1, cutoff);
            parts.executeUpdate();
        }
        try (PreparedStatement uploads = connection.prepareStatement(
                "DELETE FROM mfb_paste_uploads WHERE updated_at < ?")) {
            uploads.setLong(1, cutoff);
            uploads.executeUpdate();
        }
    }

    /** Accept one command chunk and assemble it only after every chunk is durable. */
    public ChunkResult acceptChunk(UUID playerId, String transferId, int index, int total,
                                   String envelopeDigest, String chunk) {
        if (playerId == null || transferId == null || !transferId.matches("[0-9a-f]{8,32}")) {
            throw new IllegalArgumentException("invalid_chunk_transfer");
        }
        if (total < 1 || total > CommandChunkAssembler.MAX_CHUNKS || index < 0 || index >= total
                || chunk == null || chunk.length() > CommandChunkAssembler.CHUNK_SIZE
                || !chunk.matches("[A-Za-z0-9_\\- ]*")) {
            throw new IllegalArgumentException("invalid_chunk");
        }
        if (envelopeDigest != null && !envelopeDigest.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("invalid_envelope_checksum");
        }
        if ((long) total * CommandChunkAssembler.CHUNK_SIZE > maxChunkStorageBytes) {
            throw new IllegalArgumentException("transfer_storage_limit");
        }
        return database.transaction(connection -> {
            long now = System.currentTimeMillis();
            purgeExpired(connection, now - retentionMillis);
            TransferRow transfer = findChunkTransfer(connection, playerId, transferId);
            if (transfer == null) {
                if (countChunkTransfers(connection, playerId) >= maxChunkTransfersPerPlayer
                        || totalChunkBytes(connection) + chunk.length() > maxChunkStorageBytes) {
                    throw new IllegalArgumentException("too_many_chunk_transfers");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mfb_protocol_chunk_transfers(player_id,transfer_id,total,envelope_digest,created_at,updated_at)
                        VALUES(?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, playerId.toString()); insert.setString(2, transferId);
                    insert.setInt(3, total); insert.setString(4, envelopeDigest);
                    insert.setLong(5, now); insert.setLong(6, now); insert.executeUpdate();
                }
                transfer = new TransferRow(total, envelopeDigest);
            } else if (transfer.total != total || !same(transfer.digest, envelopeDigest)) {
                throw new IllegalArgumentException("chunk_transfer_mismatch");
            }

            String existing = findChunk(connection, playerId, transferId, index);
            if (existing != null) {
                if (!existing.equals(chunk)) throw new IllegalArgumentException("chunk_conflict");
            } else {
                if (totalChunkBytes(connection) + chunk.length() > maxChunkStorageBytes) {
                    throw new IllegalArgumentException("too_many_chunks");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mfb_protocol_chunks(player_id,transfer_id,part,chunk,created_at)
                        VALUES(?,?,?,?,?)
                        """)) {
                    insert.setString(1, playerId.toString()); insert.setString(2, transferId);
                    insert.setInt(3, index); insert.setString(4, chunk); insert.setLong(5, now);
                    insert.executeUpdate();
                }
            }
            touchChunkTransfer(connection, playerId, transferId, now);
            int received = countChunks(connection, playerId, transferId);
            if (received < total) return new ChunkResult(null, received, total, existing != null);

            String envelope = readEnvelope(connection, playerId, transferId, total);
            if (transfer.digest != null && !ProtocolDigest.matches(transfer.digest, envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                deleteChunkTransfer(connection, playerId, transferId);
                throw new IllegalArgumentException("envelope_checksum_mismatch");
            }
            deleteChunkTransfer(connection, playerId, transferId);
            return new ChunkResult(envelope, total, total, existing != null);
        });
    }

    /** Store one verified paste payload. Fully received uploads remain READY until task admission. */
    public PasteResult acceptPastePart(UUID playerId, PasteTransfer.Payload payload, byte[] compressedPayload) {
        if (playerId == null || payload == null || compressedPayload == null) {
            throw new IllegalArgumentException("invalid_paste_upload");
        }
        PasteTransfer.verifyChecksum(payload);
        if (payload.parts() < 1 || payload.parts() > maxPasteParts || payload.part() < 0
                || payload.part() >= payload.parts() || compressedPayload.length > maxPayloadBytes) {
            throw new IllegalArgumentException("invalid_paste_upload");
        }
        String metadataDigest = PasteTransfer.metadataChecksum(payload);
        String checksum = payload.checksum();
        return database.transaction(connection -> {
            long now = System.currentTimeMillis();
            purgeExpired(connection, now - retentionMillis);
            PasteRow upload = findPasteUpload(connection, playerId, payload.pasteSessionId());
            if (upload == null) {
                if (countOpenPasteUploads(connection, playerId) >= maxPasteUploadsPerPlayer
                        || countPasteUploads(connection, playerId) >= maxPasteUploadsPerPlayer + MAX_COMPLETED_PASTE_TOMBSTONES_PER_PLAYER
                        || countAllPasteUploads(connection) >= MAX_PASTE_UPLOAD_RECORDS
                        || totalPasteBytes(connection) + compressedPayload.length > maxPasteStorageBytes) {
                    throw new IllegalArgumentException("paste_storage_quota");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mfb_paste_uploads(player_id,paste_id,total,metadata_digest,status,total_bytes,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, playerId.toString()); insert.setString(2, payload.pasteSessionId());
                    insert.setInt(3, payload.parts()); insert.setString(4, metadataDigest);
                    insert.setString(5, "OPEN"); insert.setInt(6, 0);
                    insert.setLong(7, now); insert.setLong(8, now); insert.executeUpdate();
                }
                upload = new PasteRow(payload.parts(), metadataDigest, "OPEN");
            } else {
                if (upload.total != payload.parts() || !upload.metadataDigest.equals(metadataDigest)) {
                    throw new IllegalArgumentException("paste_upload_mismatch");
                }
                if ("COMPLETED".equals(upload.status)) {
                    return new PasteResult(true, true, payload.parts(), payload.parts(), List.of());
                }
            }

            byte[] old = findPastePart(connection, playerId, payload.pasteSessionId(), payload.part());
            if (old != null) {
                String oldChecksum = findPastePartChecksum(connection, playerId, payload.pasteSessionId(), payload.part());
                if (!checksum.equals(oldChecksum) || !java.util.Arrays.equals(old, compressedPayload)) {
                    throw new IllegalArgumentException("paste_part_conflict");
                }
            } else {
                if (totalPasteBytes(connection) + compressedPayload.length > maxPasteStorageBytes) {
                    throw new IllegalArgumentException("paste_storage_quota");
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO mfb_paste_parts(player_id,paste_id,part,checksum,payload,created_at)
                        VALUES(?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, playerId.toString()); insert.setString(2, payload.pasteSessionId());
                    insert.setInt(3, payload.part()); insert.setString(4, checksum);
                    insert.setBytes(5, compressedPayload); insert.setLong(6, now); insert.executeUpdate();
                }
                updatePasteBytes(connection, playerId, payload.pasteSessionId(), compressedPayload.length, now);
            }
            int received = countPasteParts(connection, playerId, payload.pasteSessionId());
            if (received < payload.parts()) {
                return new PasteResult(true, old != null, received, payload.parts(), List.of());
            }

            List<byte[]> parts = readPasteParts(connection, playerId, payload.pasteSessionId(), payload.parts());
            if (parts.size() != payload.parts()) throw new IllegalArgumentException("paste_part_gap");
            try (PreparedStatement complete = connection.prepareStatement("""
                    UPDATE mfb_paste_uploads SET status='READY',updated_at=?
                    WHERE player_id=? AND paste_id=?
                    """)) {
                complete.setLong(1, now); complete.setString(2, playerId.toString());
                complete.setString(3, payload.pasteSessionId()); complete.executeUpdate();
            }
            return new PasteResult(true, old != null, received, payload.parts(), parts);
        });
    }

    /** Return fully received uploads that still need task admission after a reload/restart. */
    public List<ReadyPaste> readyPastes(UUID playerId) {
        if (playerId == null) return List.of();
        return database.transaction(connection -> {
            long now = System.currentTimeMillis();
            purgeExpired(connection, now - retentionMillis);
            List<ReadyPaste> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT paste_id,total FROM mfb_paste_uploads WHERE player_id=? AND status='READY' ORDER BY created_at")) {
                query.setString(1, playerId.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String pasteId = rows.getString(1);
                        int total = rows.getInt(2);
                        List<byte[]> parts = readPasteParts(connection, playerId, pasteId, total);
                        if (parts.size() == total) result.add(new ReadyPaste(pasteId, total, parts));
                    }
                }
            }
            return result;
        });
    }

    /** Atomically turn a admitted upload into a small idempotency tombstone and release its bytes. */
    public void markPasteCompleted(UUID playerId, String pasteId) {
        if (playerId == null || pasteId == null) return;
        database.transaction(connection -> {
            long now = System.currentTimeMillis();
            int completed;
            try (PreparedStatement complete = connection.prepareStatement("""
                    UPDATE mfb_paste_uploads SET status='COMPLETED',total_bytes=0,updated_at=?
                    WHERE player_id=? AND paste_id=? AND status='READY'
                    """)) {
                complete.setLong(1, now);
                complete.setString(2, playerId.toString());
                complete.setString(3, pasteId);
                completed = complete.executeUpdate();
            }
            if (completed > 0) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM mfb_paste_parts WHERE player_id=? AND paste_id=?")) {
                    delete.setString(1, playerId.toString());
                    delete.setString(2, pasteId);
                    delete.executeUpdate();
                }
            }
            return null;
        });
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        database.transaction(connection -> {
            try (PreparedStatement parts = connection.prepareStatement("DELETE FROM mfb_protocol_chunks WHERE player_id=?")) {
                parts.setString(1, playerId.toString()); parts.executeUpdate();
            }
            try (PreparedStatement transfers = connection.prepareStatement("DELETE FROM mfb_protocol_chunk_transfers WHERE player_id=?")) {
                transfers.setString(1, playerId.toString()); transfers.executeUpdate();
            }
            try (PreparedStatement pasteParts = connection.prepareStatement("DELETE FROM mfb_paste_parts WHERE player_id=?")) {
                pasteParts.setString(1, playerId.toString()); pasteParts.executeUpdate();
            }
            try (PreparedStatement uploads = connection.prepareStatement("DELETE FROM mfb_paste_uploads WHERE player_id=?")) {
                uploads.setString(1, playerId.toString()); uploads.executeUpdate();
            }
            return null;
        });
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static TransferRow findChunkTransfer(Connection c, UUID player, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT total,envelope_digest FROM mfb_protocol_chunk_transfers WHERE player_id=? AND transfer_id=?")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? new TransferRow(r.getInt(1), r.getString(2)) : null; }
        }
    }

    private static String findChunk(Connection c, UUID player, String id, int index) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT chunk FROM mfb_protocol_chunks WHERE player_id=? AND transfer_id=? AND part=?")) {
            s.setString(1, player.toString()); s.setString(2, id); s.setInt(3, index);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private static int countChunks(Connection c, UUID player, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM mfb_protocol_chunks WHERE player_id=? AND transfer_id=?")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static int countChunkTransfers(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM mfb_protocol_chunk_transfers WHERE player_id=?")) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static long totalChunkBytes(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT coalesce(sum(length(chunk)),0) FROM mfb_protocol_chunks")) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private static void touchChunkTransfer(Connection c, UUID player, String id, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE mfb_protocol_chunk_transfers SET updated_at=? WHERE player_id=? AND transfer_id=?")) {
            s.setLong(1, now); s.setString(2, player.toString()); s.setString(3, id); s.executeUpdate();
        }
    }

    private static String readEnvelope(Connection c, UUID player, String id, int total) throws SQLException {
        StringBuilder out = new StringBuilder(total * CommandChunkAssembler.CHUNK_SIZE);
        try (PreparedStatement s = c.prepareStatement("SELECT part,chunk FROM mfb_protocol_chunks WHERE player_id=? AND transfer_id=? ORDER BY part")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) {
                int expected = 0;
                while (r.next()) {
                    if (r.getInt(1) != expected++) throw new IllegalArgumentException("chunk_gap");
                    out.append(r.getString(2));
                }
                if (expected != total) throw new IllegalArgumentException("chunk_gap");
            }
        }
        return out.toString();
    }

    private static void deleteChunkTransfer(Connection c, UUID player, String id) throws SQLException {
        try (PreparedStatement parts = c.prepareStatement("DELETE FROM mfb_protocol_chunks WHERE player_id=? AND transfer_id=?")) {
            parts.setString(1, player.toString()); parts.setString(2, id); parts.executeUpdate();
        }
        try (PreparedStatement transfer = c.prepareStatement("DELETE FROM mfb_protocol_chunk_transfers WHERE player_id=? AND transfer_id=?")) {
            transfer.setString(1, player.toString()); transfer.setString(2, id); transfer.executeUpdate();
        }
    }

    private static PasteRow findPasteUpload(Connection c, UUID player, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT total,metadata_digest,status FROM mfb_paste_uploads WHERE player_id=? AND paste_id=?")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? new PasteRow(r.getInt(1), r.getString(2), r.getString(3)) : null; }
        }
    }

    private static int countOpenPasteUploads(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM mfb_paste_uploads WHERE player_id=? AND status<>'COMPLETED'")) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static int countPasteUploads(Connection c, UUID player) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM mfb_paste_uploads WHERE player_id=?")) {
            s.setString(1, player.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static int countAllPasteUploads(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM mfb_paste_uploads")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private static long totalPasteBytes(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT coalesce(sum(total_bytes),0) FROM mfb_paste_uploads WHERE status<>'COMPLETED'")) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private static byte[] findPastePart(Connection c, UUID player, String id, int part) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT payload FROM mfb_paste_parts WHERE player_id=? AND paste_id=? AND part=?")) {
            s.setString(1, player.toString()); s.setString(2, id); s.setInt(3, part);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getBytes(1) : null; }
        }
    }

    private static String findPastePartChecksum(Connection c, UUID player, String id, int part) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT checksum FROM mfb_paste_parts WHERE player_id=? AND paste_id=? AND part=?")) {
            s.setString(1, player.toString()); s.setString(2, id); s.setInt(3, part);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private static void updatePasteBytes(Connection c, UUID player, String id, int added, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE mfb_paste_uploads SET total_bytes=total_bytes+?,updated_at=? WHERE player_id=? AND paste_id=?")) {
            s.setInt(1, added); s.setLong(2, now); s.setString(3, player.toString()); s.setString(4, id); s.executeUpdate();
        }
    }

    private static int countPasteParts(Connection c, UUID player, String id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM mfb_paste_parts WHERE player_id=? AND paste_id=?")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static List<byte[]> readPasteParts(Connection c, UUID player, String id, int total) throws SQLException {
        List<byte[]> result = new ArrayList<>(total);
        try (PreparedStatement s = c.prepareStatement("SELECT part,payload FROM mfb_paste_parts WHERE player_id=? AND paste_id=? ORDER BY part")) {
            s.setString(1, player.toString()); s.setString(2, id);
            try (ResultSet r = s.executeQuery()) {
                int expected = 0;
                while (r.next()) {
                    if (r.getInt(1) != expected++) throw new IllegalArgumentException("paste_part_gap");
                    result.add(r.getBytes(2));
                }
            }
        }
        return result;
    }

    public record ChunkResult(String envelope, int received, int total, boolean duplicate) {
        public boolean complete() { return envelope != null; }
    }

    public record PasteResult(boolean accepted, boolean duplicate, int received, int total, List<byte[]> completedParts) {
        public PasteResult {
            completedParts = completedParts == null ? List.of() : List.copyOf(completedParts);
        }

        public boolean complete() { return received == total; }
    }

    public record ReadyPaste(String pasteSessionId, int total, List<byte[]> parts) {
        public ReadyPaste {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    private record TransferRow(int total, String digest) {}
    private record PasteRow(int total, String metadataDigest, String status) {}
}
