package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The held weapon in third person, turned so its blade lies along the swinging arm with the edge leading. Vanilla holds a sprite weapon with its blade about
 * square to the arm (a sword in a fist), so an arm thrust along the aim line would show the blade pointing up. The wanted blade orientation is worked out in
 * the world (blade collinear with the arm's final pose, whoever posed it; flat faces turned so the edge leads the direction of travel), expressed in the item
 * layer's own frame through the pose stack, and turned into a rotation about the hand from the item's rest orientation, which is read from the display transform
 * of the model really drawn there. The maths follows v6.2's {@code ThirdPersonBlade}. The trident is drawn by its own renderer, so it has no sprite to read the blade from: its shaft is
 * the model's own (see {@link #tridentRest}); any other custom-rendered model keeps vanilla's hold.
 */
public final class Blade {
    private Blade() {
    }

    /** A blade orientation: the axis (hilt to tip) and the flat-face normal, perpendicular to it. */
    record Frame(Vector3f b, Vector3f n) {
        Matrix3f basis() {
            Vector3f bb = new Vector3f(b).normalize();
            Vector3f nn = new Vector3f(n).sub(new Vector3f(bb).mul(n.dot(bb))).normalize();
            Vector3f c = new Vector3f(bb).cross(nn);
            return new Matrix3f(bb.x, bb.y, bb.z, nn.x, nn.y, nn.z, c.x, c.y, c.z); // columns: b, n, b x n
        }
    }

    private static final Vector3f UP = new Vector3f(0, 1, 0);

    /**
     * The blade axis and flat normal of a sprite weapon in its display frame, as {@code ItemTransform.apply} places them: the sprite's blade runs bottom-left to
     * top-right (1, 1, 0) and its flat normal is z; both are scaled, then rotated by the display rotation (x, y, z in degrees, mirrored for the left hand).
     */
    static Frame rest(Vector3f rotationDeg, Vector3f scale, boolean leftHand) {
        Quaternionf q = displayRotation(rotationDeg, leftHand);
        return new Frame(q.transform(new Vector3f(scale.x(), scale.y(), 0f)).normalize(), q.transform(new Vector3f(0f, 0f, scale.z())).normalize());
    }

    /**
     * The trident's blade axis and flat normal in its display frame. Its model is boxes drawn by a custom renderer that flips y and z first ({@code scale(1, -1, -1)}), so the head (the
     * prongs, 4 pixels above the model's origin before the flip) is above the grip: the shaft runs up the item's y axis, hilt to tip, and the prongs lie in the plane whose normal is z.
     * Only the display rotation turns them: the scale is 1, and the display translation already puts the shaft within 7 cm of the hand frame, so a turn about the hand keeps the grip in the fist.
     */
    static Frame tridentRest(Vector3f rotationDeg, boolean leftHand) {
        Quaternionf q = displayRotation(rotationDeg, leftHand);
        return new Frame(q.transform(new Vector3f(0f, 1f, 0f)), q.transform(new Vector3f(0f, 0f, 1f)));
    }

    /** The display rotation as {@code ItemTransform.apply} turns by it: x, y, z in degrees, y and z mirrored for the left hand. */
    private static Quaternionf displayRotation(Vector3f rotationDeg, boolean leftHand) {
        float ry = leftHand ? -rotationDeg.y() : rotationDeg.y(), rz = leftHand ? -rotationDeg.z() : rotationDeg.z();
        return new Quaternionf().rotationXYZ((float) Math.toRadians(rotationDeg.x()), (float) Math.toRadians(ry), (float) Math.toRadians(rz));
    }

    /**
     * Blade collinear with the arm, flat faces turned so the edge leads: the normal is that of the plane containing the blade and its direction of travel
     * {@code tangent}; with no travel (a thrust) the flat faces up. The sign of a flat normal is arbitrary, so it is kept stable and the blade never flips.
     */
    static Frame fromArm(Vector3f armDir, Vector3f tangent) {
        Vector3f b = new Vector3f(armDir).normalize();
        Vector3f n = new Vector3f(b).cross(tangent);
        if (n.lengthSquared() < 1.0E-8f) n = new Vector3f(UP).sub(new Vector3f(b).mul(UP.dot(b)));
        if (n.lengthSquared() < 1.0E-8f) n = new Vector3f(1, 0, 0);
        n.normalize();
        if (Math.abs(n.y) > 0.5f ? n.y < 0 : n.x < 0) n.negate();
        return new Frame(b, n);
    }

    /** The rotation part of a pose matrix with any uniform scale taken out (columns normalised). */
    static Matrix3f rotationOf(Matrix4fc pose) {
        Matrix3f m = new Matrix3f(pose);
        Vector3f c = new Vector3f();
        for (int i = 0; i < 3; i++) {
            m.getColumn(i, c).normalize();
            m.setColumn(i, c);
        }
        return m;
    }

    /** The rotation that carries orientation {@code from} onto {@code to}. */
    static Quaternionf between(Frame from, Frame to) {
        return new Quaternionf().setFromNormalized(to.basis().mul(from.basis().transpose())).normalize();
    }

    /** Body frame (x right, y up, z forward) to world at body yaw {@code yawDeg} (0 = facing +Z). */
    static Vector3f toWorld(Vector3f v, double yawDeg) {
        double y = Math.toRadians(yawDeg);
        double rx = -Math.cos(y), rz = -Math.sin(y), fx = -Math.sin(y), fz = Math.cos(y);
        return new Vector3f((float) (rx * v.x + fx * v.z), v.y, (float) (rz * v.x + fz * v.z));
    }

    /**
     * The rotation about the hand that lays a blade at rest orientation {@code rest} (in the item layer's frame) onto {@code bodyFrame} (in the wearer's body frame):
     * the wanted frame goes body to world to the layer's frame (through the pose matrix of the layer), and when the blade travels the flat normal's sign is chosen
     * so the cutting edge (n x b at rest) points along {@code travelBody}. The body frame is left-handed, so that is decided here, in the layer's right-handed one.
     */
    static Quaternionf solve(Frame bodyFrame, Vector3f travelBody, double bodyYawDeg, Matrix4fc layerPose, Frame rest) {
        Matrix3f toLocal = rotationOf(layerPose).transpose();
        Vector3f b = toLocal.transform(toWorld(bodyFrame.b(), bodyYawDeg)), n = toLocal.transform(toWorld(bodyFrame.n(), bodyYawDeg));
        Vector3f travel = toLocal.transform(toWorld(travelBody, bodyYawDeg));
        if (travel.lengthSquared() > 1.0E-8f && new Vector3f(n).cross(b).dot(travel) < 0) n.negate();
        return between(rest, new Frame(b, n));
    }

    private static Vector3f dirBody(ClientSwings.View v, double t, double bodyYaw) {
        double[] d = ThirdPerson.bodyDirection(ThirdPerson.axis(v.clip(), t), v.yaw(), v.pitch(), bodyYaw);
        return new Vector3f((float) d[0], (float) d[1], (float) d[2]);
    }

    /**
     * The blade's direction of travel in the body frame, signed to follow the strike's stroke and held at its value at the ends of the strike, so the edge leads
     * all the way and the blade does not roll when the arm stops. Zero for a thrust.
     */
    static Vector3f travel(ClientSwings.View v, double bodyYaw) {
        Anim.Trail tr = v.clip().trail();
        double dt = 0.03, t = Math.max(tr.start() + dt, Math.min(tr.end() - dt, v.time()));
        Vector3f tangent = dirBody(v, t + dt, bodyYaw).sub(dirBody(v, t - dt, bodyYaw));
        Vector3f stroke = dirBody(v, tr.end(), bodyYaw).sub(dirBody(v, tr.start(), bodyYaw));
        if (tangent.dot(stroke) < 0) tangent.negate();
        return tangent;
    }

    // ---- the item in hand (client) ----

    /** The model drawn in hand for this stack: a "_held" variant if the owning mod provides one (Basic Weapons' big spears, quarterstaves and glaives), else the normal one. */
    static BakedModel modelInHand(LivingEntity e, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel base = mc.getItemRenderer().getModel(stack, mc.level, e, 0);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ModelManager mm = mc.getModelManager();
        BakedModel held = mm.getModel(ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_held")));
        return held != null && held != mm.getMissingModel() ? held : base;
    }

    /**
     * Where the model really drawn in {@code ctx} puts a sprite's blade: NeoForge's {@code applyTransform} into an empty stack (the display transform with its {@code right_rotation},
     * or whatever a model does for itself per context, such as {@code neoforge:separate_transforms} handing over another model), then the sprite's diagonal and flat face through it.
     * The same numbers as {@link #rest} for a plain display transform. Also gives the model drawn for the context, which is the one whose shape counts.
     */
    record Placed(BakedModel model, Frame frame) {
    }

    static Placed placed(BakedModel model, ItemDisplayContext ctx, boolean left) {
        PoseStack ps = new PoseStack();
        BakedModel drawn = model.applyTransform(ctx, ps, left);
        return new Placed(drawn, frameOf(ps.last()));
    }

    /** A sprite's blade (its diagonal) and flat face (z) through a pose: the face by the normal matrix, so a non-uniform scale does not tilt it. */
    static Frame frameOf(PoseStack.Pose p) {
        return new Frame(p.pose().transformDirection(new Vector3f(1f, 1f, 0f)).normalize(), p.normal().transform(new Vector3f(0f, 0f, 1f)).normalize());
    }

    /** No rest orientation: the model keeps vanilla's hold. */
    private static final Frame NONE = new Frame(new Vector3f(), new Vector3f());

    /** By model and hand, so each is read once; a resource reload bakes new models. Render thread only. */
    private static final Map<BakedModel, Frame[]> RESTS = new WeakHashMap<>();

    /**
     * The rest orientation of the sprite weapon or the trident in this hand, or null for a model with no readable blade, which keeps vanilla's hold: one drawn by a custom renderer
     * (other than the trident) or a 3D model that is not a flat sprite ({@link Fit#flat(BakedModel)}).
     */
    @SuppressWarnings("deprecation") // the trident's display transform is read directly: its model is drawn by a custom renderer, so applyTransform has nothing more to say
    private static Frame restOf(LivingEntity e, ItemStack stack, boolean left) {
        BakedModel model = modelInHand(e, stack);
        Frame[] both = RESTS.computeIfAbsent(model, m -> new Frame[2]);
        int i = left ? 1 : 0;
        if (both[i] == null) {
            ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            if (model.isCustomRenderer()) {
                both[i] = stack.is(Items.TRIDENT) ? tridentRest(model.getTransforms().getTransform(ctx).rotation, left) : NONE;
            } else {
                Placed p = placed(model, ctx, left);
                both[i] = p.model().isCustomRenderer() || !Fit.flat(p.model()) ? NONE : p.frame();
            }
        }
        return both[i] == NONE ? null : both[i];
    }

    /** True when this item in this hand is turned along a swing: the swinger's weapon arm, a sprite weapon or the trident, the pose on. */
    public static boolean active(LivingEntity e, ItemStack stack, HumanoidArm arm) {
        return !stack.isEmpty() && e.getMainArm() == arm && ThirdPerson.posed(e) != null && restOf(e, stack, arm == HumanoidArm.LEFT) != null;
    }

    /** The rotation about the hand to apply before the item is drawn; the pose stack is at the item layer's hand frame and {@code model} has its final pose. */
    public static Quaternionf correction(LivingEntity e, ItemStack stack, HumanoidArm arm, PoseStack ps, HumanoidModel<?> model) {
        ClientSwings.View v = ThirdPerson.posed(e);
        Frame rest = v == null ? null : restOf(e, stack, arm == HumanoidArm.LEFT);
        if (rest == null) return new Quaternionf();
        ModelPart part = arm == HumanoidArm.LEFT ? model.leftArm : model.rightArm;
        double[] a = ThirdPerson.armDir(part.xRot, part.yRot, part.zRot);
        double yaw = ThirdPerson.bodyYaw(e);
        Vector3f travel = travel(v, yaw);
        Frame want = fromArm(new Vector3f((float) a[0], (float) a[1], (float) a[2]), travel);
        Quaternionf q = solve(want, travel, yaw, ps.last().pose(), rest);
        return new Quaternionf().slerp(q, (float) ThirdPerson.weight(v.clip(), v.time())); // from vanilla's hold into the swing, and back
    }
}
