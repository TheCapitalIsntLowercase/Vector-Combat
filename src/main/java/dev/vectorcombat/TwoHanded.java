package dev.vectorcombat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Holding a two-handed weapon disables the off hand: no use, no interaction, and it is not drawn. */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class TwoHanded {
    static boolean locked(Player p) {
        return Attacks.twoHanded(p.getMainHandItem());
    }

    @SubscribeEvent
    static void use(LivingEntityUseItemEvent.Start e) {
        if (e.getEntity() instanceof Player p && e.getItem() == p.getOffhandItem() && locked(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getHand() == InteractionHand.OFF_HAND && locked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() == InteractionHand.OFF_HAND && locked(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void entityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() == InteractionHand.OFF_HAND && locked(e.getEntity())) e.setCanceled(true);
    }

    @EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        static void hideOffHand(RenderHandEvent e) {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (e.getHand() == InteractionHand.OFF_HAND && player != null && locked(player)) e.setCanceled(true);
        }
    }
}
