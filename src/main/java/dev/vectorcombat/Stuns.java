package dev.vectorcombat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Stun: a status effect that freezes movement and AI (see LivingEntityMixin), blocks attacking, item use, interaction
 * and mining, and is shortened by the {@code stun_resistance} attribute (1.0 = immune). It has no potion icon: the client keeps it out of the effect lists
 * ({@link VectorCombatClient}) and shows your own stun as a ring at the crosshair ({@link StunCrosshair}).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Stuns {
    static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, VectorCombat.ID);
    static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, VectorCombat.ID);

    public static final DeferredHolder<MobEffect, MobEffect> STUN = EFFECTS.register("stun", () -> new MobEffect(MobEffectCategory.HARMFUL, 0xF2C94C) {
        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            return duration % 5 == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity e, int amplifier) {
            if (e.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + e.getBbHeight() + 0.25, e.getZ(), 3, 0.2, 0.05, 0.2, 0.02);
            }
            return true;
        }
    });

    public static final DeferredHolder<Attribute, Attribute> RESISTANCE = ATTRIBUTES.register("stun_resistance",
            () -> new RangedAttribute("attribute.name.vectorcombat.stun_resistance", 0.0, 0.0, 1.0).setSyncable(true));

    public static boolean isStunned(LivingEntity e) {
        return e.hasEffect(STUN);
    }

    public static void apply(LivingEntity victim, double seconds) {
        double resistance = victim.getAttributes().hasAttribute(RESISTANCE) ? victim.getAttributeValue(RESISTANCE) : 0;
        int ticks = (int) Math.round(seconds * 20 * (1 - resistance));
        Diag.event("stun", "victim", victim.getType().getDescriptionId(), "seconds", seconds, "resistance", resistance, "ticks", ticks);
        if (ticks > 0) {
            victim.addEffect(new MobEffectInstance(STUN, ticks, 0, false, false, true));
            WindUp.breakPoise(victim);
        }
    }

    /** Every living entity gets a stun_resistance attribute (default 0). */
    @SubscribeEvent
    static void attributes(EntityAttributeModificationEvent e) {
        for (EntityType<? extends LivingEntity> type : e.getTypes()) e.add(type, RESISTANCE);
    }

    @SubscribeEvent
    static void expired(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Expired e) {
        if (e.getEffectInstance() != null && e.getEffectInstance().is(STUN)) Diag.event("stun_expired", "victim", e.getEntity().getType().getDescriptionId());
    }

    @SubscribeEvent
    static void attack(AttackEntityEvent e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void leftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void entityInteract(PlayerInteractEvent.EntityInteract e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void useItem(LivingEntityUseItemEvent.Start e) {
        if (isStunned(e.getEntity())) e.setCanceled(true);
    }

    @SubscribeEvent
    static void mining(PlayerEvent.BreakSpeed e) {
        if (isStunned(e.getEntity())) e.setNewSpeed(0);
    }
}
