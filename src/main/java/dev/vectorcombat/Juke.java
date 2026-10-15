package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

/**
 * The enderman's signature move: a readable juke. A player 2 to 8 blocks away, in sight, sees it shiver in place with portal particles and a low
 * warp sound for {@link #TELL} ticks; it then teleports to its left or right, ending in front of the player just out of its reach, and winds up while it closes in. The wind-up is
 * either the real hit or a {@link #FEINT_CHANCE feint}: the same tell and the same snap (the arm swings), no hit, then a quick {@link #FOLLOW} tick wind-up into the real one. A parry
 * spent on the feint has closed by then (5 ticks), so the follow-up meets a plain block, and an enderman's blocked hit disables the shield ({@code #vectorcombat:disables_shields}).
 * The state lives in {@link dev.vectorcombat.mixin.MeleeAttackGoalMixin}; the rules and the spot are pure so they are checked without a game.
 */
public final class Juke {
    private Juke() {
    }

    /** The distances (blocks, centre to centre) at which the enderman jukes instead of walking up. */
    static final double MIN = 2, MAX = 8;

    /** The shiver before the teleport, in ticks: the cue (10, up from 6). */
    public static final int TELL = 10;

    /** The wind-up after the teleport, feint or real, in ticks (14, up from the bite's 10, so the attack out of a juke reads). */
    public static final int WIND_UP = 14;

    /** How far from the player it lands: just past its reach (1.93 between centres for an enderman with its extra half block and a player), so the wind-up plays before the hit. */
    static final double LAND = 2.3;

    /**
     * How far it aims to travel sideways, in blocks (a fixed 45 degrees moved it under 2 blocks from close in): the angle round the player is the one that makes its
     * jump this long, within {@link #MIN_ANGLE} and {@link #MAX_ANGLE} off the line between them, so from close in it swings out to the player's side and never behind.
     */
    static final double TRAVEL = 4.5, MIN_ANGLE = 30, MAX_ANGLE = 90;

    /** How often the wind-up after a juke is a feint. */
    static final double FEINT_CHANCE = 0.35;

    /** The real hit's wind-up after a feint, in ticks: longer than a parry's 5, so a parry raised on the feint's snap has closed when it lands. */
    public static final int FOLLOW = 6;

    /** Ticks before it jukes again (5 s), and before it tries again after finding nowhere to land (1 s). */
    static final int COOLDOWN = 100, RETRY = 20;

    private static final Map<Mob, Long> NEXT = new WeakHashMap<>();

    /** Whether a mob {@code distance} blocks from its target, seeing it, jukes. */
    static boolean wants(double distance, boolean sees) {
        return sees && distance >= MIN && distance <= MAX;
    }

    /** The angle off the line, in degrees, that makes a jump of {@link #TRAVEL} from {@code distance} blocks out to {@link #LAND} (the law of cosines), within the limits. */
    static double angle(double distance) {
        double c = (distance * distance + LAND * LAND - TRAVEL * TRAVEL) / (2 * Math.max(distance, 1e-6) * LAND);
        double degrees = c <= -1 ? 180 : c >= 1 ? 0 : Math.toDegrees(Math.acos(c));
        return Math.max(MIN_ANGLE, Math.min(MAX_ANGLE, degrees));
    }

    /**
     * Where it lands: {@link #LAND} blocks from the player at ({@code px}, {@code pz}), {@link #angle} degrees to the {@code side} (+1 or -1) of the line from the player to where
     * the mob is now ({@code mx}, {@code mz}). Only x and z; the height is the player's, and the teleport finds the floor.
     */
    static Vec3 spot(double px, double pz, double mx, double mz, int side) {
        double base = Math.atan2(mz - pz, mx - px), a = base + side * Math.toRadians(angle(Math.hypot(mx - px, mz - pz)));
        return new Vec3(px + Math.cos(a) * LAND, 0, pz + Math.sin(a) * LAND);
    }

    /** Whether this wind-up is a feint. */
    static boolean feints(double roll) {
        return roll < FEINT_CHANCE;
    }

    public static boolean feints(RandomSource random) {
        return feints(random.nextDouble());
    }

    /** Whether {@code mob} starts a juke at {@code target} now (an enderman off cooldown, 2 to 8 blocks away, in sight); the caller then runs the {@link #TELL}. */
    public static boolean ready(Mob mob, LivingEntity target) {
        return mob instanceof EnderMan && NEXT.getOrDefault(mob, 0L) <= mob.level().getGameTime() && wants(mob.distanceTo(target), mob.getSensing().hasLineOfSight(target));
    }

    /** One tick of the shiver: portal particles around the body, and the low warp at its start. */
    public static void shiver(Mob mob, boolean first) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY(0.5), mob.getZ(), 10, mob.getBbWidth() * 0.6, mob.getBbHeight() * 0.35, mob.getBbWidth() * 0.6, 0.4);
        if (first) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8f, 0.55f);
    }

    /**
     * The teleport at the end of the shiver: to {@link #spot}, the random side first, else the other; true when it went. Uses NeoForge's ender teleport event, so mods that stop
     * endermen teleporting stop this too; either way the next juke waits {@link #COOLDOWN} (or {@link #RETRY} when there was no room).
     */
    public static boolean teleport(Mob mob, LivingEntity target) {
        long now = mob.level().getGameTime();
        Vec3 from = mob.position();
        int first = mob.getRandom().nextBoolean() ? 1 : -1;
        for (int side : new int[] {first, -first}) {
            Vec3 s = spot(target.getX(), target.getZ(), from.x, from.z, side);
            EntityTeleportEvent.EnderEntity ev = EventHooks.onEnderTeleport(mob, s.x, target.getY(), s.z);
            if (ev.isCanceled()) break;
            if (!mob.randomTeleport(ev.getTargetX(), ev.getTargetY(), ev.getTargetZ(), true)) continue;
            mob.level().playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 1.2f);
            mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1f, 1.2f);
            mob.lookAt(target, 360, 360);
            mob.setYBodyRot(mob.getYRot());
            mob.setYHeadRot(mob.getYRot());
            NEXT.put(mob, now + COOLDOWN);
            Diag.event("juke", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), "from", Math.round(from.distanceTo(target.position()) * 100) / 100.0,
                    "to", Math.round(mob.distanceTo(target) * 100) / 100.0, "side", side, "tick", now);
            return true;
        }
        NEXT.put(mob, now + RETRY);
        return false;
    }
}
