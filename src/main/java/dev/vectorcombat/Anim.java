package dev.vectorcombat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A first-person swing: keyframes for the held item (offset in blocks, rotation in degrees) and for the camera
 * (pitch, yaw, roll in degrees), eased between keys (per key: in, out, linear, or smooth by default). {@code impact} is the second the blow
 * lands, which an attack's {@code hit_at} must match. {@code trail} (null for none) is the path the crescent follows. Data lives in {@code vectorcombat/animations/*.json}.
 */
public record Anim(String id, double length, double impact, List<Key> hand, List<Key> camera, Trail trail) {
    /** {@code pos} is x (right), y (up), z (toward the viewer); {@code rot} is rotation about x, y, z. */
    public record Key(double t, double[] pos, double[] rot, String ease) {
        public Key(double t, double[] pos, double[] rot) {
            this(t, pos, rot, "");
        }
    }

    /**
     * The path the blade axis travels during the strike, which {@link Trails} draws a crescent along. {@code from} and {@code to} are {azimuth, elevation} of
     * the blade axis in view space, in degrees (0 = forward, positive = right); {@code start} and {@code end} are the clip seconds it travels them (windup end
     * to impact). {@code kind} says how it gets there: h azimuth at a fixed elevation, v elevation at a fixed azimuth, d along the great circle, t a thrust that
     * stays on one line and only reaches out ({@code reach} from the shoulder). {@code pow} above 1 makes the strike accelerate. These are the numbers the
     * generator builds the clip from, so the crescent runs exactly where the blade does.
     */
    public record Trail(String kind, double[] from, double[] to, double start, double end, double pow, double[] reach) {
        public static final List<String> KINDS = List.of("h", "v", "d", "t");

        public boolean thrust() {
            return kind.equals("t");
        }

        /** How far along the path the blade is at clip second {@code t}: 0 before {@code start}, 1 from {@code end}. */
        public double progress(double t) {
            if (end <= start) return t >= end ? 1 : 0;
            return Math.pow(Math.max(0, Math.min(1, (t - start) / (end - start))), pow);
        }

        /** Unit vector in view space (x right, y up, -z forward) for azimuth and elevation in degrees. */
        static double[] unit(double azimuth, double elevation) {
            double a = Math.toRadians(azimuth), e = Math.toRadians(elevation);
            return new double[] {Math.sin(a) * Math.cos(e), Math.sin(e), -Math.cos(a) * Math.cos(e)};
        }

        /** The blade axis in view space at progress {@code s} (0 to 1). */
        public double[] direction(double s) {
            switch (kind) {
                case "h":
                    return unit(from[0] + (to[0] - from[0]) * s, from[1]);
                case "v":
                    return unit(from[0], from[1] + (to[1] - from[1]) * s);
                case "d": {
                    double[] a = unit(from[0], from[1]), b = unit(to[0], to[1]);
                    double w = Math.acos(Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])));
                    if (w < 1e-9) return a;
                    double[] out = new double[3];
                    for (int i = 0; i < 3; i++) out[i] = (Math.sin((1 - s) * w) * a[i] + Math.sin(s * w) * b[i]) / Math.sin(w);
                    return out;
                }
                default:
                    return unit(from[0], from[1]);
            }
        }

        /** A thrust's distance from the shoulder to the grip at progress {@code s}. */
        public double reachAt(double s) {
            return reach == null ? 0 : reach[0] + (reach[1] - reach[0]) * s;
        }
    }

    public static final Anim NONE = new Anim("none", 0, 0, List.of(), List.of(), null);
    private static volatile Map<String, Anim> clips = Map.of();
    private static volatile List<String> raw = List.of();

    public static List<String> raw() {
        return raw;
    }

    public static Anim get(String id) {
        return clips.getOrDefault(id, NONE);
    }

    public static void load(List<String> jsons) {
        Map<String, Anim> out = new HashMap<>();
        for (String json : jsons) {
            try {
                for (JsonObject o : Attacks.objects(JsonParser.parseString(json))) {
                    Anim a = parse(o);
                    out.put(a.id(), a);
                }
            } catch (RuntimeException e) {
                VectorCombat.LOG.error("Skipping bad animation file: {}", json, e);
            }
        }
        clips = Map.copyOf(out);
        raw = List.copyOf(jsons);
    }

    private static double[] vec(JsonObject o, String key) {
        double[] v = new double[3];
        if (o.has(key)) {
            for (int i = 0; i < 3; i++) v[i] = o.getAsJsonArray(key).get(i).getAsDouble();
        }
        return v;
    }

    private static List<Key> keys(JsonObject o, String key) {
        List<Key> out = new ArrayList<>();
        if (o.has(key)) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                JsonObject k = e.getAsJsonObject();
                out.add(new Key(k.get("t").getAsDouble(), vec(k, "pos"), vec(k, "rot"), k.has("ease") ? k.get("ease").getAsString() : ""));
            }
        }
        return List.copyOf(out);
    }

    private static double[] pair(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    private static Trail trail(JsonObject o) {
        if (!o.has("trail")) return null;
        JsonObject t = o.getAsJsonObject("trail");
        String kind = t.get("kind").getAsString();
        if (!Trail.KINDS.contains(kind)) throw new IllegalArgumentException("unknown trail kind " + kind);
        return new Trail(kind, pair(t, "from"), pair(t, "to"), t.get("start").getAsDouble(), t.get("end").getAsDouble(), t.has("pow") ? t.get("pow").getAsDouble() : 1,
                t.has("reach") ? pair(t, "reach") : null);
    }

    static Anim parse(JsonObject o) {
        return new Anim(o.get("id").getAsString(), o.get("length").getAsDouble(), o.has("impact") ? o.get("impact").getAsDouble() : 0, keys(o, "hand"), keys(o, "camera"), trail(o));
    }

    /** Eased pose {x, y, z, rx, ry, rz} at {@code t} seconds; holds the first and last keys outside their range. */
    public static double[] sample(List<Key> keys, double t) {
        double[] out = new double[6];
        if (keys.isEmpty()) return out;
        Key a = keys.get(0), b = a;
        for (Key k : keys) {
            if (k.t() <= t) a = b = k;
            else {
                b = k;
                break;
            }
        }
        double u = b.t() > a.t() ? Math.min(1, Math.max(0, (t - a.t()) / (b.t() - a.t()))) : 0;
        // ease belongs to the segment ending at b: "in" accelerates into the key (the strike), "out" decelerates (windup, recovery).
        double s = switch (b.ease()) {
            case "in" -> u * u;
            case "out" -> 1 - (1 - u) * (1 - u);
            case "linear" -> u;
            default -> u * u * (3 - 2 * u);
        };
        for (int i = 0; i < 3; i++) {
            out[i] = a.pos()[i] + (b.pos()[i] - a.pos()[i]) * s;
            out[3 + i] = a.rot()[i] + (b.rot()[i] - a.rot()[i]) * s;
        }
        return out;
    }
}
