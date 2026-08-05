package dev.maxfastbuild.core.protocol;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Reassembles command-safe chunks before the authenticated envelope is parsed. */
public final class CommandChunkAssembler {
    public static final int MAX_COMMAND_LENGTH = 240;
    public static final int CHUNK_SIZE = 128;
    /** Max chunks per transfer; each chunk is CHUNK_SIZE chars, so one envelope may carry up to 128 KiB. */
    public static final int MAX_CHUNKS = 1024;
    /** Maximum number of in-flight transfers a single player may have at once. */
    public static final int MAX_TRANSFERS_PER_PLAYER = 3;
    /** Maximum number of chunk parts kept in memory across all players. */
    public static final int MAX_TOTAL_CHUNKS = 4096;
    private final Clock clock;
    private final Duration timeout;
    private final Map<Key, Transfer> transfers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerTransferCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalChunksInMemory = new AtomicInteger(0);

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
        int currentPlayerTransfers = playerTransferCounts.getOrDefault(playerId, 0);
        if (currentPlayerTransfers >= MAX_TRANSFERS_PER_PLAYER) {
            throw new IllegalArgumentException("too_many_transfers");
        }
        if (totalChunksInMemory.get() >= MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("too_many_chunks");
        }
        Key key = new Key(playerId, transferId);
        boolean[] isNew = {false};
        Transfer transfer = transfers.compute(key, (ignored, current) -> {
            if (current == null) {
                current = new Transfer(total, clock.millis());
                isNew[0] = true;
            }
            if (current.total != total) throw new IllegalArgumentException("chunk_total_changed");
            current.parts.putIfAbsent(index, chunk);
            return current;
        });
        if (isNew[0]) {
            playerTransferCounts.merge(playerId, 1, Integer::sum);
            totalChunksInMemory.addAndGet(total);
        }
        if (transfer.parts.size() != total) return Optional.empty();
        StringBuilder result = new StringBuilder(total * CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            String part = transfer.parts.get(i);
            if (part == null) return Optional.empty();
            result.append(part);
        }
        transfers.remove(key);
        playerTransferCounts.merge(playerId, -1, (old, ignore) -> old <= 1 ? null : old - 1);
        totalChunksInMemory.addAndGet(-total);
        return Optional.of(result.toString());
    }

    private void purgeExpired() {
        long cutoff = clock.millis() - timeout.toMillis();
        transfers.entrySet().removeIf(entry -> {
            if (entry.getValue().createdAt < cutoff) {
                playerTransferCounts.merge(entry.getKey().playerId(), -1, (old, ignore) -> old <= 1 ? null : old - 1);
                totalChunksInMemory.addAndGet(-entry.getValue().total);
                return true;
            }
            return false;
        });
    }

    /** Drop all in-flight transfers (plugin disable / hot-reload). */
    public void clear() {
        transfers.clear();
        playerTransferCounts.clear();
        totalChunksInMemory.set(0);
    }

    private record Key(UUID playerId, String transferId) {}
    private static final class Transfer {
        private final int total;
        private final long createdAt;
        private final Map<Integer, String> parts = new HashMap<>();
        private Transfer(int total, long createdAt) { this.total = total; this.createdAt = createdAt; }
    }
}
