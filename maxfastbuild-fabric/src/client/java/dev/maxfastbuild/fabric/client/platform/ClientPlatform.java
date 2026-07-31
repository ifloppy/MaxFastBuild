package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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

    /** True when a screen is currently open (radial must not re-open). */
    public abstract boolean isScreenOpen(Minecraft client);

    public abstract void setScreen(Screen screen);

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
}
