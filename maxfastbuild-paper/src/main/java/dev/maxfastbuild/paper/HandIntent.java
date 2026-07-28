package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.OperationKind;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Main-hand intent shared by {@code /mfb apply} and {@code /__mfb place|break}.
 * Same rules as Fabric client: placeable block item → place; mining tool → break; else none.
 */
final class HandIntent {
    enum Kind { PLACE, BREAK, NONE }

    private final Kind kind;
    private final OperationKind operation;
    /** Block id for place (e.g. minecraft:oak_planks); null otherwise. */
    private final String material;
    private final String rejectReason;

    private HandIntent(Kind kind, OperationKind operation, String material, String rejectReason) {
        this.kind = kind;
        this.operation = operation;
        this.material = material;
        this.rejectReason = rejectReason;
    }

    static HandIntent from(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir() || main.getAmount() <= 0) {
            return none("empty_hand");
        }
        Material type = main.getType();
        // Fabric: item instanceof BlockItem  ≈  Material.isBlock() && isItem()
        if (type.isBlock() && type.isItem() && !type.isAir()
                && !PaperWorldAccess.isForbiddenPlaceMaterial(type)) {
            return new HandIntent(Kind.PLACE, OperationKind.PLACE, type.getKey().toString(), null);
        }
        if (BreakToolHelper.isMiningTool(main)) {
            return new HandIntent(Kind.BREAK, OperationKind.BREAK, null, null);
        }
        return none("hold_block_or_tool");
    }

    private static HandIntent none(String reason) {
        return new HandIntent(Kind.NONE, null, null, reason);
    }

    boolean isNone() { return kind == Kind.NONE; }
    boolean isPlace() { return kind == Kind.PLACE; }
    boolean isBreak() { return kind == Kind.BREAK; }
    Kind kind() { return kind; }
    OperationKind operation() { return operation; }
    String material() { return material; }
    String rejectReason() { return rejectReason; }
}
