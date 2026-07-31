package dev.maxfastbuild.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Loads Paper command/help strings. Default language is zh_cn (server-side CLI UX).
 */
final class PluginMessages {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private FileConfiguration bundle;
    private String language;

    PluginMessages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void reload() {
        String lang = plugin.getConfig().getString("default-language", "zh_cn");
        if (lang == null || lang.isBlank()) lang = "zh_cn";
        language = lang.toLowerCase(Locale.ROOT).replace('-', '_');
        bundle = loadBundle(language);
        if (bundle == null && !"zh_cn".equals(language)) {
            bundle = loadBundle("zh_cn");
            language = "zh_cn";
        }
        if (bundle == null) {
            bundle = loadBundle("en_us");
            language = "en_us";
        }
        if (bundle == null) {
            bundle = new YamlConfiguration();
            language = "zh_cn";
        }
    }

    String language() {
        return language;
    }

    void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(component(key, args));
    }

    Component component(String key, Object... args) {
        String raw = raw(key);
        if (raw == null || raw.isBlank()) {
            return Component.text("[" + key + "]");
        }
        String formatted = format(raw, args);
        if (formatted.indexOf('<') >= 0 && formatted.indexOf('>') > formatted.indexOf('<')) {
            try {
                return MINI.deserialize(formatted);
            } catch (RuntimeException ignored) {
                // fall through to legacy
            }
        }
        return LEGACY.deserialize(formatted);
    }

    String raw(String key) {
        if (bundle == null) return null;
        return bundle.getString(key);
    }

    /** Human-readable line for protocol messageKey + data (CLI users without Fabric). */
    Component fromProtocol(String messageKey, Map<String, ?> data) {
        if (data == null) data = Map.of();
        return switch (messageKey) {
            case "maxfastbuild.task.accepted" ->
                    component("task-accepted", data.get("blocks"), data.get("charge"));
            case "maxfastbuild.task.completed" ->
                    component("task-completed", data.get("applied"), data.get("planned"), data.get("refund"));
            case "maxfastbuild.task.partial" ->
                    component("task-partial", data.get("applied"), data.get("planned"), data.get("refund"));
            case "maxfastbuild.error.insufficient_materials" ->
                    component("insufficient-materials", data.get("need"), data.get("have"), data.get("material"));
            case "maxfastbuild.error.insufficient_tool" ->
                    component("insufficient-tool");
            case "maxfastbuild.error.insufficient_tool_durability" ->
                    component("insufficient-tool-durability", data.get("need"), data.get("have"));
            case "maxfastbuild.error.wrong_tool" ->
                    component("wrong-tool", data.get("block"));
            case "maxfastbuild.error.unbreakable_block" ->
                    component("unbreakable-block", data.get("position"), data.get("block"));
            case "maxfastbuild.error.invalid_material" ->
                    component("invalid-material", data.get("material"));
            case "maxfastbuild.error.no_permission" ->
                    component("no-permission-key", data.get("permission"));
            case "maxfastbuild.error.shape_too_large" ->
                    component("shape-too-large", data.get("limit"));
            case "maxfastbuild.error.protected" ->
                    component("protected", data.get("position"), data.get("reason"));
            case "maxfastbuild.error.payment_failed" ->
                    component("payment-failed", data.get("reason"));
            case "maxfastbuild.error.positions_required" ->
                    component("positions-required");
            case "maxfastbuild.error.coreprotect_unavailable" ->
                    component("coreprotect-required");
            case "maxfastbuild.error.economy_unavailable" ->
                    component("economy-required");
            case "maxfastbuild.error.task_limit" ->
                    component("task-limit");
            case "maxfastbuild.error.no_changes" ->
                    component("no-changes");
            case "maxfastbuild.error.rate_limited" ->
                    component("rate-limited");
            case "maxfastbuild.error.hold_block_or_tool" ->
                    component("apply-need-hand");
            case "maxfastbuild.error.persistence_failed" ->
                    component("persistence-failed", data.get("reason"));
            case "maxfastbuild.error.protocol", "maxfastbuild.error.malformed" ->
                    component("protocol-rejected", data.get("reason"));
            case "maxfastbuild.error.world_mismatch" ->
                    component("world-mismatch", data.get("world"));
            case "maxfastbuild.error.planning_in_progress" ->
                    component("planning-in-progress");
            case "maxfastbuild.task.planning_started" ->
                    component("planning-started", data.get("world"));
            default -> component("protocol-rejected", messageKey);
        };
    }

    private FileConfiguration loadBundle(String lang) {
        String path = "messages_" + lang + ".yml";
        try {
            plugin.saveResource(path, false);
        } catch (IllegalArgumentException ignored) {
            // resource may not exist for this lang
        }
        java.io.File file = new java.io.File(plugin.getDataFolder(), path);
        if (file.isFile()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        InputStream stream = plugin.getResource(path);
        if (stream == null) return null;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String format(String template, Object... args) {
        String result = template;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String value = args[i] == null ? "" : Objects.toString(args[i]);
                result = result.replace("{" + i + "}", value);
            }
        }
        return result;
    }
}
