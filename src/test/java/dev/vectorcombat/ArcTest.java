package dev.vectorcombat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Run with {@code java -ea}. The attacker's eye is at (0, 1.6, 0) facing +z (yaw 0); a target is a 0.6 x 1.8 box. */
public final class ArcTest {
    private static final Vec3 EYE = new Vec3(0, 1.6, 0);

    private static AABB target(double x, double z) {
        return new AABB(x - 0.3, 0, z - 0.3, x + 0.3, 1.8, z + 0.3);
    }

    public static void main(String[] args) {
        assert Arc.hits(EYE, 0, target(0, 2), 3, 120) : "straight ahead";
        assert !Arc.hits(EYE, 0, target(0, -2), 3, 180) : "behind never hits, even a half-circle sweep";
        assert !Arc.hits(EYE, 0, target(0, 4.5), 3, 120) : "out of range";
        assert Arc.hits(EYE, 180, target(0, -2), 3, 120) : "facing yaw 180 hits -z";

        // Minecraft yaw 90 faces -x, so a target at -x is "ahead" there.
        assert Arc.hits(EYE, 90, target(-2, 0), 3, 120);

        // 80 degrees off to the side: a 120 arc misses it, a 180 sweep catches it.
        assert !Arc.hits(EYE, 0, target(-2, 0.35), 3, 120);
        assert Arc.hits(EYE, 0, target(-2, 0.35), 3, 180);

        // A thrust (angle 0) still has a forgiving cone, but not a wide one.
        assert Arc.hits(EYE, 0, target(-0.5, 2), 3, 0);
        assert !Arc.hits(EYE, 0, target(-0.93, 2), 3, 0);
        System.out.println("ArcTest ok");
    }
}
