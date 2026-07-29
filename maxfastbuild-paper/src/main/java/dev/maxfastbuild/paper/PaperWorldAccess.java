package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Real block changes use vanilla/Paper APIs only:
 * <ul>
 *   <li>break: {@link Block#breakNaturally(ItemStack, boolean)} (drops + tool interaction)</li>
 *   <li>place: {@link Block#setBlockData(BlockData, boolean)}</li>
 * </ul>
 * CoreProtect recording:
 *   • BREAK: audit calls {@code logRemoval()} once (breakNaturally may not fire BlockBreakEvent on Leaf).
 *   • PLACE: audit calls {@code logPlacement()} once.
 *   • PLACE-over-solid: audit calls {@code logRemoval()} (for old block) + {@code logPlacement()} (for new block).
 * No synthetic BlockBreak/Place events are fired during planning.
 */
final class PaperWorldAccess implements WorldAccess {
    @Override public String stateAt(String world, BlockPos position) {
        return block(world, position).getBlockData().getAsString();
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
            return mayBreakLocal(player, target, material);
        }
        if (!isReplaceableOccupant(material)) {
            ValidationResult breakCheck = mayBreakLocal(player, target, material);
            if (!breakCheck.allowed()) {
                if ("unbreakable_block".equals(breakCheck.reason())) {
                    return new ValidationResult(false, "unbreakable_replace");
                }
                return breakCheck;
            }
        }
        try {
            BlockData targetData = Bukkit.createBlockData(mutation.targetState());
            if (RestrictedMaterials.isForbiddenPlace(targetData.getMaterial()) || !targetData.getMaterial().isBlock()) {
                return new ValidationResult(false, "forbidden_material");
            }
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(false, "invalid_block_state");
        }
        // No synthetic BlockBreak/Place events here — they multi-logged in CoreProtect.
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
            return breakVanilla(player, block);
        }

        Material occupant = block.getType();
        boolean replacedSolid = !isReplaceableOccupant(occupant);
        BlockData placedData;
        try {
            placedData = Bukkit.createBlockData(mutation.targetState());
        } catch (IllegalArgumentException ex) {
            return new MutationResult(false, "invalid_block_state");
        }
        if (RestrictedMaterials.isForbiddenPlace(placedData.getMaterial())) {
            return new MutationResult(false, "forbidden_material");
        }

        boolean naturalBreakLogged = false;
        if (replacedSolid) {
            MutationResult broken = breakVanilla(player, block);
            if (!broken.changed()) {
                return new MutationResult(false, broken.reason().isEmpty() ? "replace_break_failed" : broken.reason());
            }
            naturalBreakLogged = broken.breakAlreadyLogged();
        }

        // Vanilla-style place: set block data in world (physics on for normal updates).
        block.setBlockData(placedData, true);
        String flags = replacedSolid ? "replaced" : "";
        if (naturalBreakLogged) {
            flags = flags.isEmpty() ? "break_logged" : flags + ",break_logged";
        }
        return new MutationResult(true, flags);
    }

    static boolean isReplaceableOccupant(Material material) {
        if (material == null || material.isAir()) return true;
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
            return !isReplaceableOccupant(Bukkit.createBlockData(expectedState).getMaterial());
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    /** Local rules only — never fires Bukkit block events. */
    private static ValidationResult mayBreakLocal(Player player, Block target, Material material) {
        if (RestrictedMaterials.isForbiddenBreak(material) || material.getHardness() < 0) {
            return new ValidationResult(false, "unbreakable_block");
        }
        if (material.isAir()) return new ValidationResult(false, "already_air");
        if (player.getGameMode() != GameMode.CREATIVE && !BreakToolHelper.canBreakBlock(player, target)) {
            return new ValidationResult(false, "insufficient_tool");
        }
        return new ValidationResult(true, "");
    }

    /**
     * One vanilla break via {@code breakNaturally}. CoreProtect records this once.
     * Audit service now does nothing for BREAK (no duplicate logRemoval).
     */
    private static MutationResult breakVanilla(Player player, Block block) {
        Material type = block.getType();
        if (type.isAir()) return new MutationResult(false, "already_air");
        if (RestrictedMaterials.isForbiddenBreak(type) || type.getHardness() < 0) {
            return new MutationResult(false, "unbreakable_block");
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            boolean changed = block.breakNaturally(true);
            return new MutationResult(changed, changed ? "" : "break_failed");
        }
        BreakToolHelper.Selection tool = BreakToolHelper.findTool(player, block);
        if (tool == null) {
            return new MutationResult(false, "insufficient_tool");
        }
        if (!BreakToolHelper.isEffectiveFor(tool.tool(), block)) {
            return new MutationResult(false, "insufficient_tool");
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
