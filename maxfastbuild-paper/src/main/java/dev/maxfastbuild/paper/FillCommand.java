package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /mfbfill command that mirrors vanilla /fill syntax for Litematica compatibility.
 * Syntax: /mfbfill <x1> <y1> <z1> <x2> <y2> <z2> <block> [destroy|hollow|keep|outline|replace [filter]]
 */
final class FillCommand implements TabExecutor {
    private final MaxFastBuildPlugin plugin;

    FillCommand(MaxFastBuildPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        plugin.handleFillCommand(player, args);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
