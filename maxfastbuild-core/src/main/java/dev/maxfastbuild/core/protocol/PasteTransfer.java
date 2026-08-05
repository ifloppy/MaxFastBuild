package dev.maxfastbuild.core.protocol;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Payload codec for the Litematica bulk-paste channel.
 * <p>
 * A paste is a palette (unique block-state strings) plus an entry list. Each entry is a
 * schematic-relative offset ({@code dx,dy,dz}) and a palette index, serialized as
 * {@code "dx,dy,dz:index"}. The whole payload is gzipped before being placed inside the
 * authenticated {@link SecureProtocol} envelope; the server distinguishes paste payloads
 * from legacy {@code ClientRequest} JSON by the gzip magic bytes.
 * <p>
 * A large paste is split into several parts. Every part repeats the full palette so parts
 * are independent and may be processed out of order.
 */
public final class PasteTransfer {
    /** Maximum number of parts a single paste may be split into. */
    public static final int MAX_PARTS = 64;
    /** Maximum number of block entries carried by a single part. */
    public static final int MAX_BLOCKS_PER_PART = 1600;
    /** Hard cap on decompressed paste JSON bytes (anti gzip-bomb). */
    public static final int MAX_GUNZIP_BYTES = 8_000_000;
    /** Hard cap on entities for an instant (synchronous) paste. */
    public static final int MAX_INSTANT_ENTITIES = 64;
    /** Hard cap on entities per chunk for an instant paste. */
    public static final int MAX_INSTANT_ENTITIES_PER_CHUNK = 32;
    /** Hard cap on entities for a normal queued paste. */
    public static final int MAX_NORMAL_ENTITIES = 500;
    /** Hard cap on entities per chunk for a normal queued paste. */
    public static final int MAX_NORMAL_ENTITIES_PER_CHUNK = 64;

    private static final Gson GSON = new Gson();

    private PasteTransfer() {}

    /** Schematic-relative block entry. */
    public record Entry(int dx, int dy, int dz, int paletteIndex) {
        public Entry {
            if (paletteIndex < 0) throw new IllegalArgumentException("negative_palette_index");
        }
    }

    /** One part of a paste transfer. */
    public record Payload(String pasteSessionId, int part, int parts, int[] origin, List<String> palette, List<String> blocks, boolean instant) {
        public Payload {
            Objects.requireNonNull(pasteSessionId);
            Objects.requireNonNull(palette);
            Objects.requireNonNull(blocks);
            palette = List.copyOf(palette);
            blocks = List.copyOf(blocks);
        }

        /** Backward-compatible constructor defaulting {@code instant} to false (legacy/non-instant). */
        public Payload(String pasteSessionId, int part, int parts, int[] origin, List<String> palette, List<String> blocks) {
            this(pasteSessionId, part, parts, origin, palette, blocks, false);
        }

        @Override
        public int[] origin() {
            return origin == null ? new int[0] : origin.clone();
        }
    }

    public static byte[] gzip(byte[] input) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 4));
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(input);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("gzip failed", ex);
        }
    }

    public static byte[] gunzip(byte[] input) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length * 4))) {
            byte[] buffer = new byte[4096];
            int read;
            long total = 0;
            while ((read = gzip.read(buffer)) != -1) {
                total += read;
                if (total > MAX_GUNZIP_BYTES) {
                    throw new IllegalArgumentException("gunzip_too_large");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid_gzip", ex);
        }
    }

    /** Serialize a payload to UTF-8 JSON bytes (caller gzips for the envelope). */
    public static byte[] encode(Payload payload) {
        return GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
    }

    /** Deserialize a payload from UTF-8 JSON bytes (caller gunzips first). */
    public static Payload decode(byte[] json) {
        return GSON.fromJson(new String(json, StandardCharsets.UTF_8), Payload.class);
    }

    public static String formatEntry(Entry entry) {
        return entry.dx() + "," + entry.dy() + "," + entry.dz() + ":" + entry.paletteIndex();
    }

    public static Entry parseEntry(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) throw new IllegalArgumentException("invalid_paste_entry");
        int index;
        try {
            index = Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid_paste_entry");
        }
        String[] coords = value.substring(0, colon).split(",", 3);
        if (coords.length != 3) throw new IllegalArgumentException("invalid_paste_entry");
        try {
            return new Entry(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]), index);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid_paste_entry");
        }
    }

    /**
     * Split a paste into {@link Payload} parts. Every part repeats the full palette.
     *
     * @throws IllegalArgumentException when the paste exceeds {@link #MAX_PARTS}.
     */
    public static List<Payload> split(String pasteSessionId, int[] origin, List<String> palette, List<Entry> entries) {
        return split(pasteSessionId, origin, palette, entries, false);
    }

    /**
     * Split a paste into {@link Payload} parts. Every part repeats the full palette.
     *
     * @param instant true when the paste should be executed immediately by the server (paid, capped size)
     * @throws IllegalArgumentException when the paste exceeds {@link #MAX_PARTS}.
     */
    public static List<Payload> split(String pasteSessionId, int[] origin, List<String> palette, List<Entry> entries, boolean instant) {
        if (origin == null || origin.length != 3) throw new IllegalArgumentException("invalid_origin");
        int totalParts = Math.max(1, (entries.size() + MAX_BLOCKS_PER_PART - 1) / MAX_BLOCKS_PER_PART);
        if (totalParts > MAX_PARTS) throw new IllegalArgumentException("request_too_large");
        int[] originCopy = origin.clone();
        List<Payload> result = new ArrayList<>(totalParts);
        for (int part = 0; part < totalParts; part++) {
            int from = part * MAX_BLOCKS_PER_PART;
            int to = Math.min(entries.size(), from + MAX_BLOCKS_PER_PART);
            List<String> blockEntries = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                blockEntries.add(formatEntry(entries.get(i)));
            }
            result.add(new Payload(pasteSessionId, part, totalParts, originCopy, palette, blockEntries, instant));
        }
        return result;
    }
}
