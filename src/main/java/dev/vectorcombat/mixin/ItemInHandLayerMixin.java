package dev.vectorcombat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vectorcombat.Blade;
import dev.vectorcombat.ThirdPerson;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns a swung weapon so its blade lies along the arm with the edge leading ({@link Blade}). Only while a swing of the wielder is posed; otherwise this returns at once. It adds
 * one rotation about the hand just before vanilla draws the item, after vanilla's own steps (to the hand, the fixed turns, the offset) and whatever other mods do at them (Entity
 * Model Features' custom hands, Player Animation Lib's item hooks), so none of theirs is skipped.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin<T extends LivingEntity, M extends EntityModel<T> & ArmedModel> extends RenderLayer<T, M> {
    protected ItemInHandLayerMixin(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Inject(method = "renderArmWithItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"), require = 0)
    private void vectorcombat$bladeAlongArm(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, HumanoidArm arm, PoseStack ps, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!(this.getParentModel() instanceof HumanoidModel<?> humanoid)) return;
        try {
            if (Blade.active(entity, stack, arm)) ps.mulPose(Blade.correction(entity, stack, arm, ps, humanoid));
        } catch (RuntimeException e) {
            ThirdPerson.fail(e); // vanilla's hold from the next frame on
        }
    }
}
