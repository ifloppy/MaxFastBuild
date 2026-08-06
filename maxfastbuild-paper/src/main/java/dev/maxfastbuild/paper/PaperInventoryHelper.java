package dev.maxfastbuild.paper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts and removes placeable block items from a player's inventory by material key
 * (e.g. {@code minecraft:oak_planks}), optionally including contents of carried shulker boxes
 * and — when container search is enabled — nearby chests/barrels/shulker boxes.
 * <p>
 * Fluids ({@code minecraft:water}, {@code minecraft:lava}) are placed without consuming an item:
 * when the player holds at least {@code requiredBuckets} matching buckets the available count is
 * treated as unlimited and {@code take} removes nothing. Fire blocks behave the same way but
 * require a flint and steel instead of buckets (never consumed).
 */
final class PaperInventoryHelper {
    private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

    /** Block-state ids that are billed as a different (mapped) item; generic suffix rules live in {@link #genericBlockToItem}. */
    private static final Map<String, String> BLOCK_TO_ITEM = Map.ofEntries(
            Map.entry("minecraft:snow_layer", "minecraft:snow"),
            Map.entry("minecraft:big_dripleaf_stem", "minecraft:big_dripleaf"),
            Map.entry("minecraft:pitcher_crop", "minecraft:pitcher_plant"),
            Map.entry("minecraft:torchflower_crop", "minecraft:torchflower"),
            Map.entry("minecraft:structure_void", "minecraft:structure_void"));

    private PaperInventoryHelper() {}

