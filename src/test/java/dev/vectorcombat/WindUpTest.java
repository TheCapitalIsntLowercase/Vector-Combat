package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;

/**
 * A melee mob's wind-up: the pure state machine (when it starts, holds, lands, whiffs or is cancelled, and that it keeps vanilla's attack rate), the lean the clients draw
 * (its shape, how far it goes for each real mob, which way is back) and its message. Run with {@code java -ea}.
 */
public final class WindUpTest {
    private static void is(WindUp.Step s, boolean hold, long until, String event, String what) {
        assert s.hold() == hold && s.until() == until && java.util.Objects.equals(s.event(), event) : what + ": " + s;
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        assert Math.abs(actual - expected) <= tolerance : what + ": " + actual + " instead of " + expected;
    }

    public static void main(String[] args) throws Exception {
        // Not winding up: nothing to do until the mob is ready and the target is in reach; then it starts, held, ending 8 ticks on.
        is(WindUp.next(100, 0, false, true, false, 8), false, 0, null, "a mob on cooldown is left to vanilla");
        is(WindUp.next(100, 0, true, false, false, 8), false, 0, null, "a target out of reach is left to vanilla");
        is(WindUp.next(100, 0, true, true, false, 8), true, 108, "start", "ready and in reach starts the wind-up");
        is(WindUp.next(100, 0, true, true, true, 8), true, 0, null, "a mob that was just hurt waits instead of hitting at once");

        // Winding up to 108: held every tick until then, and at the end the hit lands if the target is still there, else it whiffs.
        for (long t = 101; t < 108; t++) is(WindUp.next(t, 108, true, true, false, 8), true, 108, null, "held at " + t);
        is(WindUp.next(105, 108, true, false, false, 8), true, 108, null, "stepping out early does not end it: the mob stands and waits");
        is(WindUp.next(108, 108, true, true, false, 8), false, 0, "land", "at the end, in reach: vanilla's attack runs");
        is(WindUp.next(108, 108, true, false, false, 8), true, 0, "whiff", "at the end, out of reach: no attack");
        is(WindUp.next(110, 108, true, true, false, 8), false, 0, "land", "a tick or two late still lands");

        // A hit on the mob cancels it; a stall (stun, hitstop) past the end drops it.
        is(WindUp.next(104, 108, true, true, true, 8), true, 0, "interrupted", "hurt mid wind-up");
        is(WindUp.next(108 + WindUp.STALE, 108, true, true, false, 8), false, 0, "land", "right at the stale limit it still lands");
        is(WindUp.next(108 + WindUp.STALE + 1, 108, true, true, false, 8), true, 0, "stale", "after the stale limit it is dropped, not landed");

        // The rate stays vanilla's: simulate a goal with a 20 tick cooldown, in reach all the time; hits land 20 ticks apart, the first after the wind-up.
        long until = 0;
        int cooldown = 0;
        java.util.List<Long> lands = new java.util.ArrayList<>();
        for (long now = 0; now < 200; now++) {
            cooldown = Math.max(cooldown - 1, 0);
            WindUp.Step s = WindUp.next(now, until, cooldown <= 0, true, false, WindUp.DEFAULT_TICKS);
            until = s.until();
            if (!s.hold() && cooldown <= 0) { // vanilla's attack: swing, hit, reset the cooldown
                lands.add(now);
                cooldown = WindUp.afterLanding(20, WindUp.DEFAULT_TICKS);
            }
        }
        assert lands.size() >= 9 && lands.get(0) == WindUp.DEFAULT_TICKS : "the first hit lands after the wind-up: " + lands;
        for (int i = 1; i < lands.size(); i++) assert lands.get(i) - lands.get(i - 1) == 20 : "hits stay 20 ticks apart as in vanilla: " + lands;
        assert WindUp.afterLanding(20, 12) == 8 && WindUp.afterLanding(20, 30) == 0 : "the cooldown never goes negative";

        // A miss is paid for like a hit: the same simulation with the player gone at every end, one whiff per 20 ticks.
        until = 0;
        cooldown = 0;
        java.util.List<Long> whiffs = new java.util.ArrayList<>();
        for (long now = 0; now < 200; now++) {
            cooldown = Math.max(cooldown - 1, 0);
            WindUp.Step s = WindUp.next(now, until, cooldown <= 0, until == 0, false, WindUp.DEFAULT_TICKS); // in reach only to start it
            until = s.until();
            if ("whiff".equals(s.event())) {
                whiffs.add(now);
                cooldown = WindUp.afterLanding(20, WindUp.DEFAULT_TICKS);
            }
        }
        assert whiffs.size() >= 9 && whiffs.get(0) == WindUp.DEFAULT_TICKS : "the first whiff comes after the wind-up: " + whiffs;
        for (int i = 1; i < whiffs.size(); i++) assert whiffs.get(i) - whiffs.get(i - 1) == 20 : "a dodge opens the same 12 tick recovery: " + whiffs;

        // The tell is lower for a longer wind-up, within a range.
        assert Math.abs(WindUp.pitch(8) - 0.9f) < 1e-6f && Math.abs(WindUp.pitch(12) - 0.6f) < 1e-6f && WindUp.pitch(40) == 0.6f && WindUp.pitch(1) == 1.2f : "pitch";
        for (int t = 1; t < 30; t++) assert WindUp.pitch(t + 1) <= WindUp.pitch(t) : "a longer wind-up is never higher";

        // ---- the lean, as a fraction of the full rear-back ----
        int start = WindUpPayload.START, strike = WindUpPayload.STRIKE, cancel = WindUpPayload.CANCEL;
        assert WindUp.lean(start, 0, 0, 8) == 0 && Math.abs(WindUp.lean(start, 0, 8, 8) - 1) < 1e-6 && WindUp.lean(start, 0, 20, 8) == 1 : "START rears back over the wind-up and stays";
        assert WindUp.lean(start, 0, 2, 8) > 0.4f : "the first quarter of the wind-up already shows (ease-out)";
        assert WindUp.lean(start, 0.5f, 0, 8) == 0.5f : "a START continues from where the mob was";
        float prev = 0;
        for (float t = 0; t <= 8; t += 1 / 16f) {
            float v = WindUp.lean(start, 0, t, 8);
            assert v >= prev - 1e-6f && v <= 1 + 1e-6f : "the rear-back only grows, to 1: " + v + " at " + t;
            prev = v;
        }
        assert Math.abs(WindUp.lean(strike, 1, 0, 8) - 1) < 1e-6 && Math.abs(WindUp.lean(strike, 1, WindUp.SNAP, 8) + WindUp.LUNGE) < 1e-6 : "STRIKE snaps from the rear-back to past upright";
        assert WindUp.lean(strike, 1, WindUp.SNAP + WindUp.HOLD / 2, 8) == -WindUp.LUNGE : "and holds there a moment";
        assert WindUp.lean(strike, 1, 1, 8) > 0 && WindUp.lean(strike, 1, 1.9f, 8) < 0 : "it crosses upright only near the end of the snap (ease-in), so the snap is a snap";
        assert WindUp.length(strike, 8) == WindUp.SNAP + WindUp.HOLD + WindUp.RECOVER && WindUp.length(cancel, 8) == WindUp.CANCEL_TICKS && WindUp.length(start, 8) == 8 + WindUp.LOST + WindUp.CANCEL_TICKS : "lengths";
        for (int phase : new int[] {start, strike, cancel}) {
            for (float from : new float[] {0, 0.3f, 1, -0.4f}) {
                float last = WindUp.lean(phase, from, 0, 8);
                assert Math.abs(last - from) < 1e-6 : "phase " + phase + " begins where the last one left the mob (" + from + "): " + last;
                for (float t = 1 / 32f; t <= WindUp.length(phase, 8) + 5; t += 1 / 32f) {
                    float v = WindUp.lean(phase, from, t, 8);
                    assert Math.abs(v - last) < 0.06f : "phase " + phase + " from " + from + " jumps from " + last + " to " + v + " at " + t;
                    assert v <= Math.max(1, from) + 1e-6f && v >= Math.min(-WindUp.LUNGE, from) - 1e-6f : "phase " + phase + " from " + from + " leaves its range: " + v + " at " + t;
                    last = v;
                }
                assert Math.abs(last) < 1e-6 : "phase " + phase + " from " + from + " ends upright, not " + last;
            }
        }

        // ---- how far, for each real mob (heights from the game's own entity types) ----
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        java.lang.reflect.Field reach = net.minecraft.world.entity.Mob.class.getDeclaredField("DEFAULT_ATTACK_REACH");
        reach.setAccessible(true);
        near(WindUp.REACH, (double) reach.get(null), 1e-12, "the reach is vanilla's melee reach");
        near(WindUp.peakDegrees(EntityType.ZOMBIE.getHeight()), 25.1, 0.1, "zombie");
        near(WindUp.peakDegrees(EntityType.VINDICATOR.getHeight()), 25.1, 0.1, "vindicator");
        near(WindUp.peakDegrees(EntityType.RAVAGER.getHeight()), 22.1, 0.1, "ravager");
        near(WindUp.peakDegrees(EntityType.IRON_GOLEM.getHeight()), 17.9, 0.1, "iron golem");
        near(WindUp.peakDegrees(EntityType.ENDERMAN.getHeight()), 16.6, 0.1, "enderman");
        assert WindUp.peakDegrees(EntityType.SPIDER.getHeight()) == 35 && WindUp.peakDegrees(EntityType.CAVE_SPIDER.getHeight()) == 35 : "a spider rears as far as it can";
        assert WindUp.peakDegrees(EntityType.GIANT.getHeight()) == 8 : "a giant barely leans";
        assert WindUp.peakDegrees(EntityType.SILVERFISH.getHeight()) == WindUp.SMALL_DEGREES && WindUp.peakDegrees(EntityType.ENDERMITE.getHeight()) == WindUp.SMALL_DEGREES
                : "a scuttler rocks, it does not flip";
        float lastDeg = 36;
        for (float h = WindUp.SMALL_HEIGHT; h < 13; h += 0.1f) {
            assert WindUp.peakDegrees(h) <= lastDeg + 1e-4f && WindUp.peakDegrees(h) >= 8 && WindUp.peakDegrees(h) <= 35 : "a taller mob never leans more: " + h;
            lastDeg = WindUp.peakDegrees(h);
        }

        // ---- the overhead (the vindicator's signature): arms up through the wind-up, through their own pose to the slam, back to the model's ----
        for (float base : new float[] {-1.57f, -1.2f, -2.0f, 0f}) {
            near(WindUp.overheadArm(base, 0), base, 1e-6, "upright the arm is the model's own (" + base + ")");
            near(WindUp.overheadArm(base, 1), WindUp.OVERHEAD, 1e-6, "at the top of the rear-back it is over the head (" + base + ")");
            near(WindUp.overheadArm(base, -WindUp.LUNGE), WindUp.SLAM, 1e-6, "at the end of the snap it is slammed down (" + base + ")");
        }
        assert Math.toDegrees(-WindUp.OVERHEAD) > 150 && Math.toDegrees(-WindUp.SLAM) < 45 : "over the head means past straight up's 90; the slam ends below level";
        float lastArm = WindUp.overheadArm(-1.57f, 0);
        for (float t = 1 / 32f; t <= 14; t += 1 / 32f) { // a 14 tick wind-up (the iron axe's): the arm only rises
            float a = WindUp.overheadArm(-1.57f, WindUp.lean(start, 0, t, 14));
            assert a <= lastArm + 1e-6f : "the arm only rises through the wind-up: " + lastArm + " to " + a + " at " + t;
            lastArm = a;
        }
        float lastSlam = WindUp.overheadArm(-1.57f, 1);
        for (float t = 1 / 32f; t <= WindUp.SNAP; t += 1 / 32f) {
            float a = WindUp.overheadArm(-1.57f, WindUp.lean(strike, 1, t, 14));
            assert a >= lastSlam - 1e-6f : "the slam only comes down: " + lastSlam + " to " + a + " at " + t;
            lastSlam = a;
        }
        near(WindUp.overheadArm(-1.57f, WindUp.lean(strike, 1, WindUp.length(strike, 14), 14)), -1.57f, 1e-5, "and the arm is the model's own once it has straightened");
        assert WindUp.style("bite", "overhead") == WindUpPayload.OVERHEAD && WindUp.style("bite", null) == WindUpPayload.LEAN && WindUp.style("pounce", "overhead") == WindUpPayload.LEAN
                && WindUp.style("blink", "overhead") == WindUpPayload.LEAN && WindUp.style("bite", "mystery") == WindUpPayload.LEAN : "only a bite takes the signature, and only a known one";
        assert WindUp.OVERHEAD_LEAN > 0 && WindUp.OVERHEAD_LEAN < 1 : "the body leans less under an overhead";

        // ---- the juke (the enderman's signature) ----
        assert !Juke.wants(1.9, true) && Juke.wants(2, true) && Juke.wants(8, true) && !Juke.wants(8.1, true) && !Juke.wants(5, false) : "2 to 8 blocks, in sight";
        double endermanReach = EntityType.ENDERMAN.getWidth() / 2 + WindUp.REACH + 0.5 + EntityType.PLAYER.getWidth() / 2; // centres apart when the boxes just touch, square on, with its extra half block
        assert Juke.LAND > endermanReach && Juke.LAND < endermanReach + 0.6 : "it lands a short step out of reach (" + endermanReach + "), so the wind-up plays before the hit";
        for (double[] at : new double[][] {{6, 0}, {0, -5}, {-3, 3}, {2.5, 7}}) {
            double base = Math.atan2(at[1], at[0]);
            net.minecraft.world.phys.Vec3 l = Juke.spot(0, 0, at[0], at[1], 1), r = Juke.spot(0, 0, at[0], at[1], -1);
            for (net.minecraft.world.phys.Vec3 v : new net.minecraft.world.phys.Vec3[] {l, r}) {
                near(Math.hypot(v.x, v.z), Juke.LAND, 1e-9, "it lands LAND from the player");
                double off = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (v.x * Math.cos(base) + v.z * Math.sin(base)) / Juke.LAND))));
                near(off, Juke.angle(Math.hypot(at[0], at[1])), 1e-6, "the angle off the line to where it stood");
                assert off >= Juke.MIN_ANGLE - 1e-9 && off <= Juke.MAX_ANGLE + 1e-9 : "still in front of the player, never behind: " + off;
                double jump = Math.hypot(v.x - at[0], v.z - at[1]);
                if (off > Juke.MIN_ANGLE + 1e-6 && off < Juke.MAX_ANGLE - 1e-6) near(jump, Juke.TRAVEL, 1e-6, "between the limits the jump is TRAVEL long");
                else if (off >= Juke.MAX_ANGLE - 1e-6) assert jump < Juke.TRAVEL + 1e-6 : "close in, at the side, it is shorter: " + jump;
                else assert jump > Juke.TRAVEL - 1e-6 : "from far, at the narrowest angle, it is longer (it closes in as well): " + jump;
            }
            assert (l.x * -Math.sin(base) + l.z * Math.cos(base)) * (r.x * -Math.sin(base) + r.z * Math.cos(base)) < 0 : "left and right are the two sides";
        }
        assert Juke.feints(0) && Juke.feints(Juke.FEINT_CHANCE - 1e-9) && !Juke.feints(Juke.FEINT_CHANCE) && !Juke.feints(0.99) : "a feint 35% of the time";
        assert Juke.FOLLOW > 5 : "a parry raised on the feint's snap has closed when the real hit lands";
        assert Juke.TELL == 10 && Juke.WIND_UP == 14 && Juke.WIND_UP > WindUp.derived(24) : "a slower shiver and a slower attack out of the juke than the plain bite";
        near(Math.hypot(Juke.spot(0, 0, 2.2, 0, 1).x - 2.2, Juke.spot(0, 0, 2.2, 0, 1).z), Math.hypot(2.2, Juke.LAND), 1e-6, "from close in it swings out to the player's side (90 degrees)");
        assert Juke.angle(6) > Juke.MIN_ANGLE && Juke.angle(6) < Juke.MAX_ANGLE && Juke.angle(2.2) == Juke.MAX_ANGLE && Juke.angle(8) >= Juke.MIN_ANGLE : "angles";
        for (double dist = Juke.MIN; dist <= Juke.MAX; dist += 0.25) { // at least 3 blocks from anywhere it jukes
            net.minecraft.world.phys.Vec3 v = Juke.spot(0, 0, dist, 0, -1);
            assert Math.hypot(v.x - dist, v.z) > 3 : "a juke from " + dist + " blocks travels more than 3: " + Math.hypot(v.x - dist, v.z);
        }
        assert WindUp.style("juke_tell", null) == WindUpPayload.SHIVER && WindUp.style("juke", null) == WindUpPayload.LEAN && WindUp.style("feint", null) == WindUpPayload.LEAN
                && WindUp.style("followup", null) == WindUpPayload.LEAN : "the tell shivers, the attacks lean, feint and real alike";
        for (float t = 0; t <= Juke.TELL + 2; t += 1 / 16f) assert Math.abs(WindUp.shiver(t, Juke.TELL)) <= WindUp.SHIVER_DEGREES + 1e-6f : "the shiver stays small";
        assert WindUp.shiver(0, Juke.TELL) == 0 && WindUp.shiver(Juke.TELL + 0.5f, Juke.TELL) == 0 : "it starts and ends still";
        float shiverMax = 0;
        for (float t = Juke.TELL / 2f; t <= Juke.TELL; t += 1 / 16f) shiverMax = Math.max(shiverMax, Math.abs(WindUp.shiver(t, Juke.TELL)));
        assert shiverMax > WindUp.SHIVER_DEGREES * 0.9f : "and shakes at full size over the second half: " + shiverMax;
        // the base attack's rate (data: the enderman 1.2 s, the scuttlers 1.1 s): vanilla's 20 tick cooldown plus what the data adds
        assert WindUp.interval((Double) null) == 20 && WindUp.interval(1.2) == 24 && WindUp.interval(1.1) == 22 : "intervals";
        assert WindUp.slower(24) == 4 && WindUp.slower(22) == 2 && WindUp.slower(20) == 0 && WindUp.slower(10) == 0 : "what each adds to vanilla's cooldown";
        assert WindUp.pick(null, 0.35, 22) == 7 : "the scuttler's tell is 7 ticks, 10% over the old 6";
        assert WindUp.derived(24) == 10 : "the enderman's tell follows its slower rhythm (10 ticks)";
        for (int interval : new int[] {20, 22, 24}) { // the rate a mob keeps: wind-up, then the cooldown less the wind-up plus the slowdown, is its interval
            int windUp = WindUp.derived(interval);
            assert windUp + WindUp.afterLanding(20, windUp) + WindUp.slower(interval) == interval : "a mob of interval " + interval + " attacks every " + interval + " ticks";
        }

        // ---- the vex's dash ----
        for (double a : new double[] {0, 90, 200, -30}) near(VexDash.orbit(1, 2, a).subtract(1, 0, 2).length(), VexDash.RADIUS, 1e-9, "the orbit is RADIUS out at " + a);
        near(VexDash.hoverY(64, null), 64 + VexDash.HEIGHT, 1e-9, "above the player's feet");
        near(VexDash.hoverY(64, 63.0), 64 + VexDash.HEIGHT, 1e-9, "the floor under it lower than that changes nothing");
        near(VexDash.hoverY(64, 66.0), 66 + VexDash.CLEARANCE, 1e-9, "over a ledge it rises to stay CLEARANCE above the floor, never in it");
        assert VexDash.opening(0.5f, true, 0) && VexDash.opening(1f, false, 0) && VexDash.opening(1f, true, 90) && !VexDash.opening(1f, true, 30) : "openings: a swing, a lowered shield, a look away";
        assert !VexDash.commits(VexDash.MIN_HOVER - 1, true) && VexDash.commits(VexDash.MIN_HOVER, true) && !VexDash.commits(VexDash.MAX_HOVER - 1, false) && VexDash.commits(VexDash.MAX_HOVER, false)
                : "it watches at least MIN_HOVER, takes an opening after, and goes by MAX_HOVER anyway";
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(0, 67, 0), chest = new net.minecraft.world.phys.Vec3(3, 64.9, 4);
        net.minecraft.world.phys.Vec3[] u = VexDash.swoop(from, chest);
        near(u[0].distanceTo(from), 0, 1e-9, "the swoop starts where the vex is");
        near(VexDash.bezier(u[0], u[1], u[2], 0.5).distanceTo(chest), 0, 1e-9, "its middle is the player's chest");
        near(u[2].y, from.y, 1e-9, "it ends level with where it started");
        near(Math.hypot(u[2].x - chest.x, u[2].z - chest.z), Math.hypot(from.x - chest.x, from.z - chest.z), 1e-9, "as far past the player as it started before");
        double lowest = 1e9;
        for (double t = 0; t <= 1; t += 1 / 256.0) lowest = Math.min(lowest, VexDash.bezier(u[0], u[1], u[2], t).y);
        near(lowest, chest.y, 1e-4, "nowhere lower than the chest, so never into flat ground under the player");
        net.minecraft.world.phys.Vec3 low = new net.minecraft.world.phys.Vec3(0, 64, 0);
        net.minecraft.world.phys.Vec3[] flat = VexDash.swoop(low, chest);
        for (double t = 0; t <= 1; t += 1 / 64.0) assert VexDash.bezier(flat[0], flat[1], flat[2], t).y >= chest.y - 1e-9 : "from below the chest it flies level, not under it";
        int steps = VexDash.steps(u[0], u[1], u[2]);
        assert steps * VexDash.SPEED >= 2 * from.distanceTo(chest) - 1e-6 && steps < 40 : "the swoop's ticks cover its length at SPEED: " + steps;
        assert VexDash.TELL >= 8 && VexDash.SPEED < 1 : "a tell the eye catches and a swoop that can be sidestepped";
        double[] f = {67, 0};
        int ticks = 0;
        while (f[0] > 64 && ticks < 40) {
            f = VexDash.fall(f[0], f[1], 64.0);
            ticks++;
        }
        assert f[0] == 64 && ticks < 12 : "a parried vex falls three blocks to the floor in under 12 ticks and stops on it: " + ticks;
        assert VexDash.fall(64, 0.5, 64.0)[0] == 64 : "and lies there, never in it";
        assert VexDash.fall(70, 0.5, null)[0] == 70 : "with no floor in reach it stays";
        assert VexDash.RETREAT_RADIUS > VexDash.RADIUS : "after a parry it makes distance";

        // ---- the enderman's limbs ----
        assert WindUp.style("bite", "limbs", false) == WindUpPayload.FLAIL && WindUp.style("juke", "limbs", true) == WindUpPayload.KICK && WindUp.style("followup", "limbs", true) == WindUpPayload.KICK
                && WindUp.style("juke_tell", "limbs", true) == WindUpPayload.SHIVER && WindUp.style("pounce", "limbs", true) == WindUpPayload.LEAN : "a limbs mob's attacks flail or kick; its shiver shivers";
        for (float base : new float[] {0f, -0.4f}) {
            near(WindUp.flailArmX(base, 0), base, 1e-6, "upright the arm is the model's");
            near(WindUp.flailArmX(base, 1), WindUp.FLAIL_UP, 1e-6, "raised at the top");
            near(WindUp.flailArmX(base, -WindUp.LUNGE), WindUp.FLAIL_SWEEP, 1e-6, "swept down at the snap");
            near(WindUp.kickLegX(base, 1), base + WindUp.KICK_CHAMBER, 1e-6, "the leg cocked back at the top");
            near(WindUp.kickLegX(base, -WindUp.LUNGE), WindUp.KICK_OUT, 1e-6, "kicked out at the snap");
            near(WindUp.kickLegX(base, 0), base, 1e-6, "and its own when upright");
        }
        assert WindUp.square(0) == 0 && WindUp.square(1) == 1 && WindUp.square(-WindUp.LUNGE) == 1 && WindUp.square(0.5f) == 0.5f : "the arms are squared onto one axis at the top and the snap";

        // ---- arm tells for humanoids ----
        int oneH = WindUp.ONE_HANDED, twoH = WindUp.TWO_HANDED, none = WindUp.UNARMED;
        assert WindUp.style("bite", null, false, oneH) == WindUpPayload.WEAPON && WindUp.style("bite", null, false, twoH) == WindUpPayload.OVERHEAD
                && WindUp.style("bite", null, false, none) == WindUpPayload.LEAN : "a one-handed weapon raises its arm, a two-handed one both, bare hands claw";
        assert WindUp.style("bite", "lean", false, oneH) == WindUpPayload.PLAIN && WindUp.style("bite", "overhead", false, none) == WindUpPayload.OVERHEAD
                && WindUp.style("bite", "limbs", true, oneH) == WindUpPayload.KICK && WindUp.style("pounce", null, false, oneH) == WindUpPayload.LEAN
                && WindUp.style("juke_tell", "limbs", false, oneH) == WindUpPayload.SHIVER : "the data's signature wins over the weapon; the opt-out keeps the plain lean";
        for (float base : new float[] {-1.5f, 0f, -0.2f}) {
            near(WindUp.weaponArmX(base, 0), base, 1e-6, "upright the weapon arm is the model's");
            near(WindUp.weaponArmX(base, 1), WindUp.WEAPON_UP, 1e-6, "over the shoulder at the top");
            near(WindUp.weaponArmX(base, -WindUp.LUNGE), WindUp.WEAPON_CHOP, 1e-6, "chopped down at the snap");
            near(WindUp.clawArmX(base, 1), base - WindUp.CLAW_LIFT, 1e-6, "a claw lifts the arms above their own pose");
            near(WindUp.clawArmX(base, -WindUp.LUNGE), base + WindUp.CLAW_DROP, 1e-6, "and drops them below it at the snap");
        }
        assert Math.toDegrees(-WindUp.WEAPON_UP) > 150 && Math.toDegrees(-WindUp.WEAPON_CHOP) < 45 : "over the shoulder means past straight up; the chop ends below level";
        assert Math.toDegrees(-WindUp.KICK_OUT) > 90 : "a front kick reaches past level";

        // ---- landed hits: absorption counts ----
        assert Combat.landed(0, 0.17f) && Combat.landed(1, 0) && !Combat.landed(0, 0) : "a hit absorption soaked still landed; one a shield took did not";

        // ---- poise: interrupted once in a row ----
        boolean poised = false;
        poised = WindUp.poiseAfter("start", poised);
        assert !poised : "a fresh wind-up is not poised";
        poised = WindUp.poiseAfter("interrupted", poised);
        assert poised : "an interrupted one poises the next";
        poised = WindUp.poiseAfter("start", poised);
        assert poised : "which keeps it through its start";
        assert WindUp.poiseAfter("feint", true) : "and through a feint into its follow-up";
        for (String end : new String[] {"land", "whiff", "stale", "goal_stopped", "no_room"}) assert !WindUp.poiseAfter(end, true) : "an attack's end spends it: " + end;
        // a poised wind-up is given no hurt, so the state machine runs it to the end through hits
        WindUp.Step poisedStep = WindUp.next(100, 110, true, true, false, 10);
        assert poisedStep.hold() && poisedStep.until() == 110 && poisedStep.event() == null : "a poised wind-up holds through a hit";
        WindUp.Step plain = WindUp.next(100, 110, true, true, true, 10);
        assert plain.event().equals("interrupted") : "an unpoised one is interrupted";

        // ---- the slime's engulf ----
        for (double[] c : new double[][] {{0, 64, 0, 6, 64, 3}, {0, 64, 0, -4, 66, 7}, {2, 70, 2, 2.5, 67, 2}, {0, 64, 0, 0, 64, 9.5}}) {
            net.minecraft.world.phys.Vec3 a0 = new net.minecraft.world.phys.Vec3(c[0], c[1], c[2]), at = new net.minecraft.world.phys.Vec3(c[3], c[4], c[5]);
            net.minecraft.world.phys.Vec3 v = Engulf.launch(a0, at, Engulf.FLIGHT), pos = a0;
            double peak = a0.y;
            for (int k = 0; k < Engulf.FLIGHT; k++) { // vanilla's LivingEntity.travel in the air: move, then gravity and drag
                pos = pos.add(v);
                peak = Math.max(peak, pos.y);
                v = new net.minecraft.world.phys.Vec3(v.x * Engulf.DRAG_H, (v.y - Engulf.GRAVITY) * Engulf.DRAG_Y, v.z * Engulf.DRAG_H);
            }
            near(pos.distanceTo(at), 0, 1e-6, "the leap lands on the locked spot " + at);
            assert peak > Math.max(a0.y, at.y) + 1.5 : "on a high arc, 1.5 blocks or more over the higher end: " + peak;
        }
        assert !Engulf.ready(2.9, 4, true, true, 100, 0) && Engulf.ready(3, 4, true, true, 100, 0) && Engulf.ready(10, 2, true, true, 100, 0) && !Engulf.ready(10.1, 4, true, true, 100, 0)
                : "3 to 10 blocks";
        assert Engulf.ready(5, 1, true, true, 100, 0) && !Engulf.ready(5, 4, false, true, 100, 0) && !Engulf.ready(5, 4, true, false, 100, 0) && !Engulf.ready(5, 4, true, true, 99, 100)
                : "a small slime leaps too; not off the ground, not unseen, not on cooldown";
        assert !Engulf.traps(1) && Engulf.traps(2) && Engulf.traps(4) : "a small slime's landing slows, a bigger one's traps";
        assert Engulf.hops(4, 2, true, true, 10, 0) && Engulf.hops(0.5, 4, true, true, 10, 0) && !Engulf.hops(4.1, 4, true, true, 10, 0) && !Engulf.hops(2, 1, true, true, 10, 0)
                && !Engulf.hops(2, 4, true, true, 9, 10) : "the pounce: within 4 blocks, a slime that deals damage, off its recovery";
        net.minecraft.world.phys.Vec3 aimed = Engulf.aim(new net.minecraft.world.phys.Vec3(0, 64, 0), new net.minecraft.world.phys.Vec3(6, 64, 0));
        near(aimed.x, 6 + Engulf.BEHIND, 1e-9, "the leap aims BEHIND past the player, along the line from the slime");
        near(aimed.z, 0, 1e-9, "on that line");
        double peak18 = 0, peak24 = 0;
        for (int flight : new int[] {18, Engulf.FLIGHT}) {
            net.minecraft.world.phys.Vec3 v = Engulf.launch(net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(6, 0, 0), flight);
            double y = 0, top = 0, vy = v.y;
            for (int k = 0; k < flight; k++) {
                y += vy;
                top = Math.max(top, y);
                vy = (vy - Engulf.GRAVITY) * Engulf.DRAG_Y;
            }
            if (flight == 18) peak18 = top;
            else peak24 = top;
        }
        assert peak24 > peak18 * 1.6 && peak24 > 4 : "the leap goes much higher: " + peak18 + " to " + peak24;
        net.minecraft.world.phys.Vec3 hopV = Engulf.launch(net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(3, 0, 0), Engulf.HOP_FLIGHT), hopPos = net.minecraft.world.phys.Vec3.ZERO;
        for (int k = 0; k < Engulf.HOP_FLIGHT; k++) {
            hopPos = hopPos.add(hopV);
            hopV = new net.minecraft.world.phys.Vec3(hopV.x * Engulf.DRAG_H, (hopV.y - Engulf.GRAVITY) * Engulf.DRAG_Y, hopV.z * Engulf.DRAG_H);
        }
        near(hopPos.x, 3, 1e-6, "the pounce lands where the player stood at take-off");
        assert Engulf.HOP_TELL >= 6 && Engulf.HOP_TELL < Engulf.TELL : "the pounce has a tell, shorter than the leap's";
        assert WindUp.style("slime_hop", null) == WindUpPayload.SQUASH : "and squashes for it";
        assert EngulfClient.wash(EngulfPayload.ORANGE)[0] == 0xFF && EngulfClient.wash(EngulfPayload.ORANGE)[1] < 0x90 && EngulfClient.wash(EngulfPayload.GREEN)[1] > EngulfClient.wash(EngulfPayload.GREEN)[0]
                : "orange inside a magma cube, green inside a slime";
        assert Engulf.cooldown(0) == 120 && Engulf.cooldown(1) == 200 && Engulf.cooldown(0.5) == 160 : "6 to 10 s between leaps";
        net.minecraft.world.phys.Vec3 player = new net.minecraft.world.phys.Vec3(0, 64, 0);
        net.minecraft.world.phys.Vec3 toMob = Engulf.spit(player, 0, new net.minecraft.world.phys.Vec3(-6, 65, 0));
        assert toMob.x < -1 && Math.abs(toMob.z) < 1e-9 && toMob.y == Engulf.SPIT_UP : "spat at the nearest hostile mob: " + toMob;
        net.minecraft.world.phys.Vec3 spitAhead = Engulf.spit(player, 90, null);
        near(spitAhead.x, -Engulf.SPIT_SPEED, 1e-9, "with none near, out the way the slime faces (yaw 90 is -x)");
        near(Math.hypot(toMob.x, toMob.z), Engulf.SPIT_SPEED, 1e-9, "at SPIT_SPEED");
        assert Engulf.TELL >= 12 && Engulf.HOLD == 30 : "a tell long enough to step out of the ring; a 1.5 s hold";
        // the shape: squash over the tell, stretch on the take-off, wobble in the hold, the slime's own shape otherwise
        float[] squashTop = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.START, Engulf.TELL, Engulf.TELL);
        assert squashTop[0] > 1.25f && squashTop[1] < 0.65f : "squashed wide and flat at the end of the tell: " + java.util.Arrays.toString(squashTop);
        float[] still = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.START, 0, Engulf.TELL);
        assert still[0] == 1 && still[1] == 1 : "and its own shape at the start";
        float[] off = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.STRIKE, 2, Engulf.TELL);
        near(off[1], 1 + WindUp.STRETCH_TALL, 1e-5, "stretched tall two ticks into the leap");
        float[] settled = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.STRIKE, 10, Engulf.TELL);
        near(settled[0], 1, 1e-5, "and back to its own shape eight ticks later");
        float[] lastShape = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.START, Engulf.TELL, Engulf.TELL);
        for (float t = 1 / 16f; t <= 10; t += 1 / 16f) {
            float[] sh = WindUp.shape(WindUpPayload.SQUASH, WindUpPayload.STRIKE, t, Engulf.TELL);
            assert Math.abs(sh[1] - lastShape[1]) < 0.12f : "the leap's shape never jumps: " + lastShape[1] + " to " + sh[1] + " at " + t;
            lastShape = sh;
        }
        for (float t = 0; t <= 30; t += 0.5f) {
            float[] w = WindUp.shape(WindUpPayload.WOBBLE, WindUpPayload.START, t, 30);
            assert Math.abs(w[0] - 1) <= WindUp.WOBBLE + 1e-6f && Math.abs(w[0] - 1 + w[1] - 1) < 1e-6f : "the hold wobbles flat and tall in turn";
        }
        assert WindUp.style("engulf", null) == WindUpPayload.SQUASH && WindUp.style("engulf_hold", null) == WindUpPayload.WOBBLE : "styles";
        assert EngulfClient.strength(0, 30) == 0 && EngulfClient.strength(3, 30) == 1 && EngulfClient.strength(30, 30) == 1 && EngulfClient.strength(34, 30) == 0
                && EngulfClient.strength(1.5f, 30) == 0.5f : "the green screen fades in over 3 ticks and out over 4";

        // ---- the hoglin's charge ----
        assert Charge.ready(4, true, true, 10, 10) && Charge.ready(14, true, true, 10, 0) && !Charge.ready(3.9, true, true, 10, 0) && !Charge.ready(14.1, true, true, 10, 0)
                && !Charge.ready(8, false, true, 10, 0) && !Charge.ready(8, true, false, 10, 0) && !Charge.ready(8, true, true, 9, 10) : "4 to 14 blocks, on the ground, in sight, off cooldown";
        net.minecraft.world.phys.Vec3 hogAt = new net.minecraft.world.phys.Vec3(0, 64, 0), hogEnd = Charge.lineEnd(hogAt, new net.minecraft.world.phys.Vec3(6, 65, 8));
        near(hogEnd.distanceTo(new net.minecraft.world.phys.Vec3(6, 64, 8)), Charge.PAST, 1e-9, "the line runs PAST blocks beyond the player");
        near(hogEnd.y, 64, 1e-9, "level with the hoglin");
        near((hogEnd.x * 8 - hogEnd.z * 6), 0, 1e-9, "on the straight line through the player");
        assert Charge.ticks(Charge.Phase.LINE1, false) == 12 && Charge.ticks(Charge.Phase.LINE2, false) == 8 && Charge.COOLDOWN == 160
                : "shorter lines, a quicker re-target";
        net.minecraft.world.phys.Vec3 thrown = Charge.launch(new net.minecraft.world.phys.Vec3(1, 0, 0));
        assert thrown.y == Charge.LAUNCH_UP && thrown.x == Charge.LAUNCH_BACK && thrown.y > 0.6 && Charge.HIT_STUN == 2.0 : "run over blind: thrown up and on, hard-stunned 2 s";
        assert Charge.ticks(Charge.Phase.LINE1, false) == 12 && Charge.ticks(Charge.Phase.LINE1, true) < 20 && Charge.ticks(Charge.Phase.ROAR, false) > Charge.ticks(Charge.Phase.ROAR, true)
                : "a baby's tells are shorter";
        assert Charge.stunsOnBlock(false, Charge.Phase.DASH1) && !Charge.stunsOnBlock(false, Charge.Phase.DASH2) && !Charge.stunsOnBlock(true, Charge.Phase.DASH1)
                : "blocking an adult's first dash stuns; the second, and a baby's, disable the shield instead";
        double longestDash = (Charge.MAX + Charge.PAST) / Charge.SPEED;
        assert Charge.BLOCK_STUN * 20 > longestDash && Charge.HIT_STUN * 20 > longestDash
                : "either stun outlasts the rest of the first dash, where it is renewed to cover the turn, the roar, the second line and the second dash's run";
        assert WindUp.rear(0, 25) == 0 && WindUp.rear(12, 25) == WindUp.REAR_DEGREES && WindUp.rear(25, 25) == 0 && WindUp.rear(3, 25) > 0 && WindUp.rear(3, 25) < WindUp.REAR_DEGREES : "the rear-up rises, holds and comes down";
        float lastRear = 0;
        for (float t = 0; t <= 25; t += 1 / 16f) {
            float r = WindUp.rear(t, 25);
            assert Math.abs(r - lastRear) < 6 : "the rear-up never jumps: " + lastRear + " to " + r + " at " + t;
            lastRear = r;
        }
        assert WindUp.style("charge_line", null) == WindUpPayload.LINE && WindUp.style("charge_dash", null) == WindUpPayload.CHARGE && WindUp.style("charge_rear", null) == WindUpPayload.REAR
                && WindUp.style("charge_tired", null) == WindUpPayload.TIRED : "styles";
        assert WindUp.chargeHead(WindUpPayload.CHARGE) > 0 && WindUp.chargeHead(WindUpPayload.TIRED) > WindUp.chargeHead(WindUpPayload.LINE) && WindUp.chargeHead(WindUpPayload.REAR) < 0
                : "head down to charge, lower when tired, up when rearing";
        assert Math.abs(WindUp.chargeLeg(WindUpPayload.REAR, 1.5f, 1) - WindUp.chargeLeg(WindUpPayload.REAR, 1.5f, -1)) > 0.5f && WindUp.chargeLeg(WindUpPayload.REAR, 3, 1) < 0 && WindUp.chargeLeg(WindUpPayload.LINE, 2, -1) == 0
                : "the front legs paw out of step while rearing; one hoof scrapes before the charge";

        // ---- the ravager's charge ----
        assert Charge.ready(Charge.RAVAGER, 5, true, true, 0, 0) && Charge.ready(Charge.RAVAGER, 16, true, true, 0, 0) && !Charge.ready(Charge.RAVAGER, 4.9, true, true, 0, 0)
                && !Charge.ready(Charge.RAVAGER, 16.1, true, true, 0, 0) : "5 to 16 blocks";
        assert Charge.RAVAGER.backup() > 0 && Charge.RAVAGER.roarAtLaunch() && !Charge.RAVAGER.twice() && !Charge.RAVAGER.tires() && !Charge.RAVAGER.babyParry()
                && Charge.HOGLIN.twice() && Charge.HOGLIN.tires() && Charge.HOGLIN.backup() == 0 : "the ravager backs up, roars as it launches, charges once; the hoglin's profile is unchanged";
        assert Charge.ticks(Charge.RAVAGER, Charge.Phase.BACKUP, false) == Charge.BACKUP && Charge.ticks(Charge.RAVAGER, Charge.Phase.LINE1, false) == Charge.PAW
                && Charge.ticks(Charge.HOGLIN, Charge.Phase.LINE1, false) == Charge.LINE1 : "each profile's own counts";
        near(Charge.BACKUP * Charge.BACKUP_SPEED, 1.44, 0.1, "it backs up about a block and a half");
        assert !Charge.stunsOnBlock(Charge.RAVAGER, false, Charge.Phase.DASH1) : "a ravager's block disables the shield, no stun";
        net.minecraft.world.phys.Vec3 flung = Charge.fling(new net.minecraft.world.phys.Vec3(0, 0, 1));
        assert flung.z < 0 && flung.y > 2 * Math.abs(flung.z) && flung.y > 1 : "flung up over its back, against the charge, on a steep arc: " + flung;
        net.minecraft.world.phys.Vec3 kicked = Charge.kickBack(new net.minecraft.world.phys.Vec3(1, 0, 0));
        assert kicked.x < 0 && kicked.y > 0 : "terrain kicks it back";
        assert Charge.roarReaches(false, false, false, 6) && !Charge.roarReaches(false, false, false, 6.1) && !Charge.roarReaches(true, false, false, 0)
                && !Charge.roarReaches(false, true, false, 1) && !Charge.roarReaches(false, false, true, 1) : "the roar reaches all within 6 but itself, its rider and raiders";
        net.minecraft.client.model.geom.ModelPart ravRoot = net.minecraft.client.model.RavagerModel.createBodyLayer().bakeRoot();
        for (String part : new String[] {"neck", "right_front_leg", "left_front_leg"}) assert ravRoot.hasChild(part) : "the ravager model has the root child the paw poses: " + part;

        // ---- goal mobs re-path when shoved out of reach; a charger dazed by a wall ----
        assert WindUp.repath(false, false, true) && !WindUp.repath(true, false, true) && !WindUp.repath(false, true, true) && !WindUp.repath(false, false, false)
                : "path again only when not winding up, out of reach, and the path is done";
        assert WindUp.daze(0, 30) == 0 && WindUp.daze(15, 30) == WindUp.DAZE_DEGREES && WindUp.daze(30, 30) == 0 && WindUp.daze(1, 30) > 0 : "tipped forward, held, back";
        assert WindUp.style("charge_dazed", null) == WindUpPayload.DAZED && WindUp.chargeHead(WindUpPayload.DAZED) > 0 && WindUp.chargeLeg(WindUpPayload.DAZED, 5, 1) < 0
                && WindUp.chargeLeg(WindUpPayload.DAZED, 5, -1) < 0 : "dazed: head low, both front legs forward";

        // ---- the cow's stampede ----
        assert Charge.COW.retaliates() && Charge.COW.parryable() && Charge.COW.dashes() == 2 && !Charge.HOGLIN.retaliates() && !Charge.RAVAGER.retaliates() && !Charge.HOGLIN.parryable()
                : "the cow retaliates, can be parried, dashes up to twice; the others hunt as before";
        net.minecraft.world.phys.Vec3 away = Charge.fleeFrom(new net.minecraft.world.phys.Vec3(5, 64, 0), new net.minecraft.world.phys.Vec3(0, 64, 0));
        near(away.x, 1, 1e-9, "a hurt cow runs away from its attacker");
        net.minecraft.world.phys.Vec3 cowEnd = Charge.lineEnd(new net.minecraft.world.phys.Vec3(10, 64, 0), new net.minecraft.world.phys.Vec3(0, 64, 0), Charge.COW_PAST);
        near(cowEnd.x, -Charge.COW_PAST, 1e-9, "its dash carries COW_PAST blocks on through the attacker, so it is also its escape");
        assert !Charge.turns(Charge.COW_FLEE - 1, 0, 6, true, true) && Charge.turns(Charge.COW_FLEE, 0, 6, true, true) && !Charge.turns(Charge.COW_FLEE, 5, 6, true, true)
                && !Charge.turns(Charge.COW_FLEE, 0, 2.9, true, true) && !Charge.turns(Charge.COW_FLEE, 0, 12.1, true, true) && !Charge.turns(Charge.COW_FLEE, 0, 6, false, true)
                : "it turns after its flight (and the herd's stagger), 3 to 12 blocks off, in sight";
        assert Charge.another(1, 100) && !Charge.another(2, 100) && !Charge.another(3, 100) && !Charge.another(1, Charge.COW_TIME) : "up to three dashes in 15 s";
        assert Charge.ticks(Charge.COW, Charge.Phase.LINE1, false) == Charge.COW_PAW && Charge.COW_PAW < Charge.LINE1 : "a short paw";
        assert Charge.neutralLine(Charge.COW) && !Charge.neutralLine(Charge.HOGLIN) && !Charge.neutralLine(Charge.RAVAGER) && Charge.COW_PAST == 7
                : "a neutral charger's line is yellow; the cow's dash runs 7 blocks on";
        io.netty.buffer.ByteBuf lbuf = io.netty.buffer.Unpooled.buffer();
        LinePayload line = new LinePayload(1, 64, -2, 9.5f, 64, 3, 12, LinePayload.MAGMA, true);
        LinePayload.CODEC.encode(lbuf, line);
        assert LinePayload.CODEC.decode(lbuf).equals(line) : "the line telegraph's message round trip";
        for (String part : new String[] {"head", "rightFrontLeg", "leftFrontLeg"}) net.minecraft.client.model.QuadrupedModel.class.getDeclaredField(part);

        // ---- the spider rework ----
        assert Pounce.drops(true, 1.5, 5, true) && !Pounce.drops(false, 1.5, 5, true) && !Pounce.drops(true, 2.5, 5, true) && !Pounce.drops(true, 1, 2.9, true) && !Pounce.drops(true, 1, 8.1, true)
                : "a climbing spider drops on a target within 2 sideways, 3 to 8 below";
        assert Pounce.spits(false, 8, true, true, 10, 0) && !Pounce.spits(true, 8, true, true, 10, 0) && !Pounce.spits(false, 5.9, true, true, 10, 0) && !Pounce.spits(false, 12.1, true, true, 10, 0)
                && !Pounce.spits(false, 8, true, true, 9, 10) : "the web spit: 6 to 12 blocks, not a cave spider, off cooldown";
        assert Pounce.pounces(3, false) && !Pounce.pounces(6, false) && Pounce.pounces(11, true) : "vanilla's 2 to 4 blocks, or from range at a rooted player";
        assert Pounce.DISPLAY == 10 && Pounce.CAVE_DISPLAY < Pounce.DISPLAY && Pounce.ROOTED_DISPLAY < Pounce.CAVE_DISPLAY && Pounce.FLIGHT == 12 : "the display and flight";
        net.minecraft.world.phys.Vec3 gFrom = new net.minecraft.world.phys.Vec3(0, 65.5, 0), gTo = new net.minecraft.world.phys.Vec3(9, 64.9, 3);
        int gt = SpiderMoves.globTicks(gFrom.distanceTo(gTo));
        net.minecraft.world.phys.Vec3 gv = SpiderMoves.globLaunch(gFrom, gTo, gt), gp = gFrom;
        for (int k = 0; k < gt; k++) {
            gp = gp.add(gv);
            gv = gv.subtract(0, SpiderMoves.GLOB_GRAVITY, 0);
        }
        near(gp.distanceTo(gTo), 0, 1e-9, "the web glob arrives on the player's chest");
        assert gt >= gFrom.distanceTo(gTo) / SpiderMoves.GLOB_SPEED : "at GLOB_SPEED, so it can be seen and sidestepped";
        assert SpiderMoves.bites(15) && SpiderMoves.bites(30) && !SpiderMoves.bites(1) && !SpiderMoves.bites(20) : "the pin bites twice more over 1.5 s";
        assert SpiderMoves.FLIP_DAMAGE == 1.5 && SpiderMoves.FLIP == 40 && SpiderMoves.ROOT == 30 : "flipped 2 s taking 1.5x; rooted 1.5 s";
        assert LineClient.shown(true, false, true) && !LineClient.shown(true, true, false) && LineClient.shown(false, true, false) && !LineClient.shown(false, false, true)
                : "rings and lines each by their own setting";
        assert WindUp.display(WindUpPayload.START, 0, 10) == 0 && WindUp.display(WindUpPayload.START, WindUp.DISPLAY_RISE, 10) == 1 && WindUp.display(WindUpPayload.START, 10, 10) == 1
                && WindUp.display(WindUpPayload.START, 14, 10) == 1 : "the display is up in 4 ticks and stays up until the leap, never coming down first";
        assert WindUp.display(WindUpPayload.STRIKE, 0, 10) == 1 && WindUp.display(WindUpPayload.STRIKE, WindUp.DISPLAY_FALL, 10) == 0 && WindUp.display(WindUpPayload.STRIKE, 2, 10) > 0
                : "the leap starts from the raised display and eases out of it in the air";
        assert WindUp.style("pounce_leap", null) == WindUpPayload.DISPLAY && WindUp.chargeLeg(WindUpPayload.DISPLAY, 3, 1) < -0.9f && WindUp.chargeHead(WindUpPayload.DISPLAY) < 0 : "legs and head up";
        assert WindUp.style("pounce_display", null) == WindUpPayload.DISPLAY && WindUp.style("spider_flipped", null) == WindUpPayload.FLIPPED && WindUp.style("spider_hang", null) == WindUpPayload.HANG
                : "the display rears it up; flipped and hanging have their own";
        assert Math.abs(WindUp.legFlail(WindUpPayload.FLIPPED, 0, 1.3f)) > Math.abs(WindUp.legFlail(WindUpPayload.HANG, 0, 1.3f)) || Math.abs(WindUp.legFlail(WindUpPayload.FLIPPED, 0, 1.3f)) > 0.3f
                : "a flipped spider flails wider than a hanging one twitches";
        assert WindUp.legFlail(WindUpPayload.FLIPPED, 0, 2) != WindUp.legFlail(WindUpPayload.FLIPPED, 1, 2) : "its legs out of step";
        net.minecraft.client.model.geom.ModelPart spiderRoot = net.minecraft.client.model.SpiderModel.createSpiderBodyLayer().bakeRoot();
        for (String part : new String[] {"head", "right_front_leg", "left_front_leg", "right_hind_leg", "left_middle_hind_leg", "right_middle_front_leg"})
            assert spiderRoot.hasChild(part) : "the spider model has the root child the poses use: " + part;

        // ---- the drowned's drag ----
        assert Drag.ready(true, true, true, false, 4, true, 10, 0) && !Drag.ready(false, true, true, false, 3, true, 10, 0) && !Drag.ready(true, false, true, false, 3, true, 10, 0)
                && !Drag.ready(true, true, false, false, 3, true, 10, 0) && !Drag.ready(true, true, true, true, 3, true, 10, 0) && !Drag.ready(true, true, true, false, 4.1, true, 10, 0)
                && !Drag.ready(true, true, true, false, 3, false, 10, 0) && !Drag.ready(true, true, true, false, 3, true, 9, 10)
                : "unarmed adult, the player in water and not riding, within 4, not already held, off cooldown";
        assert Drag.latches(true, 2, 3) && !Drag.latches(true, 2.1, 1) && !Drag.latches(false, 0, 1) && !Drag.latches(true, 1, 3.1) : "leaving the column or the water dodges it";
        assert Drag.hold(false, false) == 40 && Drag.hold(true, false) == 20 && Drag.hold(false, true) == 20 : "2 s, halved by Respiration or Water Breathing";
        assert Drag.drain(false, false) == 2 && Drag.drain(true, false) == 1 && Drag.drain(false, true) == 0 && Drag.drain(true, true) == 0 : "air drains 3x, Respiration halves the extra, Water Breathing stops it";
        double dy = 70;
        for (int k = 0; k < 40; k++) dy = Drag.sink(dy, 70, false);
        near(dy, 70 - Drag.DEPTH, 1e-9, "sunk to DEPTH and held there");
        assert Drag.sink(69.5, 70, true) == 69.5 : "never into a block";
        assert Drag.drained(100, 2) == 98 && Drag.drained(-18, 2) == -19 && Drag.drained(-19, 2) == -19 && Drag.drained(-25, 2) == -25
                : "the extra drain never skips vanilla's -20 drowning tick";
        int dragAir = 10;
        boolean drowned = false;
        for (int k = 0; k < 40 && !drowned; k++) { // the extra, then vanilla's own decrement and its exact -20 check
            dragAir = Drag.drained(dragAir, 2) - 1;
            if (dragAir == -20) drowned = true;
        }
        assert drowned : "a dragged player still takes vanilla's drowning damage";

        // ---- mob throws ----
        MobThrows.Entry row = new MobThrows.Entry("minecraft:husk", "minecraft:silverfish", 1, 5, 14, 200, 120, true);
        assert MobThrows.ready(row, 8, true, true, false, 1000, 0, 0) && !MobThrows.ready(row, 4.9, true, true, false, 1000, 0, 0) && !MobThrows.ready(row, 14.1, true, true, false, 1000, 0, 0)
                && !MobThrows.ready(row, 8, false, true, false, 1000, 0, 0) && !MobThrows.ready(row, 8, true, false, false, 1000, 0, 0) : "range, sight, bare hands";
        assert !MobThrows.ready(row, 8, true, true, true, 1000, 0, 0) : "one thrown mob of its own at a time";
        assert !MobThrows.ready(row, 8, true, true, false, 1000, 0, 900) && MobThrows.ready(row, 8, true, true, false, 1020, 0, 900) : "not again until 6 s after it died";
        assert !MobThrows.ready(row, 8, true, true, false, 1000, 1001, 0) : "and not inside the cooldown";
        MobThrows.Entry armed = new MobThrows.Entry("minecraft:bogged", "minecraft:slime", 1, 5, 14, 240, 120, false);
        assert MobThrows.ready(armed, 8, true, false, false, 1000, 0, 0) : "an armed thrower throws with its weapon in hand";
        assert MobThrows.grabs(false, 2.5, 10, 0) && !MobThrows.grabs(true, 1, 10, 0) && !MobThrows.grabs(false, 2.6, 10, 0) : "a heaver grabs a mob in reach, never a player";
        assert !MobThrows.grabs(false, 1, 10, 0, 1.95, 2.2) && MobThrows.grabs(false, 1, 10, 0, 0.6, 1.95) : "humanoid-sized only: no heaving a ravager";
        net.minecraft.world.phys.Vec3 aimAt = MobThrows.landing(new net.minecraft.world.phys.Vec3(0, 64, 0), new net.minecraft.world.phys.Vec3(10, 64, 0));
        near(aimAt.x, 10 - MobThrows.SHORT, 1e-9, "thrown to land SHORT of the player");
        assert WindUp.style("throw", null) == WindUpPayload.WEAPON && WindUp.style("heave", null) == WindUpPayload.OVERHEAD && WindUp.style("drag", null) == WindUpPayload.OVERHEAD : "the throw arm, the heave";
        assert !EngulfClient.blurs(EngulfPayload.DEEP) && EngulfClient.blurs(EngulfPayload.GREEN) && EngulfClient.fill(EngulfPayload.DEEP) == 0 && EngulfClient.wash(EngulfPayload.DEEP)[2] > EngulfClient.wash(EngulfPayload.DEEP)[0]
                : "a drowned's grip darkens the edges deep blue, no blur";

        // ---- the cow refresh, domesticated animals ----
        assert Charge.bunts(Charge.COW_FLEE, 0, 2, true) && !Charge.bunts(Charge.COW_FLEE, 0, 4, true) && !Charge.bunts(Charge.COW_FLEE - 1, 0, 2, true) && !Charge.bunts(Charge.COW_FLEE, 0, 2, false)
                : "a panicked cow bunts an attacker too close to stampede";
        net.minecraft.world.phys.Vec3 v0 = Charge.veer(new net.minecraft.world.phys.Vec3(1, 0, 0), 0), v90 = Charge.veer(new net.minecraft.world.phys.Vec3(1, 0, 0), 90);
        near(v0.x, 1, 1e-9, "no veer runs straight away");
        near(v90.z, 1, 1e-9, "a veer turns the way it runs");
        assert Charge.COW_FLEE == 30 && Charge.COW_DASHES == 2 && Charge.BITE_CHANCE == 0.4 : "runs less, two stampedes, bites back 40%";
        assert Domestic.exempt(true, false) && Domestic.exempt(false, true) && !Domestic.exempt(false, false) : "bred or tamed animals stay vanilla";

        // ---- the zombie grab, mob arming ----
        assert Grab.ready(true, 2.5, true, 100, 0) && !Grab.ready(false, 2.5, true, 100, 0)
                && !Grab.ready(true, 4, true, 100, 0) && !Grab.ready(true, 0.8, true, 100, 0) && !Grab.ready(true, 2.5, false, 100, 0) && !Grab.ready(true, 2.5, true, 100, 200)
                : "an adult, armed or not, grabs a free player 1.2 to 3.5 blocks off, out of its cooldown";
        assert Grab.shielded(false, 0, 5) == 0 && Grab.shielded(true, 2, 5) == 2 && Grab.shielded(true, 30, 5) == 1 : "a raised shield stops the grab, a fresh one parries it";
        assert MobArming.pick(java.util.List.of(4, 3, 1), 0.0) == 0 && MobArming.pick(java.util.List.of(4, 3, 1), 0.5) == 1 && MobArming.pick(java.util.List.of(4, 3, 1), 0.99) == 2
                && MobArming.pick(java.util.List.of(0, 1), 0.0) == 1 : "items are picked by weight";
        assert MobArming.active(CommonConfig.ArmingMode.AUTO, false) && !MobArming.active(CommonConfig.ArmingMode.AUTO, true) && MobArming.active(CommonConfig.ArmingMode.ON, true)
                && !MobArming.active(CommonConfig.ArmingMode.OFF, false) : "AUTO leaves arming to Jaam's when it is installed";

        // ---- the warden's slam ----
        assert Slam.ready(6, false, 0, false, 100, 0) && !Slam.ready(9, false, 0, false, 100, 0) && !Slam.ready(2, true, 0, false, 100, 0) && Slam.ready(2, true, 40, false, 100, 0)
                : "slams at a target out of reach within 8, or one kept still 2 s";
        assert !Slam.ready(6, false, 0, true, 100, 0) && !Slam.ready(6, false, 0, false, 100, 200) : "not while its sonic boom charges, nor in its cooldown";
        assert Slam.still(5, 0.01) == 6 && Slam.still(5, 0.5) == 0 : "moving resets the still count";
        assert Slam.radius(0) == 0 && Slam.radius(5) == 3 && Slam.radius(10) == 6 && Slam.radius(14) == 6 : "the crest runs out to 6 over 10 ticks";
        assert Slam.crest(2.5, 2.4, 3) && !Slam.crest(2.4, 2.4, 3) && !Slam.crest(3.1, 2.4, 3) : "each distance is crossed once";
        assert Slam.struck(true, 0.5) && !Slam.struck(false, 0) && !Slam.struck(true, 3) : "a jump dodges it";
        assert WindUp.style("slam", null) == WindUpPayload.OVERHEAD : "the slam raises both fists";

        // ---- one hold per player, held mobs freed with their heaver ----
        assert Holds.claims(false, false, true) && Holds.claims(true, true, true) && !Holds.claims(true, false, true) && Holds.claims(true, false, false)
                : "a second grab finds the player taken, unless its holder is gone";
        assert MobThrows.heldValid(true, true) && !MobThrows.heldValid(false, true) && !MobThrows.heldValid(true, false) : "a held mob goes free when its heaver is gone or done";
        assert Pounce.HANGING.equals("vectorcombat.hanging") : "a hanging spider is tagged, so one saved mid-hang gets its gravity back";

        // ---- a feral bite ----
        assert WindUp.style("bite", "feral", false, WindUp.UNARMED) == WindUpPayload.BITE && WindUp.style("bite", "feral", false, WindUp.ONE_HANDED) == WindUpPayload.BITE
                && WindUp.style("pounce", "feral") == WindUpPayload.LEAN : "a feral mob bites with its head, armed or not; only its bite";
        near(WindUp.biteHead(0.2f, 0), 0.2, 1e-6, "the head is its own when upright");
        near(WindUp.biteHead(0.2f, 1), 0.2 - WindUp.BITE_UP, 1e-6, "reared up and back at the top of the tell");
        assert WindUp.BITE_UP >= 0.9f && WindUp.BITE_SNAP >= 1.1f && WindUp.BITE_LEAN <= 0.5f : "a bigger bite, the body leaning half as much so the head carries it";
        assert WindUp.biteThrust(1) == 0 && WindUp.biteThrust(-WindUp.LUNGE) == WindUp.BITE_THRUST && WindUp.biteThrust(0) == 0 : "the head thrusts forward only with the snap";
        // the accessor's targets exist on the real model, and a baked hoglin head takes the bite
        for (String part : new String[] {"head", "rightFrontLeg", "leftFrontLeg"}) net.minecraft.client.model.HoglinModel.class.getDeclaredField(part);
        net.minecraft.client.model.HoglinModel<?> hogModel = new net.minecraft.client.model.HoglinModel<>(net.minecraft.client.model.HoglinModel.createBodyLayer().bakeRoot());
        java.lang.reflect.Field hf = net.minecraft.client.model.HoglinModel.class.getDeclaredField("head");
        hf.setAccessible(true);
        net.minecraft.client.model.geom.ModelPart hogHead = (net.minecraft.client.model.geom.ModelPart) hf.get(hogModel);
        float rest = hogHead.xRot;
        assert Math.abs(WindUp.biteHead(rest, 1) - rest) > 0.8f && Math.abs(WindUp.biteHead(rest, -WindUp.LUNGE) - rest) > 1f : "on the real hoglin head the bite moves it more than 45 degrees each way";
        near(WindUp.biteHead(0.2f, -WindUp.LUNGE), 0.2 + WindUp.BITE_SNAP, 1e-6, "snapped forward and down at the strike");

        // ---- the swarm's slow ----
        Swarm.Count c = null;
        int[] levels = new int[13];
        for (int i = 1; i <= 12; i++) {
            c = Swarm.hit(c, i * 10);
            assert c.hits() == i : "hits close together count up: " + c;
            levels[i] = Swarm.raises(c.hits()) ? Swarm.level(c.hits()) : 0;
        }
        assert levels[1] == 0 && levels[2] == 0 && levels[3] == 1 && levels[6] == 2 && levels[9] == 3 && levels[12] == 3 && levels[4] == 0 : "a level every third hit, to III, refreshed at III";
        Swarm.Count late = Swarm.hit(c, c.lapses());
        assert late.hits() == 1 : "the count starts over once the slow has worn off";
        Swarm.Count one = Swarm.hit(null, 0), stale = Swarm.hit(one, Swarm.DURATION);
        assert stale.hits() == 1 : "a lone hit long ago does not count towards the next";
        Swarm.Count mid = Swarm.hit(Swarm.hit(Swarm.hit(Swarm.hit(null, 0), 10), 20), 30);
        assert mid.lapses() == 20 + Swarm.DURATION : "between levels the count lapses with the slow it gave, not with each hit: " + mid;

        // ---- which way is back: with the renderer's own turn to the body after ours, the head goes back by sin(lean) of its height, against the facing, at any yaw ----
        for (float yaw : new float[] {0, 90, 180, -45, 137, -170}) {
            PoseStack ps = new PoseStack();
            WindUpClient.tilt(ps, yaw, 30f);
            ps.mulPose(Axis.YP.rotationDegrees(180f - yaw)); // setupRotations turns to the body next
            Vector3f head = ps.last().pose().transformPosition(new Vector3f(0, 1.8f, 0), new Vector3f());
            double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw)); // the way Entity.calculateViewVector(0, yaw) points
            near(head.x * fx + head.z * fz, -1.8 * Math.sin(Math.toRadians(30)), 1e-4, "the head goes back against the facing at yaw " + yaw);
            near(head.x * -fz + head.z * fx, 0, 1e-4, "and not sideways at yaw " + yaw);
            near(head.y, 1.8 * Math.cos(Math.toRadians(30)), 1e-4, "and comes down a little at yaw " + yaw);
            PoseStack front = new PoseStack();
            front.mulPose(Axis.YP.rotationDegrees(180f - yaw));
            Vector3f ahead = front.last().pose().transformPosition(new Vector3f(0, 0, -1), new Vector3f()); // the model's front is -z after the renderer's turn
            near(ahead.x * fx + ahead.z * fz, 1, 1e-4, "the model's front is the way the mob faces at yaw " + yaw);
            PoseStack forward = new PoseStack();
            WindUpClient.tilt(forward, yaw, -20f);
            forward.mulPose(Axis.YP.rotationDegrees(180f - yaw));
            Vector3f lunged = forward.last().pose().transformPosition(new Vector3f(0, 1.8f, 0), new Vector3f());
            assert lunged.x * fx + lunged.z * fz > 0.5 : "a negative lean leans forward at yaw " + yaw;
        }

        // ---- the legs: a hinge at the hip with the lean, a stride with the snap ----
        near(WindUp.legHinge(0, 25f), 0, 1e-9, "no hinge upright");
        float peak = WindUp.peakDegrees(EntityType.ZOMBIE.getHeight());
        near(WindUp.legHinge(1, peak), Math.toRadians(0.5 * peak), 1e-5, "the legs lean half as much as the torso at the top of the rear-back");
        near(WindUp.legHinge(-WindUp.LUNGE, peak), Math.toRadians(-0.5 * WindUp.LUNGE * peak), 1e-5, "and half of the forward lean at the snap");
        double lean = Math.toRadians(peak);
        near(0.75 * (Math.sin(lean) - Math.sin(lean - WindUp.legHinge(1, peak))), 0.155, 0.01, "the feet are about 16 cm further back than a plank's at the top");
        near(0.75 * Math.sin(Math.toRadians(WindUp.STRIDE)), WindUp.REACH / 3, 1e-3, "the stride advances the lead foot by a third of the reach");
        assert WindUp.stride(start, 3) == 0 && WindUp.stride(cancel, 1) == 0 && WindUp.stride(strike, 0) == 0 : "no stride outside a strike, none at its start";
        assert WindUp.stride(strike, WindUp.SNAP) == 1 && WindUp.stride(strike, WindUp.SNAP + WindUp.HOLD / 2) == 1 : "it is full as the snap ends and through the hold";
        near(WindUp.stride(strike, WindUp.length(strike, 8)), 0, 1e-6, "and gone when the recovery ends");
        float lastStride = 0;
        for (float t = 1 / 32f; t <= WindUp.length(strike, 8) + 2; t += 1 / 32f) {
            float w = WindUp.stride(strike, t);
            assert w >= 0 && w <= 1 && Math.abs(w - lastStride) < 0.04f : "the stride never jumps: " + lastStride + " to " + w + " at " + t;
            lastStride = w;
        }
        near(WindUp.legLead(1), -Math.toRadians(WindUp.STRIDE), 1e-6, "the lead leg swings forward");
        near(WindUp.legTrail(1), Math.toRadians(WindUp.STRIDE) / 2, 1e-6, "the other back, half as far");
        assert WindUp.legLead(0) == 0 && WindUp.legTrail(0) == 0 : "and neither moves without it";

        // ---- the pounce: vanilla's leap, aimed ----
        double y = 0, vy = 0.4, top = 0;
        int air = 0;
        do { // the same flight stepped here independently: up 0.4, gravity 0.08 and drag 0.98 a tick
            y += vy;
            air++;
            top = Math.max(top, y);
            vy = (vy - 0.08) * 0.98;
        } while (y > 0);
        assert air == Pounce.AIR_TICKS && air == 11 : "vanilla's leap is in the air 11 ticks (0.55 s), not " + air;
        near(top, 1.15, 0.01, "and rises 1.15 blocks");
        near(Pounce.REACH_PER_SPEED, 6.98, 0.01, "a unit of launch speed carries 6.98 blocks");
        for (double d : new double[] {0.5, 1.0, 2.0, 2.8, 3.1}) {
            double v = Pounce.launchSpeed(d), x = 0;
            for (int k = 0; k < air - 1; k++) {
                x += v;
                v *= 0.91; // the horizontal friction in the air
            }
            near(x + v * 0.5, d, 1e-9, "a launch aimed at " + d + " blocks lands " + d + " blocks away"); // the tick it lands in covers about half
        }
        assert Pounce.launchSpeed(-1) == 0 && Pounce.launchSpeed(10) == Pounce.MAX_SPEED : "never backwards, never faster than 0.45";
        assert Pounce.MAX_SPEED * Pounce.REACH_PER_SPEED > 4 - Pounce.STOP_SHORT : "from the far edge of vanilla's four block window the leap still arrives";
        assert Pounce.MAX_SPEED * Pounce.REACH_PER_SPEED < 5 - Pounce.STOP_SHORT : "but a player who has backed off to five blocks is out of reach";
        assert Pounce.CROUCH_TICKS == 6 && Pounce.RECOVERY == 20 : "the shipped constants";

        // ---- the brain mobs: a wind-up is 40% of the attack interval the game gives the behaviour ----
        assert WindUp.derived(20) == WindUp.DEFAULT_TICKS : "the goal mobs' 20 ticks gives the 8 every mob already had";
        assert WindUp.derived(18) == 7 && WindUp.derived(40) == 16 && WindUp.derived(15) == 6 && WindUp.derived(5) == 6 && WindUp.derived(0) == 6 : "warden, hoglin and zoglin, their babies, and the floor";
        assert WindUp.pick(null, 40) == 16 && WindUp.pick(0.6, 40) == 12 && WindUp.pick(0.5, 20) == 10 && WindUp.pick(0.01, 20) == 1 : "an entry in the data beats the derived value";

        // ---- the weapon in the mob's hand: 64% of its own recharge, 6 to 16 ticks at the usual interval of 20 ----
        double[] speeds = {0.4, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.5, 1.6, 1.7, 1.8, 2.0, 2.2, 2.4, 2.6, 2.8, 3.0, 3.4, 4.0}; // every attack speed among the 196 melee weapons of vanilla, Basic Weapons and Jaam's
        int[] tells = {16, 16, 16, 16, 14, 13, 12, 11, 9, 9, 8, 8, 7, 6, 6, 6, 6, 6, 6, 6, 6};
        for (int i = 0; i < speeds.length; i++) assert WindUp.forWeapon(20, speeds[i]) == tells[i] : "speed " + speeds[i] + " gives " + WindUp.forWeapon(20, speeds[i]) + ", not " + tells[i];
        for (int i = 1; i < speeds.length; i++) assert WindUp.forWeapon(20, speeds[i]) <= WindUp.forWeapon(20, speeds[i - 1]) : "a faster weapon never winds up longer";
        assert WindUp.forWeapon(20, WindUp.SWORD_SPEED) == WindUp.DEFAULT_TICKS : "a sword is the zombie's 8";
        assert WindUp.forWeapon(20, 0) == 16 && WindUp.forWeapon(20, -1) == 16 && WindUp.forWeapon(20, Double.NaN) == 16 : "no usable speed reads as the slowest";
        assert WindUp.forWeapon(15, 1.6) == 6 && WindUp.forWeapon(15, 0.4) == 12 : "a baby's interval of 15: its derived 6 at a sword, at most 80% of 15";
        assert WindUp.forWeapon(40, 1.6) == 16 && WindUp.forWeapon(40, 0.4) == 32 : "an interval of 40: its derived 16 at a sword, at most 80% of 40";
        assert WindUp.forWeapon(5, 0.4) == 6 : "never below the floor";
        assert WindUp.pick(0.9, 0.5, 20) == 14 && WindUp.pick(null, 0.5, 20) == 10 && WindUp.pick(null, null, 20) == 8 && WindUp.pick(null, null, 40) == 16 : "the weapon, then the entry, then the derived value";
        near(WeaponData.weaponSpeed(new ItemStack(Items.IRON_SWORD)), WindUp.SWORD_SPEED, 1e-6, "the constant is the iron sword's attack speed (vanilla stores its modifiers as floats)");
        Object[][] real = {{Items.IRON_SWORD, 8}, {Items.IRON_AXE, 14}, {Items.GOLDEN_AXE, 13}, {Items.IRON_SHOVEL, 13}, {Items.TRIDENT, 12}, {Items.MACE, 16}};
        for (Object[] r : real) {
            ItemStack held = new ItemStack((net.minecraft.world.item.Item) r[0]);
            assert WindUp.pick(WeaponData.weaponSpeed(held), null, 20) == (Integer) r[1] : held.getItem() + " winds up " + WindUp.pick(WeaponData.weaponSpeed(held), null, 20) + ", not " + r[1];
        }
        assert WeaponData.weaponSpeed(new ItemStack(Items.STICK)) == null && WeaponData.weaponSpeed(new ItemStack(Items.TORCH)) == null && WeaponData.weaponSpeed(new ItemStack(Items.FISHING_ROD)) == null
                && WeaponData.weaponSpeed(ItemStack.EMPTY) == null : "a stick, a torch, a rod and an empty hand are not weapons";
        assert WindUp.pick(WeaponData.weaponSpeed(ItemStack.EMPTY), 0.5, 20) == 10 && WindUp.pick(WeaponData.weaponSpeed(new ItemStack(Items.STICK)), null, 20) == 8 : "an unarmed mob keeps its entry or the derived value";

        // ---- the message ----
        io.netty.buffer.ByteBuf ebuf = io.netty.buffer.Unpooled.buffer();
        EngulfPayload.CODEC.encode(ebuf, new EngulfPayload(30, EngulfPayload.ORANGE));
        assert EngulfPayload.CODEC.decode(ebuf).equals(new EngulfPayload(30, EngulfPayload.ORANGE)) : "the engulf message carries its tint";
        for (WindUpPayload p : new WindUpPayload[] {new WindUpPayload(5, WindUpPayload.START, 8, WindUpPayload.LEAN), new WindUpPayload(70000, WindUpPayload.STRIKE, 12, WindUpPayload.OVERHEAD),
                new WindUpPayload(0, WindUpPayload.CANCEL, 0, WindUpPayload.SHIVER)}) {
            ByteBuf buf = Unpooled.buffer();
            WindUpPayload.CODEC.encode(buf, p);
            WindUpPayload back = WindUpPayload.CODEC.decode(buf);
            assert back.equals(p) && buf.readableBytes() == 0 : "round trip of " + p + ", got " + back;
        }

        // The shipped data: golem and ravager 0.6 s, vindicator 0.5 s (checked against mob_attacks.json in AttackLoadTest).
        System.out.println("WindUpTest ok");
    }
}
