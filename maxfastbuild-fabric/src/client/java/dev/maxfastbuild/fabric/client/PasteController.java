package dev.maxfastbuild.fabric.client;

import com.google.gson.JsonObject;
import dev.maxfastbuild.core.protocol.CommandChunkAssembler;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import dev.maxfastbuild.core.protocol.ProtocolEnvelope;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
import dev.maxfastbuild.fabric.client.platform.HudCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streams a Litematica placement to the Paper server as one bulk paste.
 * <p>
 * Flow: press the paste key → read the active placement → request a fresh {@code /__mfb hello}
 * session → send the palette + block list as gzipped envelope parts over {@code /__mfb p}
 * transfers, one part at a time, waiting for the server's {@code paste_ack} before the next.
 * The server validates every mutation, deducts materials, charges, and enqueues a single task.
 * Block-entity NBT is preserved in the palette; the instant-paste toggle switches to the paid
 * synchronous server path.
 */
final class PasteController {
    private static final long HELLO_TIMEOUT_MS = 6_000;
    private static final long ACK_TIMEOUT_MS = 12_000;
    /** Vanilla command packets are sent gradually; this prevents a large paste from looking like a command flood. */
    private static final int MAX_RETRIES = 3;

    private enum State { IDLE, PENDING_HELLO, SENDING }

    private static State state = State.IDLE;
    private static boolean prevKeyDown;
    private static boolean prevInstantKeyDown;
    /** Instant-paste mode: paid synchronous server execution (Litematica creative-style placement). */
    private static boolean instant;
    /** Client-side paste filters selected in the settings screen; applied before sending. */
    private static PasteSettings settings = PasteSettings.DEFAULT;

    static PasteSettings settings() {
        return settings;
    }

    static boolean instant() {
        return instant;
    }

    static PasteMetrics pasteMetrics() {
        return pasteMetrics;
    }

