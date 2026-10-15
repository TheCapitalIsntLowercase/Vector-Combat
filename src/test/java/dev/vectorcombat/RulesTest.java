package dev.vectorcombat;

import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.HitResult;

/** Pure-logic checks for damage modifiers and attack-speed penalties. Run with {@code java -ea}. */
public final class RulesTest {
    public static void main(String[] args) {
        // Vindicator from the demo: weak to pierce, resists slash and cleave/bash.
        List<Profiles.Mod> vindicator = List.of(new Profiles.Mod(Set.of("pierce"), 1.25),
                new Profiles.Mod(Set.of("slash"), 0.75), new Profiles.Mod(Set.of("cleave", "bash"), 0.5));
        assert Profiles.multiplier(vindicator, List.of("pierce")) == 1.25;
        assert Profiles.multiplier(vindicator, List.of("slash")) == 0.75;
        assert Profiles.multiplier(vindicator, List.of("bash")) == 0.5;
        assert Profiles.multiplier(vindicator, List.of("magic")) == 1.0;
        assert Profiles.multiplier(vindicator, List.of("pierce", "bash")) == 0.625 : "multi-category attacks multiply";

        // Repeating the same attack escalates; a different attack resets; waiting resets.
        Props props = Props.BASE;
        Penalties.State s = new Penalties.State();
        assert s.hit("Lunge", 0, props, 1) == 0.15;
        assert s.hit("Lunge", 10, props, 1) == 0.30 : "repeat within 1s window stacks";
        assert Math.abs(s.hit("Lunge", 20, props, 1) - 0.45) < 1e-9;
        assert s.hit("Sweep", 30, props, 1) == 0.15 : "different attack resets the stack (no fatigue)";
        Penalties.State alt = new Penalties.State();
        double[] alternating = new double[6];
        for (int i = 0; i < 6; i++) alternating[i] = alt.hit(i % 2 == 0 ? "Slash" : "sweep", i * 13, props, 1, 0.08);
        assert Math.abs(alternating[0] - 0.15) < 1e-9 && Math.abs(alternating[1] - 0.23) < 1e-9 && Math.abs(alternating[2] - 0.31) < 1e-9 && Math.abs(alternating[5] - 0.55) < 1e-9
                : "alternating at a sword's recharge still grows, 8% a hit: " + java.util.Arrays.toString(alternating);
        Penalties.State same = new Penalties.State();
        same.hit("Slash", 0, props, 1, 0.08);
        assert Math.abs(same.hit("Slash", 13, props, 1, 0.08) - (0.15 + 0.15 + 0.08)) < 1e-9 : "repeating the same attack costs fatigue and its repeat penalty";
        assert alt.hit("Slash", 5 * 13 + 21, props, 1, 0.08) == 0.15 : "a pause as long as the window clears the fatigue";
        assert Strike.MIN_STRENGTH == 0.9f && Math.abs(Strike.strengthFactor(1) - 1) < 1e-9 && Math.abs(Strike.strengthFactor(0.9f) - 0.725) < 1e-3 && Strike.strengthFactor(0.9f) < 0.2 + 0.81 * 0.8
                : "a swing needs 90% charge and an early one loses more than vanilla's";
        assert s.hit("Sweep", 500, props, 1) == 0.15 : "an expired penalty does not stack";
        for (int t = 0; t < 40; t++) s.hit("Lunge", 600 + t, props, 1);
        assert s.hit("Lunge", 640, props, 1) == Penalties.CAP : "penalty is capped";

        // The windows are measured in the weapon's own cooldowns: stretched for slow weapons, a little shorter for fast ones, off at reference 0.
        assert Penalties.windowScale(1.6, 1.6) == 1 && Math.abs(Penalties.windowScale(0.7, 1.6) - 16 / 7.0) < 1e-9 && Math.abs(Penalties.windowScale(0.6, 1.6) - 16 / 6.0) < 1e-9 : "the sword is the reference";
        assert Penalties.windowScale(2.4, 1.6) == 0.75 && Penalties.windowScale(3.0, 1.6) == 0.75 : "fast weapons shrink to a floor, the animation limits them anyway";
        assert Penalties.windowScale(0.1, 1.6) == 3 && Penalties.windowScale(0.7, 0) == 1 : "capped for the slowest, 0 is off";
        Penalties.State plain = new Penalties.State(), hammer = new Penalties.State();
        double hammerScale = Penalties.windowScale(0.7, 1.6);
        plain.hit("Slash", 0, props, 1);
        hammer.hit("Slash", 0, props, hammerScale);
        assert plain.hit("Slash", 34, props, 1) == 0.15 : "a hammer swing 1.7 s later is outside a 1 s window";
        assert Math.abs(hammer.hit("Slash", 34, props, hammerScale) - 0.30) < 1e-9 && hammer.window == (long) (props.consecutive().seconds() * hammerScale * 20) : "but a repeat inside the stretched one, which then keeps the longer 2 s window open";

        // Props inherit: attack over set defaults over base.
        Props defaults = new Props(List.of("slash"), null, null, null).over(Props.BASE);
        Props attack = new Props(null, List.of(new Effect.Stun(1)), new Penalty(0.3, 2), null).over(defaults);
        assert attack.categories().equals(List.of("slash")) && attack.penalty().amount() == 0.3
                && attack.consecutive().equals(Props.BASE.consecutive());

        // A swing without categories of its own takes its weapon's (axes cleave); the rest of the attack is untouched.
        Props axe = new Props(List.of("cleave"), null, null, null).over(Props.BASE);
        Props onAxe = Attacks.withCategories(attack, axe);
        assert onAxe.categories().equals(List.of("cleave")) && onAxe.effects().equals(attack.effects()) && onAxe.penalty().equals(attack.penalty())
                && onAxe.consecutive().equals(attack.consecutive()) : "weapon categories replace only the categories";
        assert Attacks.withCategories(attack, null) == attack && Attacks.withCategories(attack, defaults) == attack : "no weapon set, or the same categories, change nothing";
        assert Attacks.withCategories(attack, Props.BASE) == attack : "a weapon set naming no categories keeps the attack's";

        // Which clicks vanilla keeps: a mining tool keeps every block, others only the blocks they are the right tool for; mobs and air swing.
        HitResult.Type block = HitResult.Type.BLOCK, entity = HitResult.Type.ENTITY;
        assert "tool".equals(Swing.keepsClick(block, false, true, false)) && "tool".equals(Swing.keepsClick(block, false, true, true)) : "a pickaxe on dirt mines";
        assert "correct".equals(Swing.keepsClick(block, false, false, true)) : "a sword on cobweb mines";
        assert Swing.keepsClick(block, false, false, false) == null : "a sword on dirt swings";
        assert Swing.minesFast(2f) && Swing.minesFast(6f) && !Swing.minesFast(1.5f) && !Swing.minesFast(1f)
                : "a tool faster than a sword's 1.5 on a block mines it (modded tools with no ability); a sword on leaves or a hand still swing";
        assert "entity".equals(Swing.keepsClick(entity, false, false, false)) : "item frames, boats and crystals stay vanilla";
        assert Swing.keepsClick(entity, true, true, true) == null && Swing.keepsClick(entity, true, false, false) == null : "a mob is swung at, tool or not";
        assert Swing.keepsClick(HitResult.Type.MISS, false, true, true) == null : "air swings";

        // A held attack key swings again only when every condition holds: setting on, key down with the game in front, hand free, no clip, not stunned,
        // the press not mining (a block breaking must not end in a swing at the air behind it), recharged.
        assert Swing.repeats(true, true, true, false, false, false, false, 1f, 1) : "all met: swing again";
        assert !Swing.repeats(false, true, true, false, false, false, false, 1f, 1) : "setting off";
        assert !Swing.repeats(true, false, true, false, false, false, false, 1f, 1) : "key up";
        assert !Swing.repeats(true, true, false, false, false, false, false, 1f, 1) : "a screen is open or the mouse is free";
        assert !Swing.repeats(true, true, true, true, false, false, false, 1f, 1) : "eating, drawing or blocking";
        assert !Swing.repeats(true, true, true, false, true, false, false, 1f, 1) : "the clip is still playing";
        assert !Swing.repeats(true, true, true, false, false, true, false, 1f, 1) : "stunned";
        assert !Swing.repeats(true, true, true, false, false, false, true, 1f, 1) : "this press is mining";
        assert !Swing.repeats(true, true, true, false, false, false, false, 0.95f, 1) && Swing.repeats(true, true, true, false, false, false, false, 0.85f, 0.8) : "recharge against the minimum";

        // The server's attack-strength clock reads like vanilla's ticker (ticks since the reset, plus half, over the recharge time) and is clamped to 0..1.
        assert Math.abs(Strike.clockStrength(100, 100, 20f) - 0.025f) < 1e-6 && Math.abs(Strike.clockStrength(110, 100, 20f) - 0.525f) < 1e-6 : "(ticks + 0.5) / delay";
        assert Strike.clockStrength(130, 100, 20f) == 1f && Strike.clockStrength(99, 100, 20f) == 0f : "clamped";
        assert Math.max(0.09f, Strike.clockStrength(130, 100, 16.7f)) == 1f : "mining reset vanilla's ticker to 0.09, the clock still says ready";
        assert Math.max(0.9f, Strike.clockStrength(101, 100, 16.7f)) == 0.9f : "a vanilla ticker above the clock is never lowered";
        // The standing slash alternates: first plain, then the alternate, then plain again, as long as each swing follows the last within the window
        // (the weapon's recharge and a second more); a pause, a different attack or weapon, the setting off or an attack with no alternate starts over.
        long win = 1600;
        assert !Swing.alternates(true, "swing_alt", "", false, "", 0, "Slash", "minecraft:iron_sword", 1000, win) : "the first swing is the forehand";
        assert Swing.alternates(true, "swing_alt", "Slash", false, "minecraft:iron_sword", 1000, "Slash", "minecraft:iron_sword", 1700, win) : "then the backhand";
        assert !Swing.alternates(true, "swing_alt", "Slash", true, "minecraft:iron_sword", 1700, "Slash", "minecraft:iron_sword", 2400, win) : "then the forehand again";
        assert !Swing.alternates(true, "swing_alt", "Slash", false, "minecraft:iron_sword", 1000, "Slash", "minecraft:iron_sword", 2700, win) : "too long a pause: the pair starts over";
        assert Swing.alternates(true, "swing_alt", "Slash", false, "minecraft:iron_sword", 1000, "Slash", "minecraft:iron_sword", 2600, win) : "right at the window it still follows";
        assert !Swing.alternates(true, "swing_alt", "Left Sweep", false, "minecraft:iron_sword", 1000, "Slash", "minecraft:iron_sword", 1700, win) : "another attack in between resets it";
        assert !Swing.alternates(true, "swing_alt", "Slash", false, "minecraft:iron_axe", 1000, "Slash", "minecraft:iron_sword", 1700, win) : "so does another weapon";
        assert !Swing.alternates(false, "swing_alt", "Slash", false, "minecraft:iron_sword", 1000, "Slash", "minecraft:iron_sword", 1700, win) : "setting off: always the forehand";
        assert !Swing.alternates(true, "", "Falling Slash", false, "minecraft:iron_sword", 1000, "Falling Slash", "minecraft:iron_sword", 1700, win) : "an attack with no alternate never alternates";

        // The server shows everyone only a clip the weapon really has for the attack or its alternate.
        assert Strike.chooseClip("swing_alt", "swing", "swing_alt").equals("swing_alt") && Strike.chooseClip("swing", "swing", "swing_alt").equals("swing") : "either clip of the pair";
        assert Strike.chooseClip("overhead", "swing", "swing_alt").equals("swing") && Strike.chooseClip(null, "swing", "swing_alt").equals("swing") : "anything else is the attack's own";
        assert Strike.chooseClip("swing_alt", "swing", null).equals("swing") && Strike.chooseClip("swing", "swing", null).equals("swing") : "no alternate: only the attack's own";
        assert Strike.chooseClip("swing_alt", "swing", "swing").equals("swing") : "a weapon that maps the alternate back to the first shows the first";
        System.out.println("RulesTest ok");
    }
}
