package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.fabric.client.BuildSelectionController;
import dev.maxfastbuild.fabric.client.LitematicaBridge;
import dev.maxfastbuild.fabric.client.PasteBlock;
import dev.maxfastbuild.fabric.client.PasteSettingsScreen;
import dev.maxfastbuild.fabric.client.RadialBuildScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    public KeyMapping createInstantKey() {
        KeyMapping mapping = new KeyMapping("key.maxfastbuild.instant",
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
    public void openPasteSettings() {
        setScreen(new PasteSettingsScreen());
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
    public String nbtToSnbtWithoutKey(Object nbtTag, String key) {
        if (nbtTag instanceof net.minecraft.nbt.CompoundTag compound) {
            net.minecraft.nbt.CompoundTag copy = compound.copy();
            copy.remove("id");
            copy.remove("x");
            copy.remove("y");
            copy.remove("z");
            copy.remove(key);
            return copy.toString();
        }
        if (nbtTag instanceof net.minecraft.nbt.Tag tag) return tag.toString();
        return null;
    }

    @Override
    public String entityType(Object nbt) {
        if (nbt instanceof net.minecraft.nbt.CompoundTag compound) {
            String id = compound.getString("id").orElse("");
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    @Override
    public String entityNbtToSnbt(Object nbt) {
        if (nbt instanceof net.minecraft.nbt.CompoundTag compound) {
            net.minecraft.nbt.CompoundTag copy = compound.copy();
            copy.remove("id");
            copy.remove("Pos");
            copy.remove("UUID");
            return copy.toString();
        }
        return null;
    }

    @Override
    public String entityNbtToSnbtWithoutKey(Object nbt, String key) {
        if (nbt instanceof net.minecraft.nbt.CompoundTag compound) {
            net.minecraft.nbt.CompoundTag copy = compound.copy();
            copy.remove("id");
            copy.remove("Pos");
            copy.remove("UUID");
            copy.remove(key);
            return copy.toString();
        }
        return null;
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
