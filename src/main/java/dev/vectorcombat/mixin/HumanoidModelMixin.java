package dev.vectorcombat.mixin;

import dev.vectorcombat.ThirdPerson;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Start of {@code setupAnim}: vanilla's own swing plays nothing while a Vector swing is posed ({@link ThirdPerson#before}). The pose itself goes on after {@code setupAnim} returns
 * ({@code LivingEntityRendererMixin}), so mods that pose the model inside it run first. {@code require = 0} so a model that changes under us costs the pose, not the game.
 */
@Mixin(value = HumanoidModel.class, priority = 1500)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"), require = 0)
    private void vectorcombat$before(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        try {
            ThirdPerson.before((HumanoidModel<?>) (Object) this, entity);
        } catch (RuntimeException e) {
            ThirdPerson.fail(e);
        }
    }
}
