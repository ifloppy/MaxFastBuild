package dev.maxfastbuild.fabric.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.maxfastbuild.core.protocol.ProtocolEnvelope;
import dev.maxfastbuild.fabric.client.platform.ClientPlatform;
import dev.maxfastbuild.fabric.mixin.KeyMappingAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class MaxFastBuildClient implements ClientModInitializer {
    /** Hold to open radial (default Left Alt). Rebindable in controls. */
    public static KeyMapping radialKey;
    /** Paste the active Litematica placement (default unbound; requires Litematica + server support). */
    public static KeyMapping pasteKey;
    /** Toggle instant paste mode (paid synchronous server execution). */
    public static KeyMapping instantKey;

    private static boolean helloPending;
    /** True after the first successful handshake this connection, so the "loaded" notice shows once. */
    private static boolean handshakeNotified;

    @Override
    public void onInitializeClient() {
        MaxFastBuildConfig.load();
        radialKey = ClientPlatform.instance().createRadialKey();
        pasteKey = ClientPlatform.instance().createPasteKey();
        instantKey = ClientPlatform.instance().createInstantKey();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (helloPending && client.player != null && client.getConnection() != null) {
                helloPending = false;
                client.getConnection().sendCommand("__mfb hello " + ProtocolEnvelope.CURRENT_VERSION);
            }
            // Hold-to-open: use physical key (Screen opens → KeyMapping.releaseAll clears isDown).
            if (isRadialKeyPhysicallyDown()
                    && client.player != null
                    && client.level != null
                    && !ClientPlatform.instance().isScreenOpen(client)) {
                ClientPlatform.instance().setScreen(new RadialBuildScreen());
            }
            PasteController.tick(client);
            BuildSelectionController.tick(client);
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> consumeProtocol(message));
        UseBlockCallback.EVENT.register(BuildSelectionController::onUseBlock);
        UseItemCallback.EVENT.register(BuildSelectionController::onUseItem);
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) ->
                BuildSelectionController.cancelOnAttack(client, clickCount));
        // Selection active + Shift: scroll adjusts look distance instead of hotbar.
        ClientPlatform.instance().registerHotbarScrollHook();

        ClientPlatform.instance().registerHud();
        ClientPlatform.instance().registerPreviewRenderer();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            helloPending = true;
            handshakeNotified = false;
        });
    }

    private static boolean consumeProtocol(Component message) {
        String text = message.getString();
        if (!text.startsWith(ProtocolEnvelope.MESSAGE_MARKER)) {
            // Hide the vanilla "unknown or incomplete command: __mfb ..." error that appears when
            // the server has no MaxFastBuild plugin (handshake can never succeed there).
            if (text.contains("__mfb")) return false;
            return true;
        }
        try {
            JsonObject object = JsonParser.parseString(text.substring(ProtocolEnvelope.MESSAGE_MARKER.length())).getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : "response";
            if ("hello".equals(type)) {
                PasteController.onHello(object);
                notifyHandshakeOk();
                return false;
            }
            if ("paste_ack".equals(type)) {
                PasteController.onAck(object);
                return false;
            }
            if ("error".equals(type)) {
                PasteController.onError(object);
            }
            String key = object.has("messageKey") ? object.get("messageKey").getAsString() : "maxfastbuild.error.protocol";
            Object[] args = arguments(key, object);
            boolean seedHint = isSeedHint(key, object);
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                Component translated = Component.translatable(key, args);
                String plain = translated.getString();
                // Fall back if key unresolved OR format placeholders left unfilled (%s).
                Component finalMessage;
                if (plain.equals(key) || plain.contains("%s") || plain.contains("%d")) {
                    finalMessage = Component.literal(formatFallback(key, args));
                } else {
                    finalMessage = translated;
                }
                if (seedHint) {
                    finalMessage = finalMessage.copy()
                            .append(Component.literal("\n"))
                            .append(seedHintComponent(args));
                }
                ClientPlatform.instance().sendSystemMessage(finalMessage);
            }
        } catch (RuntimeException ex) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                ClientPlatform.instance().sendSystemMessage(Component.literal("MaxFastBuild: invalid server response"));
            }
        }
        return false;
    }

    private static boolean isSeedHint(String key, JsonObject object) {
        if (!"maxfastbuild.error.insufficient_materials".equals(key)) return false;
        if (!object.has("data") || !object.get("data").isJsonObject()) return false;
        JsonObject data = object.getAsJsonObject("data");
        return data.has("seedFarm") && data.get("seedFarm").getAsBoolean();
    }

    private static Component seedHintComponent(Object[] args) {
        String material = args.length >= 3 ? String.valueOf(args[2]) : "";
        Component hint = Component.translatable("maxfastbuild.error.seed_farm_hint", material);
        if (hint.getString().contains("maxfastbuild.error.seed_farm_hint") || hint.getString().contains("%s")) {
            return Component.literal("MaxFastBuild: hold 1 " + material
                    + " + sticky piston + slime block + observer to place it infinitely (not consumed)");
        }
        return hint;
    }

    private static void notifyHandshakeOk() {
        if (handshakeNotified) return;
        handshakeNotified = true;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        Component message = Component.translatable("maxfastbuild.handshake.ok")
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN));
        ClientPlatform.instance().sendSystemMessage(message);
    }

    private static String formatFallback(String key, Object[] args) {
        if ("maxfastbuild.error.version_mismatch".equals(key) && args.length >= 2) {
            return "MaxFastBuild: version mismatch — client v" + args[0] + ", server v" + args[1] + ". Please update to a matching version.";
        }
        if ("maxfastbuild.error.insufficient_materials".equals(key) && args.length >= 3) {
            return "MaxFastBuild: not enough materials: need " + args[0] + ", have " + args[1] + " (" + args[2] + ")";
        }
        if ("maxfastbuild.error.requires_flint_and_steel".equals(key) && args.length >= 1) {
            return "MaxFastBuild: placing fire (" + args[0] + ") requires a flint and steel (1 durability per fire)";
        }
        if ("maxfastbuild.error.requires_buckets".equals(key) && args.length >= 2) {
            return "MaxFastBuild: placing fluid (" + args[0] + ") requires " + args[1] + " matching buckets in your inventory or nearby containers (not consumed)";
        }
        if ("maxfastbuild.error.invalid_material".equals(key) && args.length >= 1) {
            return "MaxFastBuild: invalid material: " + args[0];
        }
        if ("maxfastbuild.error.wrong_tool".equals(key) && args.length >= 1) {
            return "MaxFastBuild: no effective tool for " + args[0];
        }
        if ("maxfastbuild.error.insufficient_tool".equals(key)) {
            return "MaxFastBuild: no mining tool (keep ≥ 4 durability)";
        }
        if ("maxfastbuild.error.hold_block_or_tool".equals(key)) {
            return "MaxFastBuild: hold a placeable block or mining tool";
        }
        if ("maxfastbuild.error.insufficient_tool_durability".equals(key) && args.length >= 2) {
            return "MaxFastBuild: not enough tool durability (need " + args[0] + " hits, have " + args[1] + ")";
        }
        if ("maxfastbuild.error.unbreakable_block".equals(key) && args.length >= 2) {
            return "MaxFastBuild: cannot break/replace unbreakable block at " + args[0] + " (" + args[1] + ")";
        }
        if ("maxfastbuild.error.no_permission".equals(key) && args.length >= 1) {
            return "MaxFastBuild: missing permission " + args[0];
        }
        if ("maxfastbuild.error.shape_too_large".equals(key) && args.length >= 1) {
            return "MaxFastBuild: shape exceeds limit " + args[0];
        }
        if ("maxfastbuild.error.protected".equals(key) && args.length >= 1) {
            return "MaxFastBuild: protected " + args[0];
        }
        if ("maxfastbuild.task.partial".equals(key) && args.length >= 4) {
            return "MaxFastBuild: partial " + args[0] + "/" + args[1] + ", cost " + args[2] + ", refund " + args[3];
        }
        if ("maxfastbuild.task.completed".equals(key) && args.length >= 4) {
            return "MaxFastBuild: complete " + args[0] + "/" + args[1] + ", cost " + args[2] + ", refund " + args[3];
        }
        if ("maxfastbuild.task.accepted".equals(key) && args.length >= 2) {
            return "MaxFastBuild: accepted " + args[0] + " blocks, cost " + args[1];
        }
        if ("maxfastbuild.paste.blocks_skipped".equals(key) && args.length >= 3) {
            return "MaxFastBuild: " + args[0] + " blocks, " + args[1] + " entities skipped (protected/unbreakable/unsupported) — " + args[2] + " placed";
        }
        if ("maxfastbuild.error.paste_precheck_failed".equals(key) && args.length >= 3) {
            return "MaxFastBuild: paste precheck failed (" + args[0] + " items, " + args[1] + " fatal) — " + args[2];
        }
        if (args.length == 0) return "MaxFastBuild: " + key;
        StringBuilder sb = new StringBuilder("MaxFastBuild: ").append(key).append(" [");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.append(']').toString();
    }

    /** Map protocol data fields to translation format args for each messageKey. */
    private static Object[] arguments(String key, JsonObject object) {
        if (!object.has("data") || !object.get("data").isJsonObject()) return new Object[0];
        JsonObject data = object.getAsJsonObject("data");
        return switch (key) {
            case "maxfastbuild.task.accepted" ->
                    new Object[]{jsonString(data, "blocks"), jsonString(data, "charge")};
            case "maxfastbuild.paste.blocks_skipped" ->
                    new Object[]{jsonString(data, "skipped"), jsonString(data, "entitySkipped"), jsonString(data, "planned")};
            case "maxfastbuild.error.paste_precheck_failed" ->
                    new Object[]{jsonString(data, "count"), jsonString(data, "fatal"), jsonString(data, "detail")};
            case "maxfastbuild.task.completed", "maxfastbuild.task.partial" ->
                    new Object[]{jsonString(data, "applied"), jsonString(data, "planned"),
                            jsonString(data, "cost"), jsonString(data, "refund")};
            case "maxfastbuild.error.insufficient_materials" ->
                    new Object[]{jsonString(data, "need"), jsonString(data, "have"), jsonString(data, "material")};
            case "maxfastbuild.error.requires_flint_and_steel" ->
                    new Object[]{jsonString(data, "material")};
            case "maxfastbuild.error.requires_buckets" ->
                    new Object[]{jsonString(data, "material"), jsonString(data, "buckets")};
            case "maxfastbuild.error.unbreakable_block" ->
                    new Object[]{jsonString(data, "position"), jsonString(data, "block")};
            case "maxfastbuild.error.wrong_tool" ->
                    new Object[]{jsonString(data, "block")};
            case "maxfastbuild.error.invalid_material" ->
                    new Object[]{jsonString(data, "material")};
            case "maxfastbuild.error.no_permission" ->
                    new Object[]{jsonString(data, "permission")};
            case "maxfastbuild.error.shape_too_large" ->
                    new Object[]{jsonString(data, "limit")};
            case "maxfastbuild.error.protected" ->
                    data.has("position")
                            ? new Object[]{jsonString(data, "position") + " (" + jsonString(data, "reason") + ")"}
                            : new Object[]{jsonString(data, "reason")};
            case "maxfastbuild.error.payment_failed",
                 "maxfastbuild.error.protocol",
                 "maxfastbuild.error.malformed",
                 "maxfastbuild.error.persistence_failed" ->
                    new Object[]{jsonString(data, "reason")};
            case "maxfastbuild.error.insufficient_tool",
                 "maxfastbuild.error.hold_block_or_tool" -> new Object[0];
            case "maxfastbuild.error.version_mismatch" ->
                    new Object[]{jsonString(data, "clientVersion"), jsonString(data, "serverVersion")};
            default -> legacyArguments(data);
        };
    }

    private static Object[] legacyArguments(JsonObject data) {
        if (data.has("blocks") && data.has("charge")) {
            return new Object[]{jsonString(data, "blocks"), jsonString(data, "charge")};
        }
        if (data.has("need") && data.has("have") && data.has("material")) {
            return new Object[]{jsonString(data, "need"), jsonString(data, "have"), jsonString(data, "material")};
        }
        if (data.has("applied") && data.has("planned") && data.has("refund")) {
            return new Object[]{jsonString(data, "applied"), jsonString(data, "planned"), jsonString(data, "refund")};
        }
        if (data.has("position") && data.has("block")) {
            return new Object[]{jsonString(data, "position"), jsonString(data, "block")};
        }
        if (data.has("block") && data.has("reason")) {
            return new Object[]{jsonString(data, "block")};
        }
        if (data.has("material") && !data.has("need")) {
            return new Object[]{jsonString(data, "material")};
        }
        if (data.has("permission")) return new Object[]{jsonString(data, "permission")};
        if (data.has("limit")) return new Object[]{jsonString(data, "limit")};
        if (data.has("reason")) return new Object[]{jsonString(data, "reason")};
        return new Object[0];
    }

    /** Gson may store numbers as JsonPrimitive numbers — never use getAsString() blindly. */
    private static String jsonString(JsonObject data, String key) {
        if (!data.has(key) || data.get(key).isJsonNull()) return "";
        var el = data.get(key);
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsJsonPrimitive().getAsBigDecimal().stripTrailingZeros().toPlainString();
        }
        return el.getAsString();
    }

    /**
     * Physical bind state via GLFW — works while a Screen is open.
     * {@link KeyMapping#isDown()} is cleared by {@code KeyMapping.releaseAll()} on setScreen.
     */
    static boolean isKeyPhysicallyDown(KeyMapping mapping) {
        if (mapping == null) return false;
        return ClientPlatform.instance().isKeyPhysicalDown(((KeyMappingAccessor) mapping).maxfastbuild$getKey());
    }

    static boolean isRadialKeyPhysicallyDown() {
        return isKeyPhysicallyDown(radialKey);
    }
}
