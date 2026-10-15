package dev.vectorcombat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * The third-person refresh ({@code [display] thirdPersonStyle} REFRESHED; CLASSIC is everything as it was before it, untouched): our crosshair pieces where a third-person
 * crosshair is drawn, and a swing aimed where an over-the-shoulder camera's crosshair points.
 * <ul>
 * <li>The direction arrow and the stun ring show in third person whenever a crosshair does there: with Shoulder Surfing (it draws one), or with ours ({@code thirdPersonCrosshair}:
 * a plain crosshair, split by the attack-speed penalty as in first person, for vanilla's F5, which has none). With Shoulder Surfing its own crosshair stays; we only add to it.</li>
 * <li>The swing's pose settles back over at least {@link ThirdPerson#RECOVER_MIN} after the blade's path ends, eased, instead of the clip's own short recovery.</li>
 * <li>With Shoulder Surfing the camera sits beside the player, so its crosshair and the player's facing part: a swing starts by turning the player to the point under the
 * camera's crosshair (a block or a mob within 24 blocks, else 24 blocks out), and the server is told before the swing is.</li>
 * </ul>
 */
public final class ThirdView {
    private ThirdView() {
    }

    static final double AIM_RANGE = 24;

    private static Boolean shoulderSurfing;

    static boolean shoulderSurfing() {
        if (shoulderSurfing == null) shoulderSurfing = ModList.get().isLoaded("shouldersurfing");
        return shoulderSurfing;
    }

    static boolean refreshed() {
        try {
            return ClientConfig.THIRD_PERSON_STYLE.get() == ClientConfig.ThirdPersonStyle.REFRESHED;
        } catch (IllegalStateException e) { // the config is not loaded (a test, a server): as before the refresh
            return false;
        }
    }

    /** Whether our crosshair pieces show now: first person always; third person when a crosshair is drawn there (Shoulder Surfing's, or ours) under the refresh. */
    static boolean crosshairPieces(Minecraft mc) {
        if (mc.options.getCameraType().isFirstPerson()) return true;
        return refreshed() && (shoulderSurfing() || ClientConfig.THIRD_PERSON_CROSSHAIR.get());
    }

    /** Whether we draw the crosshair itself in third person: vanilla's F5 has none; with Shoulder Surfing its own stays. */
    static boolean ownCrosshair(Minecraft mc) {
        return !mc.options.getCameraType().isFirstPerson() && refreshed() && !shoulderSurfing() && ClientConfig.THIRD_PERSON_CROSSHAIR.get();
    }

    /** Yaw and pitch, in degrees, from {@code from} toward {@code to}. */
    static float[] facing(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z, h = Math.sqrt(dx * dx + dz * dz);
        return new float[] {(float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f, (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG)};
    }

    /** Before a swing in an over-the-shoulder view: turns {@code p} to the point under the camera's crosshair and tells the server, so the swing's arc and aim follow it. */
    /** The assist's cone round the camera's crosshair (degrees) and its slack on the swing's reach (blocks), for LIGHT and STRONG. */
    static double cone(ClientConfig.ThirdPersonAssist a) {
        return a == ClientConfig.ThirdPersonAssist.STRONG ? 35 : a == ClientConfig.ThirdPersonAssist.LIGHT ? 20 : 0;
    }

    static double slack(ClientConfig.ThirdPersonAssist a) {
        return a == ClientConfig.ThirdPersonAssist.STRONG ? 0.5 : 0.25;
    }

    /** Degrees between direction {@code look} and the way from {@code from} to {@code to}. */
    static double angle(Vec3 look, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length() * look.length();
        return len < 1e-9 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d.dot(look) / len))));
    }

    /** Whether the assist may turn the player to {@code t}: an enemy, or anything going for the player; never their pets, a calm mob or another player. */
    private static boolean foe(LocalPlayer p, net.minecraft.world.entity.LivingEntity t) {
        if (t == p || !t.isAlive() || t.isSpectator() || t instanceof net.minecraft.world.entity.player.Player || p.isAlliedTo(t)) return false;
        if (t instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwner() == p) return false;
        return t instanceof net.minecraft.world.entity.monster.Enemy || t instanceof net.minecraft.world.entity.Mob m && m.getTarget() == p;
    }

    /**
     * Before a swing in third person from behind ({@code range}: the swing's reach): the player turns to what the camera's crosshair is on, a mob first; with no mob there and the
     * assist on ({@code [display] thirdPersonAssist}), to the foe nearest the crosshair inside its cone and within the swing's reach (plus a little slack); else, over the shoulder
     * (Shoulder Surfing), to the point under the crosshair. The server is told before the swing.
     */
    static void aimSwing(LocalPlayer p, double range) {
        Minecraft mc = Minecraft.getInstance();
        // Only from behind: in the front view the camera looks back at the player, and turning to its crosshair spun the player round, the camera with them.
        if (!refreshed() || mc.options.getCameraType() != net.minecraft.client.CameraType.THIRD_PERSON_BACK) return;
        ClientConfig.ThirdPersonAssist assist = ClientConfig.THIRD_PERSON_ASSIST.get();
        boolean shoulder = shoulderSurfing();
        if (!shoulder && assist == ClientConfig.ThirdPersonAssist.OFF) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 from = cam.getPosition(), look = Vec3.directionFromRotation(cam.getXRot(), cam.getYRot()), end = from.add(look.scale(AIM_RANGE));
        HitResult block = p.level().clip(new ClipContext(from, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        Vec3 target = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        EntityHitResult mob = ProjectileUtil.getEntityHitResult(p, from, target, new AABB(from, target).inflate(1), en -> en != p && !en.isSpectator() && en.isPickable(),
                from.distanceToSqr(target));
        String on;
        if (mob != null) {
            target = mob.getLocation();
            on = "mob";
        } else {
            net.minecraft.world.entity.LivingEntity best = null;
            double bestAngle = Double.MAX_VALUE;
            if (assist != ClientConfig.ThirdPersonAssist.OFF) {
                Vec3 eye = p.getEyePosition();
                double reach = range + slack(assist);
                for (net.minecraft.world.entity.LivingEntity t : p.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(reach + 1), t -> foe(p, t))) {
                    if (t.getBoundingBox().distanceToSqr(eye) > reach * reach || !p.hasLineOfSight(t)) continue;
                    double a = angle(look, from, t.getBoundingBox().getCenter());
                    if (a <= cone(assist) && a < bestAngle) {
                        best = t;
                        bestAngle = a;
                    }
                }
            }
            if (best != null) {
                target = best.getBoundingBox().getCenter();
                on = "assist";
            } else if (shoulder) {
                on = block.getType() == HitResult.Type.MISS ? "air" : "block";
            } else {
                return; // vanilla's view from behind: the player already looks along the camera
            }
        }
        float[] yp = facing(p.getEyePosition(), target);
        p.setYRot(yp[0]);
        p.setXRot(Mth.clamp(yp[1], -90f, 90f));
        p.setYHeadRot(yp[0]);
        p.connection.send(new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), p.onGround()));
        Diag.event("tp_aim", "yaw", Math.round(yp[0]), "pitch", Math.round(yp[1]), "on", on, "assist", assist.name());
    }
}
