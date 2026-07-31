package dev.maxfastbuild.fabric.client;

import com.google.gson.JsonObject;
import dev.maxfastbuild.core.protocol.CommandChunkAssembler;
import dev.maxfastbuild.core.protocol.PasteTransfer;
import dev.maxfastbuild.core.protocol.ProtocolEnvelope;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
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
 */
final class PasteController {
    private static final long HELLO_TIMEOUT_MS = 6_000;
    private static final long ACK_TIMEOUT_MS = 12_000;

    private enum State { IDLE, PENDING_HELLO, SENDING }

    private static State state = State.IDLE;
    private static boolean prevKeyDown;
    private static final CommandChunkAssembler CHUNKS = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(15));
    private static List<PasteTransfer.Payload> parts;
    private static String pasteSessionId;
    private static int currentPart;
    private static boolean waitingAck;
    private static long pendingSince;
    private static String sessionId;
    private static byte[] secret;
    private static long sequence;

    private PasteController() {}

    /** Called from the client tick; also tracks the paste key press edge. */
    static void tick(Minecraft client) {
        boolean down = client.player != null && MaxFastBuildClient.isKeyPhysicallyDown(MaxFastBuildClient.pasteKey);
        if (down && !prevKeyDown) {
            startPaste();
        }
        prevKeyDown = down;
        if (state == State.PENDING_HELLO && now() - pendingSince > HELLO_TIMEOUT_MS) {
            abort("maxfastbuild.paste.hello_timeout");
        } else if (state == State.SENDING && waitingAck && now() - pendingSince > ACK_TIMEOUT_MS) {
            abort("maxfastbuild.paste.ack_timeout");
        }
    }

    static void onHello(JsonObject object) {
        if (state != State.PENDING_HELLO) return;
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
        if (object.has("maxBlocks")) {
            try {
                LitematicaBridge.setMaxBlocks(object.get("maxBlocks").getAsInt());
            } catch (IllegalStateException | NumberFormatException ignored) {
            }
        }
        List<PasteBlock> blocks = ClientPlatform.instance().collectLitematicaPlacement();
        if (blocks == null || blocks.isEmpty()) {
            notify(Component.translatable(reasonKey(LitematicaBridge.lastReason())));
            resetSession();
            return;
        }
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
            String clean = stripNbt(block.blockData());
            int index = paletteIndex.computeIfAbsent(clean, ignored -> {
                palette.add(clean);
                return palette.size() - 1;
            });
            entries.add(new PasteTransfer.Entry(block.x() - minX, block.y() - minY, block.z() - minZ, index));
        }
        pasteSessionId = UUID.randomUUID().toString();
        try {
            parts = PasteTransfer.split(pasteSessionId, new int[]{minX, minY, minZ}, palette, entries);
        } catch (IllegalArgumentException ex) {
            resetSession();
            notify(Component.translatable("maxfastbuild.paste.too_large"));
            return;
        }
        currentPart = 0;
        waitingAck = false;
        sequence = 0;
        state = State.SENDING;
        notify(Component.translatable("maxfastbuild.paste.starting", blocks.size()));
        sendPart();
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
            notify(Component.translatable("maxfastbuild.paste.sent", total));
        } else {
            sendPart();
        }
    }

    /** Abort the active paste session (the error message is shown by the caller). */
    static void onError(JsonObject object) {
        resetSession();
    }

    private static void startPaste() {
        if (state != State.IDLE) {
            notify(Component.translatable("maxfastbuild.paste.in_progress"));
            return;
        }
        state = State.PENDING_HELLO;
        pendingSince = now();
        send("__mfb hello");
    }

    private static void sendPart() {
        if (parts == null || currentPart < 0 || currentPart >= parts.size()) {
            resetSession();
            return;
        }
        PasteTransfer.Payload payload = parts.get(currentPart);
        byte[] zipped = PasteTransfer.gzip(PasteTransfer.encode(payload));
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(zipped);
        long seq = sequence++;
        String signingInput = ProtocolEnvelope.CURRENT_VERSION + "\n" + sessionId + "\n" + seq + "\n" + payloadB64;
        String mac = hmac(secret, signingInput);
        String envelope = ProtocolEnvelope.CURRENT_VERSION + " " + sessionId + " " + seq + " " + payloadB64 + " " + mac;
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            abort("maxfastbuild.paste.send_failed");
            return;
        }
        try {
            for (String command : CHUNKS.split(envelope)) {
                client.getConnection().sendCommand(command);
            }
        } catch (IllegalArgumentException ex) {
            abort("maxfastbuild.paste.too_large");
            return;
        }
        waitingAck = true;
        pendingSince = now();
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
        sessionId = null;
        secret = null;
    }

    /** Drop any block-entity NBT ({@code {...}}) so the server can parse the state. */
    private static String stripNbt(String blockData) {
        int brace = blockData.indexOf('{');
        return brace >= 0 ? blockData.substring(0, brace) : blockData;
    }

    private static String reasonKey(LitematicaBridge.Reason reason) {
        return switch (reason) {
            case NOT_LOADED -> "maxfastbuild.paste.reason.not_loaded";
            case NO_PLACEMENT -> "maxfastbuild.paste.reason.no_placement";
            case ALL_DISABLED -> "maxfastbuild.paste.reason.all_disabled";
            case NO_CONTAINER -> "maxfastbuild.paste.reason.no_container";
            case ZERO_BLOCKS -> "maxfastbuild.paste.reason.zero_blocks";
            case TOO_LARGE -> "maxfastbuild.paste.reason.too_large";
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
