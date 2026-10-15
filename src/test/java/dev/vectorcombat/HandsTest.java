package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.block.model.ItemTransform;
import org.joml.Vector3f;

/**
 * First-person hands: the real player model's arm laid from a shoulder to a grip, the default hand's arm reproduced from its own end points, the arm's orientation,
 * where the hand rests and the raise, the shield's handle and the setting. Run with {@code java -ea}.
 */
public final class HandsTest {
    private static PlayerModel<AbstractClientPlayer> model(boolean slim) {
        return new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64).bakeRoot(), slim);
    }

    /** The shield's handle for a model whose display transform for this hand is {@code t}, through the same steps the item renderer takes. */
    private static Vector3f handle(ItemTransform t, boolean left) {
        PoseStack ps = new PoseStack();
        t.apply(left, ps);
        return Hands.shieldHandle(ps);
    }

    private static void near(Vector3f v, double x, double y, double z, double tol, String what) {
        assert Math.abs(v.x - x) < tol && Math.abs(v.y - y) < tol && Math.abs(v.z - z) < tol : what + ": " + v;
    }

    /** The centres of the two end faces of a drawn arm, its shoulder end and its hand end, found along {@code axis}: each face is four corners seen from three faces. */
    private static Vector3f[] ends(Hands.Corners c, Vector3f axis, String what) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (Vector3f p : c.points) {
            min = Math.min(min, p.dot(axis));
            max = Math.max(max, p.dot(axis));
        }
        Vector3f top = new Vector3f(), bottom = new Vector3f();
        int tops = 0, bottoms = 0;
        for (Vector3f p : c.points) {
            float t = p.dot(axis);
            if (t < min + 1e-3) {
                top.add(p);
                tops++;
            } else if (t > max - 1e-3) {
                bottom.add(p);
                bottoms++;
            }
        }
        assert tops == 12 && bottoms == 12 : what + ": " + tops + " and " + bottoms + " corners on the end faces";
        return new Vector3f[] {top.div(tops), bottom.div(bottoms)};
    }

    /** The arm laid from {@code shoulder} to {@code hand}: its top face centre is on the shoulder, its bottom face centre on the hand, and it is stretched only along its length and slimmed to {@code thick}. */
    private static void lay(PlayerModel<AbstractClientPlayer> model, boolean left, boolean slim, Vector3f shoulder, Vector3f hand, float thick) {
        ModelPart arm = left ? model.leftArm : model.rightArm;
        arm.resetPose();
        PoseStack ps = new PoseStack();
        Hands.place(ps, shoulder, hand, Hands.top(arm, left, slim), left, thick);
        Hands.Corners c = new Hands.Corners();
        arm.render(ps, c, 0, 0);
        assert c.points.size() == 24 : "six faces of four corners, got " + c.points.size();

        String what = (left ? "left" : "right") + (slim ? " slim" : " wide") + " arm of thickness " + thick + " from " + shoulder + " to " + hand;
        Vector3f d = hand.sub(shoulder, new Vector3f());
        float len = d.length();
        d.div(len);
        for (Vector3f p : c.points) {
            float t = p.sub(shoulder, new Vector3f()).dot(d);
            assert t > -1e-4 && t < len + 1e-4 : what + ": a corner lies off the segment, " + t + " of " + len;
        }
        Vector3f[] e = ends(c, d, what);
        near(e[0], shoulder.x, shoulder.y, shoulder.z, 1e-4, what + ": top face centre on the shoulder");
        near(e[1], hand.x, hand.y, hand.z, 1e-4, what + ": bottom face centre on the hand");

        // The cross-section is the part's own (4 by 4 px, 3 by 4 slim) times the thickness: the stretch is along the length only.
        Vector3f ex = Hands.aim(new Vector3f(d), left).transform(new Vector3f(1, 0, 0)), ez = Hands.aim(new Vector3f(d), left).transform(new Vector3f(0, 0, 1));
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3f p : c.points) {
            minX = Math.min(minX, p.dot(ex));
            maxX = Math.max(maxX, p.dot(ex));
            minZ = Math.min(minZ, p.dot(ez));
            maxZ = Math.max(maxZ, p.dot(ez));
        }
        assert Math.abs(maxX - minX - (slim ? 3 : 4) / 16f * thick) < 1e-4 && Math.abs(maxZ - minZ - 4 / 16f * thick) < 1e-4 : what + ": width " + (maxX - minX) + " depth " + (maxZ - minZ);
    }

    /** The farthest any point of {@code a} is from its nearest point of {@code b}. */
    private static float apart(List<Vector3f> a, List<Vector3f> b) {
        float worst = 0;
        for (Vector3f p : a) {
            float best = Float.MAX_VALUE;
            for (Vector3f q : b) best = Math.min(best, p.distance(q));
            worst = Math.max(worst, best);
        }
        return worst;
    }

    public static void main(String[] args) {
        // Both arms of both skins, from the shoulder to the rest grip, a raised hand, a lowered one, a near one, and a hand straight above and straight below the shoulder, at full
        // thickness, the default 0.75 and the slimmest the arm gets (0.75 of NEAR).
        float[][] grips = {{0.56f, -0.52f, -0.72f}, {0.2f, 0.15f, -0.8f}, {0.45f, -1.1f, -0.5f}, {0.35f, -0.4f, -0.5f}, {0.3f, 0.5f, -0.1f}, {0.3f, -1.2f, -0.1f}};
        for (boolean slim : new boolean[] {false, true}) {
            PlayerModel<AbstractClientPlayer> model = model(slim);
            for (boolean left : new boolean[] {false, true}) {
                Vector3f shoulder = new Vector3f(left ? -0.3f : 0.3f, -0.4f, -0.1f);
                for (float thick : new float[] {1f, 0.75f, 0.75f * Hands.NEAR}) {
                    for (float[] g : grips) lay(model, left, slim, shoulder, new Vector3f(left ? -g[0] : g[0], g[1], g[2]), thick);
                }
            }
        }

        // The default hand reproduced from its own end points: lay an arm from the default arm's shoulder end to its fist and every corner lands on the vanilla arm's, for both
        // arms of both skins. That is the orientation (the turns of renderPlayerArm and the idle bob) the arm has at rest, twist included.
        for (boolean slim : new boolean[] {false, true}) {
            PlayerModel<AbstractClientPlayer> model = model(slim);
            for (boolean left : new boolean[] {false, true}) {
                String what = (left ? "left" : "right") + (slim ? " slim" : " wide") + " default hand";
                Hands.Corners v = Hands.defaultHand(model, left);
                Vector3f[] e = ends(v, Hands.restAxis(left), what);
                assert Math.abs(e[0].distance(e[1]) - Hands.ARM) < 1e-4 : what + " is 12 px long, " + e[0].distance(e[1]);
                Vector3f axis = e[1].sub(e[0], new Vector3f()).normalize();
                near(axis, Hands.restAxis(left).x, Hands.restAxis(left).y, Hands.restAxis(left).z, 1e-4, what + ": its axis is restAxis");
                ModelPart arm = left ? model.leftArm : model.rightArm;
                arm.resetPose();
                PoseStack ps = new PoseStack();
                Hands.place(ps, e[0], e[1], Hands.top(arm, left, slim), left, 1f);
                Hands.Corners mine = new Hands.Corners();
                arm.render(ps, mine, 0, 0);
                float worst = Math.max(apart(v.points, mine.points), apart(mine.points, v.points));
                assert worst < 3e-4 : what + " is reproduced to 3e-4, worst " + worst;
            }
        }
        near(Hands.restAxis(false), 0.3687, 0.5407, -0.7561, 1e-3, "the default right arm points forward and up");
        near(Hands.restAxis(true), -0.3687, 0.5407, -0.7561, 1e-3, "the left mirrors it");

        // aim: the default orientation turned the shortest way; the arm's length axis lands on the direction and the frame stays orthonormal, straight up and down included.
        for (float[] d : new float[][] {{0.3687f, 0.5407f, -0.7561f}, {0.38f, -0.18f, -0.91f}, {0, 0, -1}, {-1, 0, 0}, {0.3f, 0.9f, -0.3f}, {0, 1, 0}, {0, -1, 0}, {-0.3687f, -0.5407f, 0.7561f}}) {
            Vector3f dir = new Vector3f(d).normalize();
            for (boolean left : new boolean[] {false, true}) {
                var q = Hands.aim(new Vector3f(dir), left);
                near(q.transform(new Vector3f(0, 1, 0)), dir.x, dir.y, dir.z, 1e-4, "the arm's length axis points along the segment");
                Vector3f side = q.transform(new Vector3f(1, 0, 0)), depth = q.transform(new Vector3f(0, 0, 1));
                assert Math.abs(side.length() - 1) < 1e-4 && Math.abs(depth.length() - 1) < 1e-4 && Math.abs(side.dot(dir)) < 1e-4 && Math.abs(side.dot(depth)) < 1e-4 : "an orthonormal frame for " + dir;
            }
        }
        var atRest = Hands.aim(Hands.restAxis(false), false);
        near(atRest.transform(new Vector3f(1, 0, 0)), -0.3328, -0.6827, -0.6505, 1e-3, "along its own axis the arm keeps the default hand's twist");

        // Where the hand rests: the rest grip is the hand origin raised, moved a fifth of that toward the middle and dropped; the shoulder is where the default arm would start, so the
        // arm from it to the rest grip is the default arm's, 12 px long, whatever the settings.
        near(Hands.restGrip(false, 0.2, 0.12), 0.52, -0.4328, -0.7610, 1e-3, "the right rest grip: raised, moved in, and 0.12 down the handle");
        near(Hands.restGrip(true, 0.2, 0.12), -0.52, -0.4328, -0.7610, 1e-3, "the left rest grip mirrors it");
        near(Hands.restGrip(false, 0, 0), 0.56, -0.52, -0.72, 1e-5, "raise 0 and grip 0 are vanilla's hand origin");
        near(Hands.shoulder(false, 0.2, 0.12), 0.2435, -0.8383, -0.1939, 1e-3, "the right shoulder, under the screen");

        // The handle's direction in the hand frame is the sprite diagonal through vanilla's handheld first-person display transform (0, -90, 25), the same for both hands.
        for (boolean left : new boolean[] {false, true}) {
            PoseStack ps = new PoseStack();
            new ItemTransform(new Vector3f(0, left ? 90 : -90, left ? -25 : 25), new Vector3f(1.13f, 3.2f, 1.13f).mul(1 / 16f), new Vector3f(0.68f, 0.68f, 0.68f)).apply(left, ps);
            Vector3f axis = ps.last().pose().transformDirection(new Vector3f(1, 1, 0).normalize()).normalize();
            near(axis, Hands.ITEM_AXIS.x, Hands.ITEM_AXIS.y, Hands.ITEM_AXIS.z, 2e-3, "up the handle, " + (left ? "left" : "right") + " hand");
        }
        for (double[] s : new double[][] {{0.2, 0.12}, {0, 0}, {0.4, 0.3}, {0.1, 0.05}}) {
            for (boolean left : new boolean[] {false, true}) {
                Vector3f arm = Hands.restGrip(left, s[0], s[1]).sub(Hands.shoulder(left, s[0], s[1]), new Vector3f());
                assert Math.abs(arm.length() - Hands.ARM) < 1e-5 : "the rest arm is 12 px long";
                near(arm.normalize(), Hands.restAxis(left).x, Hands.restAxis(left).y, Hands.restAxis(left).z, 1e-5, "and points the default arm's way");
            }
        }

        // The arm's length is clamped: a hand too near the shoulder is pushed out to 0.65, one too far pulled in to 0.95, one in between left alone, always on the same line.
        Vector3f grip = new Vector3f(0.5f, -0.3f, -0.7f);
        for (float distance : new float[] {0.3f, 0.65f, 0.8f, 0.95f, 1.5f}) {
            Vector3f dir = new Vector3f(0.2f, -0.9f, 0.3f).normalize();
            Vector3f shoulder = dir.mul(distance, new Vector3f()).add(grip);
            Vector3f kept = Hands.anchor(shoulder, grip, Hands.MIN_ARM, Hands.MAX_ARM);
            float expect = Math.max(Hands.MIN_ARM, Math.min(Hands.MAX_ARM, distance));
            assert Math.abs(kept.distance(grip) - expect) < 1e-5 : "an arm of " + distance + " becomes " + expect + ", got " + kept.distance(grip);
            near(kept.sub(grip, new Vector3f()).normalize(), dir.x, dir.y, dir.z, 1e-5, "on the same line");
        }

        // The raise fades out over the first 0.05 s of a clip and back in over the last 0.12 s, and is gone for the whole strike (which starts after 0.05 s and ends before the recovery).
        assert Hands.raiseKeep(0, 0.4) == 1 && Math.abs(Hands.raiseKeep(0.025, 0.4) - 0.5) < 1e-9 : "all of it at the start, half at half the fade";
        for (double t : new double[] {0.05, 0.1, 0.15, 0.19, 0.28}) assert Hands.raiseKeep(t, 0.4) < 1e-9 : "none of it at " + t + " s of a 0.4 s clip";
        assert Math.abs(Hands.raiseKeep(0.34, 0.4) - 0.5) < 1e-9 && Hands.raiseKeep(0.4, 0.4) == 1 && Hands.raiseKeep(0.5, 0.4) == 1 : "and back in over the last 0.12 s";
        for (double t = 0; t <= 0.4; t += 0.01) assert Hands.raiseKeep(t, 0.4) >= 0 && Hands.raiseKeep(t, 0.4) <= 1 : "within 0..1";
        for (double t = 0; t <= 0.1; t += 0.01) assert Hands.raiseKeep(t, 0.1) >= 0 && Hands.raiseKeep(t, 0.1) <= 1 : "a clip shorter than both fades stays within 0..1";

        // The arm's thickness: the setting at rest, in proportion to how near the hand comes, never below NEAR of it, and no thicker for a hand farther out than at rest.
        double restDepth = -Hands.restGrip(false, 0.2, 0.12).z;
        assert Math.abs(Hands.thick(0.75, restDepth, restDepth) - 0.75) < 1e-6 && Math.abs(Hands.thick(1, restDepth, restDepth) - 1) < 1e-6 : "the setting at the rest depth";
        assert Math.abs(Hands.thick(0.75, restDepth * 2, restDepth) - 0.75) < 1e-6 : "a hand farther out is not thickened";
        assert Math.abs(Hands.thick(0.75, restDepth * 0.85, restDepth) - 0.75 * 0.85) < 1e-6 : "a nearer hand is slimmer in proportion";
        assert Math.abs(Hands.thick(0.75, 0.05, restDepth) - 0.75 * Hands.NEAR) < 1e-6 : "down to NEAR of the setting";
        float slimmer = 0;
        for (double depth = 0.05; depth < 2; depth += 0.01) {
            float k = Hands.thick(0.75, depth, restDepth);
            assert k >= slimmer - 1e-6f && k <= 0.75f + 1e-6f : "the nearer the hand the slimmer, never above the setting: " + k + " at " + depth;
            slimmer = k;
        }

        // What the fitted thickness is for: at rest the arm's width on the bottom edge of the screen, against the default hand's (the same arm part drawn by renderPlayerArm, farther
        // out). At full size the arm is about 29% wider than the default hand (slim) or 24% (wide); the fit, solved per skin type from the real arm part, brings each to the default
        // hand's width, a different thickness for each (the wide arm is a third thicker to begin with), and stays within a sensible range.
        StringBuilder widths = new StringBuilder();
        for (boolean slim : new boolean[] {false, true}) {
            PlayerModel<AbstractClientPlayer> model = model(slim);
            double target = Hands.bottomEdgeWidth(Hands.defaultHand(model, false).points);
            assert target > 0.12 && target < 0.22 : "the default hand spans 16 to 20% of the screen on its bottom edge (measured 317 px of 1920): " + target;
            float fitted = Hands.fit(model, slim);
            assert fitted >= 0.6f && fitted < 0.95f : (slim ? "slim" : "wide") + " fits at " + fitted;
            for (float thick : new float[] {1f, fitted}) {
                double ratio = Hands.bottomEdgeWidth(Hands.restArm(model, slim, thick).points) / target;
                String what = (slim ? "slim" : "wide") + " arm of thickness " + thick + " against the default hand: " + ratio;
                if (thick == 1f) assert ratio > 1.15 : "full size is wider than the default hand, " + what;
                else assert Math.abs(ratio - 1) < 0.02 : "the fitted arm is as wide as the default hand, " + what;
                widths.append(String.format(" %s %.3f=%.3f", slim ? "slim" : "wide", thick, ratio));
            }
        }
        assert Hands.fit(model(true), true) != Hands.fit(model(false), false) : "each skin type has its own thickness";
        assert Hands.fit(model(true), true) < Hands.fit(model(false), false) + 0.1f : "and they are of the same order";

        // The shield's handle: the display transform of each model, the item renderer's centring and the shield renderer's flip. The left hand's blocking model puts it about
        // 0.26 right of, 0.36 below and 0.09 in front of the hand frame's origin, the idle one 0.05, 0.57 below and 0.16 in front: raising lifts the handle 0.2 and moves it 0.2 inward.
        ItemTransform blocking = new ItemTransform(new Vector3f(0, 180, -5), new Vector3f(5, 5, -11).mul(1 / 16f), new Vector3f(1.25f, 1.25f, 1.25f));
        ItemTransform idle = new ItemTransform(new Vector3f(0, 180, 5), new Vector3f(10, 0, -10).mul(1 / 16f), new Vector3f(1.25f, 1.25f, 1.25f));
        Vector3f up = handle(blocking, true), down = handle(idle, true);
        near(up, 0.256, -0.365, 0.094, 0.01, "the left hand's blocking shield handle");
        near(down, 0.052, -0.568, 0.156, 0.01, "the left hand's idle shield handle");
        Vector3f lifted = up.sub(down, new Vector3f());
        assert Math.abs(lifted.y - 0.2) < 0.02 && Math.abs(lifted.x - 0.2) < 0.02 : "raising the shield lifts its handle and brings it in: " + lifted;
        // The right hand's numbers in the model JSON are not the left's mirrored: blocking about 0.26 left of the origin, 0.26 below, 0.09 in front; idle 0.06 left, 0.55 below, 0.16 in front.
        near(handle(new ItemTransform(new Vector3f(0, 180, -5), new Vector3f(-15, 5, -11).mul(1 / 16f), new Vector3f(1.25f, 1.25f, 1.25f)), false), -0.260, -0.256, 0.094, 0.01, "the right hand's blocking shield handle");
        near(handle(new ItemTransform(new Vector3f(0, 180, 5), new Vector3f(-10, 2, -10).mul(1 / 16f), new Vector3f(1.25f, 1.25f, 1.25f)), false), -0.057, -0.552, 0.156, 0.01, "the right hand's idle shield handle");

        // The setting: off by default; on draws the hands unless a mod that already draws them is installed.
        assert Hands.blocker(id -> false) == null && "punchy".equals(Hands.blocker(id -> id.equals("punchy") || id.equals("firstperson"))) : "the first installed hands mod is named";
        for (String id : Hands.OTHERS) assert id.equals(Hands.blocker(id::equals)) : id + " keeps the hands off";
        assert Hands.on(true, null) && !Hands.on(true, "punchy") : "on draws the hands unless another mod does";
        assert !Hands.on(false, null) && !Hands.on(false, "punchy") : "off never does";
        System.out.println("HandsTest ok (arm width on the bottom edge against the default hand's:" + widths + ")");
    }
}
