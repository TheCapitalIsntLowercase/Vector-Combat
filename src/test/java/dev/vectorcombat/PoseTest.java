package dev.vectorcombat;

import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The third-person swing: the arm solved along the blade axis, the body frame, the weight over a clip, the chest's twist, the leg step and the trident's turn against real baked models, the registry of swings and the swing message. Run with {@code java -ea}. */
public final class PoseTest {
    private static Anim clip(String trail) {
        String json = "{\"id\": \"x\", \"length\": 0.5, \"impact\": 0.18, \"hand\": [{\"t\": 0, \"pos\": [0, 0, 0], \"rot\": [0, 0, 0]}]" + (trail == null ? "" : ", \"trail\": " + trail) + "}";
        return Anim.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static void near(double[] v, double x, double y, double z, String what) {
        assert Math.abs(v[0] - x) < 1e-6 && Math.abs(v[1] - y) < 1e-6 && Math.abs(v[2] - z) < 1e-6 : what + ": " + java.util.Arrays.toString(v);
    }

    private static void near(Vector3f v, double x, double y, double z, String what) {
        assert Math.abs(v.x - x) < 1e-4 && Math.abs(v.y - y) < 1e-4 && Math.abs(v.z - z) < 1e-4 : what + ": " + v;
    }

    private static PlayerModel<AbstractClientPlayer> player(boolean slim) {
        return new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, slim), 64, 64).bakeRoot(), slim);
    }

    /** What the entity renderer does before it draws a model turned to body yaw {@code yaw}: the turn, the flip, the player scale and the lift (world: x east, y up, z south; at yaw 0 it faces +z, its right is -x). */
    private static PoseStack entityStack(double yaw) {
        PoseStack ps = new PoseStack();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - (float) yaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.scale(0.9375F, 0.9375F, 0.9375F);
        ps.translate(0.0F, -1.501F, 0.0F);
        return ps;
    }

    /** A point given in the stack's current frame in model pixels, as a world position in blocks. */
    private static Vector3f world(PoseStack ps, float x, float y, float z) {
        return ps.last().pose().transformPosition(new Vector3f(x / 16f, y / 16f, z / 16f));
    }

    /** The world direction an arm points along (model +y runs from the shoulder to the hand). */
    private static Vector3f armAxis(PlayerModel<?> m, HumanoidArm arm, double yaw) {
        PoseStack ps = entityStack(yaw);
        m.translateToHand(arm, ps);
        return Blade.rotationOf(ps.last().pose()).transform(new Vector3f(0, 1, 0));
    }

    private static double angle(Vector3f a, Vector3f b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, new Vector3f(a).normalize().dot(new Vector3f(b).normalize())))));
    }

    /** The third-person polish: the chest turns half-way toward the blade, the shoulders go round the spine as vanilla's own attack twist puts them, and the foot opposite the arm steps forward. */
    private static void torsoAndLegs() {
        assert Math.abs(ThirdPerson.twist(Math.toRadians(30), 1) - Math.toRadians(15)) < 1e-9 && Math.abs(ThirdPerson.twist(Math.toRadians(-60), 1) + Math.toRadians(30)) < 1e-9 : "half of the arm's yaw, to either side";
        assert Math.abs(ThirdPerson.twist(Math.toRadians(30), 0.5) - Math.toRadians(7.5)) < 1e-9 && ThirdPerson.twist(Math.toRadians(80), 0) == 0 : "with the swing's weight, nothing at rest";
        assert Math.abs(ThirdPerson.twist(Math.toRadians(120), 1) - Math.PI / 4) < 1e-9 && Math.abs(ThirdPerson.twist(Math.toRadians(-170), 1) + Math.PI / 4) < 1e-9 : "capped at a quarter turn";
        Anim c = clip("{\"kind\": \"h\", \"from\": [35, 15], \"to\": [-52, 15], \"start\": 0.05, \"end\": 0.18}");
        assert ThirdPerson.step(c, 0) == 0 && ThirdPerson.step(c, 0.05) == 0 : "no step until the strike begins";
        assert Math.abs(ThirdPerson.step(c, 0.115) - 0.5) < 1e-9 && ThirdPerson.step(c, 0.18) == 1 && ThirdPerson.step(c, 0.22) == 1 : "half way through the strike, whole from the impact to the end of the hold";
        assert Math.abs(ThirdPerson.step(c, 0.36) - 0.5) < 1e-9 && ThirdPerson.step(c, 0.5) == 0 && ThirdPerson.step(clip(null), 0.1) == 0 : "released over the recovery; a clip without a trail never steps";
        assert ThirdPerson.stepAmount(1, 0) == 1 && ThirdPerson.stepAmount(1, 1) == 0 && ThirdPerson.stepAmount(1, 3) == 0 && ThirdPerson.stepAmount(1, -1) == 1 && Math.abs(ThirdPerson.stepAmount(0.5, 0.25) - 0.375) < 1e-12
                : "legs already walking keep their own stride";

        double psi = Math.toRadians(23);
        for (boolean slim : new boolean[] {false, true}) {
            for (HumanoidArm main : HumanoidArm.values()) {
                PlayerModel<AbstractClientPlayer> m = player(slim);
                boolean left = main == HumanoidArm.LEFT;
                ModelPart swinging = left ? m.leftArm : m.rightArm, off = left ? m.rightArm : m.leftArm;
                double[] a = ThirdPerson.armAngles(new double[] {0.5, 0.3, 0.8}); // aim the swinging arm as apply does, then turn the chest
                swinging.xRot = (float) a[0];
                swinging.yRot = (float) a[1];
                float headYaw = m.head.yRot, offYaw = off.yRot;
                Vector3f aimBefore = armAxis(m, main, 0);
                PoseStack rest = entityStack(0);
                m.rightArm.translateAndRotate(rest);
                Vector3f shoulderBefore = world(rest, 0, 0, 0);
                ThirdPerson.turnTorso(m, psi, main);
                assert armAxis(m, main, 0).distance(aimBefore) < 1e-5 : "turning the chest does not move the swinging arm's aim (the arm is the model's child, not the torso's)";
                assert Math.abs(m.body.yRot - psi) < 1e-6 && m.head.yRot == headYaw && Math.abs(swinging.yRot - a[1]) < 1e-6 : "the chest turns; the head and the swinging arm keep their angles";
                assert Math.abs(off.yRot - (offYaw + psi)) < 1e-6 : "the other arm turns with the chest";
                assert Math.abs(m.rightArm.x + 5 * Math.cos(psi)) < 1e-4 && Math.abs(m.rightArm.z - 5 * Math.sin(psi)) < 1e-4 && Math.abs(m.leftArm.x - 5 * Math.cos(psi)) < 1e-4
                        && Math.abs(m.leftArm.z + 5 * Math.sin(psi)) < 1e-4 : "the shoulders go where vanilla's own attack twist (HumanoidModel.setupAttackAnimation) puts them";
                PoseStack body = entityStack(0);
                m.body.translateAndRotate(body);
                Vector3f front = Blade.rotationOf(body.last().pose()).transform(new Vector3f(0, 0, -1)); // the model's front is -z; at yaw 0 it faces +z
                assert Math.abs(front.x + Math.sin(psi)) < 1e-4 && Math.abs(front.z - Math.cos(psi)) < 1e-4 : "a positive twist turns the chest toward the wearer's right (-x): " + front;
                PoseStack after = entityStack(0);
                m.rightArm.translateAndRotate(after);
                Vector3f shoulder = world(after, 0, 0, 0);
                assert Math.abs(shoulder.x - (shoulderBefore.x * Math.cos(psi) - shoulderBefore.z * Math.sin(psi))) < 1e-4 && Math.abs(shoulder.z - (shoulderBefore.x * Math.sin(psi) + shoulderBefore.z * Math.cos(psi))) < 1e-4
                        && shoulder.z < -0.05 : "the right shoulder turns with the chest, as one body, and goes back: " + shoulder;
            }
        }
        for (HumanoidArm main : HumanoidArm.values()) {
            PlayerModel<AbstractClientPlayer> m = player(false);
            boolean left = main == HumanoidArm.LEFT;
            ModelPart lead = left ? m.rightLeg : m.leftLeg, trail = left ? m.leftLeg : m.rightLeg;
            ThirdPerson.stepLegs(m, main, 1);
            assert Math.abs(Math.toDegrees(-lead.xRot) - WindUp.STRIDE) < 1e-3 && Math.abs(trail.xRot + lead.xRot / 2) < 1e-6 : "the lead foot swings forward by a mob's stride, the other back by half of it";
            PoseStack l = entityStack(0), t = entityStack(0);
            lead.translateAndRotate(l);
            trail.translateAndRotate(t);
            double leg = 0.75 * 0.9375; // 12 pixels at the player's scale
            assert Math.abs(world(l, 0, 12, 0).z - leg * Math.sin(Math.toRadians(WindUp.STRIDE))) < 1e-3 && Math.abs(world(t, 0, 12, 0).z + leg * Math.sin(Math.toRadians(WindUp.STRIDE) / 2)) < 1e-3
                    : "the lead foot advances a third of a mob's reach, the other goes back";
            PlayerModel<AbstractClientPlayer> still = player(false);
            ThirdPerson.stepLegs(still, main, 0);
            assert still.leftLeg.xRot == 0 && still.rightLeg.xRot == 0 : "no step, no change";
        }
    }

    /** The trident follows the arm: through the item layer's own chain, vanilla's hold carries the shaft square to the arm and the turn lays it along the arm with the grip still in the fist. */
    @SuppressWarnings("deprecation") // ItemTransform.apply is what the game draws the item with
    private static void trident() {
        Vector3f spin = new Vector3f(0, 60, 0);
        Blade.Frame rest = Blade.tridentRest(spin, false), mirrored = Blade.tridentRest(spin, true);
        near(rest.b(), 0, 1, 0, "the shaft runs up the item's y axis, a turn about y leaves it there");
        assert Math.abs(rest.n().dot(new Vector3f(0, 0, 1)) - Math.cos(Math.toRadians(60))) < 1e-5 && Math.abs(rest.n().dot(mirrored.n()) - Math.cos(Math.toRadians(120))) < 1e-5 : "the prongs' normal turns by the display rotation, mirrored for the left hand";
        for (HumanoidArm arm : HumanoidArm.values()) {
            boolean left = arm == HumanoidArm.LEFT;
            // item/trident_in_hand's third-person display transforms in the game's files
            ItemTransform tr = new ItemTransform(new Vector3f(0, 60, 0), (left ? new Vector3f(3, 17, 12) : new Vector3f(11, 17, -2)).mul(1 / 16f), new Vector3f(1, 1, 1));
            for (int bodyYaw : new int[] {0, 37, -120}) {
                for (int az = -60; az <= 60; az += 60) {
                    for (int el = -20; el <= 60; el += 40) {
                        PlayerModel<AbstractClientPlayer> m = player(false);
                        double[] body = ThirdPerson.bodyDirection(Anim.Trail.unit(az, el), bodyYaw + 15, 10, bodyYaw);
                        double[] a = ThirdPerson.armAngles(body);
                        ModelPart part = left ? m.leftArm : m.rightArm;
                        part.xRot = (float) a[0];
                        part.yRot = (float) a[1];
                        part.zRot = 0;
                        Vector3f arm3 = armAxis(m, arm, bodyYaw);
                        for (boolean turned : new boolean[] {false, true}) {
                            PoseStack ps = entityStack(bodyYaw);
                            m.translateToHand(arm, ps);
                            ps.mulPose(Axis.XP.rotationDegrees(-90.0F));
                            ps.mulPose(Axis.YP.rotationDegrees(180.0F));
                            ps.translate((left ? -1 : 1) / 16.0F, 0.125F, -0.625F);
                            Vector3f hand = ps.last().pose().transformPosition(new Vector3f());
                            if (turned) {
                                Vector3f travel = new Vector3f(az > 0 ? -1 : 1, 0, 0);
                                Blade.Frame want = Blade.fromArm(new Vector3f((float) body[0], (float) body[1], (float) body[2]), travel);
                                ps.mulPose(Blade.solve(want, travel, bodyYaw, ps.last().pose(), Blade.tridentRest(tr.rotation, left)));
                            }
                            tr.apply(left, ps);
                            ps.translate(-0.5F, -0.5F, -0.5F);
                            ps.scale(1.0F, -1.0F, -1.0F); // the trident renderer's flip: model pixels, y down
                            Vector3f shaft = Blade.rotationOf(ps.last().pose()).transform(new Vector3f(0, -1, 0)); // hilt to tip
                            String where = arm + " yaw " + bodyYaw + " az " + az + " el " + el;
                            if (turned) {
                                assert angle(shaft, arm3) < 0.05 : "the turned shaft lies along the arm (" + angle(shaft, arm3) + " degrees off) " + where;
                            } else {
                                assert Math.abs(angle(shaft, arm3) - 90) < 0.1 : "vanilla's hold carries the shaft square to the arm (" + angle(shaft, arm3) + ") " + where;
                            }
                            Vector3f p0 = world(ps, 0, 9, 0), p1 = world(ps, 0, 20, 0);
                            Vector3f u = new Vector3f(p1).sub(p0).normalize();
                            double fist = new Vector3f(hand).sub(p0).cross(u).length();
                            assert fist < 0.07 : "the shaft passes " + fist + " blocks from the hand frame " + where + (turned ? " (turned)" : "");
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        // armAngles undoes armDir: every direction a swing points an arm along comes back as the angles that make it.
        for (int az = -180; az < 180; az += 15) {
            for (int el = -85; el <= 85; el += 17) {
                double[] d = Anim.Trail.unit(az, el);
                double[] body = {d[0], d[1], -d[2]}; // view space has z toward the viewer; the body frame has z forward
                double[] a = ThirdPerson.armAngles(body);
                near(ThirdPerson.armDir(a[0], a[1], 0), body[0], body[1], body[2], "armDir(armAngles(d)) at azimuth " + az + " elevation " + el);
                assert a[0] <= 1e-9 && a[0] >= -Math.PI - 1e-9 : "the arm swings forward and up (x from 0 to -180 degrees), never behind";
            }
        }
        double[] level = ThirdPerson.armAngles(new double[] {0, 0, 1});
        assert Math.abs(level[0] + Math.PI / 2) < 1e-9 && Math.abs(level[1]) < 1e-9 : "forward and level is x = -90 degrees";
        assert Math.abs(ThirdPerson.armAngles(new double[] {0, 1, 0})[0] + Math.PI) < 1e-9 : "straight up is x = -180 degrees";
        assert Math.abs(ThirdPerson.armAngles(new double[] {0, -1, 0})[0]) < 1e-9 : "straight down is x = 0 (hanging)";
        double[] right = ThirdPerson.armAngles(new double[] {1, 0, 0});
        assert Math.abs(right[1] - Math.PI / 2) < 1e-9 : "a positive yaw turns the arm toward the wearer's right";
        assert ThirdPerson.armAngles(new double[] {-1, 0, 1})[1] < 0 : "and a negative one to the left";

        // The body frame: the swing's own look maps view space onto it, and a body that has turned since sees the same world direction rotated.
        double[] ahead = {0, 0, -1}; // view space: straight ahead
        near(ThirdPerson.bodyDirection(ahead, 40, 0, 40), 0, 0, 1, "ahead of the swing is ahead of a body facing the same way");
        near(ThirdPerson.bodyDirection(Anim.Trail.unit(30, 0), 0, 0, 0), 0.5, 0, Math.cos(Math.toRadians(30)), "30 degrees right of the swing's look");
        near(ThirdPerson.bodyDirection(ahead, 0, 0, 30), -Math.sin(Math.toRadians(30)), 0, Math.cos(Math.toRadians(30)), "the body has turned 30 degrees right since: the swing is now to its left");
        near(ThirdPerson.bodyDirection(ahead, 0, 45, 0), 0, -Math.sin(Math.toRadians(45)), Math.cos(Math.toRadians(45)), "a swing begun looking 45 degrees down points down");

        // The weight over a clip: nothing before it, in over the windup, full through the strike and the hold, out over the recovery, nothing after.
        Anim c = clip("{\"kind\": \"h\", \"from\": [35, 15], \"to\": [-52, 15], \"start\": 0.05, \"end\": 0.18}");
        assert ThirdPerson.weight(c, 0) == 0 && ThirdPerson.weight(c, -1) == 0 : "no pose before the swing";
        assert Math.abs(ThirdPerson.weight(c, 0.025) - 0.5) < 1e-9 && ThirdPerson.weight(c, 0.05) == 1 : "in over the windup";
        assert ThirdPerson.weight(c, 0.18) == 1 && ThirdPerson.weight(c, 0.22) == 1 : "full through the strike and the hold";
        assert Math.abs(ThirdPerson.weight(c, 0.36) - 0.5) < 1e-9 && ThirdPerson.weight(c, 0.5) == 0 && ThirdPerson.weight(c, 2) == 0 : "out over the recovery, nothing after";
        assert ThirdPerson.weight(clip(null), 0.1) == 0 : "a clip without a trail has no blade path to pose along";
        near(ThirdPerson.axis(c, 0), Anim.Trail.unit(35, 15)[0], Anim.Trail.unit(35, 15)[1], Anim.Trail.unit(35, 15)[2], "before the strike the arm is on the path's start");
        near(ThirdPerson.axis(c, 9), Anim.Trail.unit(-52, 15)[0], Anim.Trail.unit(-52, 15)[1], Anim.Trail.unit(-52, 15)[2], "after it, on the end");

        // The blade turn: with the layer frame equal to the world and a body facing +z, a blade wanted straight ahead comes out pointing ahead whatever its rest orientation.
        Blade.Frame rest = Blade.rest(new Vector3f(0, -90, 55), new Vector3f(0.85f, 0.85f, 0.85f), false); // a handheld sword's third-person display transform
        Blade.Frame want = Blade.fromArm(new Vector3f(0, 0, 1), new Vector3f(0, 0, 0));
        Quaternionf q = Blade.solve(want, new Vector3f(), 0, new Matrix4f(), rest);
        near(q.transform(new Vector3f(rest.b())), 0, 0, 1, "the rest blade is laid along the wanted axis");
        Blade.Frame sweep = Blade.fromArm(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0)); // swinging toward +x: the flat contains the travel, so the normal is perpendicular to it
        assert Math.abs(sweep.n().dot(new Vector3f(1, 0, 0))) < 1e-6 && Math.abs(sweep.n().dot(sweep.b())) < 1e-6 : "the flat faces are turned away from the direction of travel";
        Quaternionf same = Blade.between(rest, rest);
        near(same.transform(new Vector3f(1, 2, 3)), 1, 2, 3, "no rotation between a frame and itself");
        Matrix4f scaled = new Matrix4f().scale(0.9375f).rotateY((float) Math.toRadians(90));
        assert Math.abs(Blade.rotationOf(scaled).determinant() - 1) < 1e-5 : "the pose's uniform scale is taken out, leaving a pure rotation";

        // ---- the first-person fit: one turn about the hand carries a model's blade onto the sword's ----
        // First-person display rotations and scales of real models and how far their blade axis is from the sword's, measured offline from the model files of the game, Basic Weapons and Jaam's.
        Object[][] models = {
                {"the sword", new Vector3f(0, -90, 25), new Vector3f(0.68f, 0.68f, 0.68f), 0.0},
                {"a Jaam's greatsword (a 32 pixel sprite on the sword's transform)", new Vector3f(0, -90, 25), new Vector3f(0.68f, 0.68f, 0.68f), 0.0},
                {"Basic Weapons' spear", new Vector3f(0, 90, 0), new Vector3f(1.75f, 1.75f, 0.85f), 65.0},
                {"Basic Weapons' quarterstaff", new Vector3f(0, 90, 0), new Vector3f(1.4f, 1.4f, 0.75f), 65.0},
                {"Basic Weapons' glaive", new Vector3f(-60, -90, 0), new Vector3f(1.75f, 1.75f, 0.85f), 35.0},
                {"Jaam's katar", new Vector3f(290, -90, 25), new Vector3f(0.68f, 0.68f, 0.68f), 70.0},
                {"Jaam's claw", new Vector3f(290, -90, 25), new Vector3f(0.75f, 0.75f, 0.75f), 70.0},
                {"Jaam's knuckle", new Vector3f(0, -90, 85), new Vector3f(0.68f, 0.68f, 0.68f), 60.0},
                {"Jaam's reverse dagger", new Vector3f(40, 90, 203), new Vector3f(0.68f, 0.68f, 0.68f), 178.0}};
        for (Object[] m : models) {
            Blade.Frame item = Blade.rest((Vector3f) m[1], (Vector3f) m[2], false);
            Quaternionf turn = Fit.turn(item);
            assert Math.abs(Fit.degrees(turn) - (Double) m[3]) < 0.1 : m[0] + " is " + Fit.degrees(turn) + " degrees off the sword's blade axis, not " + m[3];
            Vector3f blade = turn.transform(new Vector3f(item.b())), face = turn.transform(new Vector3f(item.n()));
            assert blade.distance(Fit.SWORD.b()) < 1e-4 : m[0] + "'s blade lands on the sword's: " + blade;
            assert Math.abs(face.dot(Fit.SWORD.n())) > 0.9999f : m[0] + "'s flat face lands on the sword's line: " + face;
        }
        // the frame read through the real transform (what NeoForge's applyTransform does to the stack) is the closed form's, both hands, non-uniform scale included
        for (Object[] m : models) {
            for (boolean left : new boolean[] {false, true}) {
                PoseStack applied = new PoseStack();
                new net.minecraft.client.renderer.block.model.ItemTransform((Vector3f) m[1], new Vector3f(0.1f, 0.2f, 0.3f), (Vector3f) m[2]).apply(left, applied);
                Blade.Frame read = Blade.frameOf(applied.last()), closed = Blade.rest((Vector3f) m[1], (Vector3f) m[2], left);
                assert read.b().distance(closed.b()) < 1e-5 && read.n().distance(closed.n()) < 1e-5 : m[0] + (left ? " left" : " right") + ": the applied transform reads as the closed form";
            }
        }
        PoseStack squashed = new PoseStack();
        new net.minecraft.client.renderer.block.model.ItemTransform(new Vector3f(0, 90, 0), new Vector3f(), new Vector3f(1.75f, 1.75f, 0.85f)).apply(false, squashed);
        assert Blade.frameOf(squashed.last()).n().distance(Blade.rest(new Vector3f(0, 90, 0), new Vector3f(1.75f, 1.75f, 0.85f), false).n()) < 1e-5 : "a thinner z keeps the face's direction";
        Blade.Frame spear = Blade.rest(new Vector3f(0, 90, 0), new Vector3f(1.75f, 1.75f, 0.85f), false);
        Vector3f swordScale = new Vector3f(0.68f, 0.68f, 0.68f);
        assert Fit.decide(Fit.SWORD, false, true).why().equals("aligned") && !Fit.decide(Fit.SWORD, false, true).fits() : "a model on the sword's axis is left alone";
        assert Fit.decide(spear, false, true).fits() && Math.abs(Fit.decide(spear, false, true).degrees() - 65) < 0.1 : "a spear is fitted";
        assert Fit.decide(Blade.rest(new Vector3f(0, -90, 28), swordScale, false), false, true).why().equals("aligned") : "3 degrees is the sprite's own looseness";
        assert Fit.decide(Blade.rest(new Vector3f(0, -90, 30), swordScale, false), false, true).why().equals("fit") : "5 degrees is a fit";
        assert Fit.decide(Blade.rest(new Vector3f(0, -90, 25 + 119), swordScale, false), false, true).why().equals("fit")
                && Fit.decide(Blade.rest(new Vector3f(0, -90, 25 + 121), swordScale, false), false, true).why().equals("reversed") : "up to 120 degrees is fitted, past it the hold is kept";
        assert Fit.decide(Blade.rest(new Vector3f(40, 90, 203), swordScale, false), false, true).why().equals("reversed") && !Fit.decide(Blade.rest(new Vector3f(40, 90, 203), swordScale, false), false, true).fits() : "a reverse grip keeps its hold";
        assert Fit.decide(spear, true, true).why().equals("custom") && Fit.decide(null, false, false).why().equals("not_flat") : "no readable blade axis, no fit";
        assert Fit.flat(new float[] {0, 0, 7.5f / 16, 1, 1, 8.5f / 16}) : "a generated sprite is a sixteenth thick and fills the block";
        assert !Fit.flat(new float[] {0, 0, 0.3f, 1, 1, 0.7f}) && !Fit.flat(new float[] {0.45f, 0, 7.5f / 16, 0.55f, 1, 8.5f / 16}) : "a 3D model is thicker, a thin plank is not a sprite";
        assert Swing.modelFitted("joust_spear", "joust") && Swing.modelFitted("uppercut_glaive", "uppercut") && !Swing.modelFitted("swing", "swing_alt") && !Swing.modelFitted("uppercut", "uppercut")
                : "a clip made for the model is left to itself; another sword-authored clip standing in is fitted";

        // ---- third-person polish: the chest, the legs and the trident ----
        torsoAndLegs();
        trident();

        // The registry of swings (injected clock): a swing's clip runs from its start, a hold stops it, it ends with its length, and a clip without a trail is never seen.
        ClientSwings.clear();
        ClientSwings.start(7, c, 1.0, 12f, -5f, 1000);
        assert Math.abs(ClientSwings.view(7, 1100).time() - 0.1) < 1e-9 && ClientSwings.view(7, 1100).yaw() == 12f : "0.1 s in, with the look it began with";
        ClientSwings.hold(7, 200, 1100);
        assert Math.abs(ClientSwings.view(7, 1200).time() - 0.1) < 1e-9 && Math.abs(ClientSwings.view(7, 1300).time() - 0.1) < 1e-9 : "held for 200 ms";
        assert Math.abs(ClientSwings.view(7, 1400).time() - 0.2) < 1e-9 : "and on again afterwards";
        ClientSwings.hold(7, 100, 1150);
        assert Math.abs(ClientSwings.view(7, 1400).time() - 0.1) < 1e-9 && Math.abs(ClientSwings.view(7, 1450).time() - 0.15) < 1e-9 : "a second hold while one runs extends it (300 ms in all)";
        ClientSwings.hold(99, 100, 1000); // nobody swinging: ignored
        assert ClientSwings.view(99, 1000) == null;
        assert ClientSwings.view(7, 1000 + 400 + 2000) == null : "over once its clip's length has played";
        ClientSwings.cleanup(1000 + 400 + 2000);
        assert ClientSwings.all(1000 + 400 + 2000).isEmpty() && ClientSwings.view(7, 1100) == null : "finished swings are forgotten";
        ClientSwings.start(8, clip(null), 1.0, 0, 0, 0);
        assert ClientSwings.view(8, 100) == null : "no trail, no view";
        ClientSwings.start(9, c, 0.25, 0, 0, 0);
        assert Math.abs(ClientSwings.view(9, 1000).time() - 0.25) < 1e-9 : "slow motion plays at its speed";
        ClientSwings.start(9, Anim.NONE, 1.0, 0, 0, 0);
        assert ClientSwings.all(500).isEmpty() : "starting the empty clip ends a swing";
        ClientSwings.clear();

        // The swing message survives the wire.
        ByteBuf buf = Unpooled.buffer();
        SwingPayload.CODEC.encode(buf, new SwingPayload(4242, "uppercut_spear"));
        SwingPayload back = SwingPayload.CODEC.decode(buf);
        assert back.entityId() == 4242 && back.clip().equals("uppercut_spear") && buf.readableBytes() == 0 : "round trip: " + back;
        buf = Unpooled.buffer();
        AttackPayload.CODEC.encode(buf, new AttackPayload("Slash", "swing_alt"));
        AttackPayload sent = AttackPayload.CODEC.decode(buf);
        assert sent.pick().equals("Slash") && sent.clip().equals("swing_alt") && buf.readableBytes() == 0 : "the attack message carries the clip: " + sent;
        System.out.println("PoseTest ok");
    }
}
