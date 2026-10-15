package dev.vectorcombat.mixin;

import dev.vectorcombat.BrainWindUp;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.MeleeAttack;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.behavior.declarative.MemoryAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a brain mob decides to hit: the lambda of {@code MeleeAttack.create} that vanilla runs every brain tick once a target is in reach and the cooldown is over. A mob going for a
 * player winds up first ({@link BrainWindUp}); vanilla's attack runs when the wind-up ends. The target is the synthetic lambda of 1.21.1, so a different Minecraft build makes
 * this fail the server's boot (the mixin config requires its injections) instead of silently doing nothing.
 */
@Mixin(MeleeAttack.class)
public abstract class MeleeAttackMixin {
    @Shadow
    private static boolean isHoldingUsableProjectileWeapon(Mob mob) {
        throw new AssertionError();
    }

    @Inject(method = "lambda$create$0", at = @At("HEAD"), cancellable = true)
    private static void vectorcombat$windUp(BehaviorBuilder.Instance<?> instance, MemoryAccessor<?, ?> a, MemoryAccessor<?, ?> b, MemoryAccessor<?, ?> c, MemoryAccessor<?, ?> d,
            int interval, ServerLevel level, Mob mob, long time, CallbackInfoReturnable<Boolean> cir) {
        if (BrainWindUp.hold(mob, interval, isHoldingUsableProjectileWeapon(mob))) cir.setReturnValue(true);
    }

    /** Vanilla's cooldown right after the attack: shortened by the wind-up that led to it. */
    @ModifyArg(method = "lambda$create$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;setWithExpiry(Ljava/lang/Object;J)V"), index = 1)
    private static long vectorcombat$cooldown(long expiry) {
        return BrainWindUp.cooldown(expiry);
    }
}
