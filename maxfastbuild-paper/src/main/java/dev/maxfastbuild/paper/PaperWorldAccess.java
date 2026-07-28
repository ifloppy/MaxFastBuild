package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCanBuildEvent;

import java.util.UUID;

final class PaperWorldAccess implements WorldAccess {
    @Override public String stateAt(String world, BlockPos position) {
        Block block = block(world, position);
        return block.getBlockData().getAsString();
    }

    @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return new ValidationResult(false, "player_offline");
        Block target = block(world, mutation.position());
        Material material = target.getType();
        if (kind == OperationKind.BREAK && (material == Material.BEDROCK || material == Material.BARRIER
                || material == Material.COMMAND_BLOCK || material == Material.CHAIN_COMMAND_BLOCK
                || material == Material.REPEATING_COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK
                || material == Material.JIGSAW)) {
            return new ValidationResult(false, "unbreakable_block");
        }
        if (kind == OperationKind.BREAK) {
            if (material.isAir()) return new ValidationResult(false, "already_air");
            BlockBreakEvent event = new BlockBreakEvent(target, player);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return new ValidationResult(false, "protected");
            if (player.getGameMode() != GameMode.CREATIVE && !BreakToolHelper.canBreakBlock(player, target)) {
                return new ValidationResult(false, "insufficient_tool");
            }
            return new ValidationResult(true, "");
        }
        Material targetMaterial;
        try { targetMaterial = Bukkit.createBlockData(mutation.targetState()).getMaterial(); }
        catch (IllegalArgumentException ex) { return new ValidationResult(false, "invalid_block_state"); }
        BlockCanBuildEvent event = new BlockCanBuildEvent(target, player, targetMaterial.createBlockData(), true);
        Bukkit.getPluginManager().callEvent(event);
        return new ValidationResult(event.isBuildable(), event.isBuildable() ? "" : "protected");
    }

    @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return new MutationResult(false, "player_offline");
        Block block = block(world, mutation.position());
        if (kind == OperationKind.BREAK) {
            if (block.getType().isAir()) return new MutationResult(false, "already_air");
            BreakToolHelper.Selection tool = BreakToolHelper.findTool(player, block);
            if (tool == null && player.getGameMode() != GameMode.CREATIVE) {
                return new MutationResult(false, "insufficient_tool");
            }
            if (tool == null) {
                // Creative bare hand
                boolean changed = block.breakNaturally(true);
                return new MutationResult(changed, changed ? "" : "break_failed");
            }
            boolean changed = BreakToolHelper.breakWithTool(player, block, tool);
            return new MutationResult(changed, changed ? "" : "break_failed");
        }
        try {
            block.setBlockData(Bukkit.createBlockData(mutation.targetState()), true);
            return new MutationResult(true, "");
        } catch (IllegalArgumentException ex) { return new MutationResult(false, "invalid_block_state"); }
    }

    private static Block block(String world, BlockPos position) {
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) throw new IllegalArgumentException("Unknown world " + world);
        return resolved.getBlockAt(position.x(), position.y(), position.z());
    }
}
