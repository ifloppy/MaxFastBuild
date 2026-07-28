package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BuildMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

final class PublicCommand implements TabExecutor {
    private static final List<String> ROOT = List.of("mode", "pos1", "pos2", "apply", "cancel", "undo", "redo", "status", "replace", "hollow", "material", "mirror", "array", "language");
    private final MaxFastBuildPlugin plugin;
    PublicCommand(MaxFastBuildPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command requires a player"); return true; }
        plugin.handlePublicCommand(player, args);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return matching(ROOT, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return matching(Arrays.stream(BuildMode.values()).map(v -> v.name().toLowerCase(Locale.ROOT)).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("language")) return matching(List.of("en_us", "zh_cn"), args[1]);
        return List.of();
    }

    private static List<String> matching(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lower)).toList();
    }
}
