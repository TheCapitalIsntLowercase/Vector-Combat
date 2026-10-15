package dev.vectorcombat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Melee hit geometry: a horizontal arc in front of the attacker, measured from the eye to the target's bounding box. */
public final class Arc {
    /** Even a thrust (angle 0) gets this much cone, so it is forgiving rather than pixel-perfect. */
    static final double MIN_ANGLE = 30;

    /**
     * @param yawDeg   attacker facing, Minecraft yaw (0 = +z)
     * @param range    max distance from the eye to the target's box
     * @param angleDeg total arc width centred on the facing
     */
    public static boolean hits(Vec3 eye, float yawDeg, AABB box, double range, double angleDeg) {
        if (box.distanceToSqr(eye) > range * range) return false;
        Vec3 c = box.getCenter();
        double dx = c.x - eye.x, dz = c.z - eye.z;
        double flat = Math.hypot(dx, dz);
        if (flat < 0.3) return true; // standing in the target
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        double off = Math.abs(Mth.wrapDegrees(towards - yawDeg));
        // Wide targets subtend more of the arc than their centre does.
        double halfBody = Math.toDegrees(Math.atan2(Math.max(box.getXsize(), box.getZsize()) / 2, flat));
        return off <= Math.max(angleDeg, MIN_ANGLE) / 2 + halfBody;
    }
}
