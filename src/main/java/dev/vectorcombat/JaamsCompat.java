package dev.vectorcombat;

import net.jaams.weaponry.handler.trait.CombatEffectHandler;
import net.jaams.weaponry.util.ModTraits;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Jaam's Weaponry hooks one trait, Through Strike, into vanilla's {@code Player.attack} (just before the hit), which a Vector swing never runs ({@link Strike} delivers the hit
 * itself); every other trait of Jaam's sits on {@code ItemStack.hurtEnemy}, which {@code Strike} calls. Here the swing's primary hit runs Jaam's own Through Strike as its
 * mixin would: the main-hand item if it has the trait, else the off-hand's. Only loaded when Jaam's is installed (the check is in {@link Strike}); its classes come from the mod.
 */
final class JaamsCompat {
    static final String ID = "jaams_weaponry";

    private JaamsCompat() {
    }

    /** Before the primary hit on {@code target}: Jaam's Through Strike, if a held weapon has it. Returns whether it ran. */
    static boolean throughStrike(Player p, LivingEntity target) {
        ItemStack stack = p.getMainHandItem();
        if (!ModTraits.isThroughStrikeItem(stack)) stack = p.getOffhandItem();
        if (!ModTraits.isThroughStrikeItem(stack)) return false;
        CombatEffectHandler.handleThroughStrike(target, p, stack);
        return true;
    }
}