    /** Server-advertised instant-paste price multiplier, formatted for the toggle message. */
    private static String instantMultiplier = "2";
    /** Metrics for the most recently collected placement, shown in the paste settings screen. */
    private static PasteMetrics pasteMetrics = new PasteMetrics(0, 0, 0, 0, 0);
    private static final CommandChunkAssembler CHUNKS = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(15));
    private static List<PasteTransfer.Payload> parts;
    private static String pasteSessionId;
    private static int currentPart;
    private static boolean waitingAck;
    private static long pendingSince;
    private static List<String> commandQueue = List.of();
    private static int nextCommand;
    private static long nextCommandAt;
    private static int retries;
    private static String activeEnvelope;
    private static String activeTransferId;
    private static String sessionId;
    private static byte[] secret;
    private static long sequence;

    private PasteController() {}

    /** Called from the client tick; also tracks the paste/instant key press edges. */
    static void tick(Minecraft client) {
        boolean down = client.player != null
                && !ClientPlatform.instance().isScreenOpen(client)
                && MaxFastBuildClient.isKeyPhysicallyDown(MaxFastBuildClient.pasteKey);
        if (down && !prevKeyDown) {
            startPaste();
        }
        prevKeyDown = down;
        boolean instantDown = client.player != null
                && !ClientPlatform.instance().isScreenOpen(client)
                && MaxFastBuildClient.isKeyPhysicallyDown(MaxFastBuildClient.instantKey);
        if (instantDown && !prevInstantKeyDown) {
            toggleInstant();
        }
        prevInstantKeyDown = instantDown;
        if (state == State.SENDING) pumpCommands(client);
        if (state == State.PENDING_HELLO && now() - pendingSince > HELLO_TIMEOUT_MS) {
            abort("maxfastbuild.paste.hello_timeout");
        } else if (state == State.SENDING && waitingAck && now() - pendingSince > ACK_TIMEOUT_MS) {
            if (retries++ >= MAX_RETRIES) {
                abort("maxfastbuild.paste.ack_timeout");
            } else {
                // Re-send the same authenticated envelope and transfer id. The server's SQLite
                // store keeps already received chunks and treats duplicates as idempotent.
                waitingAck = false;
                nextCommand = 0;
                commandQueue = List.of();
                sendPart(false);
            }
        }
    }

    /** Upload progress HUD; the redstone warning remains in the settings screen. */
    static void renderHud(HudCanvas canvas) {
        if (state != State.SENDING || clientPlayer() == null || parts == null) return;
        Component text = Component.translatable("maxfastbuild.paste.uploading", currentPart + 1, parts.size());
        canvas.centeredText(Minecraft.getInstance().font, text, canvas.guiWidth() / 2, canvas.guiHeight() - 64,
                instant ? 0xFFFFC44D : 0xFF8EE9FF);
    }

    static void onHello(JsonObject object) {
        if (object.has("instantMultiplier")) {
            try {
                instantMultiplier = object.get("instantMultiplier").getAsBigDecimal().stripTrailingZeros().toPlainString();
            } catch (RuntimeException ignored) {
            }
        }
        if (state != State.PENDING_HELLO && !(state == State.SENDING && parts != null)) return;
        if (!object.has("sessionId") || !object.has("secret")) {
            abort("maxfastbuild.paste.hello_failed");
            return;
        }
        try {
            sessionId = object.get("sessionId").getAsString();
            secret = Base64.getUrlDecoder().decode(object.get("secret").getAsString());
        } catch (IllegalArgumentException ex) {
            abort("maxfastbuild.paste.hello_failed");
            return;
        }
        ServerCapabilities.Limits capabilities = ServerCapabilities.current();
        if (capabilities == null) {
            abort("maxfastbuild.paste.hello_failed");
            return;
        }
        // A reconnect gets a new HMAC session, but the SQLite server store still has the same
        // pasteSessionId. Keep the already collected parts and resume at the last unacknowledged
        // part instead of recollecting the schematic or restarting at part zero.
        if (parts != null && pasteSessionId != null) {
            if (parts.size() > capabilities.maxPasteParts()) {
                abort("maxfastbuild.paste.too_large");
                return;
            }
            currentPart = Math.max(0, Math.min(currentPart, parts.size() - 1));
            sequence = 0;
            retries = 0;
            waitingAck = false;
            commandQueue = List.of();
            activeEnvelope = null;
            activeTransferId = null;
            state = State.SENDING;
            sendPart(true);
            return;
        }
        List<PasteBlock> blocks = ClientPlatform.instance().collectLitematicaPlacement();
        if (blocks == null || blocks.isEmpty()) {
            notify(Component.translatable(reasonKey(LitematicaBridge.lastReason())));
            resetSession();
            return;
        }
        blocks = applySkip(blocks, settings);
        if (blocks.isEmpty()) {
            resetSession();
            notify(Component.translatable("maxfastbuild.paste.no_blocks_after_filters"));
            return;
        }
        pasteMetrics = PasteMetrics.from(LitematicaBridge.lastRegions(), blocks);
        List<PasteEntity> rawEntities = LitematicaBridge.lastEntities();
        List<PasteEntity> entities = applyEntitySkip(rawEntities, settings);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (PasteBlock block : blocks) {
            minX = Math.min(minX, block.x());
            minY = Math.min(minY, block.y());
            minZ = Math.min(minZ, block.z());
        }
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<String> palette = new ArrayList<>();
        List<PasteTransfer.Entry> entries = new ArrayList<>(blocks.size());
        for (PasteBlock block : blocks) {
            String clean = block.blockData();
            int index = paletteIndex.computeIfAbsent(clean, ignored -> {
                palette.add(clean);
                return palette.size() - 1;
            });
            entries.add(new PasteTransfer.Entry(block.x() - minX, block.y() - minY, block.z() - minZ, index));
        }
        List<PasteTransfer.EntityEntry> entityEntries = new ArrayList<>(entities.size());
        for (PasteEntity entity : entities) {
            entityEntries.add(new PasteTransfer.EntityEntry(entity.type(), entity.x(), entity.y(), entity.z(), entity.nbt()));
        }
        pasteSessionId = UUID.randomUUID().toString();
        try {
            parts = PasteTransfer.split(pasteSessionId, new int[]{minX, minY, minZ}, palette, entries,
                    entityEntries, instant, settings.skipContents(), LitematicaBridge.lastRegions(),
                    capabilities.maxBlocksPerPart(), capabilities.maxPasteParts());
        } catch (IllegalArgumentException ex) {
            resetSession();
            notify(Component.translatable("maxfastbuild.paste.too_large"));
            return;
        }
        currentPart = 0;
        waitingAck = false;
        sequence = 0;
        retries = 0;
        commandQueue = List.of();
        nextCommand = 0;
        activeEnvelope = null;
        activeTransferId = null;
        state = State.SENDING;
        notify(Component.translatable(instant ? "maxfastbuild.paste.starting_instant" : "maxfastbuild.paste.starting", blocks.size()));
        sendPart(true);
    }

    static void onAck(JsonObject object) {
        if (state != State.SENDING) return;
        if (!object.has("pasteSessionId") || !object.has("part") || !object.has("parts")) return;
        if (!pasteSessionId.equals(object.get("pasteSessionId").getAsString())) return;
        int acked = object.get("part").getAsInt();
        int total = object.get("parts").getAsInt();
        if (acked != currentPart || total != parts.size()) return;
        waitingAck = false;
        currentPart++;
        if (currentPart >= parts.size()) {
            state = State.IDLE;
            parts = null;
            commandQueue = List.of();
            activeEnvelope = null;
            activeTransferId = null;
            notify(Component.translatable("maxfastbuild.paste.sent", total));
        } else {
            retries = 0;
            sendPart(true);
        }
    }

    /** Abort the active paste session (the error message is shown by the caller). */
    static void onError(JsonObject object) {
        resetSession();
    }

    /** Preserve the durable paste cursor across a network reconnect and request a fresh HMAC session. */
    static void onConnectionJoin() {
        if (state != State.SENDING) return;
        state = State.PENDING_HELLO;
        pendingSince = now();
        waitingAck = false;
        commandQueue = List.of();
        nextCommand = 0;
        activeEnvelope = null;
        activeTransferId = null;
        sessionId = null;
        secret = null;
    }

    private static void startPaste() {
        if (state != State.IDLE) {
            notify(Component.translatable("maxfastbuild.paste.in_progress"));
            return;
        }
        // Open the settings screen only when a paste is actually available, so the popup is
        // never shown for an empty/disabled placement.
        List<PasteBlock> blocks = ClientPlatform.instance().collectLitematicaPlacement();
        if (blocks == null || blocks.isEmpty()) {
            notify(Component.translatable(reasonKey(LitematicaBridge.lastReason())));
            return;
        }
        pasteMetrics = PasteMetrics.from(LitematicaBridge.lastRegions(), blocks);
        ClientPlatform.instance().openPasteSettings();
    }

    /** Called by the settings screen when the player confirms. */
    static void confirmStart(PasteSettings newSettings, boolean newInstant) {
        if (state != State.IDLE) {
            notify(Component.translatable("maxfastbuild.paste.in_progress"));
            return;
        }
        settings = newSettings;
        instant = newInstant;
        // Container contents are stripped at collection time (before SNBT serialization), so the
        // collected palette never carries them; the server preserves any existing destination contents.
        LitematicaBridge.setStripContainerItems(newSettings.skipContents());
        state = State.PENDING_HELLO;
        pendingSince = now();
        send("__mfb hello " + ProtocolEnvelope.CURRENT_VERSION);
    }

    /**
     * Apply the settings screen filters to a collected placement. Fluids are dropped, block-entity
     * NBT is stripped from the palette (server then places a plain block), and container contents
     * are stripped from block-entity NBT; existing destination contents are preserved when {@code skipContents}.
     */
    private static List<PasteBlock> applySkip(List<PasteBlock> blocks, PasteSettings skip) {
        if (!skip.skipFluids() && !skip.skipEntities() && !skip.skipNbt() && !skip.skipContents()) return blocks;
        List<PasteBlock> out = new ArrayList<>(blocks.size());
        for (PasteBlock block : blocks) {
            if (skip.skipFluids() && isFluidBlock(block.blockData())) continue;
            if (skip.skipEntities() && isEntityBlock(block.blockData())) continue;
            String data = block.blockData();
            if (skip.skipNbt()) {
                int brace = data.indexOf('{');
                if (brace >= 0) data = data.substring(0, brace);
            }
            out.add(new PasteBlock(block.x(), block.y(), block.z(), data));
        }
        return out;
    }

    private static boolean isFluidBlock(String blockData) {
        if (blockData == null) return false;
        int brace = blockData.indexOf('{');
        String state = brace >= 0 ? blockData.substring(0, brace) : blockData;
        int bracket = state.indexOf('[');
        String base = bracket >= 0 ? state.substring(0, bracket) : state;
        return "minecraft:water".equals(base) || "minecraft:lava".equals(base);
    }

    private static boolean isEntityBlock(String blockData) {
        // Entity paste is collected separately (not as PasteBlock); reserved for that feature.
        return false;
    }

    /**
     * Entity skip filters: {@code skipEntities} drops every entity; {@code skipMobs} drops only
     * living creatures (villagers, animals, monsters) and keeps item/vehicle/decor entities such as
     * minecarts, boats, armor stands, item frames and paintings; {@code skipDrops} drops only
     * dropped item entities ({@code minecraft:item}) while keeping minecarts, boats and decor.
     */
    private static List<PasteEntity> applyEntitySkip(List<PasteEntity> entities, PasteSettings skip) {
        if (entities == null || entities.isEmpty()) return entities;
        if (!skip.skipEntities() && !skip.skipMobs() && !skip.skipDrops()) return entities;
        List<PasteEntity> out = new ArrayList<>(entities.size());
        for (PasteEntity entity : entities) {
            if (skip.skipEntities()) continue;
            if (skip.skipMobs() && isMobEntity(entity.type())) continue;
            if (skip.skipDrops() && isDropItemEntity(entity.type())) continue;
            out.add(entity);
        }
        return out;
    }

    private static boolean isDropItemEntity(String type) {
        String id = type.indexOf(':') >= 0 ? type.substring(type.indexOf(':') + 1) : type;
        return id.equals("item");
    }

    private static boolean isMobEntity(String type) {
        String id = type.indexOf(':') >= 0 ? type.substring(type.indexOf(':') + 1) : type;
        return !(id.endsWith("minecart")
                || id.endsWith("_boat")
                || id.equals("armor_stand")
                || id.equals("item_frame")
                || id.equals("glow_item_frame")
                || id.equals("painting")
                || id.equals("leash_knot")
                || id.endsWith("_display")
                || id.equals("interaction")
                || id.equals("marker"));
    }

    private static void sendPart(boolean freshEnvelope) {
        if (parts == null || currentPart < 0 || currentPart >= parts.size()) {
            resetSession();
            return;
        }
        if (freshEnvelope) {
            PasteTransfer.Payload payload = parts.get(currentPart);
            byte[] zipped = PasteTransfer.gzip(PasteTransfer.encode(payload));
            ServerCapabilities.Limits capabilities = ServerCapabilities.current();
            if (capabilities == null || zipped.length > capabilities.maxPayloadBytes()) {
                abort("maxfastbuild.paste.too_large");
                return;
            }
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(zipped);
            long seq = sequence++;
            String signingInput = ProtocolEnvelope.CURRENT_VERSION + "\n" + sessionId + "\n" + seq + "\n" + payloadB64;
            String mac = hmac(secret, signingInput);
            activeEnvelope = ProtocolEnvelope.CURRENT_VERSION + " " + sessionId + " " + seq + " " + payloadB64 + " " + mac;
            activeTransferId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (activeEnvelope == null || activeTransferId == null) {
            abort("maxfastbuild.paste.send_failed");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            // The join callback normally handles this first, but a disconnect can race the
            // command pump between two ticks. Preserve the durable paste cursor and wait for the
            // next handshake instead of discarding the collected parts.
            onConnectionJoin();
            return;
        }
        try {
            commandQueue = CHUNKS.splitWithDigest(activeEnvelope, activeTransferId);
            nextCommand = 0;
            nextCommandAt = now();
            pendingSince = now();
        } catch (IllegalArgumentException ex) {
            abort("maxfastbuild.paste.too_large");
            return;
        }
        waitingAck = false;
    }

    private static void pumpCommands(Minecraft client) {
        if (commandQueue.isEmpty()) return;
        if (client.getConnection() == null) {
            onConnectionJoin();
            return;
        }
        long now = now();
        if (now < nextCommandAt) return;
        client.getConnection().sendCommand(commandQueue.get(nextCommand++));
        nextCommandAt = now + MaxFastBuildConfig.pasteCommandIntervalMs();
        pendingSince = now;
        if (nextCommand >= commandQueue.size()) {
            commandQueue = List.of();
            waitingAck = true;
            pendingSince = now;
        }
    }

    private static void abort(String messageKey) {
        resetSession();
        notify(Component.translatable(messageKey));
    }

    private static void resetSession() {
        state = State.IDLE;
        parts = null;
        pasteSessionId = null;
        currentPart = 0;
        waitingAck = false;
        commandQueue = List.of();
        nextCommand = 0;
        activeEnvelope = null;
        activeTransferId = null;
        sessionId = null;
        secret = null;
    }

    private static void toggleInstant() {
        instant = !instant;
        notify(Component.translatable(instant ? "maxfastbuild.paste.instant_on" : "maxfastbuild.paste.instant_off", instantMultiplier));
    }

    private static Minecraft clientPlayer() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null ? client : null;
    }

    private static String reasonKey(LitematicaBridge.Reason reason) {
        return switch (reason) {
            case NOT_LOADED -> "maxfastbuild.paste.reason.not_loaded";
            case NO_PLACEMENT -> "maxfastbuild.paste.reason.no_placement";
            case ALL_DISABLED -> "maxfastbuild.paste.reason.all_disabled";
            case NO_CONTAINER -> "maxfastbuild.paste.reason.no_container";
            case ZERO_BLOCKS -> "maxfastbuild.paste.reason.zero_blocks";
            case API_ERROR -> "maxfastbuild.paste.reason.api_error";
        };
    }

    private static String hmac(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void send(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) client.getConnection().sendCommand(command);
    }

    private static void notify(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) ClientPlatform.instance().sendOverlayMessage(message);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
