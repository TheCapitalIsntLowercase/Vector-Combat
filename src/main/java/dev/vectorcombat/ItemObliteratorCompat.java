package dev.vectorcombat;

import elocindev.item_obliterator.neoforge.utils.Utils;
import net.minecraft.world.item.ItemStack;

/**
 * Item Obliterator (a modpack tool) disables items by its config ({@code blacklisted_items}, ids or {@code !regex}): they are removed from recipes, tabs and inventories. Asked
 * through its own check, so its regexes and later versions' rules apply as it applies them. Only loaded when it is installed (the check is in {@link MobShields}); if its API
 * moves, this says nothing is disabled and logs once.
 */
final class ItemObliteratorCompat {
    static final String ID = "item_obliterator";
    private static boolean broken;

    private ItemObliteratorCompat() {
    }

    static boolean disabled(ItemStack stack) {
        if (broken) return false;
        try {
            return Utils.isDisabled(stack);
        } catch (LinkageError | RuntimeException e) {
            broken = true;
            VectorCombat.LOG.warn("Item Obliterator check unavailable, its disabled items are not filtered ({})", e.toString());
            return false;
        }
    }
}
