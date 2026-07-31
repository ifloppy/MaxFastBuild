package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.fabric.client.BuildSelectionController;
import dev.maxfastbuild.fabric.client.LitematicaBridge;
import dev.maxfastbuild.fabric.client.PasteBlock;
import dev.maxfastbuild.fabric.client.RadialBuildScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Minecraft 26.2 implementation: extract-phase rendering, gizmos, KeyMapping.Category. */
public final class ClientPlatformImpl extends ClientPlatform {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("maxfastbuild", "main"));

    @Override
    public KeyMapping createRadialKey() {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maxfastbuild.radial", GLFW.GLFW_KEY_LEFT_ALT, CATEGORY));
    }

    @Override
    public KeyMapping createPasteKey() {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maxfastbuild.paste", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }

    @Override
    public boolean isScreenOpen(Minecraft client) {
        return client.gui.screen() != null;
    }

    @Override
    public void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    @Override
    public void registerHud() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("maxfastbuild", "selection_help"),
                (graphics, delta) -> BuildSelectionController.renderHud(new GuiGraphicsExtractorHudCanvas(graphics)));
    }

    @Override
    public void registerPreviewRenderer() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            Minecraft client = Minecraft.getInstance();
            if (client.levelRenderer == null) return;
            try (var ignored = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
                GizmoPreviewRenderer.render(BuildSelectionController.collectPreview());
            }
        });
    }

    @Override
    public void sendSystemMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(message);
    }

    @Override
    public void sendOverlayMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendOverlayMessage(message);
    }

    @Override
    public boolean isKeyPhysicalDown(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(client.getWindow(), key.getValue());
    }

    @Override
    public void registerHotbarScrollHook() {
        ClientHotbarScrollEvents.ALLOW.register((inventory, selected, next, horizontal, vertical) ->
                !BuildSelectionController.onHotbarScroll(vertical));
    }

    @Override
    public List<PasteBlock> collectLitematicaPlacement() {
        return LitematicaBridge.collect();
    }
}
