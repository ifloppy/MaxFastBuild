package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

final class PaperWorldAccess implements WorldAccess {
    @Override public String stateAt(String world, BlockPos position) {
        Block block = block(world, position);
        return block.getBlockData().getAsString();
    }

    @Override public ValidationResult mayMutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return new ValidationResult(false, "player_offline");
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) return new ValidationResult(false, "unknown_world");
        if (!inWorldHeight(resolved, mutation.position().y())) {
            return new ValidationResult(false, "unsafe_height");
        }
        Block target = resolved.getBlockAt(mutation.position().x(), mutation.position().y(), mutation.position().z());
        Material material = target.getType();
        if (kind == OperationKind.BREAK) {
            return mayBreak(player, target, material);
        }

        // PLACE: replacing a solid block requires the same break rules first.
        if (!isReplaceableOccupant(material)) {
            ValidationResult breakCheck = mayBreak(player, target, material);
            if (!breakCheck.allowed()) {
                // Surface as replace_* so callers can distinguish tool vs unbreakable.
                String reason = breakCheck.reason();
                if ("unbreakable_block".equals(reason)) return new ValidationResult(false, "unbreakable_replace");
                if ("insufficient_tool".equals(reason)) return new ValidationResult(false, "insufficient_tool");
                if ("protected".equals(reason)) return new ValidationResult(false, "protected");
                return breakCheck;
            }
        }

        BlockData targetData;
        try { targetData = Bukkit.createBlockData(mutation.targetState()); }
        catch (IllegalArgumentException ex) { return new ValidationResult(false, "invalid_block_state"); }
        Material targetMaterial = targetData.getMaterial();
        if (RestrictedMaterials.isForbiddenPlace(targetMaterial) || !targetMaterial.isBlock()) {
            return new ValidationResult(false, "forbidden_material");
        }
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                target,
                target.getState(),
                target,
                new ItemStack(targetMaterial),
                player,
                true,
                EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(placeEvent);
        if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
            return new ValidationResult(false, "protected");
        }
        return new ValidationResult(true, "");
    }

    @Override public MutationResult mutate(UUID playerId, String world, BlockMutation mutation, OperationKind kind) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return new MutationResult(false, "player_offline");
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) return new MutationResult(false, "unknown_world");
        if (!inWorldHeight(resolved, mutation.position().y())) {
            return new MutationResult(false, "unsafe_height");
        }
        Block block = resolved.getBlockAt(mutation.position().x(), mutation.position().y(), mutation.position().z());
        if (kind == OperationKind.BREAK) {
            return breakOccupant(player, block);
        }

        // PLACE over non-air: break first (drops + durability), then place.
        Material occupant = block.getType();
        boolean replacedSolid = !isReplaceableOccupant(occupant);
        if (replacedSolid) {
            MutationResult broken = breakOccupant(player, block);
            if (!broken.changed()) {
                return new MutationResult(false, broken.reason().isEmpty() ? "replace_break_failed" : broken.reason());
            }
        }

        try {
            BlockData data = Bukkit.createBlockData(mutation.targetState());
            if (RestrictedMaterials.isForbiddenPlace(data.getMaterial())) {
                return new MutationResult(false, "forbidden_material");
            }
            // Re-check place protection after break (world state changed).
            BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                    block,
                    block.getState(),
                    block,
                    new ItemStack(data.getMaterial()),
                    player,
                    true,
                    EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(placeEvent);
            if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
                return new MutationResult(false, "protected");
            }
            block.setBlockData(data, true);
            return new MutationResult(true, replacedSolid ? "replaced" : "");
        } catch (IllegalArgumentException ex) {
            return new MutationResult(false, "invalid_block_state");
        }
    }

    static boolean isReplaceableOccupant(Material material) {
        if (material == null || material.isAir()) return true;
        // Soft / fluid cells that vanilla place can overwrite without a tool break.
        return material == Material.WATER
                || material == Material.LAVA
                || material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material == Material.FERN
                || material == Material.LARGE_FERN
                || material == Material.DEAD_BUSH
                || material == Material.SEAGRASS
                || material == Material.TALL_SEAGRASS
                || material == Material.SNOW
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.VINE
                || material == Material.GLOW_LICHEN
                || material.name().endsWith("_CARPET");
    }

    static boolean requiresBreakToReplace(String expectedState) {
        if (expectedState == null || expectedState.isBlank()) return false;
        try {
            Material material = Bukkit.createBlockData(expectedState).getMaterial();
            return !isReplaceableOccupant(material);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    private static ValidationResult mayBreak(Player player, Block target, Material material) {
        if (RestrictedMaterials.isForbiddenBreak(material) || material.getHardness() < 0) {
            return new ValidationResult(false, "unbreakable_block");
        }
        if (material.isAir()) return new ValidationResult(false, "already_air");
        BlockBreakEvent event = new BlockBreakEvent(target, player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return new ValidationResult(false, "protected");
        if (player.getGameMode() != GameMode.CREATIVE && !BreakToolHelper.canBreakBlock(player, target)) {
            return new ValidationResult(false, "insufficient_tool");
        }
        return new ValidationResult(true, "");
    }

    private static MutationResult breakOccupant(Player player, Block block) {
        if (block.getType().isAir()) return new MutationResult(false, "already_air");
        if (RestrictedMaterials.isForbiddenBreak(block.getType()) || block.getType().getHardness() < 0) {
            return new MutationResult(false, "unbreakable_block");
        }
        BreakToolHelper.Selection tool = BreakToolHelper.findTool(player, block);
        if (tool == null && player.getGameMode() != GameMode.CREATIVE) {
            return new MutationResult(false, "insufficient_tool");
        }
        if (tool == null) {
            boolean changed = block.breakNaturally(true);
            return new MutationResult(changed, changed ? "" : "break_failed");
        }
        boolean changed = BreakToolHelper.breakWithTool(player, block, tool);
        return new MutationResult(changed, changed ? "" : "break_failed");
    }

    static boolean isForbiddenPlaceMaterial(Material material) {
        return RestrictedMaterials.isForbiddenPlace(material);
    }

    private static boolean inWorldHeight(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private static Block block(String world, BlockPos position) {
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) throw new IllegalArgumentException("Unknown world " + world);
        return resolved.getBlockAt(position.x(), position.y(), position.z());
    }
}
