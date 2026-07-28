package dev.maxfastbuild.paper;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Counts and removes placeable block items from a player's inventory by material key
 * (e.g. {@code minecraft:oak_planks}), optionally including contents of carried shulker boxes.
 */
final class PaperInventoryHelper {
    private PaperInventoryHelper() {}

    static Material resolveMaterial(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) return null;
        String key = materialKey.contains(":") ? materialKey.substring(materialKey.indexOf(':') + 1) : materialKey;
        Material matched = Material.matchMaterial(materialKey, false);
        if (matched == null) matched = Material.matchMaterial(key, false);
        if (matched == null) {
            try {
                matched = Material.valueOf(key.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return matched.isAir() ? null : matched;
    }

    static long count(Player player, String materialKey, boolean searchShulkers) {
        Material material = resolveMaterial(materialKey);
        if (material == null || !material.isItem()) return 0;
        long total = 0;
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (ItemStack stack : contents) {
            if (stack == null) continue;
            if (stack.getType() == material) total += stack.getAmount();
            else if (searchShulkers) total += countInShulker(stack, material);
        }
        return total;
    }

    /**
     * Removes up to {@code amount} items. Main inventory first, then shulker contents when enabled.
     * @return number actually removed
     */
    static long take(Player player, String materialKey, long amount, boolean searchShulkers) {
        if (amount <= 0) return 0;
        Material material = resolveMaterial(materialKey);
        if (material == null || !material.isItem()) return 0;
        long remaining = amount;
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();

        // 1) Loose stacks in inventory / hotbar / offhand slots
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int have = stack.getAmount();
            int use = (int) Math.min(have, remaining);
            int left = have - use;
            if (left <= 0) inv.setItem(i, null);
            else {
                stack.setAmount(left);
                inv.setItem(i, stack);
            }
            remaining -= use;
        }

        // 2) Inside carried shulker boxes
        if (searchShulkers && remaining > 0) {
            contents = inv.getContents();
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack boxStack = contents[i];
                if (boxStack == null || !isShulkerBox(boxStack.getType())) continue;
                long taken = takeFromShulker(boxStack, material, remaining);
                if (taken > 0) {
                    inv.setItem(i, boxStack);
                    remaining -= taken;
                }
            }
        }
        return amount - remaining;
    }

    static boolean hasEnough(Player player, String materialKey, long amount, boolean searchShulkers) {
        return count(player, materialKey, searchShulkers) >= amount;
    }

    static String itemKeyFromBlockState(String blockState) {
        if (blockState == null) return null;
        int bracket = blockState.indexOf('[');
        return bracket > 0 ? blockState.substring(0, bracket) : blockState;
    }

    static boolean isShulkerBox(Material type) {
        return type != null && type.name().endsWith("SHULKER_BOX");
    }

    private static long countInShulker(ItemStack boxStack, Material material) {
        if (!isShulkerBox(boxStack.getType())) return 0;
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return 0;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return 0;
        long total = 0;
        for (ItemStack inner : box.getInventory().getContents()) {
            if (inner != null && inner.getType() == material) total += inner.getAmount();
        }
        return total;
    }

    /** Mutates {@code boxStack} in place; caller must write it back to the inventory slot. */
    private static long takeFromShulker(ItemStack boxStack, Material material, long amount) {
        if (amount <= 0 || !isShulkerBox(boxStack.getType())) return 0;
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return 0;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return 0;

        long remaining = amount;
        ItemStack[] inner = box.getInventory().getContents();
        for (int i = 0; i < inner.length && remaining > 0; i++) {
            ItemStack stack = inner[i];
            if (stack == null || stack.getType() != material) continue;
            int have = stack.getAmount();
            int use = (int) Math.min(have, remaining);
            int left = have - use;
            if (left <= 0) box.getInventory().setItem(i, null);
            else {
                stack.setAmount(left);
                box.getInventory().setItem(i, stack);
            }
            remaining -= use;
        }
        long taken = amount - remaining;
        if (taken > 0) {
            meta.setBlockState(box);
            boxStack.setItemMeta(meta);
        }
        return taken;
    }
}
