package dev.vectorcombat;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.renderer.block.model.ItemTransform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The shipped attack and animation files must parse and agree with each other. Run with {@code java -ea}. */
public final class AttackLoadTest {
    private static List<String> read(String dir) throws Exception {
        try (Stream<Path> s = Files.list(Path.of(dir))) {
            return s.map(p -> {
                try {
                    return Files.readString(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
        }
    }

    private static AttackDef named(List<AttackDef> all, String name) {
        return all.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * The hand shows the rest pose the moment a clip stops, so a clip that ends anywhere else makes the weapon snap there.
     * The rotation counts as a rotation, not as three numbers: a long path's unwrapped Euler angles may end at +-360, or at (180, 180, 180), the other reading of none.
     */
    private static void atRest(Anim clip, String id) {
        double[] end = Anim.sample(clip.hand(), clip.length());
        for (int i = 0; i < 3; i++) assert Math.abs(end[i]) < 1e-3 : id + " must end at the rest pose, ends at " + Arrays.toString(end);
        Quaternionf q = new Quaternionf().rotateX((float) Math.toRadians(end[3])).rotateY((float) Math.toRadians(end[4])).rotateZ((float) Math.toRadians(end[5])); // Swing.hand's X, Y, Z
        assert Math.abs(Math.abs(q.w) - 1) < 1e-6 : id + " must end at the rest pose, ends turned " + Arrays.toString(end);
    }

    /** The model matrix of a display transform for the hand, as the item renderer applies it. */
    private static Matrix4f display(ItemTransform t, boolean left) {
        PoseStack ps = new PoseStack();
        t.apply(left, ps);
        return new Matrix4f(ps.last().pose());
    }

    /** The clip starts at {@code offset}, a rigid move in the hand frame: its translation and its x, y, z rotation (the order {@code Swing.hand} composes them in). */
    private static void startsAt(String id, Matrix4f offset) {
        Vector3f t = offset.getTranslation(new Vector3f());
        Vector3f r = offset.getUnnormalizedRotation(new Quaternionf()).getEulerAnglesZYX(new Vector3f());
        double[] start = Anim.sample(Anim.get(id).hand(), 0);
        double[] want = {t.x, t.y, t.z, Math.toDegrees(r.x), Math.toDegrees(r.y), Math.toDegrees(r.z)};
        for (int i = 0; i < 6; i++) assert Math.abs(start[i] - want[i]) < (i < 3 ? 5e-4 : 0.01) : id + " starts at " + Arrays.toString(start) + ", the models differ by " + Arrays.toString(want);
    }

    /**
     * The crescent follows the blade from the end of the windup to the impact, so a swing clip needs a trail whose strike ends on its impact and that has
     * shrunk away (0.2 s) before the clip ends; a thrust, and only a thrust, has a reach.
     */
    private static void hasTrail(Anim clip, String id) {
        Anim.Trail t = clip.trail();
        assert t != null && Anim.Trail.KINDS.contains(t.kind()) : id + " needs a trail of a known kind";
        assert t.start() >= 0 && t.start() < t.end() && Math.abs(t.end() - clip.impact()) < 1e-9 : id + " trail must end on the impact " + clip.impact() + ", got " + t.end();
        assert t.end() + 0.2 <= clip.length() + 1e-9 : id + " trail must have shrunk away by the end of the clip";
        assert t.thrust() == (t.reach() != null) : id + ": a thrust, and only a thrust, has a reach";
    }

    public static void main(String[] args) throws Exception {
        Attacks.load(read(args[0]));
        Anim.load(read(args[1]));
        Profiles.loadMobAttacks(read(args[2]).stream().map(JsonParser::parseString).toList());
        MobThrows.load(read(java.nio.file.Path.of(args[2]).resolveSibling("mob_throws").toString()).stream().map(JsonParser::parseString).toList());
        assert MobThrows.entries().size() == 4 : "four throwers ship: " + MobThrows.entries();
        MobThrows.Entry husk = MobThrows.entries().get(0), golem = MobThrows.entries().get(3);
        assert husk.thrower().equals("minecraft:husk") && husk.thrown().equals("minecraft:silverfish") && husk.unarmed() && husk.cooldown() == 200 && husk.waitAfter() == 120 && !husk.heaves()
                : "the husk throws a silverfish, bare-handed, every 10 s, 6 s after the last one died: " + husk;
        assert MobThrows.entries().get(1).thrown().equals("minecraft:slime") && !MobThrows.entries().get(1).unarmed() && MobThrows.entries().get(2).thrown().equals("minecraft:magma_cube")
                && MobThrows.entries().get(2).size() == 1 : "the bogged a small slime, the piglin brute a small magma cube, armed as they are";
        assert golem.thrower().equals("minecraft:iron_golem") && golem.heaves() : "the iron golem heaves its target";
        MobArming.load(read(java.nio.file.Path.of(args[2]).resolveSibling("mob_arming").toString()).stream().map(JsonParser::parseString).toList());
        assert MobArming.entries().size() == 1 && MobArming.entries().get(0).entities().contains("minecraft:zombie") && MobArming.entries().get(0).items().size() == 7
                && MobArming.entries().get(0).chance() == 0.3 && MobArming.entries().get(0).adultsOnly() : "zombie-family adults are armed 30% of the time: " + MobArming.entries();
        assert Profiles.windUps().equals(java.util.Map.of("minecraft:iron_golem", 0.6, "minecraft:ravager", 0.6, "minecraft:vindicator", 0.5, "minecraft:silverfish", 0.35,
                "minecraft:endermite", 0.35, "minecraft:warden", 0.7)) : "the heavy mobs wind up longer, the scuttlers shorter: " + Profiles.windUps();
        assert Profiles.signatures().equals(java.util.Map.of("minecraft:vindicator", "overhead", "minecraft:warden", "overhead", "minecraft:silverfish", "hop", "minecraft:endermite", "hop", "minecraft:enderman", "limbs", "minecraft:hoglin|adult", "feral", "minecraft:zoglin|adult", "feral", "minecraft:hoglin|baby", "feral", "minecraft:zoglin|baby", "feral")) : "signature tells: " + Profiles.signatures();
        assert Profiles.intervals().equals(java.util.Map.of("minecraft:enderman", 1.2, "minecraft:silverfish", 1.1, "minecraft:endermite", 1.1, "minecraft:hoglin|adult", 1.8, "minecraft:zoglin|adult", 1.8,
                "minecraft:hoglin|baby", 0.95, "minecraft:zoglin|baby", 0.95)) : "intervals (hoglins 10% faster, babies 20% slower than vanilla's 40 and 15 ticks): " + Profiles.intervals();
        assert WindUp.interval(1.8) == 36 && WindUp.interval(0.95) == 19 : "36 and 19 ticks";
        assert Profiles.key("minecraft:hoglin", null).equals("minecraft:hoglin") && Profiles.key("minecraft:hoglin", "baby").equals("minecraft:hoglin|baby") : "keys";
        assert Profiles.ageMatches(null, true) && Profiles.ageMatches("baby", true) && !Profiles.ageMatches("baby", false) && Profiles.ageMatches("adult", false) && !Profiles.ageMatches("adult", true)
                : "an entry's age filter";
        assert Profiles.reaches().equals(java.util.Map.of("minecraft:enderman", 0.5, "minecraft:ravager", 0.3)) : "reaches: " + Profiles.reaches();
        assert Profiles.forwards().equals(java.util.Map.of("minecraft:ravager", 1.25)) : "forward reaches: " + Profiles.forwards();
        List<AttackDef> all = Attacks.all();
        assert all.size() == 6 : "5 movement attacks + the default, got " + all.size();

        for (AttackDef a : all) {
            Anim clip = Anim.get(a.animation());
            assert clip != Anim.NONE && clip.length() > 0 && !clip.hand().isEmpty() : a.name() + " needs animation " + a.animation();
            assert a.hitAt() < clip.length() : a.name() + " must hit before its swing ends";
            assert clip.impact() > 0 && Math.abs(a.hitAt() - clip.impact()) < 1e-9 : a.name() + " hit_at " + a.hitAt() + " must equal " + a.animation() + " impact " + clip.impact();
            atRest(clip, a.animation());
            hasTrail(clip, a.animation());
            if (!a.alternate().isEmpty()) { // the other half of a pair lands on the same blow: the same impact and length, and it ends at rest with a trail of its own
                Anim alt = Anim.get(a.alternate());
                assert alt != Anim.NONE && !alt.hand().isEmpty() : a.name() + " needs its alternate " + a.alternate();
                assert Math.abs(alt.impact() - clip.impact()) < 1e-9 && Math.abs(alt.length() - clip.length()) < 1e-9 : a.alternate() + " must keep " + a.animation() + "'s impact and length";
                atRest(alt, a.alternate());
                hasTrail(alt, a.alternate());
                assert alt.trail().kind().equals(clip.trail().kind()) && !Arrays.equals(alt.trail().from(), clip.trail().from()) : a.alternate() + " starts somewhere else than " + a.animation();
            }
        }

        for (AttackDef a : all) assert List.of("", "forward", "back", "left", "right", "rising", "falling").contains(a.arrow()) : a.name() + " has unknown arrow " + a.arrow();
        assert named(all, "Left Sweep").arrow().equals("left") && named(all, "Falling Slash").arrow().equals("falling") && named(all, "Slash").arrow().isEmpty();

        AttackDef lunge = named(all, "Lunge");
        assert lunge.angle() == 0 && lunge.range() == 1.35 && lunge.damage() == 1.3;
        assert lunge.matches(new MoveState(true, true, false, 0.2, 0, 0));
        assert !lunge.matches(new MoveState(true, false, false, 0.2, 0, 0)) : "lunge needs sprinting";
        assert lunge.props().categories().equals(List.of("pierce")) && lunge.props().effects().size() == 3;
        assert lunge.props().penalty().amount() == 0.25 && lunge.props().consecutive().seconds() == 2.5;

        AttackDef fall = named(all, "Falling Slash");
        assert fall.matches(new MoveState(false, false, false, 0, 0, -0.3)) && !fall.matches(new MoveState(true, false, false, 0, 0, 0));
        assert fall.props().effects().get(0) instanceof Effect.Stun s && s.seconds() == 1.5 : "falling slash stuns";
        assert fall.hitstopBonus() == 1 && lunge.hitstopBonus() == 0 && fall.hitstop() == -1 : "the slam weighs a tick more, from the weapon's own formula";

        // A jump splits cleanly at the apex: Rising while going up, Falling from the top onward, never both, never neither.
        AttackDef rise = named(all, "Rising Slash");
        double[] vy = {0.42, 0.333, 0.248, 0.165, 0.083, 0.003, -0.075, -0.152, -0.228};
        int neither = 0;
        for (double v : vy) {
            MoveState air = new MoveState(false, false, false, 0, 0, v);
            assert !(rise.matches(air) && fall.matches(air)) : "one jump tick matched both at vy " + v;
            if (!rise.matches(air) && !fall.matches(air)) neither++;
            assert rise.matches(air) == (v >= 0.04) && fall.matches(air) == (v <= 0.04) : "vertical split wrong at vy " + v;
        }
        assert neither == 0 : "every airborne tick is Rising or Falling, got " + neither + " with neither";
        assert !rise.matches(new MoveState(true, false, false, 0, 0, 0.42)) : "no aerial attack on the ground";

        AttackDef left = named(all, "Left Sweep"), right = named(all, "Right Sweep");
        assert left.props().categories().equals(List.of("slash")) : "sweep inherits the sword's slash default";
        assert left.animation().equals("slash_to_left") && right.animation().equals("slash_to_right");
        for (AttackDef sweep : List.of(left, right)) {
            double side = sweep == left ? -1 : 1;
            assert sweep.angle() == 150 && sweep.damage() == 0.9 && sweep.props().effects().size() == 2 : "a sweep is a 150 degree, 0.9x crowd tool";
            for (Effect fx : sweep.props().effects()) {
                assert fx instanceof Effect.Motion m && Math.signum(m.motion().x) == side : sweep.name() + " pushes sideways";
                if (fx instanceof Effect.Motion m && !m.user()) assert Math.abs(m.motion().x) <= 0.35 : "the victim stays near its own reach";
            }
            assert Attacks.penaltyKey(sweep.name()).equals("sweep") : "both sweeps count as one attack for the repeat penalty";
        }
        AttackDef slashDef = named(all, "Slash");
        assert Attacks.penaltyKey(slashDef.name()).equals("Slash") && slashDef.angle() < left.angle() && slashDef.damage() > left.damage() : "the slash keeps its own penalty and the best single hit";
        assert left.matches(new MoveState(true, false, false, 0, -0.2, 0)) && !left.matches(new MoveState(true, false, false, 0, 0.2, 0));
        assert right.matches(new MoveState(true, false, false, 0, 0.2, 0)) && !right.matches(new MoveState(true, false, false, 0, -0.2, 0));

        AttackDef slash = named(all, "Slash");
        assert slash.when().isEmpty() && slash.angle() == 120 : "the default attack has no conditions";

        // Own categories are told apart from inherited ones: movement attacks keep theirs on any weapon, the default attack and sweeps take the weapon's.
        assert slash.props().categories().equals(List.of("slash")) && !slash.ownCategories() && !left.ownCategories() : "sweeps and the default attack inherit";
        assert lunge.ownCategories() && fall.ownCategories() && rise.ownCategories() : "movement attacks name their categories";

        // The shipped movement-attack set covers every weapon and sits below the category-only sets that tag axes, mace and trident.
        var sets = Attacks.raw().stream().map(s -> JsonParser.parseString(s)).flatMap(f -> Attacks.objects(f).stream()).toList();
        var weapons = sets.stream().filter(o -> o.has("attacks")).toList();
        assert weapons.size() == 1 && weapons.get(0).getAsJsonArray("match").toString().equals("[\"@weapon\"]") && weapons.get(0).get("priority").getAsInt() < 0
                : "one attack set, matching @weapon, below priority 0";
        assert sets.stream().filter(o -> !o.has("attacks") && o.has("defaults")).allMatch(o -> !o.has("priority") || o.get("priority").getAsInt() >= 0)
                : "category-only sets must not sit below the weapon set";

        // Tools stay slash where weapon data calls them maces (vanilla shovels are typed mace): the set that says so comes before the type sets that would catch them.
        java.util.function.Function<String, Integer> at = m -> {
            for (int i = 0; i < sets.size(); i++) {
                if (sets.get(i).getAsJsonArray("match").toString().contains(m)) return i;
            }
            return -1;
        };
        int tools = at.apply("#minecraft:shovels"), mace = at.apply("@type:mace");
        assert tools >= 0 && at.apply("#minecraft:pickaxes") == tools && at.apply("#minecraft:hoes") == tools : "one set pins the tools";
        assert mace > tools : "tools stay slash: their set comes before the mace type's";
        assert sets.get(tools).getAsJsonObject("defaults").getAsJsonArray("categories").toString().equals("[\"slash\"]") : "and says slash";
        assert sets.get(mace).getAsJsonObject("defaults").getAsJsonArray("categories").toString().equals("[\"bash\"]") : "maces bash";

        // A set's animation replacements (the trident's and spear's lunge and upward slash, the glaive's and quarterstaff's upward slash) are real clips that stand
        // in for a clip an attack uses: the same impact, so the server's hit_at still lands on the blow, the same length, and ending on the rest pose like every other clip.
        int mapped = 0;
        for (var set : sets) {
            if (!set.has("animations")) continue;
            for (var e : set.getAsJsonObject("animations").entrySet()) {
                String from = e.getKey(), to = e.getValue().getAsString();
                Anim clip = Anim.get(to);
                assert all.stream().anyMatch(a -> a.animation().equals(from) || a.alternate().equals(from)) : "no attack plays " + from;
                assert clip != Anim.NONE && !clip.hand().isEmpty() : to + " (replacing " + from + ") is not a clip";
                assert Math.abs(clip.impact() - Anim.get(from).impact()) < 1e-9 && Math.abs(clip.length() - Anim.get(from).length()) < 1e-9 : to + " must keep " + from + "'s impact and length";
                atRest(clip, to);
                hasTrail(clip, to);
                Anim.Trail mine = clip.trail(), theirs = Anim.get(from).trail();
                if (all.stream().anyMatch(a -> a.alternate().equals(from))) { // a long weapon showing its first clip for the alternate: not the same blow, so not the same trail
                    mapped++;
                    continue;
                }
                assert mine.kind().equals(theirs.kind()) && Arrays.equals(mine.from(), theirs.from()) && Arrays.equals(mine.to(), theirs.to()) && Arrays.equals(mine.reach(), theirs.reach())
                        && mine.start() == theirs.start() && mine.end() == theirs.end() && mine.pow() == theirs.pow() : to + " leaves the same trail as the clip it replaces, " + from;
                mapped++;
            }
        }
        assert mapped == 10 : "joust and uppercut for the trident and spears, uppercut for glaives and quarterstaves, and the first neutral clip for each one's alternate, got " + mapped;

        // A clip fitted to one mod's held model aims along that model's blade axis only. Jaam's spear sits on the sword's axis, and the spear clip (made for Basic Weapons' big
        // model) pointed it 65 degrees off the aim line, so a set that maps such a clip matches by namespaced id, never by a bare wildcard that any mod's item answers to.
        for (var set : sets) {
            if (!set.has("animations")) continue;
            boolean fitted = set.getAsJsonObject("animations").entrySet().stream().anyMatch(e -> e.getValue().getAsString().matches(".*_(trident|spear|glaive|quarterstaff)"));
            if (!fitted) continue;
            for (var m : set.getAsJsonArray("match")) {
                assert m.getAsString().matches("[a-z0-9_.-]+:.*") : "a model-fitted clip must match one mod's items, not " + m.getAsString();
            }
        }

        // A match with * is a wildcard over the whole item id: every spear, whatever mod adds it.
        assert Attacks.glob("*spear*", "basicweapons:iron_spear") && Attacks.glob("*spear*", "somemod:spear_of_dawn") && Attacks.glob("*spear*", "spears:copper_blade") : "spear anywhere in the id";
        assert !Attacks.glob("*spear*", "minecraft:trident") && !Attacks.glob("*spear*", "minecraft:iron_sword") : "not a spear";
        assert Attacks.glob("*glaive*", "basicweapons:iron_glaive") && Attacks.glob("*quarterstaff*", "basicweapons:diamond_quarterstaff") && !Attacks.glob("*glaive*", "minecraft:iron_sword") : "glaives and quarterstaves";
        assert Attacks.glob("spears:*", "spears:iron") && !Attacks.glob("spears:*", "basicweapons:spears") && Attacks.glob("*_spear", "basicweapons:iron_spear") && !Attacks.glob("*_spear", "basicweapons:iron_spear_held") : "prefix and suffix";
        assert Attacks.glob("minecraft:trident", "minecraft:trident") && !Attacks.glob("minecraft:trident", "minecraft:tridents") : "no wildcard means equal";
        assert Attacks.glob("a*b*c", "axxbxxc") && !Attacks.glob("a*b*c", "axxcxxb") && !Attacks.glob("ab*bc", "abc") : "parts in order, not overlapping";

        // Every sword clip has a showcase variant for first person (Swing.firstPerson): a hand of its own, and everything else the server, the crescent and third person read
        // (length, impact, camera, trail) the same, so the style changes only what you see of your own swing. It starts and ends on the rest pose like its clip.
        int showcase = 0;
        for (String id : List.of("swing", "swing_alt", "slash_to_left", "slash_to_right", "joust", "overhead", "uppercut")) {
            Anim base = Anim.get(id), fp = Anim.get("showcase/" + id);
            assert fp != Anim.NONE && !fp.hand().isEmpty() : id + " needs a showcase variant";
            assert fp.length() == base.length() && fp.impact() == base.impact() : "showcase/" + id + " must keep " + id + "'s length and impact";
            assert fp.camera().size() == base.camera().size() : "showcase/" + id + " keeps " + id + "'s camera";
            Anim.Trail a = fp.trail(), b = base.trail();
            assert a.kind().equals(b.kind()) && Arrays.equals(a.from(), b.from()) && Arrays.equals(a.to(), b.to()) && a.start() == b.start() && a.end() == b.end() && a.pow() == b.pow()
                    : "showcase/" + id + " leaves " + id + "'s trail";
            for (double v : Anim.sample(fp.hand(), 0)) assert Math.abs(v) < 1e-9 : "showcase/" + id + " starts at rest";
            atRest(fp, "showcase/" + id);
            showcase++;
        }
        assert showcase == 7;

        // The shield's clips are short, end on the rest pose (hand and camera) and the parry has an impact where its pause starts.
        for (String id : List.of("guard_raise", "guard_raise_right", "guard_lower", "guard_lower_right", "guard_parry", "guard_hit")) {
            Anim clip = Anim.get(id);
            assert clip != Anim.NONE && clip.length() > 0 && clip.length() <= 0.5 && !clip.hand().isEmpty() : id + " is a short clip";
            atRest(clip, id);
            for (double v : Anim.sample(clip.camera(), clip.length())) assert Math.abs(v) < 1e-9 : id + "'s camera ends at rest";
        }
        Anim parry = Anim.get("guard_parry");
        assert parry.impact() > 0 && parry.impact() < parry.length() && !parry.camera().isEmpty() && !Anim.get("guard_hit").camera().isEmpty() : "the parry pauses at its impact, and the camera moves with it and with a block";

        // Raising starts where the idle model sat and lowering where the raised one did: the offset between the two models' display transforms (vanilla's shield.json and
        // shield_blocking.json, first person), for both arms, whose numbers are not mirror images, so the right arm has clips of its own.
        Vector3f scale = new Vector3f(1.25f, 1.25f, 1.25f);
        ItemTransform leftBlocking = new ItemTransform(new Vector3f(0, 180, -5), new Vector3f(5, 5, -11).mul(1 / 16f), scale), leftIdle = new ItemTransform(new Vector3f(0, 180, 5), new Vector3f(10, 0, -10).mul(1 / 16f), scale);
        ItemTransform rightBlocking = new ItemTransform(new Vector3f(0, 180, -5), new Vector3f(-15, 5, -11).mul(1 / 16f), scale), rightIdle = new ItemTransform(new Vector3f(0, 180, 5), new Vector3f(-10, 2, -10).mul(1 / 16f), scale);
        startsAt("guard_raise", display(leftIdle, true).mul(display(leftBlocking, true).invert()));
        startsAt("guard_lower", display(leftBlocking, true).mul(display(leftIdle, true).invert()));
        startsAt("guard_raise_right", display(rightIdle, false).mul(display(rightBlocking, false).invert()));
        startsAt("guard_lower_right", display(rightBlocking, false).mul(display(rightIdle, false).invert()));
        System.out.println("AttackLoadTest ok");
    }
}