    static Material resolveMaterial(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) return null;
        return MATERIAL_CACHE.computeIfAbsent(materialKey, key -> {
            String stripped = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            Material matched = Material.matchMaterial(key, false);
            if (matched == null) matched = Material.matchMaterial(stripped, false);
            if (matched == null) {
                try {
                    matched = Material.valueOf(stripped.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
            return matched.isAir() ? null : matched;
        });
    }

    /**
     * Map a block-state id to the item a survival player must pay to place it. Wall/potted/snow
     * variants have no inventory item of their own and previously made the whole paste fail; the
     * mapped result is verified to actually be an item, otherwise the raw id is kept (and the paste
     * falls back to today's rejection).
     */
    static String itemKeyFromBlockState(String blockState) {
        if (blockState == null) return null;
        int bracket = blockState.indexOf('[');
        String id = bracket > 0 ? blockState.substring(0, bracket) : blockState;
        String mapped = genericBlockToItem(id);
        if (mapped != null && !mapped.equals(id)) {
            Material check = resolveMaterial(mapped);
            if (check != null && check.isItem()) return mapped;
        }
        return id;
    }

    private static String genericBlockToItem(String id) {
        if (id == null || !id.startsWith("minecraft:")) return id;
        if (id.startsWith("minecraft:potted_")) return "minecraft:flower_pot";
        if (id.endsWith("_wall_hanging_sign")) return id.substring(0, id.length() - "_wall_hanging_sign".length()) + "_hanging_sign";
        if (id.endsWith("_wall_sign")) return id.substring(0, id.length() - "_wall_sign".length()) + "_sign";
        if (id.endsWith("_wall_torch")) return id.substring(0, id.length() - "_wall_torch".length()) + "_torch";
        if (id.endsWith("_wall_banner")) return id.substring(0, id.length() - "_wall_banner".length()) + "_banner";
        if (id.endsWith("_coral_wall_fan")) return id.substring(0, id.length() - "_wall_fan".length()) + "_fan";
        if (id.endsWith("_plant")) return id.substring(0, id.length() - "_plant".length());
        return BLOCK_TO_ITEM.getOrDefault(id, id);
    }

    /** Search configuration for one material-consuming operation. */
    static final class SearchOptions {
        final boolean searchShulkers;
        final boolean searchContainers;
        final int containerRadius;
        final int requiredBuckets;
        final boolean fireRequiresFlint;

        SearchOptions(boolean searchShulkers, boolean searchContainers, int containerRadius,
                      int requiredBuckets, boolean fireRequiresFlint) {
            this.searchShulkers = searchShulkers;
            this.searchContainers = searchContainers;
            this.containerRadius = containerRadius;
            this.requiredBuckets = requiredBuckets;
            this.fireRequiresFlint = fireRequiresFlint;
        }

        List<ItemSource> sources(Player player) {
            return PaperInventoryHelper.sources(player, searchShulkers, searchContainers, containerRadius);
        }
    }

    /** A place materials can be counted or removed from. */
    interface ItemSource {
        long count(Material material);

        long countExact(ItemStack template);

        /** Removes up to {@code amount}; records every slot mutation on {@code ledger} for refunds. */
        long take(Material material, long amount, RemovalLedger ledger);

        long takeExact(ItemStack template, long amount, RemovalLedger ledger);

        /** Total flint-and-steel durability uses across the source's direct slots (fire ignition). */
        long countFlintUses();

        /** Consumes up to {@code amount} flint-and-steel durability, recording each slot mutation. */
        long takeFlintUses(long amount, RemovalLedger ledger);
    }

    /**
     * Player inventory and — when {@code nestedShulkers} — shulker boxes carried inside it.
     * For a player inventory the offhand slot is included (so a flint-and-steel held in the
     * offhand satisfies the fire token); its real Bukkit slot (40) is used in removal records so
     * refunds land back on the offhand.
     */
    private static final class SlotInventorySource implements ItemSource {
        private static final int OFFHAND_SLOT = 40;

        private final Inventory inventory;
        private final boolean nestedShulkers;
        private final ItemStack[] contents;
        private final int offhandPosition;

        SlotInventorySource(Inventory inventory, boolean nestedShulkers) {
            this.inventory = inventory;
            this.nestedShulkers = nestedShulkers;
            if (inventory instanceof PlayerInventory playerInventory) {
                ItemStack[] base = inventory.getContents();
                this.contents = Arrays.copyOf(base, base.length + 1);
                this.contents[base.length] = playerInventory.getItemInOffHand();
                this.offhandPosition = base.length;
            } else {
                this.contents = inventory.getContents();
                this.offhandPosition = -1;
            }
        }

        /** Map a contents index to the real inventory slot (offhand lives at 40 for players). */
        private int realSlot(int index) {
            return offhandPosition >= 0 && index == offhandPosition ? OFFHAND_SLOT : index;
        }

        private void setSlot(int index, ItemStack stack) {
            if (offhandPosition >= 0 && index == offhandPosition) inventory.setItem(OFFHAND_SLOT, stack);
            else inventory.setItem(index, stack);
        }

        @Override
        public long count(Material material) {
            long total = 0;
            for (ItemStack stack : contents) {
                if (stack == null) continue;
                if (stack.getType() == material) total += stack.getAmount();
                else if (nestedShulkers) total += countInShulker(stack, material);
            }
            return total;
        }

        @Override
        public long countExact(ItemStack template) {
            long total = 0;
            for (ItemStack stack : contents) {
                if (stack == null) continue;
                if (stack.isSimilar(template)) total += stack.getAmount();
                else if (nestedShulkers) total += countInShulkerExact(stack, template);
            }
            return total;
        }

        @Override
        public long take(Material material, long amount, RemovalLedger ledger) {
            long remaining = amount;
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() != material) continue;
                int use = (int) Math.min(stack.getAmount(), remaining);
                ItemStack original = stack.clone();
                int left = stack.getAmount() - use;
                if (left <= 0) setSlot(i, null);
                else {
                    stack.setAmount(left);
                    setSlot(i, stack);
                }
                recordRemoval(ledger, inventory, realSlot(i), original, material, use);
                remaining -= use;
            }
            if (nestedShulkers) {
                ItemStack[] fresh = snapshot();
                for (int i = 0; i < fresh.length && remaining > 0; i++) {
                    ItemStack boxStack = fresh[i];
                    if (boxStack == null || !isShulkerBox(boxStack.getType())) continue;
                    ItemStack original = boxStack.clone();
                    long taken = takeFromShulker(boxStack, material, remaining);
                    if (taken > 0) {
                        setSlot(i, boxStack);
                        recordRemoval(ledger, inventory, realSlot(i), original, material, taken);
                        remaining -= taken;
                    }
                }
            }
            return amount - remaining;
        }

        @Override
        public long takeExact(ItemStack template, long amount, RemovalLedger ledger) {
            long remaining = amount;
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack stack = contents[i];
                if (stack == null || !stack.isSimilar(template)) continue;
                int use = (int) Math.min(stack.getAmount(), remaining);
                ItemStack original = stack.clone();
                int left = stack.getAmount() - use;
                if (left <= 0) setSlot(i, null);
                else {
                    stack.setAmount(left);
                    setSlot(i, stack);
                }
                recordRemovalExact(ledger, inventory, realSlot(i), original, template, use);
                remaining -= use;
            }
            if (nestedShulkers) {
                ItemStack[] fresh = snapshot();
                for (int i = 0; i < fresh.length && remaining > 0; i++) {
                    ItemStack boxStack = fresh[i];
                    if (boxStack == null || !isShulkerBox(boxStack.getType())) continue;
                    ItemStack original = boxStack.clone();
                    long taken = takeFromShulkerExact(boxStack, template, remaining);
                    if (taken > 0) {
                        setSlot(i, boxStack);
                        recordRemovalExact(ledger, inventory, realSlot(i), original, template, taken);
                        remaining -= taken;
                    }
                }
            }
            return amount - remaining;
        }

