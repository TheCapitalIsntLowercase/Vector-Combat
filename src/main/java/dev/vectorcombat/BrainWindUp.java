package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * Wind-ups for the mobs that attack through the brain (piglin, piglin brute, hoglin, zoglin, warden), the same tell as {@link dev.vectorcombat.mixin.MeleeAttackGoalMixin} gives the
 * goal mobs. They all hit through {@code MeleeAttack.create(cooldown)}, whose attack runs every brain tick while a target is in reach and no cooldown memory is set; the mixin
 * ({@link dev.vectorcombat.mixin.MeleeAttackMixin}) asks {@link #hold} first. A mob held here has set nothing (no swing, no cooldown), so the brain asks again next tick, which is what lets
 * the wind-up run on the {@link WindUp#next} state machine unchanged; when it ends vanilla's attack runs and its cooldown is shortened by the wind-up, so the rate stays vanilla's.
 */
public final class BrainWindUp {
    private BrainWindUp() {
    }

    /** A wind-up in progress: the game time it ends at and its length. */
    private record State(long until, int ticks) {
    }

    /** Server side only (brains do not run on a client). */
    private static final Map<Mob, State> STATES = new WeakHashMap<>();

    /** The wind-up that just ended in a landing, for the cooldown vanilla sets right after; 0 when none. And the scale of that mob's attack rate ({@link Charge#attackScale}). */
    private static int landed;
    private static double scale = 1;
    private static int cooldownInterval;

    /**
     * One look at this mob's attack, before vanilla's: true when the attack is held or has become a whiff (vanilla's must not run), false when it should (no wind-up applies, or it
     * just ended and the hit is due). {@code interval} is the cooldown the behaviour was made with, which the wind-up is derived from.
     */
    /** Whether this brain mob is winding up an attack now (it then does not raise its guard: {@link MobGuard}). */
    public static boolean windingUp(Mob mob) {
        State s = STATES.get(mob);
        return s != null && s.until() > mob.level().getGameTime();
    }

    public static boolean hold(Mob mob, int interval, boolean holdingRanged) {
        landed = 0;
        if (Slam.holding(mob)) return true; // mid-slam: no punch
        scale = Charge.attackScale(mob);
        Double seconds = Profiles.interval(mob); // the mob's data may set its rate (the hoglin's 1.8 s, a baby's 0.95)
        if (seconds != null) interval = WindUp.interval(seconds);
        interval = (int) Math.round(interval * scale); // a hoglin tired out by its charge attacks slower for a while
        cooldownInterval = interval;
        LivingEntity target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        State was = STATES.get(mob);
        if (holdingRanged || target == null || !WindUp.applicable(mob, target)) {
            STATES.remove(mob);
            return false;
        }
        int ticks = was == null ? WindUp.ticks(mob, interval) : was.ticks();
        boolean inReach = mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target);
        if (was == null && inReach && !MobTokens.mayStart(mob, target)) return true; // a crowd takes turns: this one holds its attack
        WindUp.Step step = WindUp.next(mob.level().getGameTime(), was == null ? 0 : was.until(), true, inReach, WindUp.hurt(mob), ticks);
        if (step.until() == 0) STATES.remove(mob);
        else STATES.put(mob, new State(step.until(), ticks));
        String event = step.event();
        if (event != null) WindUp.signal(mob, target, event, ticks);
        if (step.until() != 0) { // winding up: planted and facing the player
            mob.getNavigation().stop();
            mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            mob.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
        }
        if ("whiff".equals(event)) { // a miss costs what a hit does: the swing, then the same shortened cooldown
            mob.swing(InteractionHand.MAIN_HAND);
            mob.getBrain().setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, WindUp.afterLanding(interval, ticks));
            return true;
        }
        if ("land".equals(event)) landed = ticks;
        return step.hold();
    }

    /** The cooldown vanilla is about to set, shortened by the wind-up when this is the attack that ended one. */
    public static long cooldown(long expiry) {
        int ticks = landed;
        landed = 0;
        long scaled = cooldownInterval > 0 ? cooldownInterval : Math.round(expiry * scale); // the rate hold() worked out: the data's, scaled after a charge
        cooldownInterval = 0;
        return ticks > 0 ? WindUp.afterLanding((int) scaled, ticks) : scaled;
    }
}
