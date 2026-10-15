package dev.vectorcombat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vectorcombat.MobGuardClient;
import dev.vectorcombat.ThirdPerson;
import dev.vectorcombat.WindUpClient;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The step after a model has been posed for the frame ({@code EntityModel.setupAnim}, with every mod's additions to it, Player Animation Lib's included) and before it is drawn:
 * the one place where a pose goes on top of whatever the model's own animation gave it. A player's or a humanoid's swing ({@link ThirdPerson#apply}) and a mob's legs (and an overhead's arms) in a wind-up
 * ({@link WindUpClient#pose}), then a mob's raised guard and an armed zombie's arms ({@link MobGuardClient#pose}). {@code require = 0} so a renderer that changes under us costs the pose, not the game.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Shadow
    protected M model;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER), require = 0)
    private void vectorcombat$posed(LivingEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (model instanceof HumanoidModel<?> humanoid) {
            float walk = entity.isAlive() && !entity.isPassenger() ? Math.min(1f, entity.walkAnimation.speed(partialTicks)) : 0f; // the renderer's own limbSwingAmount
            ThirdPerson.apply(humanoid, entity, walk);
        }
        WindUpClient.pose(model, entity, partialTicks);
        MobGuardClient.pose(model, entity, partialTicks);
    }
}