        @Override
        public long countFlintUses() {
            long total = 0;
            for (ItemStack stack : contents) total += flintUses(stack);
            return total;
        }

        @Override
        public long takeFlintUses(long amount, RemovalLedger ledger) {
            long remaining = amount;
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                ItemStack stack = contents[i];
                long uses = flintUses(stack);
                if (uses <= 0) continue;
                long use = Math.min(uses, remaining);
                ItemStack original = stack.clone();
                int max = stack.getType().getMaxDurability();
                int currentDamage = 0;
                if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                    currentDamage = damageable.getDamage();
                }
                int newDamage = currentDamage + (int) use;
                if (newDamage >= max) {
                    setSlot(i, null);
                } else {
                    org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) stack.getItemMeta();
                    damageable.setDamage(newDamage);
                    stack.setItemMeta((org.bukkit.inventory.meta.ItemMeta) damageable);
                    setSlot(i, stack);
                }
                recordRemoval(ledger, inventory, realSlot(i), original, Material.FLINT_AND_STEEL, use);
                remaining -= use;
            }
            return amount - remaining;
        }

        /** Fresh contents snapshot, including the player offhand when this is a player inventory. */
        private ItemStack[] snapshot() {
            if (offhandPosition >= 0) {
                ItemStack[] base = inventory.getContents();
                ItemStack[] all = Arrays.copyOf(base, base.length + 1);
                all[base.length] = ((PlayerInventory) inventory).getItemInOffHand();
                return all;
            }
            return inventory.getContents();
        }

        private static void recordRemoval(RemovalLedger ledger, Inventory host, int slot, ItemStack original,
                                          Material material, long amount) {
            if (ledger != null) ledger.record(new Removal(host, slot, original, material, amount, null, 0));
        }

        private static void recordRemovalExact(RemovalLedger ledger, Inventory host, int slot, ItemStack original,
                                               ItemStack template, long amount) {
            if (ledger != null) ledger.record(new Removal(host, slot, original, null, 0, template, amount));
        }
    }

    /** One slot mutation by a {@code take}: enough to restore it, and the amount taken, for partial refunds. */
    record Removal(Inventory host, int slot, ItemStack original,
                   Material material, long materialAmount,
                   ItemStack template, long templateAmount) {}

    /**
     * Tracks every slot a take touched so consumed items can be returned to their exact original
     * location (player slot or container slot) when a paste is cancelled or partially applied.
     */
    static final class RemovalLedger {
        private final List<Removal> all = new ArrayList<>();
        private final Map<Material, List<Removal>> byMaterial = new HashMap<>();
        private final Map<ItemStack, List<Removal>> byExact = new HashMap<>();

        void record(Removal removal) {
            all.add(removal);
            if (removal.material() != null) {
                byMaterial.computeIfAbsent(removal.material(), k -> new ArrayList<>()).add(removal);
            }
            if (removal.template() != null) {
                byExact.computeIfAbsent(removal.template(), k -> new ArrayList<>()).add(removal);
            }
        }

        boolean isEmpty() {
            return all.isEmpty();
        }

        /** Restore every touched slot in reverse take order, exactly undoing all removals. */
        void restoreAll() {
            for (int i = all.size() - 1; i >= 0; i--) {
                Removal removal = all.get(i);
                removal.host().setItem(removal.slot(), removal.original());
            }
            all.clear();
            byMaterial.clear();
            byExact.clear();
        }

        /** Return up to {@code amount} of {@code materialKey} to its original slots. */
        long refundMaterial(String materialKey, long amount) {
            if (amount <= 0) return 0;
            Material material = resolveMaterial(materialKey);
            if (material == null) return 0;
            List<Removal> records = byMaterial.get(material);
            if (records == null || records.isEmpty()) return 0;
            long left = amount;
            for (int i = records.size() - 1; i >= 0 && left > 0; i--) {
                Removal removal = records.get(i);
                long add = Math.min(removal.materialAmount(), left);
                if (add <= 0) continue;
                addBack(removal.host(), removal.slot(), removal.host().getItem(removal.slot()),
                        removal.original(), material, add);
                long remaining = removal.materialAmount() - add;
                if (remaining <= 0) records.remove(i);
                else records.set(i, new Removal(removal.host(), removal.slot(), removal.original(),
                        material, remaining, null, 0));
                left -= add;
            }
            return amount - left;
        }

        /** Return up to {@code amount} exact copies of {@code template} to their original slots. */
        long refundExact(ItemStack template, long amount) {
            if (amount <= 0 || template == null) return 0;
            List<Removal> records = byExact.get(template);
            if (records == null || records.isEmpty()) return 0;
            long left = amount;
            for (int i = records.size() - 1; i >= 0 && left > 0; i--) {
                Removal removal = records.get(i);
                long add = Math.min(removal.templateAmount(), left);
                if (add <= 0) continue;
                addBackExact(removal.host(), removal.slot(), removal.host().getItem(removal.slot()),
                        removal.original(), template, add);
                long remaining = removal.templateAmount() - add;
                if (remaining <= 0) records.remove(i);
                else records.set(i, new Removal(removal.host(), removal.slot(), removal.original(),
                        null, 0, template, remaining));
                left -= add;
            }
            return amount - left;
        }

        /** Refund to the original slots, giving any not-accounted leftovers to the player. */
        long refundOrGive(Player player, String materialKey, long amount) {
            long refunded = refundMaterial(materialKey, amount);
            long left = amount - refunded;
            if (left > 0 && player != null) giveOrDrop(player, materialKey, left);
            return amount;
        }

        long refundOrGiveExact(Player player, ItemStack template, long amount) {
            long refunded = refundExact(template, amount);
            long left = amount - refunded;
            if (left > 0 && player != null) giveExact(player, template, left);
            return amount;
        }
    }

    /** Build the ordered item sources: player inventory (with carried shulkers), then nearby containers. */
    static List<ItemSource> sources(Player player, boolean searchShulkers, boolean searchContainers, int containerRadius) {
        List<ItemSource> sources = new ArrayList<>();
        sources.add(new SlotInventorySource(player.getInventory(), searchShulkers));
        if (searchContainers && containerRadius > 0) {
            sources.addAll(findNearbyContainers(player.getWorld(), player.getLocation(), containerRadius));
        }
        return sources;
    }

    private static final Set<Material> CONTAINER_TYPES = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    /**
     * All chests/barrels/placed shulker boxes whose horizontal distance from {@code center} is
     * within {@code radius} (vertical within radius too). Double chests yield one combined
     * inventory, deduplicated. Shulker boxes nested inside these containers are searched by
     * {@link SlotInventorySource#nestedShulkers}.
     */
    private static List<ItemSource> findNearbyContainers(World world, Location center, int radius) {
        List<ItemSource> out = new ArrayList<>();
        Set<Inventory> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int bx = center.getBlockX();
        int by = center.getBlockY();
        int bz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    Material type = block.getType();
                    if (!CONTAINER_TYPES.contains(type) && !isShulkerBox(type)) continue;
                    if (!(block.getState() instanceof InventoryHolder holder)) continue;
                    Inventory inventory = holder.getInventory();
                    if (seen.add(inventory)) {
                        out.add(new SlotInventorySource(inventory, true));
                    }
                }
            }
        }
        return out;
    }

    static long count(List<ItemSource> sources, String materialKey, int requiredBuckets, boolean fireRequiresFlint) {
        Material material = resolveMaterial(materialKey);
        if (material == null) return 0;
        if (isFluid(material)) {
            return countAcross(sources, fluidBucket(material)) >= requiredBuckets ? Long.MAX_VALUE : 0;
        }
        if (fireRequiresFlint && isFire(material)) {
            return countFlintUses(sources);
        }
        if (!material.isItem()) return 0;
        return countAcross(sources, material);
    }

    /**
     * Removes up to {@code amount} across all sources in order (player first, then containers).
     * Fluids and fire are never consumed. Records slot mutations on {@code ledger}.
     * @return number actually removed
     */
    static long take(List<ItemSource> sources, String materialKey, long amount,
                     int requiredBuckets, boolean fireRequiresFlint, RemovalLedger ledger) {
        if (amount <= 0) return 0;
        Material material = resolveMaterial(materialKey);
        if (material == null) return 0;
        if (isFluid(material)) {
            return countAcross(sources, fluidBucket(material)) >= requiredBuckets ? amount : 0;
        }
        if (fireRequiresFlint && isFire(material)) {
            return takeFlintUses(sources, amount, ledger);
        }
        if (!material.isItem()) return 0;
        long remaining = amount;
        for (ItemSource source : sources) {
            remaining -= source.take(material, remaining, ledger);
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    static long countExact(List<ItemSource> sources, ItemStack template) {
        if (template == null || template.getType().isAir()) return 0;
        long total = 0;
        for (ItemSource source : sources) total += source.countExact(template);
        return total;
    }

    /** Remove up to {@code amount} exact matches of {@code template} across all sources. */
    static long takeExact(List<ItemSource> sources, ItemStack template, long amount, RemovalLedger ledger) {
        if (amount <= 0 || template == null || template.getType().isAir()) return 0;
        long remaining = amount;
        for (ItemSource source : sources) {
            remaining -= source.takeExact(template, remaining, ledger);
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    private static long countAcross(List<ItemSource> sources, Material material) {
        long total = 0;
        for (ItemSource source : sources) total += source.count(material);
        return total;
    }

    /** Total flint-and-steel durability uses available across all sources (fire ignition). */
    private static long countFlintUses(List<ItemSource> sources) {
        long total = 0;
        for (ItemSource source : sources) total += source.countFlintUses();
        return total;
    }

    /** Consume up to {@code amount} flint-and-steel durability across all sources; returns uses spent. */
    private static long takeFlintUses(List<ItemSource> sources, long amount, RemovalLedger ledger) {
        if (amount <= 0) return 0;
        long remaining = amount;
        for (ItemSource source : sources) {
            remaining -= source.takeFlintUses(remaining, ledger);
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    /** Player-inventory-only convenience for single/region place commands (no container search). */
    static long count(Player player, String materialKey, boolean searchShulkers, int requiredBuckets) {
        return count(List.of(new SlotInventorySource(player.getInventory(), searchShulkers)),
                materialKey, requiredBuckets, true);
    }

    static long take(Player player, String materialKey, long amount, boolean searchShulkers, int requiredBuckets) {
        return take(List.of(new SlotInventorySource(player.getInventory(), searchShulkers)),
                materialKey, amount, requiredBuckets, true, null);
    }

    static long countExact(Player player, ItemStack template, boolean searchShulkers) {
        return countExact(List.of(new SlotInventorySource(player.getInventory(), searchShulkers)), template);
    }

    static long takeExact(Player player, ItemStack template, long amount, boolean searchShulkers) {
        return takeExact(List.of(new SlotInventorySource(player.getInventory(), searchShulkers)), template, amount, null);
    }

    /** Give exact copies of {@code template} back; overflow drops at the player location. */
    static void giveExact(Player player, ItemStack template, long count) {
        if (count <= 0 || template == null || template.getType().isAir()) return;
        long left = count;
        while (left > 0) {
            int stack = (int) Math.min(left, template.getMaxStackSize());
            ItemStack item = template.clone();
            item.setAmount(stack);
            dropLeftover(player, player.getInventory().addItem(item));
            left -= stack;
        }
    }

    /** Give items back; overflow drops at the player location. */
    static void giveOrDrop(Player player, String materialKey, long count) {
        if (count <= 0) return;
        Material mat = resolveMaterial(materialKey);
        if (mat == null || !mat.isItem()) return;
        long left = count;
        while (left > 0) {
            int stack = (int) Math.min(left, mat.getMaxStackSize());
            ItemStack item = new ItemStack(mat, stack);
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            dropLeftover(player, leftover);
            left -= stack;
        }
    }

    private static void dropLeftover(Player player, Map<Integer, ItemStack> leftover) {
        if (leftover == null || leftover.isEmpty()) return;
        World world = player.getWorld();
        Location loc = player.getLocation();
        for (ItemStack stack : leftover.values()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            world.dropItemNaturally(loc, stack);
        }
    }

    static boolean isFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    static boolean isFire(Material material) {
        return material == Material.FIRE || material == Material.SOUL_FIRE;
    }

    /** Raw remaining durability uses of a flint-and-steel stack, or 0 when it is not one. */
    static long flintDurabilityUses(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FLINT_AND_STEEL) return 0;
        int max = stack.getType().getMaxDurability();
        if (max <= 0) return 0;
        int damage = 0;
        if (stack.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damage = damageable.getDamage();
        }
        return Math.max(0, max - damage);
    }

    /**
     * Usable flint-and-steel durability uses. A flint enchanted with Mending reserves its last
     * durability point — never worn to 0, exactly like other tools keep a remaining-durability
     * floor — and the next flint and steel is used instead.
     */
    static long flintUses(ItemStack stack) {
        long uses = flintDurabilityUses(stack);
        if (uses <= 0) return 0;
        if (stack.containsEnchantment(org.bukkit.enchantments.Enchantment.MENDING)) {
            return uses - 1;
        }
        return uses;
    }

    /**
     * Derived/transient blocks that have no inventory item to bill (extended-piston heads, stems,
     * frosted ice, …) are placed free. Fluids and fire are excluded: they are billed as a token
     * (bucket / flint-and-steel requirement) rather than an item.
     */
    static boolean isFreeBlock(Material material) {
        if (material == null || material.isAir()) return true;
        return !material.isItem() && !isFluid(material) && !isFire(material);
    }

    /** Bucket item that carries the given fluid block material, or null when not a fluid. */
    private static Material fluidBucket(Material fluid) {
        if (fluid == Material.WATER) return Material.WATER_BUCKET;
        if (fluid == Material.LAVA) return Material.LAVA_BUCKET;
        return null;
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

    private static long countInShulkerExact(ItemStack boxStack, ItemStack template) {
        if (!isShulkerBox(boxStack.getType())) return 0;
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return 0;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return 0;
        long total = 0;
        for (ItemStack inner : box.getInventory().getContents()) {
            if (inner != null && inner.isSimilar(template)) total += inner.getAmount();
        }
        return total;
    }

    private static long takeFromShulkerExact(ItemStack boxStack, ItemStack template, long amount) {
        if (amount <= 0 || !isShulkerBox(boxStack.getType())) return 0;
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return 0;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return 0;

        long remaining = amount;
        ItemStack[] inner = box.getInventory().getContents();
        for (int i = 0; i < inner.length && remaining > 0; i++) {
            ItemStack stack = inner[i];
            if (stack == null || !stack.isSimilar(template)) continue;
            int use = (int) Math.min(stack.getAmount(), remaining);
            int left = stack.getAmount() - use;
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

    /** Add up to {@code add} of {@code material} back into {@code slot}, never exceeding {@code original}. */
    private static void addBack(Inventory host, int slot, ItemStack current, ItemStack original,
                                Material material, long add) {
        long originalCount = countOfM(original, material);
        long currentCount = countOfM(current, material);
        long toAdd = Math.min(add, Math.max(0, originalCount - currentCount));
        if (toAdd <= 0) return;
        // Flint and steel refunds restore durability, not stack count.
        if (material == Material.FLINT_AND_STEEL) {
            int max = material.getMaxDurability();
            ItemStack stack = current != null ? current : original.clone();
            org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) stack.getItemMeta();
            if (damageable != null) {
                damageable.setDamage(Math.max(0, max - (int) (currentCount + toAdd)));
                stack.setItemMeta((org.bukkit.inventory.meta.ItemMeta) damageable);
                host.setItem(slot, stack);
            }
            return;
        }
        if (current != null && isShulkerBox(current.getType())) {
            addIntoShulker(current, material, toAdd);
            host.setItem(slot, current);
            return;
        }
        ItemStack stack = current != null ? current : new ItemStack(material, 0);
        stack.setAmount((int) (currentCount + toAdd));
        host.setItem(slot, stack);
    }

    private static void addBackExact(Inventory host, int slot, ItemStack current, ItemStack original,
                                     ItemStack template, long add) {
        long originalCount = countOfT(original, template);
        long currentCount = countOfT(current, template);
        long toAdd = Math.min(add, Math.max(0, originalCount - currentCount));
        if (toAdd <= 0) return;
        if (current != null && isShulkerBox(current.getType())) {
            addIntoShulkerExact(current, template, toAdd);
            host.setItem(slot, current);
            return;
        }
        ItemStack stack = current != null ? current : template.clone();
        stack.setAmount((int) (currentCount + toAdd));
        host.setItem(slot, stack);
    }

    /** Amount of {@code material} in a stack (a shulker box contributes its contents; a
     *  flint-and-steel contributes its full remaining durability so refunds restore it exactly). */
    private static long countOfM(ItemStack stack, Material material) {
        if (stack == null) return 0;
        if (stack.getType() == material) {
            return material == Material.FLINT_AND_STEEL ? flintDurabilityUses(stack) : stack.getAmount();
        }
        if (isShulkerBox(stack.getType())) return countInShulker(stack, material);
        return 0;
    }

    private static long countOfT(ItemStack stack, ItemStack template) {
        if (stack == null) return 0;
        if (stack.isSimilar(template)) return stack.getAmount();
        if (isShulkerBox(stack.getType())) return countInShulkerExact(stack, template);
        return 0;
    }

    /** Put {@code add} of {@code material} into the box's inner inventory. */
    private static void addIntoShulker(ItemStack boxStack, Material material, long add) {
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return;
        long left = add;
        Inventory inner = box.getInventory();
        for (ItemStack innerStack : inner.getContents()) {
            if (left <= 0) break;
            if (innerStack != null && innerStack.getType() == material) {
                int use = (int) Math.min(innerStack.getMaxStackSize() - innerStack.getAmount(), left);
                if (use > 0) {
                    innerStack.setAmount(innerStack.getAmount() + use);
                    left -= use;
                }
            }
        }
        for (int i = 0; i < inner.getSize() && left > 0; i++) {
            if (inner.getItem(i) == null) {
                int use = (int) Math.min(left, material.getMaxStackSize());
                inner.setItem(i, new ItemStack(material, use));
                left -= use;
            }
        }
        meta.setBlockState(box);
        boxStack.setItemMeta(meta);
    }

    private static void addIntoShulkerExact(ItemStack boxStack, ItemStack template, long add) {
        if (!(boxStack.getItemMeta() instanceof BlockStateMeta meta) || !meta.hasBlockState()) return;
        if (!(meta.getBlockState() instanceof ShulkerBox box)) return;
        long left = add;
        Inventory inner = box.getInventory();
        for (ItemStack innerStack : inner.getContents()) {
            if (left <= 0) break;
            if (innerStack != null && innerStack.isSimilar(template)) {
                int use = (int) Math.min(innerStack.getMaxStackSize() - innerStack.getAmount(), left);
                if (use > 0) {
                    innerStack.setAmount(innerStack.getAmount() + use);
                    left -= use;
                }
            }
        }
        for (int i = 0; i < inner.getSize() && left > 0; i++) {
            if (inner.getItem(i) == null) {
                int use = (int) Math.min(left, template.getMaxStackSize());
                ItemStack placed = template.clone();
                placed.setAmount(use);
                inner.setItem(i, placed);
                left -= use;
            }
        }
        meta.setBlockState(box);
        boxStack.setItemMeta(meta);
    }
}
