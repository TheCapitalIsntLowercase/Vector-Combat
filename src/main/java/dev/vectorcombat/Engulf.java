package dev.vectorcombat;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The slime's signature move: now and then a slime with a player target squashes down, trembling and squelching ({@link #TELL} ticks, the {@code SQUASH} style), locks on
 * where the player stood and leaps a high arc ({@link #launch}) onto that spot, which a ring of slime marks. Stepping out of the ring dodges it; a shield's block (or a parry, which
 * staggers it) bounces it off. Otherwise the player takes its hit once and is held inside it for {@link #HOLD} ticks, unable to move or attack (a stun), the screen green and blurred
 * ({@link EngulfClient}), the slime wobbling; then it spits them out with a llama's spit, at the nearest other hostile mob within {@link #SPIT_RANGE} blocks if there is one, else the
 * way it faces. Mobs tagged {@code #vectorcombat:engulfers} (the slime and the magma cube) do it. A magma cube's moves are the same with flames for slime, an
 * orange screen for the green, and the burn of a magma block ({@code hotFloor}, 1 a hit, the player's invulnerability spacing it) for as long as it holds the player.
 * <p>
 * the leap is higher ({@link #FLIGHT} 24 ticks), aimed {@link #BEHIND} past the player along the line from the slime ({@link #aim}) so its body comes down over them, and
 * catches them within {@link #TRAP} of its body. A small slime (size 1, what a medium one splits into) leaps too, but its landing slows ({@link #SMALL_SLOW}) instead of trapping:
 * no hit, no hold, no stun. Its basic attack is a pounce on the spider's model ({@link #HOP_TELL} ticks of squash, then a hop at where the player stood as it takes off, hurting on
 * touch in the hop), and the slime's vanilla touch damage on players is off: the hop and the leap are the only ways it hurts. The rules, the arcs and the spit are pure.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Engulf {
    private Engulf() {
    }

    public static final TagKey<EntityType<?>> ENGULFERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "engulfers"));

    static final double MIN = 3, MAX = 10, SPIT_RANGE = 8, SPIT_SPEED = 1.1, SPIT_UP = 0.35, BEHIND = 1.2, TRAP = 0.75, HOP_RANGE = 4;
    static final int MIN_SIZE = 1, TRAP_SIZE = 2, TELL = 15, FLIGHT = 24, HOLD = 30, COOLDOWN_MIN = 120, COOLDOWN_MAX = 200, FIRST_MIN = 40, RETRY = 40, LOST = 60;

    /** The pounce: its squash, the hop's flight, the pause after it (vanilla's attack interval). */
    static final int HOP_TELL = 8, HOP_FLIGHT = 10, HOP_RECOVERY = 20;

    /** A small slime's landing: Slowness II for 3 s. */
    static final int SMALL_SLOW = 60, SMALL_SLOW_LEVEL = 1;

    /** A living entity's motion in the air: gravity a tick, and what is kept of the vertical and horizontal speed each tick (vanilla's {@code LivingEntity.travel}). */
    static final double GRAVITY = 0.08, DRAG_Y = 0.98, DRAG_H = 0.91;

    enum Phase { TELL, LEAP, HOLD, CROUCH, HOP }

    private static final class State {
        Phase phase;
        int age;
        Vec3 spot;
        final Player victim;
        boolean struck;

        State(Phase phase, Vec3 spot, Player victim) {
            this.phase = phase;
            this.spot = spot;
            this.victim = victim;
        }
    }

    /** The game time each slime may pounce again. */
    private static final Map<Slime, Long> HOP_NEXT = new WeakHashMap<>();

    /** True while one of our own hits is being dealt, so the touch damage filter lets it through. */
    private static boolean striking;

    /** Server side: slimes in the move, the game time each may start the next, and the players held and by whom. */
    private static final Map<Slime, State> STATES = new WeakHashMap<>();
    private static final Map<Slime, Long> NEXT = new WeakHashMap<>();
    private static final Map<Player, Slime> HELD = new WeakHashMap<>();

    static {
        Holds.onClear(() -> {
            STATES.clear();
            HELD.clear();
        });
    }

    // ---- pure ----

    /** Whether a slime of {@code size}, {@code distance} blocks from its target, starts the move now. */
    static boolean ready(double distance, int size, boolean onGround, boolean sees, long now, long next) {
        return size >= MIN_SIZE && onGround && sees && distance >= MIN && distance <= MAX && now >= next;
    }

    /** Whether a slime of {@code size}, {@code distance} blocks from its target, pounces now (one that deals damage: size 2 or more). */
    static boolean hops(double distance, int size, boolean onGround, boolean sees, long now, long next) {
        return size >= TRAP_SIZE && onGround && sees && distance <= HOP_RANGE && now >= next;
    }

    /** Where the leap aims: {@link #BEHIND} past the player at {@code player}, along the level line from the slime at {@code slime}, so its body comes down over them. */
    static Vec3 aim(Vec3 slime, Vec3 player) {
        Vec3 d = new Vec3(player.x - slime.x, 0, player.z - slime.z);
        return d.lengthSqr() < 1e-6 ? player : player.add(d.normalize().scale(BEHIND));
    }

    /** Whether a landing of a slime this size traps (a hit and the hold) or only slows. */
    static boolean traps(int size) {
        return size >= TRAP_SIZE;
    }

    /** Ticks until the next one, from a roll in 0..1. */
    static int cooldown(double roll) {
        return COOLDOWN_MIN + (int) Math.round(roll * (COOLDOWN_MAX - COOLDOWN_MIN));
    }

    /**
     * The launch speed that carries a body from {@code from} to {@code to} in {@code ticks} ticks of free flight: horizontally the speed whose decaying steps (kept {@link #DRAG_H} a
     * tick) add up to the distance; vertically, the height after {@code ticks} is linear in the launch speed (each tick it moves by its speed, then loses {@link #GRAVITY} and keeps
     * {@link #DRAG_Y}), so it is solved exactly.
     */
    static Vec3 launch(Vec3 from, Vec3 to, int ticks) {
        double dx = to.x - from.x, dz = to.z - from.z, h = Math.hypot(dx, dz);
        double steps = (1 - Math.pow(DRAG_H, ticks)) / (1 - DRAG_H), vh = h / steps;
        double a = 0, b = 0, w = 1, v = 0;
        for (int k = 0; k < ticks; k++) {
            a += w;
            b += v;
            v = (v - GRAVITY) * DRAG_Y;
            w *= DRAG_Y;
        }
        double vy = (to.y - from.y - b) / a;
        return h < 1e-9 ? new Vec3(0, vy, 0) : new Vec3(dx / h * vh, vy, dz / h * vh);
    }

    /** The speed a held player is spat out at: toward {@code at} (the nearest other hostile mob's middle; null for none), else the way the slime faces ({@code yaw}). */
    static Vec3 spit(Vec3 from, float yaw, Vec3 at) {
        Vec3 dir;
        if (at != null && at.distanceToSqr(from) > 1e-6) {
            Vec3 d = at.subtract(from);
            dir = new Vec3(d.x, 0, d.z).normalize();
            if (dir.lengthSqr() < 1e-6) dir = facing(yaw);
        } else {
            dir = facing(yaw);
        }
        return dir.scale(SPIT_SPEED).add(0, SPIT_UP, 0);
    }

    private static Vec3 facing(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    // ---- the game ----

    /** Whether {@code e} is a slime in the move (it is then immobile to its own AI: {@code LivingEntityMixin}). */
    public static boolean holding(LivingEntity e) {
        return e instanceof Slime s && !STATES.isEmpty() && STATES.containsKey(s);
    }

    private static Player target(Slime slime) {
        return slime.getTarget() instanceof Player p && p.isAlive() && !p.isSpectator() && !p.isCreative() ? p : null;
    }

    /** What a slime sheds: slime, or a magma cube's flames. */
    private static ParticleOptions goo(Slime slime) {
        return slime instanceof MagmaCube ? ParticleTypes.FLAME : ParticleTypes.ITEM_SLIME;
    }


    private static String id(Slime slime) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(slime.getType()).toString();
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Slime slime) || !(slime.level() instanceof ServerLevel level) || !slime.getType().is(ENGULFERS)) return;
        State st = STATES.get(slime);
        long now = level.getGameTime();
        if (st == null) {
            Player p = target(slime);
            if (p == null || !CommonConfig.MOB_WIND_UPS.get()) return;
            Long next = NEXT.get(slime);
            if (next == null) {
                NEXT.put(slime, now + FIRST_MIN + slime.getRandom().nextInt(COOLDOWN_MAX - FIRST_MIN));
                return;
            }
            double dist = slime.distanceTo(p);
            boolean sees = slime.getSensing().hasLineOfSight(p);
            if (ready(dist, slime.getSize(), slime.onGround(), sees, now, next)) {
                STATES.put(slime, new State(Phase.TELL, aim(slime.position(), p.position()), p));
                WindUp.signal(slime, p, "engulf", "start", TELL);
                Diag.event("engulf", "result", "tell", "mob", id(slime), "size", slime.getSize(), "dist", Math.round(dist * 100) / 100.0, "tick", now);
            } else if (hops(dist, slime.getSize(), slime.onGround(), sees, now, HOP_NEXT.getOrDefault(slime, 0L))) {
                STATES.put(slime, new State(Phase.CROUCH, p.position(), p));
                WindUp.signal(slime, p, "slime_hop", "start", HOP_TELL);
            }
            return;
        }
        st.age++;
        Player p = st.victim;
        switch (st.phase) {
            case TELL -> {
                if (p == null || !p.isAlive() || WindUp.hurt(slime)) {
                    end(slime, p, "interrupted", RETRY);
                    return;
                }
                slime.setDeltaMovement(slime.getDeltaMovement().multiply(0, 1, 0));
                slime.lookAt(p, 30, 30);
                level.sendParticles(goo(slime), slime.getX(), slime.getY() + 0.1, slime.getZ(), 3, slime.getBbWidth() * 0.5, 0.05, slime.getBbWidth() * 0.5, 0.02);
                if (st.age % 5 == 1) level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1f, 0.7f + st.age * 0.04f);
                if (st.age == 1) SpiderMoves.ring(slime, st.spot, slime.getBbWidth() * 0.5, TELL + FLIGHT, slime instanceof MagmaCube ? LinePayload.MAGMA : LinePayload.SLIME); // a ring telegraph, by the clients' setting
                if (st.age >= TELL) {
                    slime.setDeltaMovement(launch(slime.position(), st.spot, FLIGHT));
                    slime.hurtMarked = true;
                    level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_JUMP, SoundSource.HOSTILE, 1.5f, 0.6f);
                    WindUp.signal(slime, p, "engulf", "leap", TELL);
                    st.phase = Phase.LEAP;
                    st.age = 0;
                }
            }
            case LEAP -> {
                // On course for the ring: the slime's own move control keeps steering it at the player mid-air (an airborne mob still gets its forward input), so a player who
                // stepped out of the ring was followed and the slime landed off its mark. Each tick the arc is re-aimed at the spot for the ticks left.
                int left = FLIGHT - st.age;
                if (left >= 2 && !slime.onGround()) {
                    slime.setZza(0);
                    slime.setXxa(0);
                    slime.setDeltaMovement(launch(slime.position(), st.spot, left));
                    slime.hurtMarked = true;
                }
                if (p != null && p.isAlive() && st.age > 2 && slime.getBoundingBox().inflate(TRAP, 0.25, TRAP).intersects(p.getBoundingBox())) {
                    land(slime, st, level);
                } else if (slime.onGround() && st.age > 3 || st.age > LOST) {
                    level.sendParticles(goo(slime), slime.getX(), slime.getY() + 0.1, slime.getZ(), 30, slime.getBbWidth() * 0.6, 0.1, slime.getBbWidth() * 0.6, 0.15);
                    level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5f, 0.5f);
                    end(slime, p, "missed", cooldown(slime.getRandom().nextDouble()));
                }
            }
            case CROUCH -> {
                if (p == null || !p.isAlive() || WindUp.hurt(slime)) {
                    endHop(slime, p, "interrupted");
                    return;
                }
                slime.setDeltaMovement(slime.getDeltaMovement().multiply(0, 1, 0));
                slime.lookAt(p, 30, 30);
                if (st.age >= HOP_TELL) { // takes off at where the player stands now
                    st.spot = p.position();
                    slime.setDeltaMovement(launch(slime.position(), st.spot, HOP_FLIGHT));
                    slime.hurtMarked = true;
                    level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_JUMP, SoundSource.HOSTILE, 1f, 0.9f);
                    WindUp.signal(slime, p, "slime_hop", "leap", HOP_TELL);
                    st.phase = Phase.HOP;
                    st.age = 0;
                }
            }
            case HOP -> {
                if (!st.struck && p != null && p.isAlive() && slime.getBoundingBox().inflate(0.2).intersects(p.getBoundingBox())) {
                    st.struck = true;
                    hit(slime, p);
                }
                if (slime.onGround() && st.age > 2 || st.age > LOST) endHop(slime, p, st.struck ? "hop_hit" : "hop_missed");
            }
            case HOLD -> {
                if (p == null || !p.isAlive() || p.isRemoved() || p.level() != slime.level() || !slime.isAlive()) {
                    release(slime, p, false);
                    return;
                }
                p.teleportTo(slime.getX(), slime.getY() + 0.05, slime.getZ());
                p.setDeltaMovement(Vec3.ZERO);
                if (slime instanceof MagmaCube) p.hurt(p.damageSources().hotFloor(), 1f); // a magma block's burn, as often as the player's invulnerability lets it
                if (st.age % 6 == 0) level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 0.8f, 0.8f);
                if (st.age >= HOLD) release(slime, p, true);
            }
        }
    }

    /** One of the slime's own hits: let through the touch damage filter. */
    private static void hit(Slime slime, Player p) {
        striking = true;
        try {
            p.hurt(slime.damageSources().mobAttack(slime), (float) slime.getAttributeValue(Attributes.ATTACK_DAMAGE));
        } finally {
            striking = false;
        }
    }

    private static void endHop(Slime slime, Player p, String result) {
        STATES.remove(slime);
        HOP_NEXT.put(slime, slime.level().getGameTime() + HOP_RECOVERY);
        WindUp.signal(slime, p, "slime_hop", "interrupted".equals(result) ? "interrupted" : "ended", HOP_TELL);
        Diag.event("slime_hop", "result", result, "mob", id(slime), "tick", slime.level().getGameTime());
    }

    /** The slime came down on the player: a block or a parry bounces it off; a small slime slows; anything else is its hit once and the hold. */
    private static void land(Slime slime, State st, ServerLevel level) {
        Player p = st.victim;
        DamageSource src = slime.damageSources().mobAttack(slime);
        boolean blocked = p.isDamageSourceBlocked(src);
        if (!traps(slime.getSize())) {
            if (!blocked) {
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SMALL_SLOW, SMALL_SLOW_LEVEL), slime);
                level.sendParticles(goo(slime), p.getX(), p.getY(0.3), p.getZ(), 15, 0.3, 0.2, 0.3, 0.1);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 1.2f, 0.7f);
            }
            end(slime, p, blocked ? "blocked" : "slowed", cooldown(slime.getRandom().nextDouble()));
            return;
        }
        hit(slime, p);
        if (blocked || Guard.justParried(p) || !p.isAlive()) {
            Vec3 away = slime.position().subtract(p.position());
            Vec3 flat = new Vec3(away.x, 0, away.z);
            slime.setDeltaMovement((flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 0) : flat.normalize().scale(0.4)).add(0, 0.3, 0));
            slime.hurtMarked = true;
            end(slime, p, "blocked", cooldown(slime.getRandom().nextDouble()));
            return;
        }
        if (!Holds.claim(p, slime)) { // already held by another grab: the hit lands, no hold
            end(slime, p, "taken", cooldown(slime.getRandom().nextDouble()));
            return;
        }
        st.phase = Phase.HOLD;
        st.age = 0;
        HELD.put(p, slime);
        Stuns.apply(p, HOLD / 20.0);
        slime.setDeltaMovement(Vec3.ZERO);
        level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5f, 0.4f);
        if (p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new EngulfPayload(HOLD, slime instanceof MagmaCube ? EngulfPayload.ORANGE : EngulfPayload.GREEN));
        WindUp.signal(slime, p, "engulf_hold", "start", HOLD);
        Diag.event("engulf", "result", "held", "mob", id(slime), "tick", level.getGameTime());
    }

    /** Lets the held player go; {@code spit}: out with a llama's spit, at the nearest other hostile mob or the way the slime faces. */
    private static void release(Slime slime, Player p, boolean spit) {
        HELD.remove(p);
        Holds.release(p, slime);
        if (p != null) {
            p.removeEffect(Stuns.STUN);
            if (p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new EngulfPayload(0, EngulfPayload.GREEN));
        }
        String towards = null;
        if (spit && p != null && slime.level() instanceof ServerLevel level) {
            Mob nearest = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(SPIT_RANGE), m -> m != slime && m.isAlive() && m instanceof Enemy && m.distanceTo(p) <= SPIT_RANGE)
                    .stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(p))).orElse(null);
            towards = nearest == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString();
            p.setDeltaMovement(spit(p.position(), slime.getYRot(), nearest == null ? null : nearest.position().add(0, nearest.getBbHeight() * 0.5, 0)));
            p.hurtMarked = true;
            level.playSound(null, slime.getX(), slime.getY(), slime.getZ(), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 1.5f, 0.7f);
            level.sendParticles(ParticleTypes.SPIT, p.getX(), p.getY(0.5), p.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(goo(slime), p.getX(), p.getY(0.5), p.getZ(), 20, 0.4, 0.4, 0.4, 0.15);
        }
        end(slime, p, spit ? "spat" : "released", cooldown(slime.getRandom().nextDouble()));
        if (towards != null) Diag.event("engulf", "result", "spat_at", "target", towards);
    }

    private static void end(Slime slime, Player p, String result, int cooldown) {
        STATES.remove(slime);
        NEXT.put(slime, slime.level().getGameTime() + cooldown);
        WindUp.signal(slime, p, "engulf", "interrupted".equals(result) ? "interrupted" : "ended", TELL);
        Diag.event("engulf", "result", result, "mob", id(slime), "tick", slime.level().getGameTime());
    }

    /** A slime's vanilla touch damage on a player is off: its hop and its leap are its attacks (and a held player is not hurt again inside it). */
    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent e) {
        if (striking || !(e.getEntity() instanceof Player) || !CommonConfig.MOB_WIND_UPS.get()) return;
        if (e.getSource().getDirectEntity() instanceof Slime slime && slime.getType().is(ENGULFERS)) e.setCanceled(true);
    }
}
