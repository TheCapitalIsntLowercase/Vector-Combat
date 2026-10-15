package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The first-person fit. Every clip is authored for the sword's model, so a weapon whose in-hand model sits at another angle (Basic Weapons' spears, Jaam's claws) played them with
 * its blade pointing somewhere else. The model's own display transform says where its blade is: the same reading {@link Blade} makes for third person. The weapon is turned about the
 * hand by one constant rotation that carries its blade axis and flat face onto the sword's, wearing it in over the windup and out over the recovery (the envelope third person uses,
 * {@link ThirdPerson#weight}), so it rests exactly as its modder holds it and runs the sword's path through the strike. Only the orientation is fitted: the hand's path is the clip's.
 * A model with its own clip for this attack (the long weapons' lunge and upward slash) is left to it; a custom-rendered or 3D model has no blade axis to read, and one held the other way
 * round on purpose keeps its hold.
 */
public final class Fit {
    private Fit() {
    }

    /** The display transform every clip was authored for: vanilla's handheld sword (rotation 0, -90, 25, scale 0.68), as the frame of its blade axis and flat face. */
    static final Blade.Frame SWORD = Blade.rest(new Vector3f(0, -90, 25), new Vector3f(0.68f, 0.68f, 0.68f), false);

    /** Under this many degrees a model already sits on the sword's blade axis (the sprite's own diagonal is not that exact); over {@link #REVERSED} it is held the other way round. */
    static final double ALIGNED = 4, REVERSED = 120;

    /** What the fit does with a model: {@code fit}, {@code aligned}, {@code reversed}, {@code custom} or {@code not_flat}; the turn about the hand (identity unless fit); how far off the sword's axis it is. */
    record Decision(String why, Quaternionf turn, double degrees) {
        boolean fits() {
            return why.equals("fit");
        }
    }

    /** The rotation that carries the model's blade axis and flat face onto the sword's; a sprite has two faces, so the sign of the normal that makes the smaller turn is used. */
    static Quaternionf turn(Blade.Frame item) {
        Quaternionf a = Blade.between(item, SWORD);
        Quaternionf b = Blade.between(new Blade.Frame(item.b(), new Vector3f(item.n()).negate()), SWORD);
        return Math.abs(a.w) >= Math.abs(b.w) ? a : b;
    }

    /** The angle of a rotation, in degrees (0 to 180). */
    static double degrees(Quaternionf q) {
        return Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(q.w))));
    }

    /** What to do with a model whose first-person frame is {@code item} (ignored for a custom or non-flat one, which have no readable blade axis). */
    static Decision decide(Blade.Frame item, boolean custom, boolean flat) {
        if (custom) return new Decision("custom", new Quaternionf(), 0);
        if (!flat) return new Decision("not_flat", new Quaternionf(), 0);
        Quaternionf q = turn(item);
        double deg = degrees(q);
        String why = deg < ALIGNED ? "aligned" : deg > REVERSED ? "reversed" : "fit";
        return new Decision(why, why.equals("fit") ? q : new Quaternionf(), deg);
    }

    /**
     * Whether vertex extents ({minX, minY, minZ, maxX, maxY, maxZ} in model space) are a flat sprite: no thicker than a sprite is (a sixteenth of a block) and more than half a block
     * across both ways. The sprite's blade then runs along its diagonal and its flat face is the model's z axis, which is what {@link Blade#rest} reads.
     */
    static boolean flat(float[] e) {
        return e[5] - e[2] <= 1 / 16f + 1e-4f && e[3] - e[0] > 0.5f && e[4] - e[1] > 0.5f;
    }

    /** The extents of every vertex of the model's quads, or null when it has none. */
    private static float[] extents(BakedModel model) {
        float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        RandomSource random = RandomSource.create(42L);
        for (Direction side : new Direction[] {null, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}) {
            for (BakedQuad quad : model.getQuads(null, side, random, ModelData.EMPTY, null)) {
                int[] v = quad.getVertices();
                for (int i = 0; i + 2 < v.length; i += 8) { // eight ints a vertex: position first
                    for (int k = 0; k < 3; k++) {
                        float x = Float.intBitsToFloat(v[i + k]);
                        e[k] = Math.min(e[k], x);
                        e[3 + k] = Math.max(e[3 + k], x);
                    }
                    any = true;
                }
            }
        }
        return any ? e : null;
    }

    /** By model, so each is read once; a resource reload bakes new models, which are read again. Render thread only. */
    private static final Map<BakedModel, Decision> DECISIONS = new WeakHashMap<>();
    private static final Map<BakedModel, Boolean> FLAT = new WeakHashMap<>();

    /** Whether this model is a flat sprite ({@link #flat(float[])}), read once per model. */
    static boolean flat(BakedModel model) {
        return FLAT.computeIfAbsent(model, m -> {
            float[] e = extents(m);
            return e != null && flat(e);
        });
    }

    /** Read from the model drawn in first person ({@link Blade#placed}: a model may hand over another one for the hand, and its {@code right_rotation} counts). */
    private static Decision read(BakedModel model) {
        Blade.Placed p = Blade.placed(model, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, false);
        if (p.model().isCustomRenderer()) return decide(null, true, false);
        if (!flat(p.model())) return decide(null, false, false);
        return decide(p.frame(), false, true);
    }

    /** The decision for the model really drawn in this holder's hand for this stack. */
    static Decision of(LivingEntity holder, ItemStack stack) {
        return DECISIONS.computeIfAbsent(Blade.modelInHand(holder, stack), Fit::read);
    }

    private static long loggedAt;
    private static boolean failed;

    /**
     * Called at the start of {@code ItemInHandRenderer.renderItem}, after the arm is drawn, with the pose stack at the hand: turns the item about the hand while a swing of the main hand
     * plays. Does nothing for the off hand, third person, a left-handed main hand (the mirror is parked), a clip made for this model, or with the setting off.
     */
    public static void apply(PoseStack ps, LivingEntity holder, ItemStack stack, ItemDisplayContext ctx) {
        if (failed || ctx != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || stack.isEmpty() || !ClientConfig.FIT_HELD_WEAPONS.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (holder != mc.player || mc.options.mainHand().get() != HumanoidArm.RIGHT) return;
        Swing.FitView view = Swing.fitView();
        if (view == null || view.modelFitted()) return;
        double weight = ThirdPerson.weight(view.clip(), view.time());
        if (weight < 0.001) return;
        Decision d = of(holder, stack);
        if (loggedAt != view.startedAt()) { // once per swing
            loggedAt = view.startedAt();
            Diag.event("fit", "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "clip", view.clip().id(), "decision", d.why(), "degrees", Math.round(d.degrees() * 10) / 10.0);
        }
        if (d.fits()) ps.mulPose(new Quaternionf().slerp(d.turn(), (float) weight));
    }

    /** What the fit makes of the main-hand item, for {@code /vcfit}; also written to the diag log. */
    static String describe(LivingEntity holder) {
        ItemStack stack = holder.getMainHandItem();
        if (stack.isEmpty()) return "fit: nothing in the main hand";
        Decision d = of(holder, stack);
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean left = Minecraft.getInstance().options.mainHand().get() != HumanoidArm.RIGHT;
        boolean on = ClientConfig.FIT_HELD_WEAPONS.get();
        Diag.event("fit_check", "item", id, "decision", d.why(), "degrees", Math.round(d.degrees() * 10) / 10.0, "enabled", on, "leftHanded", left);
        return "fit: " + id + " is " + d.why() + (d.degrees() > 0 ? String.format(" (its blade is %.1f degrees off the sword's)", d.degrees()) : "") + (on ? "" : "; the setting is off") + (left ? "; a left-handed main hand is not fitted" : "");
    }

    /** A fault anywhere in the fit: logged once, and it stays off until restart. */
    public static void fail(RuntimeException ex) {
        if (!failed) VectorCombat.LOG.error("First-person weapon fit failed, it is off until restart", ex);
        failed = true;
    }
}
