package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ItemAbilities;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Your own arm in first person, holding what you hold. The arm is the player model's own arm part, laid from a shoulder to the point the item is held by, so it
 * follows whatever pose the item is drawn in (equip, eating, a bow, vanilla's swing, a Vector clip) with no data per item. It is drawn from a hook at the start
 * of {@code ItemInHandRenderer.renderItem}, where the pose stack already is at the hand ({@link dev.vectorcombat.mixin.ItemInHandRendererMixin}).
 * <p>
 * It is meant to look like the default hand (the empty one vanilla draws): the arm has that hand's orientation at rest, turned the shortest way toward the item
 * as the hand moves, and the shoulder is where that arm would start, below the screen. Vanilla's item pose puts the grip just under the bottom edge, so while the
 * hands are drawn the item rests {@link #RAISE} higher ({@link #raise}); that fades out through a swing, so every strike is as authored. The fist sits {@link #GRIP}
 * below the item's hand origin so that a sword's guard and blade stay clear of it. An item is held nearer than the default hand floats, which would make the arm look
 * wider than that hand, so the arm is drawn slimmer: {@link #fit} finds, for the skin's own arm model (slim or wide), the thickness at which the arm at rest is exactly as
 * wide on screen as the default hand, and {@link #thick} slims it further as a swing brings the hand in. Nothing here is a setting. The view frame the shoulder lives in
 * is the one {@code renderHandsWithItems} starts with, so it bobs with the view.
 */
public final class Hands {
    /** Mods that already draw first-person hands: the hands stay off for them. */
    static final List<String> OTHERS = List.of("punchy", "holdmyitems", "hands_3d", "firstperson");

    /** The arm part is 12 px long; its cube starts 2 px above its pivot. */
    static final float ARM = 12 / 16f;

    /** The arm is stretched or squashed no more than this, whatever the distance from the shoulder to the grip. */
    static final float MIN_ARM = 0.65f, MAX_ARM = 0.95f;

    /** The arm gets no slimmer than this share of its fitted thickness, however close the hand comes. */
    static final float NEAR = 0.72f;

    /** How far above vanilla's place an item rests while the hands are drawn, in blocks: the guard of a sword lands near 79% of the screen height, as in the Punchy and Hold My Items reference, and the fist shows. */
    static final double RAISE = 0.2;

    /** How far below the item's hand origin the fist sits, in blocks: its top edge meets a sword's guard. */
    static final double GRIP = 0.12;

    private static final double[] NONE = new double[2];

    /**
     * Up the handle in the hand frame: where a handheld sprite's blade points (its diagonal turned by vanilla's first-person display transform, 0, -90, 25), 20 degrees
     * toward the viewer. The fist sits below the hand origin along it, so it stays on the handle however the clip turns the item.
     */
    static final Vector3f ITEM_AXIS = new Vector3f(0, 0.9397f, 0.3420f);

    /** The frame the shoulder lives in: the pose at the start of {@code renderHandsWithItems} (view bob applied, hand sway not). */
    private static final Matrix4f BASE = new Matrix4f();
    private static boolean haveBase;

    /** Set by the first fault: the hands switch themselves off for the session rather than break the frame every time. */
    private static boolean failed;

    private static String blockedBy;
    private static boolean checked;
    private static Boolean lastOn;
    private static final boolean[] drawn = new boolean[2];

    private Hands() {
    }

    // ---- the setting ----

    /** The first of {@link #OTHERS} that {@code loaded} reports, or null: what keeps the hands off even when they are switched on. */
    static String blocker(Predicate<String> loaded) {
        for (String id : OTHERS) if (loaded.test(id)) return id;
        return null;
    }

    /** The hands are drawn when the setting is on and no other mod draws them already (two pairs of hands would be worse than none). */
    static boolean on(boolean enabled, String blocker) {
        return enabled && blocker == null;
    }

    private static boolean enabled() {
        if (failed) return false;
        if (!checked) {
            blockedBy = blocker(ModList.get()::isLoaded);
            checked = true;
        }
        boolean setting = ClientConfig.HANDS.get();
        boolean on = on(setting, blockedBy);
        if (!Boolean.valueOf(on).equals(lastOn)) { // once, and again when the setting changes in game
            lastOn = on;
            VectorCombat.LOG.info("[diag] hands: {} (setting {}{})", on ? "on" : "off", setting ? "on" : "off", blockedBy == null ? "" : ", " + blockedBy + " is installed");
            Diag.event("hands", "on", on, "setting", setting, "blockedBy", blockedBy);
        }
        return on;
    }

    // ---- the arm ----

    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    /**
     * The orientation of the default hand's arm part at rest: the turns {@code ItemInHandRenderer.renderPlayerArm} makes (y 45, z 120, x 200, y -135, mirrored for the
     * left arm) and then the idle bob {@code HumanoidModel.setupAnim} leaves on the arm at age 0 (z +0.1 right, -0.1 left).
     */
    static Quaternionf rest(boolean left) {
        float f = left ? -1 : 1;
        return new Quaternionf().rotationY(rad(f * 45)).rotateZ(rad(f * 120)).rotateX(rad(200)).rotateY(rad(f * -135)).rotateZ(f * 0.1f);
    }

    /** Where the default hand's arm points at rest, from its shoulder to its fist (about (0.37, 0.54, -0.76) on the right), in the view frame. */
    static Vector3f restAxis(boolean left) {
        return rest(left).transform(new Vector3f(0, 1, 0));
    }

    /** The orientation that takes the arm part's +Y (shoulder to hand) onto the unit {@code dir}: the default hand's, turned the shortest way, so the arm never rolls about its own length. */
    static Quaternionf aim(Vector3f dir, boolean left) {
        Quaternionf r = rest(left);
        return new Quaternionf().rotationTo(r.transform(new Vector3f(0, 1, 0)), dir).mul(r);
    }

    /** The arm part's top face centre in blocks: its pivot plus its cube's offset (the cube starts 2 px above the pivot, centred 1 px, or 0.5 px when slim, toward the body). */
    static Vector3f top(ModelPart arm, boolean left, boolean slim) {
        float cx = (slim ? 0.5f : 1f) * (left ? 1 : -1);
        return new Vector3f(arm.x + cx, arm.y - 2, arm.z).div(16);
    }

    /**
     * How thick the arm is drawn, as a fraction of the model's own cross-section, for a hand {@code depth} blocks in front of the view and an item that rests {@code restDepth}
     * in front of it. The default hand floats about 1.14 out and the item is held 0.76 out, so at full size the arm looks half as big again as the default hand;
     * {@code thickness} brings that back at rest, and a hand that comes in toward you through a swing gets slimmer in proportion, down to {@link #NEAR} of it, so on screen the
     * arm stays about as wide as the default hand's. A hand farther out than at rest is not thickened.
     */
    static float thick(double thickness, double depth, double restDepth) {
        return (float) (thickness * Math.max(NEAR, Math.min(1, depth / restDepth)));
    }

    /**
     * Lays the arm part along the segment from {@code shoulder} to {@code hand} (any one frame, longer than a hair): translate to the shoulder, aim, stretch the
     * 12 px arm to the segment's length and slim its cross-section to {@code thick}, and step back to the part's top face centre {@code top} so that point lands on the
     * shoulder and the bottom face centre on the hand.
     */
    static void place(PoseStack ps, Vector3f shoulder, Vector3f hand, Vector3f top, boolean left, float thick) {
        Vector3f d = hand.sub(shoulder, new Vector3f());
        float len = d.length();
        ps.translate(shoulder.x, shoulder.y, shoulder.z);
        ps.mulPose(aim(d.div(len), left));
        ps.scale(thick, len / ARM, thick);
        ps.translate(-top.x, -top.y, -top.z);
    }

    /** The arm's shoulder end: on the line from {@code grip} toward {@code shoulder}, at that distance clamped to [{@code min}, {@code max}], so the arm is never stretched or squashed much. */
    static Vector3f anchor(Vector3f shoulder, Vector3f grip, float min, float max) {
        Vector3f d = shoulder.sub(grip, new Vector3f());
        float len = d.length();
        return d.mul(Math.max(min, Math.min(max, len)) / len).add(grip);
    }

    // ---- where the hand rests ----

    /** How far toward the middle the rest moves with the raise: a fifth of it. */
    static double towardCenter(double raise) {
        return 0.2 * raise;
    }

    /** The item's grip at rest in the view frame (right arm, or the left's mirror): the hand origin vanilla holds items at, raised, moved in and dropped by {@code grip} down the handle. */
    static Vector3f restGrip(boolean left, double raise, double grip) {
        return new Vector3f((float) ((left ? -1 : 1) * (Swing.HAND_X - towardCenter(raise))), (float) (Swing.HAND_Y + raise - grip * ITEM_AXIS.y), (float) (Swing.HAND_Z - grip * ITEM_AXIS.z));
    }

    /** The shoulder, in the view frame: where the default hand's arm would start if its fist were at the rest grip, so at rest the arm is the default hand's however the settings are tuned. */
    static Vector3f shoulder(boolean left, double raise, double grip) {
        return restGrip(left, raise, grip).sub(restAxis(left).mul(ARM));
    }

    /**
     * How far this hand's item rests above vanilla's place, as {x, y} to add to the hand's pose (the raise and a fifth of it toward the middle), or zeros when the hands
     * are off or the item keeps vanilla's place: an empty hand (the default hand stays vanilla), a map, a shield (its raised pose is already high) or a crossbow.
     */
    public static double[] raise(ItemStack stack, boolean leftArm) {
        if (stack.isEmpty() || stack.is(Items.FILLED_MAP) || stack.canPerformAction(ItemAbilities.SHIELD_BLOCK) || stack.getItem() instanceof CrossbowItem || !enabled()) return NONE;
        return new double[] {(leftArm ? 1 : -1) * towardCenter(RAISE), RAISE};
    }

    /** How much of the raise is left at {@code t} seconds into a clip of {@code length}: all of it before and after the swing, none through it (out over the first 0.05 s, back in over the last 0.12 s). */
    public static double raiseKeep(double t, double length) {
        return 1 - Math.min(smooth(t / 0.05), smooth((length - t) / 0.12));
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /**
     * Where the hand holds an item, in the frame the item is drawn in: {@code drop} down the handle ({@link #ITEM_AXIS}) from that frame's origin (handheld sprites have
     * their handle there), except a shield, whose handle is behind the plate and moves with the model: the blocking model sits a fifth of a block up and toward the middle
     * from the idle one.
     */
    static Vector3f grip(ItemStack stack, LivingEntity holder, ItemDisplayContext ctx, boolean left, double drop) {
        if (!stack.canPerformAction(ItemAbilities.SHIELD_BLOCK)) return ITEM_AXIS.mul((float) -drop, new Vector3f());
        PoseStack ps = new PoseStack();
        Minecraft.getInstance().getItemRenderer().getModel(stack, holder.level(), holder, 0).applyTransform(ctx, ps, left);
        return shieldHandle(ps);
    }

    /** The shield's handle (model point 0, 0, 2 px) in the hand frame, {@code ps} being the model's display transform applied to an empty stack: then the item renderer's centring and the shield renderer's y and z flip. */
    static Vector3f shieldHandle(PoseStack ps) {
        ps.translate(-0.5f, -0.5f, -0.5f);
        ps.scale(1, -1, -1);
        return ps.last().pose().transformPosition(new Vector3f(0, 0, 2 / 16f));
    }

    // ---- the arm's width, fitted to the default hand ----

    /** Collects the corners a model part is drawn with, after the pose stack's transform: nothing is rendered. */
    static final class Corners implements VertexConsumer {
        final List<Vector3f> points = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            points.add(new Vector3f(x, y, z));
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** The default hand: the arm part drawn through {@code renderPlayerArm}'s own steps at rest (swing 0, equip 0), after the idle bob {@code setupAnim} leaves on it. */
    static Corners defaultHand(PlayerModel<AbstractClientPlayer> model, boolean left) {
        ModelPart arm = left ? model.leftArm : model.rightArm;
        arm.resetPose();
        arm.zRot = left ? -0.1f : 0.1f;
        float f = left ? -1 : 1;
        PoseStack ps = new PoseStack();
        ps.translate(f * 0.64000005f, -0.6f, -0.71999997f);
        ps.mulPose(Axis.YP.rotationDegrees(f * 45));
        ps.translate(f * -1.0f, 3.6f, 3.5f);
        ps.mulPose(Axis.ZP.rotationDegrees(f * 120));
        ps.mulPose(Axis.XP.rotationDegrees(200));
        ps.mulPose(Axis.YP.rotationDegrees(f * -135));
        ps.translate(f * 5.6f, 0, 0);
        Corners c = new Corners();
        arm.render(ps, c, 0, 0);
        arm.resetPose();
        return c;
    }

    /** This mod's right arm at rest, {@code thick} as thick as the part: the arm {@link #place} lays from the rest shoulder to the rest grip. */
    static Corners restArm(PlayerModel<AbstractClientPlayer> model, boolean slim, float thick) {
        ModelPart arm = model.rightArm;
        arm.resetPose();
        PoseStack ps = new PoseStack();
        place(ps, shoulder(false, RAISE, GRIP), restGrip(false, RAISE, GRIP), top(arm, false, slim), false, thick);
        Corners c = new Corners();
        arm.render(ps, c, 0, 0);
        arm.resetPose();
        return c;
    }

    /**
     * How wide, as a fraction of the screen's width, the solid with these corners (view frame, x right, y up, -z forward) is on the bottom edge of the screen: the corners
     * projected through the game's 70 degree vertical field of view at 16:9, their convex hull cut by the bottom edge. 0 when it does not reach it or a corner is behind the view.
     */
    static double bottomEdgeWidth(List<Vector3f> corners) {
        double tan = Math.tan(Math.toRadians(35));
        List<double[]> pts = new ArrayList<>();
        for (Vector3f p : corners) {
            if (p.z >= -0.01f) return 0;
            pts.add(new double[] {p.x / -p.z / (tan * 16 / 9) * 0.5 + 0.5, 0.5 - p.y / -p.z / tan * 0.5});
        }
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<double[]> hull = new ArrayList<>(); // Andrew's monotone chain: lower hull, then upper
        for (int pass = 0; pass < 2; pass++) {
            int start = hull.size();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(pass == 0 ? i : pts.size() - 1 - i);
                while (hull.size() >= start + 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) hull.remove(hull.size() - 1);
                hull.add(p);
            }
            hull.remove(hull.size() - 1);
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < hull.size(); i++) {
            double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
            if ((a[1] - 1) * (b[1] - 1) <= 0 && a[1] != b[1]) {
                double x = a[0] + (1 - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
        }
        return hi < lo ? 0 : hi - lo;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** The thickness, as a share of the skin model's own, at which the arm at rest is as wide on the bottom edge as the default hand: the width is nearly proportional to it, so a few steps converge. */
    static float solve(PlayerModel<AbstractClientPlayer> model, boolean slim) {
        double target = bottomEdgeWidth(defaultHand(model, false).points);
        float k = 1;
        for (int i = 0; i < 4 && target > 0; i++) {
            double width = bottomEdgeWidth(restArm(model, slim, k).points);
            if (width <= 0) break;
            k = (float) Math.max(0.5, Math.min(1, k * target / width));
        }
        return k;
    }

    private static final float[] FITTED = new float[2]; // wide, slim; 0 until solved

    /** The fitted thickness for the skin model in use, solved once per skin type from the real arm part (slim and wide have different cross-sections). */
    static float fit(PlayerModel<AbstractClientPlayer> model, boolean slim) {
        int i = slim ? 1 : 0;
        if (FITTED[i] == 0) {
            FITTED[i] = solve(model, slim);
            VectorCombat.LOG.info("[diag] hands: {} arm fitted to the default hand at {}", slim ? "slim" : "wide", FITTED[i]);
        }
        return FITTED[i];
    }

    // ---- the hooks ----

    /** Called at the start of {@code renderHandsWithItems}: the frame the shoulder lives in. */
    public static void base(PoseStack ps) {
        BASE.set(ps.last().pose());
        haveBase = true;
    }

    /** Called at the start of {@code ItemInHandRenderer.renderItem}: draws the arm holding {@code stack} when the pose stack is at the hand of the first-person item. */
    public static void draw(PoseStack ps, MultiBufferSource buffer, int light, LivingEntity holder, ItemStack stack, ItemDisplayContext ctx) {
        boolean left = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        if (!left && ctx != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || stack.isEmpty() || !haveBase || !enabled()) return; // third person calls this method too
        Minecraft mc = Minecraft.getInstance();
        if (!(holder instanceof AbstractClientPlayer player) || holder != mc.player || !(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;
        PlayerSkin skin = player.getSkin();
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        float fitted = fit(model, slim); // before the arm is posed for this draw: fitting poses it too
        Matrix4f toHere = new Matrix4f(ps.last().pose()).invert().mul(BASE);
        Vector3f hand = grip(stack, holder, ctx, left, GRIP);
        Vector3f shoulder = toHere.transformPosition(shoulder(left, RAISE, GRIP));
        if (hand.distanceSquared(shoulder) < 1e-4f) return;
        shoulder = anchor(shoulder, hand, MIN_ARM, MAX_ARM);
        float depth = -toHere.invert(new Matrix4f()).transformPosition(hand, new Vector3f()).z; // how far in front of the view the hand is
        float thick = thick(fitted, depth, -restGrip(left, RAISE, GRIP).z);
        ModelPart arm = left ? model.leftArm : model.rightArm, sleeve = left ? model.leftSleeve : model.rightSleeve;
        arm.resetPose(); // the model is shared with every other draw of this player, which sets its own pose before drawing
        arm.visible = true;
        sleeve.copyFrom(arm);
        sleeve.visible = player.isModelPartShown(left ? PlayerModelPart.LEFT_SLEEVE : PlayerModelPart.RIGHT_SLEEVE);
        ResourceLocation texture = skin.texture();
        if (!drawn[left ? 1 : 0]) { // once per arm: the log shows the hands really drew, and where
            drawn[left ? 1 : 0] = true;
            Diag.event("hands_draw", "arm", left ? "left" : "right", "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "slim", slim,
                    "fitted", fitted, "thick", thick, "depth", depth, "shoulder", shoulder, "hand", hand, "length", hand.distance(shoulder));
        }
        ps.pushPose();
        try {
            place(ps, shoulder, hand, top(arm, left, slim), left, thick);
            arm.render(ps, buffer.getBuffer(RenderType.entitySolid(texture)), light, OverlayTexture.NO_OVERLAY);
            sleeve.render(ps, buffer.getBuffer(RenderType.entityTranslucent(texture)), light, OverlayTexture.NO_OVERLAY);
        } finally {
            ps.popPose();
        }
    }

    /** A fault anywhere in drawing the arm: logged once, and the hands stay off until restart. */
    public static void fail(RuntimeException ex) {
        if (!failed) VectorCombat.LOG.error("First-person hands failed, they are off until restart", ex);
        failed = true;
    }
}
