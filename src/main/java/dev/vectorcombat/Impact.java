package dev.vectorcombat;

import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Impact feel on top of a landed hit's pause, client side: the attacker's camera rattles, the world pinches in, the weapon in the hand shudders, and
 * every mob that is held shakes. All of it scales with the {@code [impact] intensity} setting (0 is off). The shapes are pure functions of time so they can be
 * checked without a game; the subscribers only feed them.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
final class Impact {
    /** How fast the camera rattles and the hand and mobs shake, in cycles per second. */
    private static final double RATTLE_HZ = 24, SHUDDER_HZ = 30;

    private static double peakRattle, peakPinch;

    /** How hard the latest hit seen hits: {@link HitFx#weight} of its pause before the cap, so a mace shakes more than a sword. */
    private static double weight = 1;

    private Impact() {
    }

    /** A hit of {@code weightTicks} landed (any attacker, seen from here): the effects that follow scale with it. */
    static void hit(int weightTicks) {
        weight = HitFx.weight(weightTicks);
    }

    /** The setting times the weight of the latest hit. */
    private static double intensity() {
        return ClientConfig.IMPACT_INTENSITY.get() * weight;
    }

    /** 1 at the start of a hold (milliseconds, {@code from} to {@code until}), falling linearly to 0 one tick after it ends; 0 before it and after. */
    static double env(long now, long from, long until) {
        if (until <= from || now < from) return 0;
        return Math.max(0, 1 - (now - from) / (double) (until - from + Hitstop.TICK_MS));
    }

    /** The camera's extra {pitch, yaw, roll} in degrees: under a degree, three out-of-step waves so it rattles instead of swaying. */
    static double[] rattle(long now, long from, long until, double intensity) {
        double e = env(now, from, until) * intensity;
        if (e <= 0) return new double[3];
        double phase = 2 * Math.PI * RATTLE_HZ * (now - from) / 1000.0;
        return new double[] {0.8 * e * Math.sin(phase), 0.5 * e * Math.sin(phase * 1.3 + 1.7), 0.4 * e * Math.sin(phase * 0.8 + 0.6)};
    }

    /** How much narrower the world's field of view is, as a fraction: in over 30 ms from the start of the hold, back out over 150 ms after its end. */
    static double pinch(long now, long from, long until, double intensity) {
        if (until <= from || now < from) return 0;
        double in = Math.min(1, (now - from) / 30.0), out = now < until ? 1 : Math.max(0, 1 - (now - until) / 150.0);
        return 0.03 * intensity * in * out;
    }

    /** The weapon's extra {x, y, z} in blocks: a small jitter and a push back toward the viewer (the clip's own clock is stopped for the hold). */
    static double[] shudder(long now, long from, long until, double intensity) {
        double e = env(now, from, until) * intensity;
        if (e <= 0) return new double[3];
        double phase = 2 * Math.PI * SHUDDER_HZ * (now - from) / 1000.0;
        return new double[] {0.006 * e * Math.sin(phase), 0.006 * e * Math.cos(phase * 1.1), 0.02 * e};
    }

    /** A held mob's {x, z} offset in blocks: a horizontal jitter that fades over the last three ticks of its hold. */
    static double[] shake(double remainingTicks, long nowMs, double intensity) {
        double e = Math.min(1, remainingTicks / 3.0) * intensity;
        if (e <= 0) return new double[2];
        double phase = 2 * Math.PI * SHUDDER_HZ * nowMs / 1000.0;
        return new double[] {0.03 * e * Math.sin(phase), 0.03 * e * Math.cos(phase * 1.3)};
    }

    /** The camera rattle to add this frame. */
    static double[] camera(Swing.Hold hold) {
        double[] r = rattle(Util.getMillis(), hold.from(), hold.until(), intensity());
        peakRattle = Math.max(peakRattle, Math.abs(r[0]));
        return r;
    }

    /** The weapon's extra offset this frame. */
    static double[] hand(Swing.Hold hold) {
        return shudder(Util.getMillis(), hold.from(), hold.until(), intensity());
    }

    /** The largest pitch rattle (degrees) and field-of-view pinch (fraction) applied since the last call, for the diag log. */
    static double[] takePeaks() {
        double[] peaks = {peakRattle, peakPinch};
        peakRattle = peakPinch = 0;
        return peaks;
    }

    @SubscribeEvent
    static void fov(ViewportEvent.ComputeFov e) {
        if (!e.usedConfiguredFov()) return; // the world, not the hand's own projection
        Swing.Hold hold = Swing.currentHold(), parry = GuardClient.currentHold(); // a swing's hold, or a parry's: whichever is later
        if (parry.until() > hold.until()) hold = parry;
        double k = pinch(Util.getMillis(), hold.from(), hold.until(), intensity());
        if (k <= 0) return;
        peakPinch = Math.max(peakPinch, k);
        e.setFOV(e.getFOV() * (1 - k));
    }

    @SubscribeEvent
    static void victim(RenderLivingEvent.Pre<?, ?> e) {
        double left = Hitstop.remaining(e.getEntity(), e.getPartialTick());
        if (left <= 0) return;
        double[] s = shake(left, Util.getMillis(), intensity());
        e.getPoseStack().translate(s[0], 0, s[1]);
    }
}
