package dev.vectorcombat.mixin;

import dev.vectorcombat.Juke;
import dev.vectorcombat.MobAttackAnims;
import dev.vectorcombat.MobGuard;
import dev.vectorcombat.MobTokens;
import dev.vectorcombat.WindUp;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a melee mob decides to hit: the last step of the goal's tick. A mob going for a player stops there and winds up first ({@link WindUp}); the vanilla attack runs
 * when the wind-up ends with the player still in reach. {@code ticksUntilNextAttack} is never touched during the wind-up, so vanilla's own checks pass when it lands.
 * <p>
 * The goal must stay alive through the wind-up: for most mobs {@code canContinueToUse} is "the navigation still has a path" and {@code canUse} only looks every 20 ticks, so
 * clearing the path (as a stop does) ended the goal and the mob never got to hit. The mob is planted by its navigation speed instead, and {@code canContinueToUse} says yes
 * while a wind-up is in progress.
 */
@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin {
    @Shadow
    @Final
    protected PathfinderMob mob;

    @Shadow
    @Final
    private double speedModifier;

    @Shadow
    private int ticksUntilNextAttack;

    @Shadow
    protected abstract boolean isTimeToAttack();

    @Shadow
    protected abstract void resetAttackCooldown();

    /**
     * Game time the wind-up in progress ends at, 0 for none; its length; which attack it is ({@code bite}; for an enderman the {@code juke_tell} shiver, then the {@code juke} or the
     * {@code feint} and its {@code followup}, {@link Juke}); whether the mob keeps chasing through it (after a juke) instead of being planted; and whether the attack about to run is the
     * end of one (its cooldown is shortened).
     */
    @Unique
    private long vectorcombat$until;
    @Unique
    private int vectorcombat$ticks;
    @Unique
    private String vectorcombat$kind = "bite";
    @Unique
    private boolean vectorcombat$chase;
    @Unique
    private boolean vectorcombat$landed;

    /** Starts a wind-up of {@code kind} for {@code ticks} at {@code now}: the state, the word to the clients and the log. */
    @Unique
    private void vectorcombat$begin(LivingEntity target, String kind, int ticks, boolean chase, long now) {
        vectorcombat$until = now + ticks;
        MobGuard.windingUp(mob, vectorcombat$until); // a mob mid wind-up does not raise its guard
        vectorcombat$ticks = ticks;
        vectorcombat$kind = kind;
        vectorcombat$chase = chase;
        WindUp.signal(mob, target, kind, "start", ticks);
        mob.getNavigation().setSpeedModifier(chase ? speedModifier : 0.0);
    }

    /** Ends the wind-up in progress without a hit ({@code event}: why), the chase going on. */
    @Unique
    private void vectorcombat$end(LivingEntity target, String event) {
        vectorcombat$until = 0;
        WindUp.signal(mob, target, vectorcombat$kind, event, vectorcombat$ticks);
        mob.getNavigation().setSpeedModifier(speedModifier);
    }

    /** A mob waiting its turn on the ring is steered there ({@link MobTokens#steering}): its goal stands aside rather than re-pathing it at the player every few ticks. */
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$waiting(CallbackInfo ci) {
        if (MobTokens.steering(mob)) ci.cancel();
    }

    @Inject(method = "checkAndPerformAttack(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$windUp(LivingEntity target, CallbackInfo ci) {
        vectorcombat$landed = false;
        if (dev.vectorcombat.Grab.holding(mob)) { // mid-grab: no plain hit
            ci.cancel();
            return;
        }
        if (MobGuard.takeRiposte(mob)) ticksUntilNextAttack = 0; // it parried: its next attack starts now (the wind-up still tells it)
        long was = vectorcombat$until;
        if (!WindUp.applicable(mob, target)) {
            vectorcombat$until = 0;
            if (was != 0) mob.getNavigation().setSpeedModifier(speedModifier);
            return;
        }
        boolean open = WindUp.open(mob);
        if (was == 0 && !open) { // recovering from a pounce or a bite: no wind-up yet, and not vanilla's instant hit either
            ci.cancel();
            return;
        }
        long now = mob.level().getGameTime();
        int interval = WindUp.interval(mob);
        // ---- the enderman's juke: the shiver, the teleport, then the real wind-up or a feint (Juke) ----
        if (was != 0 && vectorcombat$kind.equals("juke_tell")) {
            ci.cancel();
            if (WindUp.hurt(mob)) {
                vectorcombat$end(target, "interrupted");
            } else if (now > was + WindUp.STALE) {
                vectorcombat$end(target, "stale");
            } else if (now < was) {
                Juke.shiver(mob, false);
            } else if (Juke.teleport(mob, target)) {
                vectorcombat$begin(target, Juke.feints(mob.getRandom()) ? "feint" : "juke", Juke.WIND_UP, true, now);
            } else {
                vectorcombat$end(target, "no_room");
            }
            return;
        }
        if (was != 0 && vectorcombat$kind.equals("feint") && now >= was && now <= was + WindUp.STALE && !WindUp.hurt(mob)) { // the snap with no hit, then the quick real one
            ci.cancel();
            WindUp.signal(mob, target, "feint", "feint", vectorcombat$ticks);
            mob.swing(InteractionHand.MAIN_HAND);
            vectorcombat$begin(target, "followup", Juke.FOLLOW, true, now);
            return;
        }
        if (was == 0 && Juke.ready(mob, target)) {
            ci.cancel();
            vectorcombat$begin(target, "juke_tell", Juke.TELL, false, now);
            Juke.shiver(mob, true);
            return;
        }
        // ---- the bite, and the end of a juke's wind-up ----
        if (was == 0) {
            vectorcombat$kind = "bite";
            vectorcombat$chase = false;
        }
        int ticks = was == 0 ? WindUp.ticks(mob, interval) : vectorcombat$ticks;
        boolean inReach = mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target);
        if (was == 0 && isTimeToAttack() && open && inReach && !MobTokens.mayStart(mob, target)) { // a crowd takes turns: this one waits its turn, circling
            ci.cancel();
            MobTokens.circle(mob, target);
            return;
        }
        WindUp.Step step = WindUp.next(now, was, isTimeToAttack() && open, inReach, WindUp.hurt(mob), ticks);
        vectorcombat$until = step.until();
        MobGuard.windingUp(mob, vectorcombat$until);
        String event = step.event();
        if (event != null) {
            if (event.equals("start")) vectorcombat$ticks = ticks;
            WindUp.signal(mob, target, vectorcombat$kind, event, ticks);
        }
        if (vectorcombat$until != 0 && !vectorcombat$chase) mob.getNavigation().setSpeedModifier(0.0); // winding up: planted, with the path kept so the goal lives
        else if (was != 0) mob.getNavigation().setSpeedModifier(speedModifier); // it ended (or it is a juke's chase): the chase goes on
        if (WindUp.repath(vectorcombat$until != 0, inReach, mob.getNavigation().isDone())) mob.getNavigation().moveTo(target, speedModifier); // shoved out of reach (a parry, a block) with its path done, it would only stare
        if ("whiff".equals(event)) { // a miss costs what a hit does: the swing, then the same shortened cooldown, so a dodge opens the same recovery
            mob.swing(InteractionHand.MAIN_HAND);
            resetAttackCooldown();
            ticksUntilNextAttack = WindUp.afterLanding(ticksUntilNextAttack, ticks) + WindUp.slower(interval);
        } else {
            vectorcombat$landed = "land".equals(event);
            if (vectorcombat$landed) MobAttackAnims.landing(mob); // its hit runs now: a modded mob's attack animation is learned from it
        }
        if (event != null && (event.equals("land") || event.equals("whiff"))) WindUp.close(mob, WindUp.afterLanding(interval, ticks)); // the recovery also holds back a spider's pounce
        if (step.hold()) ci.cancel();
    }

    /**
     * After the vanilla attack that ended a wind-up: its cooldown, less the wind-up and plus what the mob's data makes it slower ({@code interval}), so the rate is the data's (vanilla's
     * by default). Done here rather than at the end of {@code resetAttackCooldown} because Big Brain's melee cooldown fix cancels that method (it sets the cooldown itself), and our
     * share of it was lost: mobs attacked every 28 ticks instead of 20.
     */
    @Inject(method = "checkAndPerformAttack(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("RETURN"))
    private void vectorcombat$share(LivingEntity target, CallbackInfo ci) {
        MobAttackAnims.landed(mob);
        if (!vectorcombat$landed) return;
        vectorcombat$landed = false;
        if (ticksUntilNextAttack > 0) ticksUntilNextAttack = WindUp.afterLanding(ticksUntilNextAttack, vectorcombat$ticks) + WindUp.slower(WindUp.interval(mob));
    }

    @Inject(method = "canContinueToUse()Z", at = @At("RETURN"), cancellable = true)
    private void vectorcombat$keep(CallbackInfoReturnable<Boolean> cir) {
        if (vectorcombat$until == 0 || cir.getReturnValue()) return;
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) cir.setReturnValue(true);
    }

    @Inject(method = "stop()V", at = @At("HEAD"))
    private void vectorcombat$stopped(CallbackInfo ci) {
        if (vectorcombat$until == 0) return;
        vectorcombat$until = 0;
        WindUp.signal(mob, mob.getTarget(), "goal_stopped", vectorcombat$ticks);
    }
}
