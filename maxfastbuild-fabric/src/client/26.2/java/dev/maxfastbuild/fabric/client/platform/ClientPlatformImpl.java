package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.fabric.client.BuildSelectionController;
import dev.maxfastbuild.fabric.client.LitematicaBridge;
import dev.maxfastbuild.fabric.client.PasteBlock;
import dev.maxfastbuild.fabric.client.PasteSettingsScreen;
import dev.maxfastbuild.fabric.client.RadialBuildScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    public KeyMapping createInstantKey() {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.maxfastbuild.instant", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
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
    public void openPasteSettings() {
        setScreen(new PasteSettingsScreen());
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

    @Override
    public String nbtToSnbt(Object nbtTag) {
        if (nbtTag instanceof net.minecraft.nbt.CompoundTag compound) {
            // Strip structural keys (id/x/y/z) that the server re-derives: identical tile contents
            // then deduplicate in the paste palette instead of blowing it up per position.
            net.minecraft.nbt.CompoundTag copy = compound.copy();
            copy.remove("id");
            copy.remove("x");
            copy.remove("y");
            copy.remove("z");
            return copy.toString();
        }
        if (nbtTag instanceof net.minecraft.nbt.Tag tag) return tag.toString();
        return null;
    }

    @Override
    public boolean nbtHasKey(Object nbt, String key) {
        return nbt instanceof net.minecraft.nbt.CompoundTag compound && compound.contains(key);
    }

    @Override
    public String blockEntityNbtAt(BlockPos pos, Block expectedBlock) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (mc.level.getBlockState(pos).getBlock() != expectedBlock) return null;
        BlockEntity tile = mc.level.getBlockEntity(pos);
        if (tile == null) return null;
        try {
            return nbtToSnbt(tile.saveWithFullMetadata(mc.level.registryAccess()));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
