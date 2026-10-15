package dev.vectorcombat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

/**
 * The swing as others see it: the weapon arm points along the blade axis the clip gives at its clock ({@link Anim.Trail}: the same numbers the first-person
 * clip was generated from), so the blade sweeps the same path in the world as in your view; {@link Blade} lays the item along the arm. The arm blends in over
 * the windup, stays on the blade through the strike and the hold, and blends back to whatever pose the model already had over the recovery. Runs right after
 * {@code setupAnim} (every mod that poses the model inside it has run) and again after an animator that poses it while it is drawn ({@link EmfCompat}), so animation mods are blended
 * over, not overwritten, and do not overwrite the swing. Rotation conventions (verified against the
 * real model by v6.2 and again by this build's probe): for both arms a positive yaw turns the arm toward the wearer's right, x = -90 degrees is level and
 * -180 degrees straight up, a positive roll swings it to the right.
 * The arm is not alone: the chest turns half-way toward the blade ({@link #twist}) and the foot opposite the weapon arm steps forward with the strike ({@link #step}). The arm's own angles
 * are absolute (in the entity's frame, not the torso's), so neither moves the blade: the twist only carries the shoulders round the spine.
 */
public final class ThirdPerson {
    private ThirdPerson() {
    }

    /** Seconds the arm stays on the blade after the impact: every clip holds 0.04 s past it (the generator's timeline). */
    static final double HOLD = 0.04;

    /** Set by the first fault: the pose switches itself off for the session rather than break the model every frame. */
    private static boolean failed;
    private static final Map<Integer, Long> LOGGED = new HashMap<>();

    private static double smooth(double t, double a, double b) {
        if (b <= a) return t >= b ? 1 : 0;
        double u = Math.max(0, Math.min(1, (t - a) / (b - a)));
        return u * u * (3 - 2 * u);
    }

    /** How much of the pose to wear at clip second {@code t}: 0 before the swing, rising over the windup, 1 through the strike and the hold, falling over the recovery. */
    static double weight(Anim clip, double t) {
        return weight(clip, t, ThirdView.refreshed());
    }

    /**
     * Seconds the refresh gives the arm to settle back after the blade's path ends, at the least: the clip's own recovery is often a tenth of a second, which snaps the arm from an overhead strike back to rest
     * in two frames.
     */
    static final double RECOVER_MIN = 0.3;

    /** Where the pose has blended back out, in clip seconds: the clip's end, or under the refresh at least {@link #RECOVER_MIN} after the blade's path ends. */
    static double poseEnd(Anim clip, boolean refreshed) {
        Anim.Trail tr = clip.trail();
        if (tr == null) return clip.length();
        return refreshed ? Math.max(clip.length(), tr.end() + HOLD + RECOVER_MIN) : clip.length();
    }

    static double weight(Anim clip, double t, boolean refreshed) {
        Anim.Trail tr = clip.trail();
        if (tr == null) return 0;
        return smooth(t, 0, tr.start()) * (1 - smooth(t, tr.end() + HOLD, poseEnd(clip, refreshed)));
    }

