package dev.vectorcombat;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Shows one movement attack at a time on weapon tooltips, as a few short lines ({@link AttackText}): Space cycles to the next, Shift hides them (other mods use Shift to
 * expand their own lines, so ours folds to one line while it is held).
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class AttackTooltip {
    private static int view;

    private static List<AttackDef> attacks(ItemStack stack) {
        return Attacks.attacksFor(stack);
    }

    @SubscribeEvent
    static void tooltip(ItemTooltipEvent e) {
        List<AttackDef> all = attacks(e.getItemStack());
        if (all.isEmpty()) return;
        List<Component> tip = e.getToolTip();
        if (Screen.hasShiftDown()) {
            tip.add(Component.literal("Attacks hidden").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        int i = Math.floorMod(view, all.size());
        AttackText.Block b = AttackText.block(all.get(i), i, all.size(), WeaponData.type(e.getItemStack().getItem()), Attacks.twoHanded(e.getItemStack()));
        if (!b.kind().isEmpty()) tip.add(Component.literal(b.kind()).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal(b.title()).withStyle(ChatFormatting.GREEN));
        tip.add(Component.literal(b.detail()).withStyle(ChatFormatting.GRAY));
        if (!b.effects().isEmpty()) tip.add(Component.literal(b.effects()).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal(b.hint()).withStyle(ChatFormatting.DARK_GRAY));
    }

    @SubscribeEvent
    static void key(ScreenEvent.KeyPressed.Pre e) {
        if (e.getKeyCode() == GLFW.GLFW_KEY_SPACE && e.getScreen() instanceof AbstractContainerScreen<?> s
                && s.getSlotUnderMouse() != null && attacks(s.getSlotUnderMouse().getItem()).size() > 1) {
            view++;
            e.setCanceled(true);
        }
    }
}
