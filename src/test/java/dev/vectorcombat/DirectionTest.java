package dev.vectorcombat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Run with {@code java -ea}. */
public final class DirectionTest {
    private static Direction arrow(String name) {
        return Direction.of(new AttackDef("x", List.of(), 1, 120, 1, 0.2, "a", "s", 1f, Props.BASE, name, false, -1, 0, ""));
    }

    public static void main(String[] args) {
        assert arrow("").none() && arrow("bogus").none() : "no arrow is a dot";
        assert arrow("forward").octant() == 0 && arrow("right").octant() == 2 && arrow("back").octant() == 4 && arrow("left").octant() == 6;
        assert arrow("rising").vertical() == 1 && arrow("rising").octant() == 0;
        assert arrow("falling").vertical() == -1 && arrow("falling").octant() == 4;
        for (String a : List.of("forward", "right", "back", "left", "rising", "falling")) assert !arrow(a).none() && arrow(a).octant() % 2 == 0 : "cardinal only";

        assert DirectionArrow.alpha(0) == 0.35f : "rests dim";
        assert DirectionArrow.alpha(80) > 0.85f : "flashes on a swing";
        assert DirectionArrow.alpha(5000) == 0.35f : "settles back";
        assert DirectionArrow.alpha(700) < DirectionArrow.alpha(300) : "fades monotonically";

        int[][] ring = StunCrosshair.ring(StunCrosshair.RADIUS);
        assert ring.length == 48 : "the ring at radius 8 is 48 pixels";
        assert ring[0][0] == 0 && ring[0][1] == -StunCrosshair.RADIUS : "it starts at 12 o'clock";
        Set<String> seen = new HashSet<>();
        double lastAngle = -1;
        for (int[] p : ring) {
            assert seen.add(p[0] + "," + p[1]) : "no pixel twice";
            double d = Math.hypot(p[0], p[1]);
            assert d >= 7.5 && d < 8.5 : "a pixel at distance " + d + " is off the ring";
            double a = Math.atan2(p[0], -p[1]);
            if (a < 0) a += 2 * Math.PI;
            assert a > lastAngle : "clockwise from 12 o'clock";
            lastAngle = a;
        }
        for (int[] p : ring) assert seen.contains(-p[1] + "," + p[0]) && seen.contains(-p[0] + "," + p[1]) : "a quarter turn and a mirror image land on the ring";
        assert StunCrosshair.dark(48, 1f) == 0 && StunCrosshair.dark(48, 0f) == 48 && StunCrosshair.dark(48, 0.5f) == 24 : "full, empty and half a ring";
        for (int i = 1; i <= 100; i++) assert StunCrosshair.dark(48, 1 - i / 100f) >= StunCrosshair.dark(48, 1 - (i - 1) / 100f) : "the wipe only grows as the stun runs out";
        assert StunCrosshair.fraction(0, 5) == 0 : "no total, nothing to show";
        assert StunCrosshair.fraction(20, 20) == 1 && StunCrosshair.fraction(20, 25) == 1 : "clamped at a full ring";
        assert StunCrosshair.fraction(20, 10) == 0.5f && StunCrosshair.fraction(20, -0.5f) == 0 : "half way, then clamped at empty";
        assert StunCrosshair.alpha(0) == 0 && StunCrosshair.alpha(100) == 1 && StunCrosshair.alpha(5000) == 1 : "fades in over 100 ms, then solid";
        assert StunCrosshair.alpha(30) < StunCrosshair.alpha(60) : "fades in monotonically";
        System.out.println("DirectionTest ok");
    }
}
