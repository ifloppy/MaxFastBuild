package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
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
    /**
     * Litematica-exact bulk placement: when true, {@link #mutate} places blocks with physics off
     * ({@code setBlockData(data, false)}); the caller then runs one {@link #settlePlacements} pass
     * so redstone computes against the final layout (see {@link #beginDeferredPhysics()}).
     */
    private boolean deferPhysics;

    @Override public void beginDeferredPhysics() {
        this.deferPhysics = true;
    }

    @Override public void endDeferredPhysics() {
        this.deferPhysics = false;
    }

    @Override public void settlePlacements(String world, List<BlockPos> positions) {
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) return;
        for (BlockPos position : positions) {
            settlePlaced(resolved, position);
        }
    }

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
        BlockData targetData;
        try {
            targetData = Bukkit.createBlockData(mutation.targetState());
        } catch (IllegalArgumentException ex) {
            return new ValidationResult(false, "invalid_block_state");
        }
        Material targetMaterial = targetData.getMaterial();
        if (RestrictedMaterials.isForbiddenPlace(targetMaterial) || !targetMaterial.isBlock()) {
            return new ValidationResult(false, "forbidden_material");
        }
        // Reject invalid or unsafe block-entity NBT before anything in the world is touched.
        if (mutation.targetNbt() != null) {
            PaperNbtHelper.NbtCheck check = PaperNbtHelper.validateForBlock(
                    mutation.targetNbt(), targetMaterial, PaperNbtHelper.registryAccess(resolved));
            if (check instanceof PaperNbtHelper.NbtCheck.Rejected rejected) {
                return new ValidationResult(false, "invalid_nbt:" + rejected.reason());
            }
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

        BlockData targetData;
        try {
            targetData = Bukkit.createBlockData(mutation.targetState());
        } catch (IllegalArgumentException ex) {
            return new MutationResult(false, "invalid_block_state");
        }
        Material targetMaterial = targetData.getMaterial();
        if (RestrictedMaterials.isForbiddenPlace(targetMaterial)) {
            return new MutationResult(false, "forbidden_material");
        }

        // Validate NBT before any world change. If it fails we must not have broken or replaced anything.
        Object validatedNbt = null;
        if (mutation.targetNbt() != null) {
            PaperNbtHelper.NbtCheck check = PaperNbtHelper.validateForBlock(
                    mutation.targetNbt(), targetMaterial, PaperNbtHelper.registryAccess(resolved));
            if (check instanceof PaperNbtHelper.NbtCheck.Rejected rejected) {
                return new MutationResult(false, "invalid_nbt:" + rejected.reason());
            }
            validatedNbt = ((PaperNbtHelper.NbtCheck.Ok) check).compound();
        }

        BlockData currentData = block.getBlockData();
        boolean stateAlreadyMatches = currentData.matches(targetData);
        if (stateAlreadyMatches && validatedNbt == null) {
            return new MutationResult(false, "already_target_state");
        }

        Material occupant = block.getType();
        boolean replacedSolid = !stateAlreadyMatches && !isReplaceableOccupant(occupant);
        boolean naturalBreakLogged = false;
        if (replacedSolid) {
            MutationResult broken = breakVanilla(player, block);
            if (!broken.changed()) {
                return new MutationResult(false, broken.reason().isEmpty() ? "replace_break_failed" : broken.reason());
            }
            naturalBreakLogged = broken.breakAlreadyLogged();
        }

        // If the state already matches we only need to update the block-entity data.
        if (!stateAlreadyMatches) {
            block.setBlockData(targetData, !deferPhysics);
        }
        if (validatedNbt != null && !PaperNbtHelper.applyNbt(block, validatedNbt)) {
            // NBT failed to apply. Revert the block data to what it was before we touched it.
            // This may leave CoreProtect break logs for the replaced block, but it prevents dupes.
            block.setBlockData(currentData, false);
            return new MutationResult(false, "nbt_apply_failed");
        }
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

    /**
     * Re-fire the physics update on a placed block so redstone components recompute their signals
     * with every neighbour already in place. Bulk pasting sets all blocks in one tick, which makes
     * redstone compute against a partially-built circuit; this settle pass is the same nudge
     * WorldEdit's {@code fixAfterFastMode} applies.
     */
    static void settlePlaced(World world, BlockPos position) {
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        block.getState().update(true, true);
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
