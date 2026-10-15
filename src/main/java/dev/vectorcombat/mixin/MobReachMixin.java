package dev.vectorcombat.mixin;

import dev.vectorcombat.Profiles;
import dev.vectorcombat.WeaponData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mob reaches as far as the weapon in its hand. {@code Mob.isWithinMeleeAttackRange} is the one check the goal mobs, the brain mobs and the wind-up ({@link dev.vectorcombat.WindUp})
 * all ask, and it is the mob's own box inflated by a fixed 0.83 blocks; a weapon that reaches beyond a bare hand's 3 blocks (a spear's +2) adds that much to the inflation, so the
 * mob starts its tell, and stops to give it, that much further out. A mob's data can add its own reach ({@code reach}, all round) and carry the box ahead the way it faces
 * ({@code reach_forward}: the ravager's, whose vanilla box is shrunk 0.05 and so stopped short of the player it had walked up to). Damage needs no change: {@code doHurtTarget} does not look at the distance.
 */
@Mixin(Mob.class)
public abstract class MobReachMixin {
    @Shadow
    protected abstract AABB getAttackBoundingBox();

    @Inject(method = "isWithinMeleeAttackRange(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"), cancellable = true)
    private void vectorcombat$reach(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;
        Mob self = (Mob) (Object) this;
        double extra = WeaponData.extraReach(self.getMainHandItem()) + Profiles.reach(self); // the weapon's, plus the mob's own from its data (the enderman's long arms)
        double forward = Profiles.reachForward(self); // the box carried ahead the way it faces (the ravager's head)
        if (extra <= 0 && forward <= 0) return;
        AABB box = getAttackBoundingBox().inflate(extra, 0, extra);
        if (forward > 0) {
            double yaw = Math.toRadians(self.getYRot());
            box = box.move(-Math.sin(yaw) * forward, 0, Math.cos(yaw) * forward);
        }
        if (box.intersects(target.getHitbox())) cir.setReturnValue(true);
    }
}
