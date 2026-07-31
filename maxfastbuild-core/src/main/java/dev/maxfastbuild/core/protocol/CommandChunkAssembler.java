package dev.maxfastbuild.core.protocol;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reassembles command-safe chunks before the authenticated envelope is parsed. */
public final class CommandChunkAssembler {
    public static final int MAX_COMMAND_LENGTH = 240;
    public static final int CHUNK_SIZE = 128;
    public static final int MAX_CHUNKS = 128;
    /** Maximum number of in-flight transfers a single player may have at once. */
    public static final int MAX_TRANSFERS_PER_PLAYER = 3;
    /** Maximum number of chunk parts kept in memory across all players. */
    public static final int MAX_TOTAL_CHUNKS = 512;
    private final Clock clock;
    private final Duration timeout;
    private final Map<Key, Transfer> transfers = new ConcurrentHashMap<>();

    public CommandChunkAssembler(Clock clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
    }

    public List<String> split(String envelope) {
        String transferId = UUID.randomUUID().toString().substring(0, 8);
        int total = Math.max(1, (envelope.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (total > MAX_CHUNKS) throw new IllegalArgumentException("request_too_large");
        List<String> commands = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            String chunk = envelope.substring(index * CHUNK_SIZE, Math.min(envelope.length(), (index + 1) * CHUNK_SIZE));
            String command = "__mfb p " + transferId + " " + index + " " + total + " " + chunk;
            if (command.length() > MAX_COMMAND_LENGTH) throw new IllegalStateException("command_chunk_too_long");
            commands.add(command);
        }
        return List.copyOf(commands);
    }

    public Optional<String> accept(UUID playerId, String transferId, int index, int total, String chunk) {
        purgeExpired();
        if (!transferId.matches("[0-9a-f]{8}") || total < 1 || total > MAX_CHUNKS || index < 0 || index >= total || chunk.length() > CHUNK_SIZE)
            throw new IllegalArgumentException("invalid_chunk");
        long playerTransfers = transfers.keySet().stream().filter(k -> k.playerId().equals(playerId)).count();
        if (playerTransfers >= MAX_TRANSFERS_PER_PLAYER) {
            throw new IllegalArgumentException("too_many_transfers");
        }
        int totalChunksInMemory = transfers.values().stream().mapToInt(t -> t.parts.size()).sum();
        if (totalChunksInMemory >= MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("too_many_chunks");
        }
        Key key = new Key(playerId, transferId);
        Transfer transfer = transfers.compute(key, (ignored, current) -> {
            if (current == null) current = new Transfer(total, clock.millis());
            if (current.total != total) throw new IllegalArgumentException("chunk_total_changed");
            current.parts.putIfAbsent(index, chunk);
            return current;
        });
        if (transfer.parts.size() != total) return Optional.empty();
        StringBuilder result = new StringBuilder(total * CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            String part = transfer.parts.get(i);
            if (part == null) return Optional.empty();
            result.append(part);
        }
        transfers.remove(key);
        return Optional.of(result.toString());
    }

    private void purgeExpired() {
        long cutoff = clock.millis() - timeout.toMillis();
        transfers.entrySet().removeIf(entry -> entry.getValue().createdAt < cutoff);
    }

    /** Drop all in-flight transfers (plugin disable / hot-reload). */
    public void clear() {
        transfers.clear();
    }

    private record Key(UUID playerId, String transferId) {}
    private static final class Transfer {
        private final int total;
        private final long createdAt;
        private final Map<Integer, String> parts = new HashMap<>();
        private Transfer(int total, long createdAt) { this.total = total; this.createdAt = createdAt; }
    }
}
