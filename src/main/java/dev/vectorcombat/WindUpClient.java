package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * The tell of a mob's wind-up, as drawn: the server says when a wind-up starts, strikes or ends ({@link WindUpPayload}) and the mob leans about its feet, back over the
 * wind-up and forward as the hit lands ({@link WindUp#lean}); how far follows its height ({@link WindUp#peakDegrees}). Drawn around the vanilla render with the pose
 * pushed in {@code RenderLivingEvent.Pre} and popped in {@code Post}, so the shadow and everything else stay put. The clock is the world's (game ticks and the frame's partial
 * tick), so the lean pauses with the game and follows the server's ticks.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class WindUpClient {
    private WindUpClient() {
    }

    /**
     * One mob's lean in progress: the phase, the game time it began, the wind-up's length, how far back it leaned when the phase began, its style ({@link WindUpPayload#LEAN} or
     * {@link WindUpPayload#OVERHEAD}) and whether a frame of it was drawn.
     */
    private static final class Lean {
        final int phase;
        final long since;
        final int ticks;
        final float from;
        final int style;
        boolean drawn;

        Lean(int phase, long since, int ticks, float from, int style) {
            this.phase = phase;
            this.since = since;
            this.ticks = ticks;
            this.from = from;
            this.style = style;
        }

        /** How much of the plain lean the body takes: less under an overhead or a flail, whose arms carry the tell. */
        float scale() {
            if (style == WindUpPayload.BITE) return WindUp.BITE_LEAN;
            return style == WindUpPayload.OVERHEAD || style == WindUpPayload.FLAIL || style == WindUpPayload.WEAPON ? WindUp.OVERHEAD_LEAN : 1;
        }
    }

    private static final Map<Integer, Lean> POSES = new HashMap<>();

    /** The entity whose pose {@link #pre} pushed for the frame being drawn, for {@link #post} to pop; -1 for none. */
    private static int pushed = -1;

    /** Set by the first fault: the lean switches itself off for the session rather than break the render every frame. */
    private static boolean failed;

    /**
     * A part {@link #pose} moved for the frame being drawn: the {@code xRot} and {@code zRot} it had (for {@link #restoreParts}), what was added to each and the values left on them (for
     * {@link #afterAnimator}).
     */
    private static final class Posed {
        final ModelPart part;
        final float savedX, savedY, savedZ, addX, addY, addZ;
        float wroteX, wroteY, wroteZ;

        Posed(ModelPart part, float addX, float addY, float addZ) {
            this.part = part;
            this.savedX = part.xRot;
            this.savedY = part.yRot;
            this.savedZ = part.zRot;
            this.addX = addX;
            this.addY = addY;
            this.addZ = addZ;
            part.xRot += addX;
            part.yRot += addY;
            part.zRot += addZ;
            this.wroteX = part.xRot;
            this.wroteY = part.yRot;
            this.wroteZ = part.zRot;
        }

        Posed(ModelPart part, float addX) {
            this(part, addX, 0, 0);
        }

        /** An arm moved to {@code x}, its sideways turn taken away by {@code square} ({@link WindUp#square}), so it rises and falls on one axis. */
        static Posed arm(ModelPart arm, float x, float square) {
            return new Posed(arm, x - arm.xRot, -arm.yRot * square, -arm.zRot * square);
        }
    }

    /** The legs (and an overhead's arms) posed for the frame being drawn, and whose they are; empty for none. */
    private static final List<Posed> POSED = new ArrayList<>(4);
    private static LivingEntity posedEntity;

    /** The server said mob {@code p.entity()} started, struck or ended a wind-up: from now it leans on from wherever it was. */
    static void receive(WindUpPayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Entity e = level.getEntity(p.entity());
        Diag.event("windup_pose", "entity", p.entity(), "phase", p.phase(), "ticks", p.ticks(), "found", e != null);
        if (!(e instanceof LivingEntity)) return;
        long now = level.getGameTime();
        Lean old = POSES.get(p.entity());
        float from = old == null || old.style == WindUpPayload.SHIVER || noLean(old.style) ? 0 : WindUp.lean(old.phase, old.from, now - old.since, old.ticks); // a shiver never leans
        POSES.put(p.entity(), new Lean(p.phase(), now, p.ticks(), from, p.style()));
    }

    /** How far the mob leans back now, in degrees (negative is forward); 0 when it is not in a wind-up. */
    private static float degrees(LivingEntity e, Lean p, float partialTick) {
        float t = (e.level().getGameTime() - p.since) + partialTick;
        return WindUp.lean(p.phase, p.from, t, p.ticks) * WindUp.peakDegrees(e.getBbHeight()) * p.scale();
    }

    /**
     * Leans {@code ps} {@code degrees} back (negative is forward) about the lateral axis through the feet of a body turned to {@code bodyYaw}. For use before the renderer's own
     * turn to the body ({@code YP(180 - bodyYaw)}): this turns that way, leans and turns back, so the renderer's turn that follows leaves the lean about the body's own side.
     */
    static void tilt(PoseStack ps, float bodyYaw, float degrees) {
        ps.mulPose(Axis.YP.rotationDegrees(180f - bodyYaw));
        ps.mulPose(Axis.XP.rotationDegrees(degrees));
        ps.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180f));
    }

    /** Rolls {@code ps} {@code degrees} about the body's own front-to-back axis through the feet, for use where {@link #tilt} is: a shiver's side to side shake. */
    static void roll(PoseStack ps, float bodyYaw, float degrees) {
        ps.mulPose(Axis.YP.rotationDegrees(180f - bodyYaw));
        ps.mulPose(Axis.ZP.rotationDegrees(degrees));
        ps.mulPose(Axis.YP.rotationDegrees(bodyYaw - 180f));
    }

    /** The head thrust applied this frame and the part it went on, taken back with the rest ({@link #restoreParts}); and the mobs whose first bite pose was logged. */
    private static ModelPart thrusted;
    private static float thrustBy;
    private static final java.util.Set<Integer> biteLogged = new java.util.HashSet<>();

    private static void thrust(ModelPart part, float dz) {
        thrusted = part;
        thrustBy = dz;
        part.z += dz;
    }

    private static void restoreParts() {
        if (thrusted != null) {
            thrusted.z -= thrustBy;
            thrusted = null;
            thrustBy = 0;
        }
        for (int i = POSED.size() - 1; i >= 0; i--) {
            Posed p = POSED.get(i);
            p.part.xRot = p.savedX;
            p.part.yRot = p.savedY;
            p.part.zRot = p.savedZ;
        }
        POSED.clear();
        posedEntity = null;
    }

    /** An animator that poses the model while it is drawn ({@link EmfCompat}) has run for {@code entity}: a part it overwrote gets ours again on top of its value. */
    static void afterAnimator(Object entity) {
        if (POSED.isEmpty() || posedEntity != entity) return;
        for (Posed p : POSED) {
            if (p.part.xRot != p.wroteX) p.part.xRot += p.addX;
            if (p.part.yRot != p.wroteY) p.part.yRot += p.addY;
            if (p.part.zRot != p.wroteZ) p.part.zRot += p.addZ;
            p.wroteX = p.part.xRot;
            p.wroteY = p.part.yRot;
            p.wroteZ = p.part.zRot;
        }
    }

    /** The spider model's eight legs, root children by name. */
    private static final String[] SPIDER_LEGS = {"right_hind_leg", "left_hind_leg", "right_middle_hind_leg", "left_middle_hind_leg", "right_middle_front_leg", "left_middle_front_leg",
            "right_front_leg", "left_front_leg"};

    /** The hoglin charge's styles: head and legs, no lean. */
    private static boolean charge(int style) {
        return style == WindUpPayload.LINE || style == WindUpPayload.CHARGE || style == WindUpPayload.REAR || style == WindUpPayload.TIRED || style == WindUpPayload.DAZED
                || style == WindUpPayload.DISPLAY;
    }

    /** Styles drawn without the lean: the shiver, the slime's shapes and the charge. */
    private static boolean noLean(int style) {
        return style == WindUpPayload.SHIVER || style == WindUpPayload.SQUASH || style == WindUpPayload.WOBBLE || charge(style) || style == WindUpPayload.FLIPPED
                || style == WindUpPayload.HANG;
    }

    /** A model part by name, from a humanoid's fields or a hierarchical model's root; null when the model has none. */
    private static ModelPart part(EntityModel<?> model, String name) {
        if (model instanceof HumanoidModel<?> h) {
            return switch (name) {
                case "head" -> h.head;
                case "left_leg" -> h.leftLeg;
                case "right_leg" -> h.rightLeg;
                case "left_arm" -> h.leftArm;
                case "right_arm" -> h.rightArm;
                default -> null;
            };
        }
        if (model instanceof HierarchicalModel<?> hm) { // a direct child, else a part of that name anywhere in the model (modded mobs nest arms and legs under a torso: Hominid's)
            if (hm.root().hasChild(name)) return hm.root().getChild(name);
            ModelPart nested = hm.getAnyDescendantWithName(name).orElse(null);
            if (nested != null) return nested;
        }
        if (model instanceof net.minecraft.client.model.WardenModel<?> wm && wm.root().hasChild("bone")) { // the warden's parts sit under bone and bone/body
            ModelPart bone = wm.root().getChild("bone");
            if (bone.hasChild(name)) return bone.getChild(name);
            if (bone.hasChild("body") && bone.getChild("body").hasChild(name)) return bone.getChild("body").getChild(name);
        }
        return null;
    }

    /**
     * Called by {@code LivingEntityRendererMixin} right after the model has been posed for {@code entity}: a mob in a wind-up has its legs bent at the hip instead of leaning
     * as one plank with the torso, and steps forward at the snap ({@link WindUp#legHinge}, {@link WindUp#legLead}). Works on models whose legs are {@code left_leg} and
     * {@code right_leg} (the humanoids, illagers, the iron golem); any other keeps its own legs. An overhead also raises both arms ({@code right_arm}, {@code left_arm}: the
     * illagers, the humanoids) over the head and slams them down ({@link WindUp#overheadArm}); a flail raises and spreads them, then sweeps them down crossed ({@link WindUp#flailArmX});
     * a kick cocks the right leg back and snaps it forward ({@link WindUp#kickLegX}) in place of the stride; a model without the parts shows the lean alone.
     */
    public static void pose(EntityModel<?> model, LivingEntity entity, float partialTick) {
        restoreParts();
        if (failed || POSES.isEmpty()) return;
        Lean p = POSES.get(entity.getId());
        if (p == null || !entity.hasPose(Pose.STANDING) || entity.deathTime > 0 || entity.isPassenger()) return;
        try {
            if (p.style == WindUpPayload.FLIPPED || p.style == WindUpPayload.HANG) { // a spider's legs, flailing on its back or twitching as it hangs
                float t = (entity.level().getGameTime() - p.since) + partialTick;
                if (p.phase == WindUpPayload.START && t <= p.ticks) {
                    for (int i = 0; i < SPIDER_LEGS.length; i++) {
                        ModelPart leg = part(model, SPIDER_LEGS[i]);
                        if (leg != null) POSED.add(new Posed(leg, 0, 0, WindUp.legFlail(p.style, i, t) * (i % 2 == 0 ? 1 : -1)));
                    }
                    if (!POSED.isEmpty()) posedEntity = entity;
                }
                return;
            }
            if (charge(p.style)) { // the hoglin's charge: its head and front legs
                hoglin(model, p, (entity.level().getGameTime() - p.since) + partialTick);
                if (!POSED.isEmpty()) posedEntity = entity;
                return;
            }
            if (noLean(p.style)) return; // a shake or a change of shape of the whole body, no bend
            float t = (entity.level().getGameTime() - p.since) + partialTick;
            float f = WindUp.lean(p.phase, p.from, t, p.ticks), w = WindUp.stride(p.phase, t);
            ModelPart left = part(model, "left_leg"), right = part(model, "right_leg");
            if (left != null && right != null) {
                float hinge = WindUp.legHinge(f, WindUp.peakDegrees(entity.getBbHeight()) * p.scale());
                if (p.style == WindUpPayload.KICK) { // the standing leg takes the hinge, the kicking one its own path
                    POSED.add(new Posed(left, hinge));
                    POSED.add(new Posed(right, WindUp.kickLegX(right.xRot, f) - right.xRot));
                } else {
                    POSED.add(new Posed(left, hinge + WindUp.legLead(w)));
                    POSED.add(new Posed(right, hinge + WindUp.legTrail(w)));
                }
            }
            arms(model, entity, p.style, f);
            if (!POSED.isEmpty()) posedEntity = entity;
        } catch (RuntimeException ex) {
            restoreParts();
            if (!failed) VectorCombat.LOG.error("Mob wind-up lean failed, it is off until restart", ex);
            failed = true;
        }
    }

    /**
     * The arms' part of a tell, on any model whose arms are found ({@link #part}: a humanoid's, or root children named {@code right_arm} and {@code left_arm}, which most vanilla and
     * modded humanoids are): an overhead or a two-handed weapon both arms, a flail both arms straight up and down, a one-handed weapon the arm of the mob's main hand (a left-handed
     * mob's left), and a plain lean a claw of both arms. Each arm tell squares the arm onto one axis; the claw keeps the model's own pose round it. {@code PLAIN}, a kick and a shiver
     * leave the arms alone.
     */
    private static void arms(EntityModel<?> model, LivingEntity entity, int style, float f) {
        ModelPart right = part(model, "right_arm"), left = part(model, "left_arm");
        float sq = WindUp.square(f);
        switch (style) {
            case WindUpPayload.OVERHEAD -> {
                if (right != null) POSED.add(Posed.arm(right, WindUp.overheadArm(right.xRot, f), sq));
                if (left != null) POSED.add(Posed.arm(left, WindUp.overheadArm(left.xRot, f), sq));
            }
            case WindUpPayload.FLAIL -> {
                if (right != null) POSED.add(Posed.arm(right, WindUp.flailArmX(right.xRot, f), sq));
                if (left != null) POSED.add(Posed.arm(left, WindUp.flailArmX(left.xRot, f), sq));
            }
            case WindUpPayload.WEAPON -> {
                ModelPart main = entity.getMainArm() == HumanoidArm.LEFT ? left : right;
                if (main != null) POSED.add(Posed.arm(main, WindUp.weaponArmX(main.xRot, f), sq));
            }
            case WindUpPayload.BITE -> {
                ModelPart head = model instanceof net.minecraft.client.model.HoglinModel<?> ? ((dev.vectorcombat.mixin.HoglinModelAccessor) model).vectorcombat$head() : part(model, "head");
                if (head != null) {
                    float base = head.xRot;
                    POSED.add(new Posed(head, WindUp.biteHead(base, f) - base));
                    thrust(head, -WindUp.biteThrust(f)); // forward is -z in the model
                }
                if (!biteLogged.contains(entity.getId())) {
                    biteLogged.add(entity.getId());
                    Diag.event("bite_pose", "mob", entity.getType().getDescriptionId(), "model", model.getClass().getSimpleName(), "head", head != null,
                            "baseXRot", head == null ? null : Math.round(head.xRot * 1000) / 1000.0, "f", Math.round(f * 1000) / 1000.0);
                }
            }
            case WindUpPayload.LEAN -> {
                if (right != null && left != null) {
                    POSED.add(new Posed(right, WindUp.clawArmX(right.xRot, f) - right.xRot));
                    POSED.add(new Posed(left, WindUp.clawArmX(left.xRot, f) - left.xRot));
                }
            }
            default -> {
            }
        }
    }

    /**
     * A charger's head and front legs: the hoglin's (and zoglin's) through {@link dev.vectorcombat.mixin.HoglinModelAccessor}, any other model through its root children
     * {@code neck} or {@code head} and {@code right_front_leg}, {@code left_front_leg} (the ravager's); a model with neither keeps its own.
     */
    private static void hoglin(EntityModel<?> model, Lean p, float t) {
        float w = p.style == WindUpPayload.DISPLAY ? WindUp.display(p.phase, t, p.ticks) : 1; // a spider's display holds to the leap and eases out in the air
        if (p.style == WindUpPayload.DISPLAY ? w <= 0 : p.phase != WindUpPayload.START || t > p.ticks) return;
        ModelPart head, right, left;
        if (model instanceof net.minecraft.client.model.HoglinModel<?>) {
            dev.vectorcombat.mixin.HoglinModelAccessor h = (dev.vectorcombat.mixin.HoglinModelAccessor) model;
            head = h.vectorcombat$head();
            right = h.vectorcombat$rightFrontLeg();
            left = h.vectorcombat$leftFrontLeg();
        } else if (model instanceof net.minecraft.client.model.QuadrupedModel<?>) { // cows, mooshrooms, pigs, sheep, goats, polar bears...
            dev.vectorcombat.mixin.QuadrupedModelAccessor q = (dev.vectorcombat.mixin.QuadrupedModelAccessor) model;
            head = q.vectorcombat$head();
            right = q.vectorcombat$rightFrontLeg();
            left = q.vectorcombat$leftFrontLeg();
        } else {
            head = part(model, "neck");
            if (head == null) head = part(model, "head");
            right = part(model, "right_front_leg");
            left = part(model, "left_front_leg");
        }
        if (head != null) POSED.add(new Posed(head, WindUp.chargeHead(p.style) * w));
        if (right != null) POSED.add(new Posed(right, WindUp.chargeLeg(p.style, t, 1) * w));
        if (left != null) POSED.add(new Posed(left, WindUp.chargeLeg(p.style, t, -1) * w));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void pre(RenderLivingEvent.Pre<?, ?> e) {
        restoreParts();
        pushed = -1;
        if (failed || POSES.isEmpty()) return;
        LivingEntity entity = e.getEntity();
        Lean p = POSES.get(entity.getId());
        if (p == null || !entity.hasPose(Pose.STANDING) || entity.deathTime > 0 || entity.isPassenger()) return;
        boolean pushedHere = false;
        try {
            float deg = degrees(entity, p, e.getPartialTick());
            if (!p.drawn) {
                p.drawn = true;
                Diag.event("windup_draw", "entity", entity.getId(), "mob", entity.getType().getDescriptionId(), "phase", p.phase, "peakDeg", WindUp.peakDegrees(entity.getBbHeight()),
                        "height", entity.getBbHeight(), "deg", Math.round(deg * 10) / 10.0);
            }
            e.getPoseStack().pushPose();
            pushedHere = true;
            float yaw = Mth.rotLerp(e.getPartialTick(), entity.yBodyRotO, entity.yBodyRot);
            if (p.style == WindUpPayload.REAR) { // a hoglin rearing: pitched up about its hind hips
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                float back = entity.getBbWidth() * 0.45f, hip = entity.getBbHeight() * 0.45f;
                PoseStack ps = e.getPoseStack();
                ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
                ps.translate(0, hip, back);
                ps.mulPose(Axis.XP.rotationDegrees(p.phase == WindUpPayload.START ? WindUp.rear(t, p.ticks) : 0));
                ps.translate(0, -hip, -back);
                ps.mulPose(Axis.YP.rotationDegrees(yaw - 180f));
            } else if (p.style == WindUpPayload.DISPLAY) { // a spider's display: its front half up about its hind body, held until it leaps
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                float back = entity.getBbWidth() * 0.45f, hip = entity.getBbHeight() * 0.3f;
                PoseStack ps = e.getPoseStack();
                ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
                ps.translate(0, hip, back);
                ps.mulPose(Axis.XP.rotationDegrees(WindUp.REAR_DEGREES * WindUp.display(p.phase, t, p.ticks)));
                ps.translate(0, -hip, -back);
                ps.mulPose(Axis.YP.rotationDegrees(yaw - 180f));
            } else if (p.style == WindUpPayload.FLIPPED) { // a spider on its back: rolled over about the middle of its body
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                float mid = entity.getBbHeight() * 0.5f, roll = p.phase == WindUpPayload.START && t <= p.ticks ? 180f * Math.min(1, t / 3f) * Math.min(1, (p.ticks - t) / 3f + 0.0001f) : 0;
                PoseStack ps = e.getPoseStack();
                ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
                ps.translate(0, mid, 0);
                ps.mulPose(Axis.ZP.rotationDegrees(Math.min(180f, roll)));
                ps.translate(0, -mid, 0);
                ps.mulPose(Axis.YP.rotationDegrees(yaw - 180f));
            } else if (p.style == WindUpPayload.HANG) { // still: the legs do it
            } else if (p.style == WindUpPayload.DAZED) { // a charger that hit terrain: tipped forward onto its front feet
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                float front = entity.getBbWidth() * 0.45f;
                PoseStack ps = e.getPoseStack();
                ps.mulPose(Axis.YP.rotationDegrees(180f - yaw));
                ps.translate(0, 0, -front);
                ps.mulPose(Axis.XP.rotationDegrees(p.phase == WindUpPayload.START ? -WindUp.daze(t, p.ticks) : 0));
                ps.translate(0, 0, front);
                ps.mulPose(Axis.YP.rotationDegrees(yaw - 180f));
            } else if (charge(p.style)) { // the rest of the charge: no lean, the head and legs do it
            } else if (p.style == WindUpPayload.SQUASH || p.style == WindUpPayload.WOBBLE) { // a slime: drawn squashed, stretched or wobbling about its feet
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                float[] shape = WindUp.shape(p.style, p.phase, t, p.ticks);
                e.getPoseStack().scale(shape[0], shape[1], shape[0]);
            } else if (p.style == WindUpPayload.SHIVER) {
                float t = (entity.level().getGameTime() - p.since) + e.getPartialTick();
                roll(e.getPoseStack(), yaw, p.phase == WindUpPayload.START ? WindUp.shiver(t, p.ticks) : 0);
            } else {
                tilt(e.getPoseStack(), yaw, deg);
            }
            pushed = entity.getId();
        } catch (RuntimeException ex) {
            if (pushedHere) e.getPoseStack().popPose();
            if (!failed) VectorCombat.LOG.error("Mob wind-up lean failed, it is off until restart", ex);
            failed = true;
        }
    }

    /** First among the listeners, so a mod that draws more after the mob finds the stack as vanilla leaves it. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void post(RenderLivingEvent.Post<?, ?> e) {
        restoreParts();
        if (pushed != e.getEntity().getId()) return;
        pushed = -1;
        e.getPoseStack().popPose();
    }

    /** Forgets leans that are over. */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            POSES.clear();
            return;
        }
        long now = level.getGameTime();
        POSES.entrySet().removeIf(en -> now - en.getValue().since > WindUp.length(en.getValue().phase, en.getValue().ticks) + 1);
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        POSES.clear();
    }
}
