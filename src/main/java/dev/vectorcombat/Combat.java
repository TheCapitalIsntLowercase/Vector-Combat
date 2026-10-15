package dev.vectorcombat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Server-side damage pipeline: weakness/resistance multipliers by attack category, then effects (stun, motion,
 * exhaustion) and the attack-speed penalty. A player's categories and effects come from the swing {@link Strike}
 * just resolved (or their weapon's defaults for a vanilla swing); mobs' from {@code mob_attacks} data or their weapon.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Combat {
    /** One swing: its attack's properties, the ticks it pauses for (the attacker's own set_motion waits that long), plus whether once-per-swing effects have fired yet. */
    static final class Hit {
        final String name;
        final Props props;
        final int hold;
        long tick;
        boolean userDone;

        Hit(String name, Props props, long tick, int hold) {
            this.name = name;
            this.props = props;
            this.tick = tick;
            this.hold = hold;
        }
    }

    /** A new swing is at least this many ticks after the previous one; closer calls are the same swing. */
    private static final long SAME_SWING_TICKS = 3;
    private static final Map<ServerPlayer, Hit> HITS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Called by {@link Strike} just before it damages targets, so the damage events know what swing this is. */
    public static void record(ServerPlayer p, String name, Props props, int hold) {
        long now = p.level().getGameTime();
        Hit h = HITS.get(p);
        if (h != null && h.name.equals(name) && h.props == props && now - h.tick <= SAME_SWING_TICKS) h.tick = now;
        else HITS.put(p, new Hit(name, props, now, hold));
    }

    private record Later(long due, Runnable run) {
    }

    private static final java.util.List<Later> LATER = new java.util.ArrayList<>();
    private static long serverTicks;

    /** Runs {@code r} at the end of the server tick {@code ticks} from now (0: this tick). */
    static void later(int ticks, Runnable r) {
        LATER.add(new Later(serverTicks + ticks, r));
    }

    @SubscribeEvent
    static void flush(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        long tick = serverTicks++;
        if (LATER.isEmpty()) return;
        java.util.List<Runnable> now = new java.util.ArrayList<>();
        for (java.util.Iterator<Later> it = LATER.iterator(); it.hasNext(); ) {
            Later l = it.next();
            if (l.due() <= tick) {
                now.add(l.run());
                it.remove();
            }
        }
        now.forEach(Runnable::run);
    }

    private static Hit hitFor(DamageSource src) {
        if (!(src.getEntity() instanceof LivingEntity attacker)) return null;
        if (attacker instanceof ServerPlayer p && src.getDirectEntity() == p) {
            Hit h = HITS.get(p);
            if (h != null && p.level().getGameTime() - h.tick <= SAME_SWING_TICKS) return h;
        }
        Props props = Profiles.attackProps(attacker, src.getDirectEntity());
        if (props == null && src.getDirectEntity() == attacker) props = Attacks.defaultsFor(attacker.getMainHandItem());
        return props == null ? null : new Hit("melee", props, 0, 0);
    }

    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent e) {
        DamageSource src = e.getSource();
        if (src.getDirectEntity() instanceof LivingEntity direct && Stuns.isStunned(direct)) {
            Diag.event("stun_blocked_attack", "attacker", direct.getType().getDescriptionId(), "victim", e.getEntity().getType().getDescriptionId());
            e.setCanceled(true);
            return;
        }
        Hit h = hitFor(src);
        if (h == null || h.props.categories().isEmpty()) return;
        double m = Profiles.multiplier(e.getEntity(), h.props.categories());
        Diag.event("damage_in", "attacker", src.getEntity() == null ? null : src.getEntity().getType().getDescriptionId(), "victim", e.getEntity().getType().getDescriptionId(),
                "attack", h.name, "categories", h.props.categories(), "amount", e.getAmount(), "multiplier", m, "final", e.getAmount() * m);
        if (m == 1) return;
        e.setAmount((float) (e.getAmount() * m));
        feedback(e.getEntity(), m > 1);
    }

    /**
     * Whether a hit landed: it took health, or absorption hearts took it instead (a player with absorption took 0 final damage from every silverfish bite, so no stun, effect
     * or swarm count ever applied). A shield's or a parry's hit took neither, so it did not land.
     */
    static boolean landed(float newDamage, float absorbed) {
        return newDamage + absorbed > 0;
    }

    static boolean landed(LivingDamageEvent.Post e) {
        return landed(e.getNewDamage(), e.getReduction(DamageContainer.Reduction.ABSORPTION));
    }

    @SubscribeEvent
    static void post(LivingDamageEvent.Post e) {
        if (!landed(e) || !(e.getSource().getEntity() instanceof LivingEntity attacker)) return;
        Hit h = hitFor(e.getSource());
        if (h == null) return;
        LivingEntity victim = e.getEntity();
        boolean first = !h.userDone;
        if (!(attacker instanceof ServerPlayer)) {
            Diag.event("mob_hit", "attacker", attacker.getType().getDescriptionId(), "victim", victim.getType().getDescriptionId(), "attack", h.name,
                    "damage", e.getNewDamage(), "categories", h.props.categories());
        }
        for (Effect fx : h.props.effects()) {
            switch (fx) {
                case Effect.Stun s -> Stuns.apply(victim, s.seconds());
                case Effect.Motion m -> {
                    if (!m.user() || first) {
                        LivingEntity who = m.user() ? attacker : victim;
                        // The victim's vanilla knockback lands after this event (log showed it overwriting the motion), so set it at the end of the tick.
                        // The attacker's own push waits out the pause, so the lunge does not carry them on while the blade is held.
                        Vec3 v = vector(attacker, m.motion());
                        int wait = m.user() ? h.hold : 0;
                        if (m.user() && wait == 0) kick(who, v);
                        else later(wait, () -> kick(who, v));
                        Diag.event("set_motion", "requested", m.motion(), "who", who.getType().getDescriptionId(), "user", m.user(), "deferred", !m.user() || wait > 0, "waitTicks", wait);
                        if (m.user()) Diag.watch(who, "set_motion");
                    }
                }
                case Effect.Exhaustion x -> {
                    if (first && attacker instanceof Player p) p.causeFoodExhaustion(x.amount());
                }
            }
        }
        if (first && attacker instanceof ServerPlayer p) Penalties.hit(p, Attacks.penaltyKey(h.name), h.props);
        h.userDone = true;
    }

    /** A motion from data (x right, y up, z forward) in the world, relative to where {@code user} faces now. */
    private static Vec3 vector(LivingEntity user, Vec3 m) {
        double yaw = Math.toRadians(user.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        return forward.scale(m.z).add(right.scale(m.x)).add(0, m.y, 0);
    }

    /** Sets {@code target}'s motion; a mob held by hitstop gets it as the momentum it is handed back when the hold ends. */
    private static void kick(LivingEntity target, Vec3 v) {
        if (Hitstop.launch(target, v)) return;
        target.setDeltaMovement(v);
        target.hurtMarked = true;
    }

    private static void feedback(LivingEntity victim, boolean weak) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        level.sendParticles(weak ? ParticleTypes.CRIT : ParticleTypes.SMOKE, victim.getX(), victim.getY(0.6), victim.getZ(),
                weak ? 12 : 6, 0.25, 0.3, 0.25, 0.1);
        level.playSound(null, victim.blockPosition(), weak ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.SHIELD_BLOCK,
                SoundSource.PLAYERS, 1.0f, weak ? 1.2f : 0.8f);
    }
}
