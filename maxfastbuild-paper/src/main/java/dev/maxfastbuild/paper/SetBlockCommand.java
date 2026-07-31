package dev.maxfastbuild.paper;

import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /mfbsetblock that mirrors vanilla /setblock syntax.
 * Syntax: /mfbsetblock <x y z> <block> [replace|destroy|keep]
 */
final class SetBlockCommand implements TabExecutor {
    private static final List<String> MODES = List.of("replace", "destroy", "keep");

    private final MaxFastBuildPlugin plugin;

    SetBlockCommand(MaxFastBuildPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.handleSetBlockCommand(player, args);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length <= 3) {
            return coordinateSuggestions(player, args.length, args[args.length - 1]);
        }
        if (args.length == 4) {
            return PublicCommand.materialSuggestions(args[3]);
        }
        if (args.length == 5) {
            return PublicCommand.matching(MODES, args[4]);
        }
        return List.of();
    }

    static List<String> coordinateSuggestions(Player player, int coordIndex, String prefix) {
        Location loc = player.getLocation();
        int value = switch (coordIndex) {
            case 1 -> loc.getBlockX();
            case 2 -> loc.getBlockY();
            default -> loc.getBlockZ();
        };
        String current = Integer.toString(value);
        List<String> out = new ArrayList<>();
        if ("~".startsWith(prefix)) out.add("~");
        if (current.startsWith(prefix)) out.add(current);
        return out;
    }
}
