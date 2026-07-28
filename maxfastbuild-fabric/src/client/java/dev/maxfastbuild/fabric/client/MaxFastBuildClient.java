package dev.maxfastbuild.fabric.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.core.protocol.ProtocolEnvelope;
import dev.maxfastbuild.fabric.mixin.KeyMappingAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class MaxFastBuildClient implements ClientModInitializer {
    /** Dedicated controls category: Options → Controls → MaxFastBuild */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("maxfastbuild", "main"));

    /** Hold to open radial (default Left Alt). Rebindable in controls. */
    public static KeyMapping radialKey;

    @Override
    public void onInitializeClient() {
        radialKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.maxfastbuild.radial",
                GLFW.GLFW_KEY_LEFT_ALT,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Hold-to-open: use physical key (Screen opens → KeyMapping.releaseAll clears isDown).
            if (isRadialKeyPhysicallyDown()
                    && client.player != null
                    && client.level != null
                    && client.gui.screen() == null) {
                client.gui.setScreen(new RadialBuildScreen());
            }
            BuildSelectionController.tick(client);
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> consumeProtocol(message));
        UseBlockCallback.EVENT.register(BuildSelectionController::onUseBlock);
        UseItemCallback.EVENT.register(BuildSelectionController::onUseItem);
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) ->
                BuildSelectionController.cancelOnAttack(client, clickCount));
        // Selection active + Shift: scroll adjusts look distance instead of hotbar.
        ClientHotbarScrollEvents.ALLOW.register((inventory, selected, next, horizontal, vertical) ->
                !BuildSelectionController.onHotbarScroll(vertical));

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("maxfastbuild", "selection_help"),
                (graphics, delta) -> BuildSelectionController.renderHud(graphics));

        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            Minecraft client = Minecraft.getInstance();
            if (client.levelRenderer == null) return;
            try (var ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
                BuildSelectionController.submitGizmos();
            }
        });
    }

    private static boolean consumeProtocol(Component message) {
        String text = message.getString();
        if (!text.startsWith(ProtocolEnvelope.MESSAGE_MARKER)) return true;
        try {
            JsonObject object = JsonParser.parseString(text.substring(ProtocolEnvelope.MESSAGE_MARKER.length())).getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : "response";
            if ("hello".equals(type)) return false;
            String key = object.has("messageKey") ? object.get("messageKey").getAsString() : "maxfastbuild.error.protocol";
            Object[] args = arguments(key, object);
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                Component translated = Component.translatable(key, args);
                String plain = translated.getString();
                // Fall back if key unresolved OR format placeholders left unfilled (%s).
                if (plain.equals(key) || plain.contains("%s") || plain.contains("%d")) {
                    client.player.sendSystemMessage(Component.literal(formatFallback(key, args)));
                } else {
                    client.player.sendSystemMessage(translated);
                }
            }
        } catch (RuntimeException ex) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("MaxFastBuild: invalid server response"));
            }
        }
        return false;
    }

    private static String formatFallback(String key, Object[] args) {
        if ("maxfastbuild.error.insufficient_materials".equals(key) && args.length >= 3) {
            return "MaxFastBuild: not enough materials: need " + args[0] + ", have " + args[1] + " (" + args[2] + ")";
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
        if ("maxfastbuild.task.partial".equals(key) && args.length >= 3) {
            return "MaxFastBuild: partial " + args[0] + "/" + args[1] + ", refund " + args[2];
        }
        if ("maxfastbuild.task.completed".equals(key) && args.length >= 3) {
            return "MaxFastBuild: complete " + args[0] + "/" + args[1] + ", refund " + args[2];
        }
        if ("maxfastbuild.task.accepted".equals(key) && args.length >= 2) {
            return "MaxFastBuild: accepted " + args[0] + " blocks, cost " + args[1];
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
            case "maxfastbuild.task.completed", "maxfastbuild.task.partial" ->
                    new Object[]{jsonString(data, "applied"), jsonString(data, "planned"), jsonString(data, "refund")};
            case "maxfastbuild.error.insufficient_materials" ->
                    new Object[]{jsonString(data, "need"), jsonString(data, "have"), jsonString(data, "material")};
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
            case "maxfastbuild.error.insufficient_tool_durability" ->
                    new Object[]{jsonString(data, "need"), jsonString(data, "have")};
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
    static boolean isRadialKeyPhysicallyDown() {
        if (radialKey == null) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) return false;
        InputConstants.Key bound = ((KeyMappingAccessor) radialKey).maxfastbuild$getKey();
        if (bound.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), bound.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(client.getWindow(), bound.getValue());
    }
}
