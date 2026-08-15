package dev.maxfastbuild.core.protocol;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reassembles multi-part paste payloads per (player, pasteSessionId).
 * <p>
 * Parts may arrive in any order; the paste is complete once every part index is present.
 * Every part repeats the full palette; palettes and origins are validated to match across
 * parts so the client cannot smuggle different block data into the same session.
 */
public final class PasteAccumulator {
    /** Hard memory cap on block entries held across all players. */
    public static final int MAX_TOTAL_BLOCKS = 300_000;

    private final Clock clock;
    private final Duration timeout;
    private final int maxParts;
    private final int maxBlocksPerPart;
    private final int maxTotalBlocks;
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger totalBlocks = new AtomicInteger(0);

    public PasteAccumulator(Clock clock, Duration timeout) {
        this(clock, timeout, PasteTransfer.MAX_PARTS, PasteTransfer.MAX_BLOCKS_PER_PART, MAX_TOTAL_BLOCKS);
    }

    public PasteAccumulator(Clock clock, Duration timeout, int maxParts, int maxBlocksPerPart, int maxTotalBlocks) {
        if (maxParts < 1 || maxBlocksPerPart < 1 || maxTotalBlocks < 1) {
            throw new IllegalArgumentException("invalid_paste_limits");
        }
        this.clock = clock;
        this.timeout = timeout;
        this.maxParts = maxParts;
        this.maxBlocksPerPart = maxBlocksPerPart;
        this.maxTotalBlocks = maxTotalBlocks;
    }

    /**
     * Accept one part. Returns the assembled paste once all parts have arrived (then the
     * session is removed); empty while parts are still missing.
     */
    public Optional<Assembled> accept(UUID playerId, PasteTransfer.Payload payload) {
        Objects.requireNonNull(playerId);
        purgeExpired();
        if (payload.parts() < 1 || payload.parts() > maxParts
                || payload.part() < 0 || payload.part() >= payload.parts()
                || payload.blocks().isEmpty()
                || payload.blocks().size() > maxBlocksPerPart
                || payload.origin().length != 3
                || payload.palette().isEmpty()
                || payload.regions().isEmpty()) {
            throw new IllegalArgumentException("invalid_paste_part");
        }
        int inMemory = totalBlocks.get();
        if ((long) inMemory + payload.blocks().size() > maxTotalBlocks) {
            throw new IllegalArgumentException("too_many_paste_blocks");
        }

        Key key = new Key(playerId, payload.pasteSessionId());
        int[] addedBlockCount = {0};
        Session session = sessions.compute(key, (ignored, current) -> {
            if (current == null) {
                current = new Session(payload.parts(), payload.palette(), payload.origin(), payload.instant(),
                        payload.entities(), payload.skipContents(), payload.regions(), clock.millis());
            } else if (!current.palette.equals(payload.palette()) || !Arrays.equals(current.origin, payload.origin())
                    || current.instant != payload.instant()
                    || current.skipContents != payload.skipContents()
                    || !current.entities.equals(payload.entities())
                    || !current.regions.equals(payload.regions())) {
                throw new IllegalArgumentException("paste_part_mismatch");
            }
            if (current.put(payload.part(), payload.blocks())) throw new IllegalArgumentException("duplicate_paste_part");
            addedBlockCount[0] = payload.blocks().size();
            return current;
        });
        totalBlocks.addAndGet(addedBlockCount[0]);
        if (session.blocks.size() < session.parts) return Optional.empty();

        sessions.remove(key);
        totalBlocks.addAndGet(-session.blockCount);
        List<PasteTransfer.Entry> entries = new ArrayList<>();
        for (int part = 0; part < session.parts; part++) {
            List<String> partBlocks = session.blocks.get(part);
            if (partBlocks == null) throw new IllegalArgumentException("paste_part_gap");
            for (String block : partBlocks) {
                PasteTransfer.Entry entry = PasteTransfer.parseEntry(block);
                if (entry.paletteIndex() >= session.palette.size()) {
                    throw new IllegalArgumentException("palette_index_out_of_range");
                }
                entries.add(entry);
            }
        }
        return Optional.of(new Assembled(payload.pasteSessionId(), session.origin, session.palette, entries,
                session.entities, session.instant, session.skipContents, session.regions));
    }

    /** Drop all in-flight paste sessions (plugin disable / hot-reload). */
    public void clear() {
        sessions.clear();
        totalBlocks.set(0);
    }

    private void purgeExpired() {
        long cutoff = clock.millis() - timeout.toMillis();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().createdAt < cutoff) {
                totalBlocks.addAndGet(-entry.getValue().blockCount);
                return true;
            }
            return false;
        });
    }

    public record Assembled(String pasteSessionId, int[] origin, List<String> palette, List<PasteTransfer.Entry> entries,
                            List<PasteTransfer.EntityEntry> entities, boolean instant, boolean skipContents,
                            List<PasteTransfer.Region> regions) {
        public Assembled {
            Objects.requireNonNull(pasteSessionId);
            Objects.requireNonNull(palette);
            Objects.requireNonNull(entries);
            Objects.requireNonNull(entities);
            Objects.requireNonNull(regions);
            origin = origin == null ? new int[0] : origin.clone();
            palette = List.copyOf(palette);
            entries = List.copyOf(entries);
            entities = List.copyOf(entities);
            regions = List.copyOf(regions);
        }

        /** Backward-compatible constructor with no entities and no skip flag. */
        public Assembled(String pasteSessionId, int[] origin, List<String> palette, List<PasteTransfer.Entry> entries, boolean instant) {
            this(pasteSessionId, origin, palette, entries, List.of(), instant, false, List.of());
        }

        @Override
        public int[] origin() {
            return origin.clone();
        }
    }

    private record Key(UUID playerId, String pasteSessionId) {}

    private static final class Session {
        private final int parts;
        private final List<String> palette;
        private final int[] origin;
        private final boolean instant;
        private final boolean skipContents;
        private final List<PasteTransfer.EntityEntry> entities;
        private final List<PasteTransfer.Region> regions;
        private final long createdAt;
        private final Map<Integer, List<String>> blocks = new HashMap<>();
        private int blockCount;

        private Session(int parts, List<String> palette, int[] origin, boolean instant,
                        List<PasteTransfer.EntityEntry> entities, boolean skipContents,
                        List<PasteTransfer.Region> regions, long createdAt) {
            this.parts = parts;
            this.palette = List.copyOf(palette);
            this.origin = origin.clone();
            this.instant = instant;
            this.skipContents = skipContents;
            this.entities = entities == null ? List.of() : List.copyOf(entities);
            this.regions = regions == null ? List.of() : List.copyOf(regions);
            this.createdAt = createdAt;
        }

        /** @return true when the part index was already present */
        private boolean put(int part, List<String> blockEntries) {
            if (blocks.putIfAbsent(part, List.copyOf(blockEntries)) != null) return true;
            blockCount += blockEntries.size();
            return false;
        }
    }
}
