package dev.vectorcombat;

import net.minecraft.client.model.AbstractZombieModel;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * A mob's guard in third person, client side (the server's side is {@link MobGuard}). A humanoid mob using a shield or a block-pose weapon raises that arm into vanilla's
 * blocking pose (the arm forward and turned in, following the head), blended in over {@link #RAISE_TICKS} so the raise is a tell inside the parry window; vanilla draws no
 * block pose for mobs. And an armed zombie (any {@code AbstractZombieModel}: zombie, husk, drowned, zombie villager, and modded ones built on it) reaches out with its weapon
 * arm only: its other arm hangs and swings as a humanoid's does, so a raised guard reads as one. Applied after the model's own pose and the wind-up's ({@code LivingEntityRendererMixin}),
 * and again after an animator that poses the model while it is drawn ({@link EmfCompat}).
 */
public final class MobGuardClient {
    private MobGuardClient() {
    }

    /** Ticks a raise takes to reach the full pose: well inside the 5 tick parry window, so the raise itself is what the player reads. */
    static final float RAISE_TICKS = 2;

    private static final float DEG = (float) (Math.PI / 180);

    private static boolean failed;
    /** What the last pose set, for {@link #afterAnimator}: the entity, and per part (up to two) its rotations. */
    private static Object posedEntity;
    private static final ModelPart[] PARTS = new ModelPart[2];
    private static final float[][] ROTS = new float[2][3];
    private static int posedCount;

    /** A humanoid arm's swing as vanilla's {@code HumanoidModel} gives it at rest: opposite phases for the two arms, half the stride's amplitude. */
    static float idleX(boolean right, float walkPos, float walkSpeed) {
        return Mth.cos(walkPos * 0.6662f + (right ? (float) Math.PI : 0f)) * walkSpeed;
    }

    /** Vanilla's blocking arm ({@code HumanoidModel.poseBlockingArm}) from an arm at {@code baseX}: forward and turned in, following the head within limits. Returns {x, y}. */
    static float[] blockingArm(boolean right, float baseX, float headX, float headY) {
        return new float[] {baseX * 0.5f - 0.9424779f + Mth.clamp(headX, -1.3962634f, 0.43633232f), (right ? -30f : 30f) * DEG + Mth.clamp(headY, -0.5235988f, 0.5235988f)};
    }

    /** How far into its raise a guard is drawn, 0 to 1, {@code ticks} after it went up. */
    static float raised(float ticks) {
        return Math.max(0f, Math.min(1f, ticks / RAISE_TICKS));
    }

    public static void pose(EntityModel<?> model, LivingEntity e, float partialTick) {
        posedEntity = null;
        posedCount = 0;
        if (failed || !(model instanceof HumanoidModel<?> h) || !(e instanceof Mob) || e.deathTime > 0) return;
        try {
            float pos = e.walkAnimation.position(partialTick), speed = e.isPassenger() ? 0f : Math.min(1f, e.walkAnimation.speed(partialTick)), age = e.tickCount + partialTick;
            boolean mainRight = e.getMainArm() == HumanoidArm.RIGHT;
            ModelPart main = mainRight ? h.rightArm : h.leftArm, off = mainRight ? h.leftArm : h.rightArm;
            if (model instanceof AbstractZombieModel<?> && !e.getMainHandItem().isEmpty()) { // the weapon arm reaches out, the other hangs
                off.xRot = idleX(!mainRight, pos, speed);
                off.yRot = 0;
                off.zRot = 0;
                AnimationUtils.bobModelPart(off, age, mainRight ? -1f : 1f);
                remember(off);
            }
            if (e.isUsingItem() && Guard.shield(e)) {
                ModelPart arm = e.getUsedItemHand() == InteractionHand.MAIN_HAND ? main : off;
                boolean right = arm == h.rightArm;
                float[] to = blockingArm(right, idleX(right, pos, speed), h.head.xRot, h.head.yRot);
                float b = raised(e.getTicksUsingItem() + partialTick);
                arm.xRot = Mth.lerp(b, arm.xRot, to[0]);
                arm.yRot = Mth.lerp(b, arm.yRot, to[1]);
                arm.zRot = Mth.lerp(b, arm.zRot, 0f);
                remember(arm);
            }
            if (posedCount > 0) posedEntity = e;
        } catch (RuntimeException ex) {
            posedCount = 0;
            if (!failed) VectorCombat.LOG.error("Mob guard pose failed, it is off until restart", ex);
            failed = true;
        }
    }

    private static void remember(ModelPart part) {
        int i = posedCount > 0 && PARTS[0] == part ? 0 : posedCount++;
        PARTS[i] = part;
        ROTS[i][0] = part.xRot;
        ROTS[i][1] = part.yRot;
        ROTS[i][2] = part.zRot;
    }

    /** An animator that poses the model while it is drawn ({@link EmfCompat}) has run for {@code entity}: the arms this pose set get its values back. */
    static void afterAnimator(Object entity) {
        if (posedEntity == null || posedEntity != entity) return;
        for (int i = 0; i < posedCount; i++) {
            PARTS[i].xRot = ROTS[i][0];
            PARTS[i].yRot = ROTS[i][1];
            PARTS[i].zRot = ROTS[i][2];
        }
    }
}
