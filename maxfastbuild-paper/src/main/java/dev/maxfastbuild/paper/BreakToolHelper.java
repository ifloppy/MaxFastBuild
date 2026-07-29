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
    /** Never wear a tool below this remaining durability. */
    static final int MIN_REMAINING = 4;
    private static final int BREAK_DAMAGE = 1;

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
        // Only break if the tool is actually effective (prevents soft-fail weirdness).
        if (player.getGameMode() != GameMode.CREATIVE && !isEffectiveFor(tool, block)) {
            return false;
        }
        // Vanilla break (CoreProtect sees this once). Prefer Paper 3-arg when present.
        boolean changed = breakNaturallyVanilla(block, tool);
        if (!changed) return false;
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        if (!isMiningTool(tool)) return true;
        // breakNaturally does not always apply item damage the same as player mining — wear once here.
        applyDamage(player, selection.slot(), BREAK_DAMAGE);
        return true;
    }

    private static boolean breakNaturallyVanilla(Block block, ItemStack tool) {
        try {
            var method = Block.class.getMethod("breakNaturally", ItemStack.class, boolean.class, boolean.class);
            Object result = method.invoke(block, tool, true, true);
            return result instanceof Boolean b && b;
        } catch (ReflectiveOperationException ignored) {
            return block.breakNaturally(tool, true);
        }
    }

    /** Package-visible so silent break can wear tools without going through breakNaturally. */
    static void applyDamage(Player player, int slot, int amount) {
        PlayerInventory inv = player.getInventory();
        ItemStack stack = slot == 40 ? inv.getItemInOffHand() : inv.getItem(slot);
        if (stack == null || stack.getType().isAir() || !isMiningTool(stack)) return;
        int max = maxDurability(stack);
        int damage = currentDamage(stack);
        int remaining = max - damage;
        if (remaining - amount < MIN_REMAINING) return;
        // Prefer ItemStack#damage(int, LivingEntity) when present (Paper); else meta damage.
        try {
            var method = ItemStack.class.getMethod("damage", int.class, org.bukkit.entity.LivingEntity.class);
            Object result = method.invoke(stack, amount, player);
            ItemStack updated = result instanceof ItemStack item ? item : stack;
            if (slot == 40) inv.setItemInOffHand(updated);
            else inv.setItem(slot, updated);
            return;
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damage + amount);
            stack.setItemMeta(meta);
            if (slot == 40) inv.setItemInOffHand(stack);
            else inv.setItem(slot, stack);
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
