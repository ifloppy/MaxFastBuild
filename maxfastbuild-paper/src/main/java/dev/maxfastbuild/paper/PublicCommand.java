package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BuildMode;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.stream.Collectors;

final class PublicCommand implements TabExecutor {
    static final List<String> ROOT = List.of(
            "help", "mode", "pos1", "pos2", "apply", "cancel", "status", "hollow", "material");
    private static final List<String> BOOLS = List.of("true", "false");
    private static final List<String> MODES = Arrays.stream(BuildMode.values())
            .map(v -> v.name().toLowerCase(Locale.ROOT))
            .toList();

    private final MaxFastBuildPlugin plugin;
    PublicCommand(MaxFastBuildPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.handlePublicCommand(player, args);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matching(ROOT, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "mode" -> matching(MODES, args[1]);
                case "hollow" -> matching(BOOLS, args[1]);
                case "material" -> materialSuggestions(args[1]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> materialSuggestions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || material.isAir()) continue;
            String key = material.getKey().toString();
            if (key.startsWith(lower) || material.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(key);
                if (out.size() >= 40) break;
            }
        }
        return out;
    }

    private static List<String> matching(List<String> values, String prefix) {
        return StringUtil.copyPartialMatches(prefix, values, new ArrayList<>());
    }

    static String modeList() {
        return MODES.stream().collect(Collectors.joining(" "));
    }
}
