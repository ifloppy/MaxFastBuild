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
 * {@code debug} (default {@code false}) and {@code pasteCommandIntervalMs} (default 80): when
 * enabled, the collection bridge logs the exact NBT it reads from Litematica; the paste interval
 * controls the client-side command rate guard. The file is created with defaults when missing.
 */
public final class MaxFastBuildConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("maxfastbuild");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static boolean debug;
    private static long pasteCommandIntervalMs = 80;

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
                if (root != null && root.has("pasteCommandIntervalMs")) {
                    pasteCommandIntervalMs = clampInterval(root.get("pasteCommandIntervalMs").getAsLong());
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaultRoot()), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ex) {
            debug = false;
            pasteCommandIntervalMs = 80;
            LOGGER.warn("[MaxFastBuild] Failed to read config {}", path, ex);
        }
        LOGGER.info("[MaxFastBuild] config debug={} pasteCommandIntervalMs={} file={}", debug, pasteCommandIntervalMs, path);
    }

    /** Whether the debug diagnostic logging is enabled (config {@code debug}, default false). */
    public static boolean isDebugEnabled() {
        return debug;
    }

    public static long pasteCommandIntervalMs() {
        return pasteCommandIntervalMs;
    }

    private static JsonObject defaultRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("debug", false);
        root.addProperty("pasteCommandIntervalMs", 80);
        return root;
    }

    private static long clampInterval(long value) {
        return Math.max(80, Math.min(2_000, value));
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("maxfastbuild.json");
    }
}
