package dev.vectorcombat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vectorcombat.Fit;
import dev.vectorcombat.Hands;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hands ({@link Hands}): remembers the view frame when the hands start to render, and draws the arm just before an item is drawn, where the pose
 * stack is at the hand with everything vanilla and this mod do to it (equip, eating, bow, swing, clip) already applied. Both hooks return at once when the
 * setting is off, and a fault switches the hands off for the session. The same spot turns a weapon whose model sits at another angle than a sword's about the
 * hand while a clip plays ({@link Fit}), after the arm is drawn.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
            + "Lnet/minecraft/client/player/LocalPlayer;I)V", at = @At("HEAD"), require = 0)
    private void vectorcombat$view(float partialTick, PoseStack ps, MultiBufferSource.BufferSource buffer, LocalPlayer player, int light, CallbackInfo ci) {
        Hands.base(ps);
    }

    @Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Z"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), require = 0)
    private void vectorcombat$hand(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, boolean left, PoseStack ps, MultiBufferSource buffer, int light, CallbackInfo ci) {
        try {
            Hands.draw(ps, buffer, light, entity, stack, ctx);
        } catch (RuntimeException e) {
            Hands.fail(e);
        }
        try {
            Fit.apply(ps, entity, stack, ctx); // after the arm: the arm must not turn with the item
        } catch (RuntimeException e) {
            Fit.fail(e);
        }
    }
}
