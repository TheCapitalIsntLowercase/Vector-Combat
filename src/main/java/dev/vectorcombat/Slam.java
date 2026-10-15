package dev.vectorcombat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The warden's seismic slam: a slammer ({@code #vectorcombat:slammers}) whose target is within
 * {@link #RANGE} but out of its reach, or has kept still for {@link #STILL_TICKS} (the blind warden guessing), raises both fists for {@link #RAISE} ticks while sculk souls pulse at
 * its chest and its heartbeat quickens, with a ring telegraph at the reach of the wave; then it slams the ground and a shockwave crest runs out to {@link #RADIUS} over {@link #WAVE}
 * ticks. Anyone on the ground as the crest passes takes {@link #DAMAGE} of its attack damage and is launched; anyone in the air dodges it. Jumping is the only counter:
 * the damage ({@code vectorcombat:seismic}) bypasses shields, so a parry or a block does nothing. At most every {@link #COOLDOWN} ticks; never while its sonic boom charges,
 * and none starts one during the slam.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Slam {
    private Slam() {
    }

    public static final TagKey<EntityType<?>> SLAMMERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "slammers"));
    public static final ResourceKey<DamageType> SEISMIC = ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "seismic"));

    static final double RANGE = 8, RADIUS = 6, HEIGHT = 2, DAMAGE = 0.5, LIFT = 0.9, PUSH = 0.6, STILL = 0.08;
    static final int RAISE = 20, WAVE = 10, COOLDOWN = 240, STILL_TICKS = 40;

    enum Phase { RAISE, WAVE }

    private static final class State {
        Phase phase = Phase.RAISE;
        int age;
        final LivingEntity target;
        Vec3 center;
        final Set<Entity> hit = new HashSet<>();

        State(LivingEntity target) {
            this.target = target;
        }
    }

    /** How long the target has kept still, and where it was. */
    private record Still(LivingEntity target, Vec3 pos, int ticks) {
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT = new WeakHashMap<>();
    private static final Map<Mob, Still> STILLS = new WeakHashMap<>();

    static {
        Holds.onClear(() -> {
            STATES.clear();
            STILLS.clear();
        });
    }

    // ---- pure ----

    /** Whether a slammer {@code distance} from its target starts a slam: in range, and out of reach or the target kept still long enough; not while its sonic boom charges. */
    static boolean ready(double distance, boolean inReach, int stillTicks, boolean sonic, long now, long next) {
        return distance <= RANGE && (!inReach || stillTicks >= STILL_TICKS) && !sonic && now >= next;
    }

    /** The target's still count after a tick in which it moved {@code moved} blocks sideways. */
    static int still(int ticks, double moved) {
        return moved <= STILL ? ticks + 1 : 0;
    }

    /** The crest's radius {@code age} ticks after the slam. */
    static double radius(int age) {
        return RADIUS * Math.min(Math.max(age, 0), WAVE) / WAVE;
    }

    /** Whether the crest passed {@code distance} (sideways from the slam) this tick, going from radius {@code from} to {@code to}. */
    static boolean crest(double distance, double from, double to) {
        return distance > from && distance <= to;
    }

    /** Whether the crest strikes someone it passes: on the ground (a jump dodges it) and within {@link #HEIGHT} of the slam's level. */
    static boolean struck(boolean onGround, double dy) {
        return onGround && Math.abs(dy) <= HEIGHT;
    }

    // ---- the game ----

    /** A slamming warden is immobile to its own brain ({@code LivingEntityMixin}) and holds its punch ({@link BrainWindUp#hold}). */
    public static boolean holding(LivingEntity e) {
        return e instanceof Mob m && !STATES.isEmpty() && STATES.containsKey(m);
    }

    /** Whether its sonic boom is charging or firing (the warden's brain memories; false for a mob without them). */
    private static boolean sonic(Mob mob) {
        var brain = mob.getBrain();
        return brain.hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_DELAY) || brain.hasMemoryValue(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN);
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || !mob.getType().is(SLAMMERS)) return;
        long now = level.getGameTime();
        State st = STATES.get(mob);
        if (st == null) {
            LivingEntity t = mob.getTarget();
            if (t == null) {
                STILLS.remove(mob);
                return;
            }
            Still was = STILLS.get(mob);
            int still = was == null || was.target() != t ? 0 : still(was.ticks(), Math.hypot(t.getX() - was.pos().x, t.getZ() - was.pos().z));
            STILLS.put(mob, new Still(t, t.position(), still));
            if (!CommonConfig.MOB_WIND_UPS.get() || Stuns.isStunned(mob) || !mob.onGround() || !mob.hasPose(Pose.STANDING) || !t.isAlive() || BrainWindUp.windingUp(mob)) return;
            if (t instanceof Player p && (p.isSpectator() || p.isCreative())) return;
            if (!ready(mob.distanceTo(t), mob.isWithinMeleeAttackRange(t), still, sonic(mob), now, NEXT.getOrDefault(mob, 0L))) return;
            st = new State(t);
            STATES.put(mob, st);
            if (!mob.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN)) // no sonic boom starts during the slam
                mob.getBrain().setMemoryWithExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN, Unit.INSTANCE, RAISE + WAVE + 10);
            mob.getNavigation().stop();
            WindUp.signal(mob, t, "slam", "start", RAISE);
            SpiderMoves.ring(mob, mob.position(), RADIUS, RAISE + WAVE, LinePayload.SCULK);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2f, 0.6f);
            Diag.event("slam", "result", "raise", "mob", mob.getType().getDescriptionId(), "dist", Math.round(mob.distanceTo(t) * 100) / 100.0, "still", still, "tick", now);
            return;
        }
        if (!mob.isAlive()) {
            end(mob, st, "lost");
            return;
        }
        st.age++;
        if (st.phase == Phase.RAISE) {
            if (Stuns.isStunned(mob)) {
                end(mob, st, "interrupted");
                return;
            }
            if (st.target.isAlive()) mob.getLookControl().setLookAt(st.target, 30, 30);
            if (st.age % 4 == 0) {
                level.sendParticles(ParticleTypes.SCULK_SOUL, mob.getX(), mob.getY(0.65), mob.getZ(), 3, 0.3, 0.2, 0.3, 0.02);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2f, 0.6f + 0.04f * st.age);
            }
            if (st.age >= RAISE) {
                st.phase = Phase.WAVE;
                st.age = 0;
                st.center = mob.position();
                WindUp.signal(mob, st.target, "slam", "leap", RAISE);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_ATTACK_IMPACT, SoundSource.HOSTILE, 2.5f, 0.5f);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2f, 0.6f);
                level.sendParticles(ParticleTypes.EXPLOSION, mob.getX(), mob.getY() + 0.2, mob.getZ(), 2, 0.6, 0.1, 0.6, 0);
                Diag.event("slam", "result", "slam", "mob", mob.getType().getDescriptionId(), "tick", now);
            }
            return;
        }
        // WAVE: the crest runs out; the grounded it passes are struck
        double from = radius(st.age - 1), to = radius(st.age);
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        int points = 6 + (int) (to * 4);
        for (int i = 0; i < points; i++) {
            double a = i * 2 * Math.PI / points;
            double x = st.center.x + Math.cos(a) * to, z = st.center.z + Math.sin(a) * to;
            if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, st.center.y + 0.1, z, 2, 0.1, 0.05, 0.1, 0.15);
            if (i % 3 == 0) level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, x, st.center.y + 0.2, z, 1, 0, 0.05, 0, 0.02);
        }
        DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SEISMIC), mob);
        float damage = (float) (mob.getAttributeValue(Attributes.ATTACK_DAMAGE) * DAMAGE);
        for (LivingEntity v : level.getEntitiesOfClass(LivingEntity.class, new AABB(st.center, st.center).inflate(RADIUS + 1, HEIGHT + 1, RADIUS + 1))) {
            if (v == mob || v.getType() == mob.getType() || !v.isAlive() || st.hit.contains(v) || v instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            double dx = v.getX() - st.center.x, dz = v.getZ() - st.center.z, d = Math.hypot(dx, dz);
            if (!crest(d, from, to)) continue;
            st.hit.add(v);
            if (!struck(v.onGround(), v.getY() - st.center.y)) {
                Diag.event("slam", "result", "dodged", "victim", v.getType().getDescriptionId(), "tick", now);
                continue;
            }
            v.hurt(src, damage);
            Vec3 out = d < 1e-4 ? Vec3.ZERO : new Vec3(dx / d, 0, dz / d);
            v.setDeltaMovement(out.x * PUSH, LIFT, out.z * PUSH);
            v.hurtMarked = true;
            Diag.event("slam", "result", "struck", "victim", v.getType().getDescriptionId(), "dist", Math.round(d * 100) / 100.0, "damage", damage, "tick", now);
        }
        if (st.age >= WAVE) end(mob, st, "ended");
    }

    private static void end(Mob mob, State st, String result) {
        STATES.remove(mob);
        STILLS.remove(mob);
        NEXT.put(mob, mob.level().getGameTime() + COOLDOWN);
        if (st.phase == Phase.RAISE) WindUp.signal(mob, st.target, "slam", "interrupted", RAISE);
        Diag.event("slam", "result", result, "mob", mob.getType().getDescriptionId(), "tick", mob.level().getGameTime());
    }
}
