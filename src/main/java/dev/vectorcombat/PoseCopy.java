package dev.vectorcombat;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;

/**
 * Keeps a model's second layers on the pose we give it. Vanilla copies the arms onto the sleeves, the body onto the jacket, the legs onto the pants and the head onto the hat inside
 * {@code setupAnim}; our poses go on after it ({@link ThirdPerson}, so mods that pose the model inside it run first), and the skin's outer layer stayed behind. And a mob's outer layer drawn from a copy of its model (a stray's or bogged's clothes, a drowned's outer layer: {@code RenderLayer.coloredCutoutModelCopyLayerRender})
 * is posed again from scratch by that copy's own {@code setupAnim}, which dropped a wind-up's lean and arms and a guard's raised arm.
 */
public final class PoseCopy {
    private PoseCopy() {
    }

    /** The overlay parts of {@code m} back on their base parts: the hat on the head and, on a player model, the sleeves, jacket and pants. */
    static void overlays(HumanoidModel<?> m) {
        m.hat.copyFrom(m.head);
        if (m instanceof PlayerModel<?> p) {
            p.leftSleeve.copyFrom(p.leftArm);
            p.rightSleeve.copyFrom(p.rightArm);
            p.jacket.copyFrom(p.body);
            p.leftPants.copyFrom(p.leftLeg);
            p.rightPants.copyFrom(p.rightLeg);
        }
    }

    /** A layer's copy model after its own {@code setupAnim}: given the parent's parts as they are drawn now (our poses included), as vanilla gave them before it. Humanoid models only. */
    public static void layer(EntityModel<?> parent, EntityModel<?> copy) {
        if (parent instanceof HumanoidModel<?> from && copy instanceof HumanoidModel<?> to) {
            copyParts(from, to);
            overlays(to);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyParts(HumanoidModel from, HumanoidModel to) {
        from.copyPropertiesTo(to);
    }
}
