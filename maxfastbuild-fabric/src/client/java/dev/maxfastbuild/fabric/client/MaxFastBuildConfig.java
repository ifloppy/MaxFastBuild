package dev.maxfastbuild.fabric.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side configuration, read from {@code config/maxfastbuild.json}. Currently only exposes
 * {@code debug} (default {@code false}): when enabled, the collection bridge logs the exact NBT it
 * reads from Litematica and the result of the container-{@code Items} stripping, so paste-material
 * bugs can be diagnosed without a debug build. The file is created with defaults when missing.
 */
public final class MaxFastBuildConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("maxfastbuild");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static boolean debug;

    private MaxFastBuildConfig() {}

    /** Load {@code config/maxfastbuild.json}, creating it with defaults if absent. Never throws. */
    public static void load() {
        Path path = configPath();
        try {
            if (Files.isRegularFile(path)) {
                JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (root != null && root.has("debug")) {
                    debug = root.get("debug").getAsBoolean();
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaultRoot()), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ex) {
            debug = false;
            LOGGER.warn("[MaxFastBuild] Failed to read config {}", path, ex);
        }
        LOGGER.info("[MaxFastBuild] config debug={} file={}", debug, path);
    }

    /** Whether the debug diagnostic logging is enabled (config {@code debug}, default false). */
    public static boolean isDebugEnabled() {
        return debug;
    }

    private static JsonObject defaultRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("debug", false);
        return root;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("maxfastbuild.json");
    }
}
