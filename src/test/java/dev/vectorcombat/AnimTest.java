package dev.vectorcombat;

import java.util.List;

/** Run with {@code java -ea}. */
public final class AnimTest {
    private static Anim.Key key(double t, double x, double rx) {
        return new Anim.Key(t, new double[] {x, 0, 0}, new double[] {rx, 0, 0});
    }

    public static void main(String[] args) {
        List<Anim.Key> keys = List.of(key(0, 0, 0), key(1, 1, 90), key(2, 1, 0));
        assert Anim.sample(keys, 0)[0] == 0;
        assert Anim.sample(keys, 0.5)[0] == 0.5 && Anim.sample(keys, 0.5)[3] == 45 : "smoothstep is 0.5 at the midpoint";
        assert Anim.sample(keys, 0.25)[0] < 0.25 : "eases in, so a quarter of the way is under a quarter of the distance";
        assert Anim.sample(keys, 1)[3] == 90 && Anim.sample(keys, 1.5)[3] == 45;
        assert Anim.sample(keys, 5)[0] == 1 && Anim.sample(keys, 5)[3] == 0 : "holds the last key";
        assert Anim.sample(keys, -1)[0] == 0 : "holds the first key";
        assert Anim.sample(List.of(), 0.3)[0] == 0 : "no keys means no motion";
        List<Anim.Key> in = List.of(key(0, 0, 0), new Anim.Key(1, new double[] {1, 0, 0}, new double[3], "in"));
        List<Anim.Key> out = List.of(key(0, 0, 0), new Anim.Key(1, new double[] {1, 0, 0}, new double[3], "out"));
        assert Anim.sample(in, 0.5)[0] == 0.25 && Anim.sample(out, 0.5)[0] == 0.75 : "in accelerates, out decelerates";
        assert Math.abs(Swing.lift(0.5f, 0, false) - 0.3) < 1e-6 : "lift cancels vanilla's 0.6 * equip progress";
        assert Swing.lift(0.5f, 5, false) == 0 && Swing.lift(0.5f, 0, true) == 0 : "no lift during a real switch, or when the dip is wanted";
        // A switch stays on while the new weapon is still rising (a mace takes 1.7 s, the countdown only 0.5 s), ends once it is up, and is never started here.
        assert Swing.keepSwitching(1, 0.9f) == 2 && Swing.keepSwitching(7, 0.9f) == 7 : "kept on at least 2 ticks while lowered, never shortened";
        assert Swing.keepSwitching(3, 0.05f) == 3 && Swing.keepSwitching(3, 0f) == 3 : "risen: the countdown runs out";
        assert Swing.keepSwitching(0, 0.9f) == 0 : "an attack's dip (no switch) is still cancelled";

        // A clip's trail: the blade axis path the crescent follows. Expected vectors are tools/arc_gen.py's arm_dir for the same parameters.
        Anim left = clip("{\"kind\": \"h\", \"from\": [35, 15], \"to\": [-52, 15], \"start\": 0.05, \"end\": 0.18}");
        near(left.trail().direction(0.5), -0.142773, 0.258819, -0.955316, "h: azimuth halfway at a fixed elevation");
        near(left.trail().direction(0), 0.554032, 0.258819, -0.79124, "h: starts at the from azimuth");
        near(clip("{\"kind\": \"v\", \"from\": [-8, 50], \"to\": [-8, 10], \"start\": 0.05, \"end\": 0.18}").trail().direction(0.5), -0.120527, 0.5, -0.857597, "v: elevation halfway at a fixed azimuth");
        Anim.Trail swing = clip("{\"kind\": \"d\", \"from\": [30, 35], \"to\": [-30.5, 1.8], \"start\": 0.04, \"end\": 0.15}").trail();
        near(swing.direction(0.5), -0.057957, 0.358844, -0.931597, "d: the great circle halfway");
        near(swing.direction(1), -0.507288, 0.031411, -0.861204, "d: ends at the to direction");
        Anim.Trail joust = clip("{\"kind\": \"t\", \"from\": [-8, 11], \"to\": [-8, 11], \"start\": 0.05, \"end\": 0.18, \"pow\": 2, \"reach\": [0.3, 0.74]}").trail();
        near(joust.direction(0.7), -0.136616, 0.190809, -0.972074, "t: one fixed line");
        assert joust.thrust() && !left.trail().thrust() && Math.abs(joust.reachAt(0.5) - 0.52) < 1e-9 && left.trail().reachAt(0.5) == 0 : "only a thrust reaches";
        assert left.trail().progress(0.0) == 0 && left.trail().progress(0.05) == 0 && left.trail().progress(0.18) == 1 && left.trail().progress(9) == 1 : "0 before the strike, 1 from its end";
        assert Math.abs(left.trail().progress(0.115) - 0.5) < 1e-9 && Math.abs(joust.progress(0.115) - 0.25) < 1e-9 : "linear along a sweep, accelerating along a thrust (pow 2)";
        assert clip("null").trail() == null : "a clip without a trail has none";
        System.out.println("AnimTest ok");
    }

    private static Anim clip(String trail) {
        String json = "{\"id\": \"x\", \"length\": 0.5, \"impact\": 0.18, \"hand\": [{\"t\": 0, \"pos\": [0, 0, 0], \"rot\": [0, 0, 0]}]" + (trail.equals("null") ? "" : ", \"trail\": " + trail) + "}";
        return Anim.parse(com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    private static void near(double[] v, double x, double y, double z, String what) {
        assert Math.abs(v[0] - x) < 1e-5 && Math.abs(v[1] - y) < 1e-5 && Math.abs(v[2] - z) < 1e-5 : what + ": " + java.util.Arrays.toString(v);
    }
}
