package dev.vectorcombat;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** {@code /vcdiag hud}: current movement state and the attack it would pick, so a tester sees what the mod sees. */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class DiagHud {
    @SubscribeEvent
    static void render(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!Diag.hud || mc.player == null || mc.options.hideGui) return;
        MoveState move = MoveTracker.of(mc.player);
        AttackDef def = Attacks.select(mc.player.getMainHandItem(), move);
        String[] lines = {
            "pick: " + (def == null ? "-" : def.name()),
            String.format("fwd %.2f right %.2f vy %.2f", move.forward(), move.right(), move.up()),
            "ground " + move.onGround() + " sprint " + move.sprinting() + " sneak " + move.sneaking(),
            String.format("strength %.2f  click->hit %dms", mc.player.getAttackStrengthScale(0.5f), Diag.sinceClick()),
        };
        int y = 4;
        for (String s : lines) {
            e.getGuiGraphics().drawString(mc.font, s, 4, y, 0xFFFFFF);
            y += 10;
        }
    }
}
