package dev.vectorcombat;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Pure-logic checks for the contact sound table: weapon profiles, accents, gains, and how loudness and pitch follow weight. Run with {@code java -ea}. */
public final class HitFxTest {
    public static void main(String[] args) {
        // Every weapon profile has sounds; slash, cleave and pierce a crack, a body and a ring (bash is its smash and thump); a kill adds exactly the crack.
        for (String c : new String[] {"slash", "pierce", "cleave", "bash"}) {
            List<HitFx.Layer> hit = HitFx.layers(c, false), kill = HitFx.layers(c, true);
            assert kill.size() == hit.size() + 1 && kill.get(hit.size()).sound().endsWith("attack.crit") : c + ": a kill adds the crack, last";
            assert c.equals("bash") ? hit.size() == 2 : hit.size() >= 3 : c + ": layers";
            assert hit.get(0).gain() >= 0.7f && hit.get(0).gain() <= 1.0f : c + ": the first layer is the main sound, near full gain";
            for (HitFx.Layer l : kill) {
                ResourceLocation.parse(l.sound());
                assert l.gain() > 0 && l.gain() <= 1.0f : l + ": no layer is clamped by the engine at config 1";
                assert l.pitch() >= 0.5f && l.pitch() <= 2f : l;
                assert Math.abs(HitFx.volume(l, 1f, 1f) * HitFx.def(l.sound()) - l.gain()) < 1e-5 : l + ": the passed volume undoes the definition volume";
            }
        }
        assert HitFx.layers("magic", false).equals(HitFx.layers("slash", false)) && HitFx.layers(null, true).equals(HitFx.layers("slash", true)) : "unknown means slash";
        for (String a : new String[] {"slash", "pierce", "cleave", "bash"}) {
            for (String b : new String[] {"slash", "pierce", "cleave", "bash"}) {
                assert a.equals(b) || !HitFx.layers(a, false).equals(HitFx.layers(b, false)) : a + " and " + b + " sound different";
            }
        }
        assert HitFx.def("minecraft:entity.player.attack.strong") < 1 && HitFx.def("minecraft:item.mace.smash_ground") == 1 : "vanilla softens the attack sounds, not the smash";

        // The weapon decides the sound; the attack's own category only adds an accent when it differs.
        assert "cleave".equals(HitFx.accentFor("bash", "cleave")) && "pierce".equals(HitFx.accentFor("slash", "pierce")) && "slash".equals(HitFx.accentFor("cleave", "slash")) : "accents";
        assert HitFx.accentFor("slash", "slash") == null && HitFx.accentFor("bash", null) == null && HitFx.accentFor("slash", "bash") == null : "none for the same category, none named, or no accent for it";
        for (String a : new String[] {"slash", "pierce", "cleave"}) assert HitFx.accent(a).gain() > 0 && HitFx.accent(a).gain() <= 1f : a;
        assert HitFx.lead(null).equals("slash") && HitFx.lead(List.of()).equals("slash") && HitFx.lead(List.of("bash", "x")).equals("bash") : "lead category";

        // A longer pause hits harder, within a clamp.
        assert HitFx.weight(0) == 0.8f && HitFx.weight(1) == 0.8f : "short pauses stay at the floor";
        assert Math.abs(HitFx.weight(3) - 1.0f) < 1e-6f && Math.abs(HitFx.weight(6) - 1.3f) < 1e-6f && HitFx.weight(10) == 1.3f : "1.0 at 3 ticks, 1.3 from 6";
        for (int t = 0; t < 10; t++) assert HitFx.weight(t + 1) >= HitFx.weight(t) : "monotone";

        // Heavy weapons sound lower, light ones higher, so any weapon has a voice.
        assert Math.abs(HitFx.pitchScale(1.6) - 1f) < 1e-6f && HitFx.pitchScale(0.6) == 0.85f && HitFx.pitchScale(4) == 1.15f : "1 at a sword, clamped at both ends";
        for (double s = 0.2; s < 4; s += 0.2) assert HitFx.pitchScale(s + 0.2) >= HitFx.pitchScale(s) : "faster weapons never sound lower";

        // A group shares one hit's sparks: each victim gets less as more are hit, but the swing's total still grows with the group, by well under n times.
        assert HitFx.share(0) == 1f && HitFx.share(1) == 1f : "a single victim gets the full burst";
        for (int n = 1; n < 6; n++) {
            assert HitFx.share(n + 1) < HitFx.share(n) : "each victim gets less as the group grows";
            assert HitFx.share(n + 1) * (n + 1) > HitFx.share(n) * n : "the total never shrinks as the group grows";
        }
        assert HitFx.share(3) * 3 < 2 : "three victims make under twice one hit's sparks, not three times";

        // A parry rings on its own: every layer is a real sound id at a gain the engine does not clamp, and the set is unlike any weapon's hit.
        for (HitFx.Layer l : HitFx.PARRY) {
            ResourceLocation.parse(l.sound());
            assert l.gain() > 0 && l.gain() <= 1.0f && l.pitch() >= 0.5f && l.pitch() <= 2f : l;
            assert Math.abs(HitFx.volume(l, 1f, 1f) * HitFx.def(l.sound()) - l.gain()) < 1e-5 : l + ": the passed volume undoes the definition volume";
        }
        for (String c : new String[] {"slash", "pierce", "cleave", "bash"}) assert !HitFx.layers(c, false).equals(HitFx.PARRY) : "a parry does not sound like a " + c + " hit";
        assert HitFx.PARRY.size() >= 3 && HitFx.PARRY.get(0).sound().endsWith("shield.block") && HitFx.PARRY.get(0).pitch() > 1.2f : "the shield's thud pitched up, under a crack and a ting";
        System.out.println("HitFxTest ok");
    }
}
