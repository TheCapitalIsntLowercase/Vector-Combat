package dev.vectorcombat;

import net.minecraft.world.phys.Vec3;

/** Pure-logic checks for the length of a hit's pause, the clip clock that holds for it and the registry of held mobs. Run with {@code java -ea}. */
public final class HitstopTest {
    private static int ticks(double speed) {
        return Hitstop.ticks(speed, -1, 0, false, 1, 6);
    }

    public static void main(String[] args) {
        // Heavier weapons pause longer. Speeds are 4 plus the item's modifier, a float widened to double as the game does it.
        double sword = 4 + (double) -2.4f, ironAxe = 4 + (double) -3.1f;
        assert ticks(sword) == 2 && ticks(1.2) == 3 && ticks(1.1) == 3 && ticks(1.0) == 3 : "sword 2, pickaxe 3, trident 3, diamond axe 3";
        assert ticks(ironAxe) == 4 && ticks(0.8) == 4 && ticks(0.6) == 5 : "iron axe 4, stone axe 4, mace 5";
        assert ticks(2.5) == 1 && ticks(3.0) == 1 && ticks(4.0) == 1 && ticks(2.0) == 2 && ticks(0.1) == 5 && ticks(0) == 5 : "from 1 (fast weapons barely pause) to 5 from the formula";

        assert Hitstop.ticks(sword, -1, 0, true, 1, 6) == 3 && Hitstop.ticks(0.6, -1, 0, true, 1, 6) == 6 : "a kill lingers one tick more";
        assert Hitstop.ticks(0.6, -1, 0, true, 1, 5) == 5 : "the cap wins";
        assert Hitstop.ticks(sword, -1, 0, false, 0, 6) == 0 && Hitstop.ticks(sword, -1, 0, true, 0, 6) == 0 : "scale 0 turns it off";
        assert Hitstop.ticks(sword, -1, 0, false, 2, 6) == 4 && Hitstop.ticks(0.6, -1, 0, false, 2, 6) == 6 : "scale doubles, then the cap";
        assert Hitstop.ticks(sword, -1, 0, false, 0.5, 6) == 1 : "half scale: one tick";
        assert Hitstop.ticks(0.6, 3, 0, false, 1, 6) == 3 && Hitstop.ticks(sword, 4, 0, true, 1, 6) == 5 : "an attack's own ticks replace the formula, a kill still adds one";
        assert Hitstop.ticks(0.6, 0, 0, true, 1, 6) == 0 : "an attack can opt out";
        assert Hitstop.ticks(sword, -1, 1, false, 1, 6) == 3 && Hitstop.ticks(0.6, -1, 1, false, 1, 6) == 6 : "a bonus adds ticks to the formula (the falling slash)";
        assert Hitstop.ticks(0.6, 3, 1, false, 1, 6) == 3 && Hitstop.ticks(0.6, 0, 1, true, 1, 6) == 0 : "an attack's own ticks ignore the bonus";

        // The cap limits the pause, not the weight: at your config (scale 0.9, cap 3) a mace and a pickaxe pause alike but weigh differently.
        assert Hitstop.ticks(0.6, -1, 0, false, 0.9, 3) == 3 && Hitstop.ticks(1.2, -1, 0, false, 0.9, 3) == 3 : "both hit the cap";
        assert Hitstop.weightTicks(0.6, -1, 0, false, 0.9) == 5 && Hitstop.weightTicks(0.9, -1, 0, false, 0.9) == 4 && Hitstop.weightTicks(1.2, -1, 0, false, 0.9) == 3
                && Hitstop.weightTicks(sword, -1, 0, false, 0.9) == 2 && Hitstop.weightTicks(3.0, -1, 0, false, 0.9) == 1 : "mace 5, iron axe 4, pickaxe 3, sword 2, hoe 1";
        assert Hitstop.weightTicks(sword, -1, 0, true, 0.9) == 3 && Hitstop.weightTicks(0.6, -1, 1, true, 0.9) == 6 : "a kill and a falling slash weigh more";
        assert Hitstop.weightTicks(0.6, -1, 0, false, 0) == 0 && Hitstop.weightTicks(0.6, 0, 0, true, 1) == 0 : "off when scaled to nothing or opted out";

        // The clip clock runs freely, stands still during a hold, and picks up exactly where it stopped.
        Swing.Hold none = Swing.Hold.NONE;
        assert none.clock(1500, 1000, 1) == 0.5 : "no hold: wall time since the start";
        assert none.clock(1500, 1000, 0.25) == 0.125 : "playback speed scales the clip clock";
        Swing.Hold h = none.add(1100, 100); // held 1100..1200
        assert h.clock(1100, 1000, 1) == 0.1 && h.clock(1150, 1000, 1) == 0.1 && h.clock(1199, 1000, 1) == 0.1 : "frozen at the clip time the hold began";
        assert h.holding(1100) && h.holding(1199) && !h.holding(1200) && !none.holding(1000);
        assert h.clock(1200, 1000, 1) == 0.1 && Math.abs(h.clock(1300, 1000, 1) - 0.2) < 1e-12 : "resumes from where it stopped";
        assert Math.abs(h.clock(1300, 1000, 0.25) - 0.05) < 1e-12 : "a hold is real time, the clip speed applies after it";

        Swing.Hold longer = h.add(1150, 50); // asked again during the hold: the pause extends
        assert longer.until() == 1250 && longer.clock(1249, 1000, 1) == 0.1 && Math.abs(longer.clock(1350, 1000, 1) - 0.2) < 1e-12 : "a hold during a hold extends it";

        Swing.Hold twice = h.add(1400, 100); // a second pause after the first is over
        assert twice.done() == 100 && Math.abs(twice.clock(1400, 1000, 1) - 0.3) < 1e-12 : "between holds the clock has lost only the first";
        assert Math.abs(twice.clock(1450, 1000, 1) - 0.3) < 1e-12 && Math.abs(twice.clock(1500, 1000, 1) - 0.3) < 1e-12 : "frozen again";
        assert Math.abs(twice.clock(1600, 1000, 1) - 0.4) < 1e-12 : "both holds are subtracted afterwards";

        // A held mob: free until frozen, held for exactly its ticks, ends once with the momentum it was holding back, launch swaps that momentum
        // only while the hold is on, and the gate keeps a pile-on from chaining holds.
        Hitstop.Freezes<Object> mobs = new Hitstop.Freezes<>();
        Object mob = new Object();
        Vec3 knockback = new Vec3(0, 0.4, 1.2), launched = new Vec3(0, 0.5, 0.1);
        assert mobs.idle() && mobs.ready(mob, 100) && !mobs.holding(mob, 100) && mobs.ended(mob, 100) == null : "nothing held yet";
        mobs.freeze(mob, 100, 3, knockback, Vec3.ZERO);
        assert !mobs.idle() && mobs.holding(mob, 100) && mobs.holding(mob, 102) && !mobs.holding(mob, 103) : "held for exactly its ticks";
        assert mobs.ended(mob, 102) == null : "not ended while held";
        assert mobs.launch(mob, 101, launched) && !mobs.launch(mob, 103, Vec3.ZERO) : "launch only while the hold is on";
        Hitstop.Freezes.State ended = mobs.ended(mob, 103);
        assert ended != null && ended.velocity().equals(launched) && ended.until() == 103 && ended.from().equals(Vec3.ZERO) : "ends with the launched momentum";
        assert mobs.ended(mob, 104) == null && mobs.idle() && !mobs.holding(mob, 103) : "ends once";
        assert !mobs.ready(mob, 109) && mobs.ready(mob, 110) : "the gate holds for 10 ticks from the start of the last hold";
        Object other = new Object();
        mobs.freeze(other, 200, 2, null, Vec3.ZERO);
        assert mobs.ended(other, 202).velocity() == null && mobs.ready(mob, 200) : "a client hold keeps no momentum; keys are independent";

        // How long a held mob has left, counted from the partial tick being drawn.
        Hitstop.Freezes<Object> shaken = new Hitstop.Freezes<>();
        Object m = new Object();
        assert shaken.remaining(m, 100) == 0 : "not held: nothing left";
        shaken.freeze(m, 100, 3, null, Vec3.ZERO);
        assert shaken.remaining(m, 100) == 3 && shaken.remaining(m, 101.5) == 1.5 && shaken.remaining(m, 103) == 0 && shaken.remaining(m, 110) == 0 : "counts down to the end of the hold, never below 0";

        // The impact effects follow the hold: full at its start, gone one tick after it ends, nothing before it or with intensity 0.
        long from = 1000, until = 1150; // a 150 ms hold
        assert Impact.env(999, from, until) == 0 && Impact.env(from, from, until) == 1 : "starts at full";
        assert Impact.env(1075, from, until) > Impact.env(1149, from, until) && Impact.env(1149, from, until) > 0 : "falls through the hold";
        assert Impact.env(until + 50, from, until) == 0 && Impact.env(until + 500, from, until) == 0 && Impact.env(5, 0, 0) == 0 : "gone a tick after it ends; no hold, no effect";
        for (long t = 990; t < 1300; t++) assert Impact.env(t, from, until) >= 0 && Impact.env(t, from, until) <= 1 : "always within 0..1";
        double worst = 0;
        for (long t = from; t <= until + 50; t++) for (double a : Impact.rattle(t, from, until, 1)) worst = Math.max(worst, Math.abs(a));
        assert worst > 0.2 && worst <= 0.8 : "the rattle is felt but stays under a degree, got " + worst;
        for (double a : Impact.rattle(1010, from, until, 0)) assert a == 0 : "intensity 0 is off";
        for (double a : Impact.rattle(1010, from, until, 2)) assert Math.abs(a) <= 1.6 : "intensity 2 doubles it";
        assert Impact.pinch(from, from, until, 1) == 0 && Math.abs(Impact.pinch(from + 30, from, until, 1) - 0.03) < 1e-9 && Impact.pinch(until - 1, from, until, 1) == 0.03 : "in over 30 ms, held through the hold";
        assert Impact.pinch(until + 75, from, until, 1) < 0.03 && Impact.pinch(until + 75, from, until, 1) > 0 && Impact.pinch(until + 150, from, until, 1) == 0 : "back out over 150 ms";
        assert Impact.pinch(from + 50, from, until, 0) == 0 && Impact.pinch(from - 1, from, until, 1) == 0 : "off at intensity 0, nothing before the hold";
        assert Impact.shudder(from, from, until, 1)[2] == 0.02 && Impact.shudder(until + 50, from, until, 1)[2] == 0 : "the push back is full at the start and gone a tick after the end";
        double[] still = Impact.shake(0, 12345, 1), full = Impact.shake(3, 12345, 1), part = Impact.shake(1, 12345, 1);
        assert still[0] == 0 && still[1] == 0 : "a mob that is not held does not shake";
        assert Math.hypot(part[0], part[1]) < Math.hypot(full[0], full[1]) : "it fades over the last three ticks";
        assert Math.abs(Impact.shake(9, 12345, 1)[0] - full[0]) < 1e-12 : "full from three ticks out, not more";
        System.out.println("HitstopTest ok");
    }
}