    /** The blade axis in the frame of the look the swing began with (x right, y up, -z forward) at clip second {@code t}. */
    static double[] axis(Anim clip, double t) {
        Anim.Trail tr = clip.trail();
        return tr.direction(tr.progress(t));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * A view-frame direction (the swing began looking along yaw {@code yaw0}, pitch {@code pitch0}, in degrees as Minecraft keeps them) as the wearer's body
     * frame sees it at body yaw {@code bodyYaw}: x right, y up, z forward.
     */
    static double[] bodyDirection(double[] v, double yaw0, double pitch0, double bodyYaw) {
        double y = Math.toRadians(yaw0), p = Math.toRadians(pitch0);
        double[] f = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
        double[] r = {-Math.cos(y), 0, -Math.sin(y)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double[] w = new double[3];
        for (int i = 0; i < 3; i++) w[i] = r[i] * v[0] + u[i] * v[1] - f[i] * v[2];
        double b = Math.toRadians(bodyYaw);
        return new double[] {dot(w, new double[] {-Math.cos(b), 0, -Math.sin(b)}), w[1], dot(w, new double[] {-Math.sin(b), 0, Math.cos(b)})};
    }

    /** The direction (right, up, forward in the body frame) a right or left arm points along with model rotations {@code x}, {@code y}, {@code z} (radians). */
    static double[] armDir(double x, double y, double z) {
        double r = 0, u = -1, f = 0; // hanging
        double u1 = u * Math.cos(x) - f * Math.sin(x), f1 = u * Math.sin(x) + f * Math.cos(x);
        u = u1;
        f = f1;
        double r2 = r * Math.cos(y) + f * Math.sin(y), f2 = f * Math.cos(y) - r * Math.sin(y);
        r = r2;
        f = f2;
        double r3 = r * Math.cos(z) - u * Math.sin(z), u3 = u * Math.cos(z) + r * Math.sin(z);
        double n = Math.sqrt(r3 * r3 + u3 * u3 + f * f);
        return new double[] {r3 / n, u3 / n, f / n};
    }

    /** The model rotations {x, y} (radians, no roll) that point an arm along {@code d} in the body frame: the inverse of {@link #armDir} at z = 0. */
    static double[] armAngles(double[] d) {
        double n = Math.sqrt(dot(d, d));
        double r = d[0] / n, u = d[1] / n, f = d[2] / n;
        double s = Math.hypot(r, f);
        return new double[] {-Math.acos(Math.max(-1, Math.min(1, -u))), s < 1e-6 ? 0 : Math.atan2(r, f)};
    }

    /** The most the chest turns toward the blade: a quarter turn either way. */
    static final double MAX_TWIST = Math.PI / 4;

    /**
     * How far the chest turns for a blade that points {@code armYaw} radians off the body's front (positive toward the wearer's right) at pose weight {@code w}: half-way toward it, so the chest and the
     * shoulder share the aim, at most a quarter turn. Positive turns the chest toward the wearer's right (the right shoulder back), the way vanilla's own attack twist leads in.
     */
    static double twist(double armYaw, double w) {
        return Math.max(-MAX_TWIST, Math.min(MAX_TWIST, armYaw / 2)) * w;
    }

    /** How far the leg step has come at clip second {@code t}: rising over the strike (windup end to impact), held through the hold, released over the recovery. */
    static double step(Anim clip, double t) {
        Anim.Trail tr = clip.trail();
        if (tr == null) return 0;
        return smooth(t, tr.start(), tr.end()) * (1 - smooth(t, tr.end() + HOLD, clip.length()));
    }

    /** The step that shows at walk speed {@code walk} (vanilla's {@code limbSwingAmount}, 0 standing, about 1 sprinting): legs already walking keep their own stride. */
    static double stepAmount(double step, double walk) {
        return step * (1 - Math.max(0, Math.min(1, walk)));
    }

    /**
     * Turns the torso {@code psi} radians about the spine (positive toward the wearer's right): the body part, both shoulders round the spine by the formula vanilla's own attack twist uses
     * ({@code HumanoidModel.setupAttackAnimation}: the right arm to x = -5 cos, z = 5 sin and the left to x = 5 cos, z = -5 sin of the body's yaw) and the arm that is not swinging with the
     * chest. The swinging arm keeps its own angles, which already aim along the blade, and the head keeps looking where the wearer looks.
     */
    static void turnTorso(HumanoidModel<?> model, double psi, HumanoidArm mainArm) {
        float cos = (float) Math.cos(psi), sin = (float) Math.sin(psi);
        model.body.yRot += (float) psi;
        turnShoulder(model.rightArm, model.body, cos, sin);
        turnShoulder(model.leftArm, model.body, cos, sin);
        (mainArm == HumanoidArm.LEFT ? model.rightArm : model.leftArm).yRot += (float) psi;
    }

    private static void turnShoulder(ModelPart shoulder, ModelPart body, float cos, float sin) {
        float dx = shoulder.x - body.x, dz = shoulder.z - body.z;
        shoulder.x = body.x + dx * cos + dz * sin;
        shoulder.z = body.z - dx * sin + dz * cos;
    }

    /** The foot opposite the weapon arm steps forward and the other goes back, by what a mob's legs do at its strike ({@link WindUp#legLead}, {@link WindUp#legTrail}) times {@code s}. */
    static void stepLegs(HumanoidModel<?> model, HumanoidArm mainArm, double s) {
        boolean left = mainArm == HumanoidArm.LEFT;
        (left ? model.rightLeg : model.leftLeg).xRot += WindUp.legLead((float) s);
        (left ? model.leftLeg : model.rightLeg).xRot += WindUp.legTrail((float) s);
    }

    /** The torso can twist while the entity stands or crouches; swimming, gliding, sleeping and the like turn the whole model another way. */
    static boolean twists(LivingEntity e) {
        return e.hasPose(Pose.STANDING) || e.hasPose(Pose.CROUCHING);
    }

    /** The legs can step while the entity stands on the ground and is not riding. */
    static boolean steps(LivingEntity e) {
        return e.hasPose(Pose.STANDING) && e.onGround() && !e.isPassenger();
    }

    static float bodyYaw(LivingEntity e) {
        return Mth.rotLerp(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false), e.yBodyRotO, e.yBodyRot);
    }

    /** The swing this entity is posed by right now, or null: the setting is off, nothing is swinging, or the swing has not begun or has recovered. */
    static ClientSwings.View posed(LivingEntity e) {
        if (failed) return null;
        boolean refreshed = ThirdView.refreshed();
        ClientSwings.View v = refreshed ? ClientSwings.viewUntil(e, c -> poseEnd(c, true)) : ClientSwings.view(e); // asked of every humanoid every frame: nobody swinging comes first and cheap
        if (v == null || weight(v.clip(), v.time()) < 0.001 || !ClientConfig.THIRD_PERSON.get()) return null;
        return yields(e.isUsingItem(), e.swinging, net.minecraft.Util.getMillis(), e.swingTime, v.startedAt()) ? null : v;
    }

    /** How long after our swing began a vanilla arm swing must start to be the player's own (mining, a block hit) rather than the server's echo of ours, in ms. */
    static final long OWN_SWING_AFTER_MS = 150;

    /**
     * Whether our swing pose steps aside for the model's own animation (Smooth Player Animations' mining swing would hide under our recovery): the wearer is using an item,
     * or a vanilla arm swing began {@link #OWN_SWING_AFTER_MS} or more after ours did ({@code swingTicks} ticks ago at {@code nowMs}), so it is a new action of theirs, mining most often,
     * not the swing the server echoes with ours.
     */
    static boolean yields(boolean using, boolean swinging, long nowMs, int swingTicks, long ourStartMs) {
        return using || swinging && nowMs - swingTicks * 50L >= ourStartMs + OWN_SWING_AFTER_MS;
    }

    /** Start of {@code setupAnim}: vanilla's own arm swing (and the body twist that goes with it) would fight the pose, so it plays nothing while the pose is on. */
    public static void before(HumanoidModel<?> model, LivingEntity e) {
        if (posed(e) != null) model.attackTime = 0f;
    }

    /** What {@link #apply} last posed and the part values it left, so an animator that runs later can be seen overwriting them ({@link #afterAnimator}). Render thread only. */
    private record Posed(LivingEntity e, HumanoidModel<?> model, HumanoidArm main, double w, double[] a, double psi, double s, float[] values) {
    }

    private static Posed last;

    /** The parts the pose writes: each arm's rotation and shoulder position (x, z), the chest's yaw and both legs' pitch. */
    private static float[] values(HumanoidModel<?> m) {
        return new float[] {m.rightArm.xRot, m.rightArm.yRot, m.rightArm.zRot, m.rightArm.x, m.rightArm.z, m.leftArm.xRot, m.leftArm.yRot, m.leftArm.zRot, m.leftArm.x, m.leftArm.z,
                m.body.yRot, m.rightLeg.xRot, m.leftLeg.xRot};
    }

    private static boolean changed(float[] now, float[] was, int from, int count) {
        for (int i = from; i < from + count; i++) if (now[i] != was[i]) return true;
        return false;
    }

    /** The weapon arm onto the blade direction {@code a} at weight {@code w}, from whatever pose it has. */
    private static void poseArm(ModelPart arm, double[] a, double w) {
        arm.xRot = (float) (arm.xRot + (a[0] - arm.xRot) * w);
        arm.yRot = (float) (arm.yRot + (a[1] - arm.yRot) * w);
        arm.zRot = (float) (arm.zRot * (1 - w));
    }

    /**
     * Right after {@code setupAnim} (from {@code LivingEntityRendererMixin}, so after every mod that poses the model inside it, such as Player Animation Lib and the animation mods on it):
     * the weapon arm onto the blade, the chest half-way after it and the foot opposite the arm forward ({@code walk} is vanilla's {@code limbSwingAmount}).
     */
    public static void apply(HumanoidModel<?> model, LivingEntity e, float walk) {
        last = null;
        ClientSwings.View v = posed(e);
        if (v == null) return;
        try {
            double w = weight(v.clip(), v.time());
            double[] a = armAngles(bodyDirection(axis(v.clip(), v.time()), v.yaw(), v.pitch(), bodyYaw(e)));
            HumanoidArm main = e.getMainArm();
            poseArm(main == HumanoidArm.LEFT ? model.leftArm : model.rightArm, a, w);
            boolean twisting = twists(e), stepping = steps(e);
            double psi = twisting ? twist(a[1], w) : 0;
            if (twisting) turnTorso(model, psi, main);
            double s = stepping ? stepAmount(step(v.clip(), v.time()), walk) : 0;
            if (s > 0) stepLegs(model, main, s);
            last = new Posed(e, model, main, w, a, psi, s, values(model));
            PoseCopy.overlays(model); // the skin's sleeves, jacket and pants follow (vanilla copied them inside setupAnim, before this pose)
            if (!Long.valueOf(v.startedAt()).equals(LOGGED.put(e.getId(), v.startedAt()))) {
                Diag.event("tp_pose", "entity", e.getId(), "clip", v.clip().id(), "weight", w, "armXDeg", Math.toDegrees(a[0]), "armYDeg", Math.toDegrees(a[1]), "bodyYaw", bodyYaw(e), "swingYaw", v.yaw(),
                        "twistDeg", twisting ? Math.toDegrees(twist(a[1], 1)) : 0.0, "step", stepping ? stepAmount(1, walk) : 0.0);
            }
        } catch (RuntimeException ex) {
            fail(ex);
        }
    }

    /**
     * An animator that poses the model while it is drawn (Entity Model Features, for Fresh Animations' player pack) has just run for {@code entity}: every part it overwrote gets this
     * frame's pose again on top of its values, so the swing blends from that mod's pose as it does from vanilla's. Parts it left alone already carry the pose and are not touched twice.
     */
    static void afterAnimator(Object entity) {
        Posed p = last;
        if (p == null || p.e() != entity || failed) return;
        HumanoidModel<?> m = p.model();
        float[] now = values(m), was = p.values();
        boolean left = p.main() == HumanoidArm.LEFT;
        int mainAt = left ? 5 : 0, offAt = left ? 0 : 5;
        if (changed(now, was, mainAt, 3)) poseArm(left ? m.leftArm : m.rightArm, p.a(), p.w());
        if (p.psi() != 0) {
            float cos = (float) Math.cos(p.psi()), sin = (float) Math.sin(p.psi());
            if (changed(now, was, 10, 1)) m.body.yRot += (float) p.psi();
            if (changed(now, was, 3, 2)) turnShoulder(m.rightArm, m.body, cos, sin);
            if (changed(now, was, 8, 2)) turnShoulder(m.leftArm, m.body, cos, sin);
            if (changed(now, was, offAt, 3)) (left ? m.rightArm : m.leftArm).yRot += (float) p.psi();
        }
        if (p.s() > 0) {
            ModelPart lead = left ? m.rightLeg : m.leftLeg, trail = left ? m.leftLeg : m.rightLeg;
            int leadAt = left ? 11 : 12, trailAt = left ? 12 : 11;
            if (changed(now, was, leadAt, 1)) lead.xRot += WindUp.legLead((float) p.s());
            if (changed(now, was, trailAt, 1)) trail.xRot += WindUp.legTrail((float) p.s());
        }
        last = new Posed(p.e(), m, p.main(), p.w(), p.a(), p.psi(), p.s(), values(m));
        PoseCopy.overlays(m);
    }

    /** A fault anywhere in the third-person pose or blade turn: logged once, and the whole thing stays off until restart. */
    public static void fail(RuntimeException ex) {
        if (!failed) VectorCombat.LOG.error("Third-person swing pose failed, it is off until restart", ex);
        failed = true;
    }
}
