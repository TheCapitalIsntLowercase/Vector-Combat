package dev.vectorcombat.mixin;

import dev.vectorcombat.MobAttackAnims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees the entity events a mob broadcasts while its wind-up's hit runs, so a modded mob's own attack animation can be learned and played at the tell instead
 * ({@link MobAttackAnims}). Nothing happens unless a wind-up's hit is running. {@code require = 0}.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void vectorcombat$entityEvent(Entity entity, byte event, CallbackInfo ci) {
        if (MobAttackAnims.broadcast(entity, event)) ci.cancel();
    }
}
