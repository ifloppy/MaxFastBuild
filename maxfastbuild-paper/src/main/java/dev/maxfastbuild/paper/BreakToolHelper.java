package dev.maxfastbuild.paper;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Picks an effective break tool (main hand first, then inventory).
 * Enforces remaining durability &gt;= {@link #MIN_REMAINING} and vanilla-like tool effectiveness.
 */
final class BreakToolHelper {
    /** Never select a tool with fewer than this many safe uses remaining. */
    static final int MIN_REMAINING = 4;

    private BreakToolHelper() {}

    record Selection(ItemStack tool, int slot) {}

    static Selection findTool(Player player, Block block) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            ItemStack main = player.getInventory().getItemInMainHand();
            return new Selection(main, player.getInventory().getHeldItemSlot());
        }
        PlayerInventory inv = player.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack main = inv.getItem(held);
        if (isUsable(main, block, player)) return new Selection(main, held);

        for (int slot = 0; slot < 36; slot++) {
            if (slot == held) continue;
            ItemStack stack = inv.getItem(slot);
            if (isUsable(stack, block, player)) return new Selection(stack, slot);
        }
        ItemStack off = inv.getItemInOffHand();
        if (isUsable(off, block, player)) return new Selection(off, 40);
        return null;
    }

    /** Any mining tool with remaining uses (not block-specific). */
    static boolean hasAnyMiningTool(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        PlayerInventory inv = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (isMiningTool(stack) && remainingUses(stack) > 0) return true;
        }
        return isMiningTool(inv.getItemInOffHand()) && remainingUses(inv.getItemInOffHand()) > 0;
    }

    /** True if the player has a tool that can effectively break this block under durability rules. */
    static boolean canBreakBlock(Player player, Block block) {
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        return findTool(player, block) != null;
    }

    static boolean breakWithTool(Player player, Block block, Selection selection) {
        if (selection == null) return false;
        if (player.getGameMode() != GameMode.CREATIVE && !isUsable(selection.tool(), block, player)) {
            return false;
        }
        ItemStack tool = selection.tool();
        if (player.getGameMode() != GameMode.CREATIVE && !isEffectiveFor(tool, block)) {
            return false;
        }

        // Paper's Player#breakBlock is the important part here: unlike Block#breakNaturally it goes
        // through the normal player break pipeline (BlockBreakEvent, protection/audit plugins,
        // vanilla drops/enchantments/tool damage). MFB may select a tool outside the held slot, so
        // temporarily swap that slot into the player's main hand and swap the post-break stack back.
        PlayerInventory inv = player.getInventory();
        int held = inv.getHeldItemSlot();
        int toolSlot = selection.slot();
        if (toolSlot == held) {
            return player.breakBlock(block);
        }

        ItemStack heldStack = inv.getItem(held);
        ItemStack selectedStack = toolSlot == 40 ? inv.getItemInOffHand() : inv.getItem(toolSlot);
        if (selectedStack == null || selectedStack.getType().isAir()) return false;

        if (toolSlot == 40) inv.setItemInOffHand(heldStack);
        else inv.setItem(toolSlot, heldStack);
        inv.setItem(held, selectedStack);
        try {
            return player.breakBlock(block);
        } finally {
            // Player#breakBlock may damage or consume the held tool. Move that resulting stack back
            // to the slot MFB borrowed it from, then restore the player's original held item.
            ItemStack resultingTool = inv.getItem(held);
            inv.setItem(held, heldStack);
            if (toolSlot == 40) inv.setItemInOffHand(resultingTool);
            else inv.setItem(toolSlot, resultingTool);
        }
    }

    static boolean isUsable(ItemStack stack, Block block, Player player) {
        if (stack == null || stack.getType().isAir()) return false;
        if (!isMiningTool(stack) || remainingUses(stack) <= 0) return false;
        return isEffectiveFor(stack, block);
    }

    /**
     * Vanilla-like effectiveness:
     * - preferred tool when the block requires the correct tool for drops, OR
     * - destroy speed better than bare hand for hard blocks, OR
     * - soft blocks (hardness 0) accept any mining tool.
     */
    /** Public for silent break effectiveness checks. */
    static boolean isEffectiveFor(ItemStack stack, Block block) {
        if (stack == null || block == null) return false;
        Material type = block.getType();
        if (type.isAir()) return false;
        float hardness = type.getHardness();
        // Unbreakable in survival (bedrock etc. filtered earlier, but guard anyway)
        if (hardness < 0) return false;

        boolean preferred = block.isPreferredTool(stack);
        boolean requiresCorrect = block.getBlockData().requiresCorrectToolForDrops();

        if (requiresCorrect) {
            // Obsidian, deepslate ores, etc.: must be preferred (diamond+ pick for obsidian).
            return preferred;
        }
        if (hardness == 0f) {
            // Instant soft blocks: any mining tool is fine.
            return true;
        }
        // Hard block that does not require correct tool: need preferred category or meaningful speed.
        if (preferred) return true;
        float speed = block.getBlockData().getDestroySpeed(stack);
        // Bare hand is typically 1.0; tools give higher for their category.
        return speed > 1.0f;
    }

    /**
     * Mining tools only — not bows, fishing rods, shields, armor, etc.
     * Matches client break-mode whitelist.
     */
    static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        if (maxDurability(stack) <= 0) return false;
        Material type = stack.getType();
        String name = type.name();
        return name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_SWORD")
                || type == Material.SHEARS;
    }

    static int remainingUses(ItemStack stack) {
        if (!isMiningTool(stack)) return 0;
        int remaining = maxDurability(stack) - currentDamage(stack);
        return Math.max(0, remaining - MIN_REMAINING);
    }

    private static int maxDurability(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasMaxDamage()) {
            return damageable.getMaxDamage();
        }
        return stack.getType().getMaxDurability();
    }

    private static int currentDamage(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return damageable.getDamage();
        }
        return stack.getDurability();
    }
}
