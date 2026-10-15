package dev.vectorcombat.mixin;

import dev.vectorcombat.Engulf;
import dev.vectorcombat.Hitstop;
import dev.vectorcombat.SpiderMoves;
import dev.vectorcombat.Drag;
import dev.vectorcombat.MobThrows;
import dev.vectorcombat.Slam;
import dev.vectorcombat.Grab;
import dev.vectorcombat.Charge;
import dev.vectorcombat.Stuns;
import dev.vectorcombat.WindUp;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Immobile entities get no movement input, no jumping and no AI tick; stun uses that. A mob held by a landed hit does not move, swing its
 * limbs or play out its death until the hold ends ({@link Hitstop}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "isImmobile()Z", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$stunned(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (Stuns.isStunned(self)) {
            cir.setReturnValue(true);
            return;
        }
        // the moves' state is server-side (in singleplayer the client thread must not read the server's maps while the server writes them); a mob in a move is moved only by it
        if (!self.level().isClientSide() && (Engulf.holding(self) || Charge.holding(self) || SpiderMoves.holding(self) || Drag.holding(self) || MobThrows.holding(self) || Slam.holding(self) || Grab.holding(self))) cir.setReturnValue(true);
    }

    /** A mob tagged {@code #vectorcombat:disables_shields} (the enderman) disables a shield that blocks it, as an axe does; a clean parry never reaches the block, so it never does. */
    @Inject(method = "canDisableShield()Z", at = @At("RETURN"), cancellable = true)
    private void vectorcombat$disables(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && ((LivingEntity) (Object) this).getType().is(WindUp.DISABLES_SHIELDS)) cir.setReturnValue(true);
    }

    @Inject(method = "aiStep()V", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$hold(CallbackInfo ci) {
        if (Hitstop.frozen((LivingEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "calculateEntityAnimation(Z)V", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$holdLimbs(boolean includeHeight, CallbackInfo ci) {
        if (Hitstop.active((LivingEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "tickDeath()V", at = @At("HEAD"), cancellable = true)
    private void vectorcombat$holdDeath(CallbackInfo ci) {
        if (Hitstop.active((LivingEntity) (Object) this)) ci.cancel();
    }
}
