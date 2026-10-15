package dev.vectorcombat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vectorcombat.PoseCopy;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A layer drawn from a copy of the mob's model (a stray's or bogged's clothes, a drowned's outer layer, and any mod's layer built on the same helper) poses that copy again with its
 * own {@code setupAnim}; right after it, the copy gets the parent's parts as drawn, so it wears our wind-up and guard poses too ({@link PoseCopy#layer}). {@code require = 0}: a
 * changed helper costs the clothes their pose, not the game.
 */
@Mixin(RenderLayer.class)
public abstract class RenderLayerMixin {
    @Inject(method = "coloredCutoutModelCopyLayerRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER),
            require = 0)
    private static <T extends LivingEntity> void vectorcombat$copyPose(EntityModel<T> parent, EntityModel<T> copy, ResourceLocation texture, PoseStack pose, MultiBufferSource buffers, int light,
            T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float partialTick, int color, CallbackInfo ci) {
        PoseCopy.layer(parent, copy);
    }
}
