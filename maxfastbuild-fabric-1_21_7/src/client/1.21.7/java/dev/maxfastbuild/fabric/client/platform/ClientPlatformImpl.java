package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.fabric.client.BuildSelectionController;
import dev.maxfastbuild.fabric.client.LitematicaBridge;
import dev.maxfastbuild.fabric.client.PasteBlock;
import dev.maxfastbuild.fabric.client.RadialBuildScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Minecraft 1.21.7 implementation: classic render events, String key category, render-thread rendering. */
public final class ClientPlatformImpl extends ClientPlatform {
    @Override
    public KeyMapping createRadialKey() {
        KeyMapping mapping = new KeyMapping("key.maxfastbuild.radial",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "maxfastbuild.main");
        KeyBindingHelper.registerKeyBinding(mapping);
        return mapping;
    }

    @Override
    public KeyMapping createPasteKey() {
        KeyMapping mapping = new KeyMapping("key.maxfastbuild.paste",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "maxfastbuild.main");
        KeyBindingHelper.registerKeyBinding(mapping);
        return mapping;
    }

    @Override
    public boolean isScreenOpen(Minecraft client) {
        return client.screen != null;
    }

    @Override
    public void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    public void registerHud() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) ->
                BuildSelectionController.renderHud(new GuiGraphicsHudCanvas(graphics)));
    }

    @Override
    public void registerPreviewRenderer() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                LevelPreviewRenderer.render(context, BuildSelectionController.collectPreview()));
    }

    @Override
    public void sendSystemMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.displayClientMessage(message, false);
    }

    @Override
    public void sendOverlayMessage(Component message) {
        Minecraft.getInstance().gui.setOverlayMessage(message, false);
    }

    @Override
    public boolean isKeyPhysicalDown(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) return false;
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(client.getWindow().getWindow(), key.getValue());
    }

    /** fabric-api for 1.21.7 has no hotbar-scroll event — radial scroll lock is unavailable. */
    @Override
    public void registerHotbarScrollHook() {
    }

    @Override
    public List<PasteBlock> collectLitematicaPlacement() {
        return LitematicaBridge.collect();
    }
}
