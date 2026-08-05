package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.maxfastbuild.fabric.client.PasteBlock;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Per-MC-version SPI. Every version module supplies a {@code ClientPlatformImpl} class
 * (same FQN, compiled only against its own Minecraft) that absorbs the version-volatile
 * call sites: key binding categories, screen access, HUD and world preview rendering.
 * <p>
 * Shared client sources must only use APIs that exist in every supported version and
 * must go through this platform for anything that differs.
 */
public abstract class ClientPlatform {
    private static final ClientPlatform INSTANCE = load();

    public static ClientPlatform instance() {
        return INSTANCE;
    }

    private static ClientPlatform load() {
        try {
            return (ClientPlatform) Class.forName("dev.maxfastbuild.fabric.client.platform.ClientPlatformImpl")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("MaxFastBuild: no client platform implementation found", e);
        }
    }

    /** Create the radial key binding using the version's KeyMapping category API. */
    public abstract KeyMapping createRadialKey();

    /** Create the Litematica bulk-paste key binding (default unbound, rebindable in Controls). */
    public abstract KeyMapping createPasteKey();

    /** Create the instant-paste toggle key binding (default unbound). */
    public abstract KeyMapping createInstantKey();

    /** True when a screen is currently open (radial must not re-open). */
    public abstract boolean isScreenOpen(Minecraft client);

    public abstract void setScreen(Screen screen);

    /** Open the paste-settings screen (filters + instant toggle). */
    public abstract void openPasteSettings();

    /** Register the selection HUD element (graphics receiver type differs per version). */
    public abstract void registerHud();

    /** Register the world-space preview renderer (gizmos vs classic render events). */
    public abstract void registerPreviewRenderer();

    /** Chat/log-line message to the player (sendSystemMessage vs displayClientMessage). */
    public abstract void sendSystemMessage(Component message);

    /** Action-bar style message (overlay). */
    public abstract void sendOverlayMessage(Component message);

    /** Physical key state via GLFW — works while a Screen is open. */
    public abstract boolean isKeyPhysicalDown(InputConstants.Key key);

    /** Hook hotbar-scroll cancellation while a selection is active (no-op where unsupported). */
    public abstract void registerHotbarScrollHook();

    /** Absolute block list of the player's active Litematica placement, empty when unavailable. */
    public abstract List<PasteBlock> collectLitematicaPlacement();

    /** Serialize a Minecraft NBT tag (e.g. a block-entity compound) to SNBT, or null when unsupported. */
    public abstract String nbtToSnbt(Object nbtTag);

    /** Like {@link #nbtToSnbt} but with an extra top-level key removed (e.g. container {@code Items}). */
    public abstract String nbtToSnbtWithoutKey(Object nbtTag, String key);

    /** True when a compound NBT tag holds the given key (Litematica schematic tile data). */
    public abstract boolean nbtHasKey(Object nbt, String key);

    /** The namespaced entity type id of a schematic entity's NBT compound, or null. */
    public abstract String entityType(Object nbt);

    /** Serialize a schematic entity's NBT to SNBT with {@code id}/{@code Pos}/{@code UUID} stripped. */
    public abstract String entityNbtToSnbt(Object nbt);

    /**
     * Serialize the client world's block entity at {@code pos} to SNBT, but only when the block
     * there is exactly {@code expectedBlock} (a lectern-with-book fallback source). Returns null
     * when the block or tile is absent or the save fails.
     */
    public abstract String blockEntityNbtAt(BlockPos pos, Block expectedBlock);
}
