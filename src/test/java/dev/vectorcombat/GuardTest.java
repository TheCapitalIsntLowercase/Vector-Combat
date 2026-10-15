package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;

/** Shield timing, as pure rules: the raise memory (the re-arm rule), the parry window, the front arc, the stagger, what is parryable, the setting, the refund's clock and the payload. Run with {@code java -ea}. */
public final class GuardTest {
    private static long lastSent;

    public static void main(String[] args) {
        // A raise is armed when the shield was down long enough: the ticks since it was last seen up, against rearm (10).
        Guard.Raise r = new Guard.Raise();
        assert r.look(100, true, 10) && r.armed : "the first raise of a session is armed";
        assert !r.look(100, true, 10) && r.armed : "a second look at the same raise, the same tick or later, is not a new raise";
        for (int t = 101; t < 130; t++) assert !r.look(t, true, 10) && r.armed : "holding the shield up keeps what the raise was";
        assert !r.look(130, false, 10) : "lowering is not a raise";
        assert r.lastUp == 129 : "last seen up on the last tick it was up";
        for (int c : new int[] {131, 138, 139, 140, 200}) { // the shield was last up at 129, so 139 is the first tick a raise is armed
            Guard.Raise s = new Guard.Raise();
            s.look(100, true, 10);
            s.look(129, true, 10);
            s.look(130, false, 10);
            assert s.look(c, true, 10) && s.armed == (c - 129 >= 10) && s.down == c - 129 : "a raise " + (c - 129) + " ticks after the shield was last up";
        }
        // Tapping: up for ticks 0 to 2, down, up again after 7 ticks (not armed) and the tap after that after 10 (armed). The row to check is the second raise.
        Guard.Raise tap = new Guard.Raise();
        for (int t = 0; t < 3; t++) tap.look(t, true, 10);
        for (int t = 3; t < 9; t++) tap.look(t, false, 10);
        assert tap.look(9, true, 10) && !tap.armed && tap.down == 7 : "a tap 7 ticks after the last one opens no window";
        for (int t = 10; t < 12; t++) tap.look(t, true, 10);
        for (int t = 12; t < 22; t++) tap.look(t, false, 10);
        assert tap.look(22, true, 10) && tap.armed && tap.down == 11 : "and one that waited 10 ticks does";
        Guard.Raise free = new Guard.Raise();
        free.look(0, true, 0);
        free.look(1, false, 0);
        assert free.look(2, true, 0) && free.armed : "a re-arm of 0 never limits";

        // The window: the first parryTicks of an armed raise.
        for (int used = 0; used < 10; used++) assert Guard.inWindow(true, used, 5) == (used < 5) && !Guard.inWindow(false, used, 5) : "tick " + used + " of a raise";
        assert Guard.inWindow(true, 9, 10) && !Guard.inWindow(true, 10, 10) && !Guard.inWindow(true, 0, 0) : "the setting moves the edge";

        // The front arc is vanilla's: the source in front of the view, by the horizontal vector from it to the defender.
        Vec3 view = new Vec3(0, 0, 1), defender = new Vec3(0, 0, 0);
        assert Guard.inFront(view, new Vec3(0, 0, 2), defender) : "straight ahead";
        assert Guard.inFront(view, new Vec3(2, 5, 2), defender) : "ahead and to the side, and above: height does not count";
        assert !Guard.inFront(view, new Vec3(0, 0, -2), defender) : "behind";
        assert !Guard.inFront(view, new Vec3(2, 0, 0), defender) && !Guard.inFront(view, new Vec3(-2, 0, 0), defender) : "exactly beside is not in front";
        assert !Guard.inFront(view, new Vec3(0, 3, 0), defender) : "straight above has no direction";
        assert Guard.inFront(new Vec3(1, 0, 0), new Vec3(3, 0, 0), defender) && !Guard.inFront(new Vec3(1, 0, 0), new Vec3(0, 0, 3), defender) : "any facing";

        // The stagger: base plus a share of the damage, clamped to 0.6..1.6 s.
        assert Guard.stagger(0, 0.4, 0.08) == 0.6 && Guard.stagger(2, 0.4, 0.08) == 0.6 : "light hits stagger the least";
        assert Math.abs(Guard.stagger(6, 0.4, 0.08) - 0.88) < 1e-9 && Math.abs(Guard.stagger(10, 0.4, 0.08) - 1.2) < 1e-9 : "in between it follows the damage";
        assert Guard.stagger(15, 0.4, 0.08) == 1.6 && Guard.stagger(40, 0.4, 0.08) == 1.6 : "heavy hits stagger the most";
        for (int d = 0; d < 30; d++) assert Guard.stagger(d + 1, 0.4, 0.08) >= Guard.stagger(d, 0.4, 0.08) : "a harder hit never staggers less";
        assert Guard.stagger(4, 0, 0) == 0.6 : "even with no setting it is the least";

        // How hard a parry hits: 2 to 6 weight ticks by damage.
        assert Guard.weight(0) == 2 && Guard.weight(3) == 3 && Guard.weight(6) == 4 && Guard.weight(12) == 6 && Guard.weight(60) == 6 : "weights by damage";
        for (int d = 0; d < 40; d++) assert Guard.weight(d + 1) >= Guard.weight(d) && Guard.weight(d) >= 2 && Guard.weight(d) <= 6 : "monotone, 2 to 6";

        // What can be parried: bypassing shields is never; an arrow needs no piercing; other projectiles are; melee needs a living attacker of a melee type.
        assert !Guard.parryable(true, false, 0, false, true, true) && !Guard.parryable(true, true, 0, true, false, false) : "what bypasses shields (explosions, magic, thorns) is out";
        assert Guard.parryable(false, true, 0, true, false, false) && !Guard.parryable(false, true, 1, true, false, false) : "an arrow, unless it pierces";
        assert Guard.parryable(false, false, 0, true, false, false) : "a fireball or a spit";
        assert Guard.parryable(false, false, 0, false, true, true) : "a mob's or a player's melee hit";
        assert !Guard.parryable(false, false, 0, false, false, true) && !Guard.parryable(false, false, 0, false, true, false) : "melee needs a living attacker, and a melee damage type (a cactus is not a swing)";

        // Who guards: a shield, or any item used in the block pose (a weapon mod's guard stance), whichever mod gave it; an item used another way does not.
        assert Guard.guards(true, net.minecraft.world.item.UseAnim.BLOCK) && Guard.guards(true, net.minecraft.world.item.UseAnim.NONE) : "a shield";
        assert Guard.guards(false, net.minecraft.world.item.UseAnim.BLOCK) : "a weapon in the block pose";
        assert !Guard.guards(false, net.minecraft.world.item.UseAnim.NONE) && !Guard.guards(false, net.minecraft.world.item.UseAnim.BOW) && !Guard.guards(false, net.minecraft.world.item.UseAnim.SPEAR) : "a bow, a trident, food";

        // A mob's weapon block: the share that gets through, and the wear (vanilla's shield rule).
        assert MobGuard.through(10, 0.7) > 2.99f && MobGuard.through(10, 0.7) < 3.01f && MobGuard.through(10, 1) == 0 && MobGuard.through(10, 0) == 10 : "weapon block share";
        assert MobGuard.through(10, 1.5) == 0 && MobGuard.through(10, -1) == 10 : "the share is clamped";
        assert MobGuard.wear(2.9f) == 0 && MobGuard.wear(3) == 4 && MobGuard.wear(7.5f) == 8 : "no wear under 3, else 1 + floor";

        // A mob's choice: the odds multiply and clamp, the block is cut so the two never pass 1, and a roll picks by the shares.
        MobGuardData.Odds o = MobGuardData.roll(new MobGuardData.Odds(0.3, 0.3, 1.5), new MobGuardData.Scale(1.2, 0.8), MobGuardData.Scale.ONE, 1.4);
        assert Math.abs(o.parry() - 0.504) < 1e-9 && Math.abs(o.block() - 0.336) < 1e-9 && o.cooldown() == 1.5 : "odds multiply";
        MobGuardData.Odds big = MobGuardData.roll(new MobGuardData.Odds(0.8, 0.8, 1), MobGuardData.Scale.ONE, MobGuardData.Scale.ONE, 2);
        assert big.parry() == 1 && big.block() == 0 : "clamped, the parry keeps its share";
        assert MobGuardData.roll(new MobGuardData.Odds(0.5, 0.5, 1), MobGuardData.Scale.ONE, MobGuardData.Scale.ONE, 0).parry() == 0 : "peaceful: never";
        MobGuardData.Odds pick = new MobGuardData.Odds(0.2, 0.3, 1);
        assert MobGuardData.choose(pick, 0.1).equals("parry") && MobGuardData.choose(pick, 0.3).equals("block") && MobGuardData.choose(pick, 0.6).equals("none") : "the roll picks by share";
        // When a mob raises for a hit due in some ticks: a parry lands about 2 ticks into the raise, a block past the 5 tick window; a hit due sooner gets a raise now.
        assert MobGuard.raiseIn(8, MobGuard.PARRY_LEAD) == 6 && MobGuard.raiseIn(8, MobGuard.BLOCK_LEAD) == 1 && MobGuard.raiseIn(3, MobGuard.BLOCK_LEAD) == 0 : "raise timing";
        assert MobGuard.BLOCK_LEAD > 5 && MobGuard.PARRY_LEAD < 5 : "a block's raise lands past the default window, a parry's inside it";

        // A mob's guard pose: vanilla's blocking arm (forward 54 degrees, turned in 30, the head's look within limits), blended in over 2 ticks; an idle arm hangs when standing.
        float[] arm = MobGuardClient.blockingArm(true, 0, 0, 0);
        assert Math.abs(arm[0] + 0.9424779f) < 1e-6 && Math.abs(arm[1] + Math.toRadians(30)) < 1e-6 : "the right arm blocks forward and turned in";
        assert MobGuardClient.blockingArm(false, 0, 0, 0)[1] > 0 && MobGuardClient.blockingArm(true, 0, -3, 0)[0] > -2.34f : "the left turns the other way; the head's pitch is clamped";
        assert MobGuardClient.raised(0) == 0 && MobGuardClient.raised(1) == 0.5f && MobGuardClient.raised(9) == 1 : "the raise blends in over 2 ticks";
        assert MobGuardClient.RAISE_TICKS < 5 : "the raise is drawn inside the default parry window";
        assert MobGuardClient.idleX(true, 3.7f, 0) == 0 && MobGuardClient.idleX(false, 0, 1) == 1 && MobGuardClient.idleX(true, 0, 1) == -1 : "an idle arm: still at rest, opposite phases walking";

        // Mob passives: an item is left to its own mod by its namespace or by a tag prefix (Jaam's traits on a vanilla sword); anything else runs its on-hit code.
        java.util.Set<String> ns = java.util.Set.of("jaams_weaponry");
        java.util.List<String> pre = java.util.List.of("jaams_weaponry:trait/");
        assert MobPassives.skipped("jaams_weaponry", java.util.List.of(), ns, pre) : "Jaam's own items";
        assert MobPassives.skipped("minecraft", java.util.List.of("minecraft:swords", "jaams_weaponry:trait/armor_breaker"), ns, pre) : "a vanilla sword Jaam's gives a trait";
        assert !MobPassives.skipped("basicweapons", java.util.List.of("basicweapons:clubs", "jaams_weaponry:compat/swords"), ns, pre) : "a club runs its own on-hit code";

        // A riposte: due from when it opens, for a short while, then the mob attacks on its own time.
        assert !MobGuard.due(100, 99) && MobGuard.due(100, 100) && MobGuard.due(100, 100 + MobGuard.RIPOSTE_DUE) && !MobGuard.due(100, 101 + MobGuard.RIPOSTE_DUE) : "riposte window";

        // Spawn shields: only into an empty off hand, never beside a ranged or two-handed weapon.
        assert MobShields.pick(new int[] {4, 1, 1}, 0) == 0 && MobShields.pick(new int[] {4, 1, 1}, 0.66) == 0 && MobShields.pick(new int[] {4, 1, 1}, 0.7) == 1
                && MobShields.pick(new int[] {4, 1, 1}, 0.99) == 2 : "a weighted pick";
        assert MobShields.pick(new int[] {}, 0.5) == -1 && MobShields.pick(new int[] {0, 0}, 0.5) == -1 && MobShields.pick(new int[] {0, 3}, 0.1) == 1 : "nothing to pick, or a weight of 0 skipped";
        assert MobShields.room(true, false, false) && !MobShields.room(false, false, false) && !MobShields.room(true, true, false) && !MobShields.room(true, false, true) : "room for a shield";

        // Guard break: a charged swing with a heavy attack or weapon; never a weak one.
        assert GuardBreak.heavySwing(true, false, 1f, 0.9) && GuardBreak.heavySwing(false, true, 0.95f, 0.9) : "a charged heavy swing breaks";
        assert !GuardBreak.heavySwing(true, true, 0.5f, 0.9) && !GuardBreak.heavySwing(false, false, 1f, 0.9) : "not half charged, not a light swing";
        // Deflect: in the arc on the impact tick reflects, a tick off knocks away, never one flying away.
        assert Deflect.verdict(true, true, 100, 100) == 2 && Deflect.verdict(true, true, 99, 100) == 2 && Deflect.verdict(true, true, 102, 100) == 1 && Deflect.verdict(true, true, 96, 100) == 1 : "perfect near the impact, else knocked away";
        assert Deflect.verdict(false, true, 100, 100) == 0 && Deflect.verdict(true, false, 100, 100) == 0 : "not met, or flying away: nothing";
        // An arrow crossing the arc between two ticks is met: 3 blocks a tick passing 2 blocks in front, sampled on its path.
        assert Deflect.path(new net.minecraft.world.phys.Vec3(0, 1.6, 0), 0f, new net.minecraft.world.phys.Vec3(-1.5, 1.6, 2), new net.minecraft.world.phys.Vec3(3, 0, 0), 3, 90) : "a fast arrow's path is swept";
        // The waiting ring: n spots spread over the far side, none within the gap round the attackers.
        double[] ring = MobTokens.spots(4, 0);
        assert ring.length == 4 && ring[0] > MobTokens.GAP - 1e-9 && ring[3] < 360 - MobTokens.GAP + 1e-9 && Math.abs(ring[1] - ring[0] - (360 - 2 * MobTokens.GAP) / 4) < 1e-9 : "spots spread evenly";
        assert Math.abs(MobTokens.spots(1, 90)[0] - 270) < 1e-9 : "one waiting mob stands opposite the attackers";
        // Turn-taking: a limit of 2 lets a third wait; 0 is no limit.
        assert MobTokens.free(1, 2) && !MobTokens.free(2, 2) && MobTokens.free(9, 0) : "attack tokens";
        // The pack: turns rotate, the ring prowls within its sway, surges come from the flanks.
        assert MobTokens.rank(true, true, 0, 999) == 0 && MobTokens.rank(false, true, 0, 50) == 1 && MobTokens.rank(false, false, 0, 50) == 2
                && MobTokens.rank(false, true, 0, MobTokens.ROTATE_TICKS) == 3 : "an attacker whose turn is over gives way";
        for (long t = 0; t < 400; t += 7) assert Math.abs(MobTokens.sway(t, 5)) <= MobTokens.PROWL + 1e-9 && MobTokens.PROWL < MobTokens.GAP : "the prowl stays off the attackers' side";
        assert java.util.Arrays.equals(MobTokens.flanks(5, 2), new int[] {0, 4}) && java.util.Arrays.equals(MobTokens.flanks(5, 3), new int[] {0, 4, 1})
                && MobTokens.flanks(1, 2).length == 1 && MobTokens.flanks(3, 0).length == 0 : "surges from the flanks";
        // The arena: the kit's counts, and the wave ring stays inside the pen.
        assert Arena.idAndCount("minecraft:arrow*32")[1].equals("32") && Arena.idAndCount("minecraft:bow")[1].equals("1") && Arena.idAndCount("minecraft:bow")[0].equals("minecraft:bow") : "kit counts";
        for (double a = 0; a < 7; a += 0.3) for (double d = 0; d <= 1; d += 0.25) {
            int[] at = Arena.ringSpot(a, d);
            assert Math.abs(at[0]) <= Arena.HALF && Math.abs(at[1]) <= Arena.HALF && at[0] * at[0] + at[1] * at[1] >= 9 : "a mob spawns inside the pen, not on the player";
        }

        // Loris stamina by weapon: a sword's is the base, a dagger's less, a hammer's more, within half and one and a half.
        assert Penalties.staminaScale(1.6) == 1 && Penalties.staminaScale(3.2) == 0.5 && Penalties.staminaScale(0.8) == 1.5 && Penalties.staminaScale(0.4) == 1.5 && Penalties.staminaScale(0) == 1 : "stamina by weapon";

        // Attacker spacing: a second wind-up waits until 6 ticks after the first started; 0 is off.
        assert !MobTokens.spaced(100, 105, 6) && MobTokens.spaced(100, 106, 6) && MobTokens.spaced(100, 100, 0) : "attacker spacing";

        // Sweeps spare: your pets, tagged types and calm bystanders; never an enemy, a player, or a mob after you.
        assert Strike.spared(true, false, false, false, false) && Strike.spared(false, true, true, false, true) && Strike.spared(false, false, false, false, false) : "pets, tagged, a horse";
        assert !Strike.spared(false, false, true, false, false) && !Strike.spared(false, false, false, true, false) && !Strike.spared(false, false, false, false, true) : "an enemy, a player, an angry wolf";

        // Our third-person swing yields to the model's own animation: an item in use, or a vanilla swing begun well after ours (mining); never the server's echo of ours.
        assert ThirdPerson.yields(true, false, 1000, 0, 1000) && ThirdPerson.yields(false, true, 1400, 1, 1000) : "using an item, or a later swing of their own";
        assert !ThirdPerson.yields(false, true, 1100, 1, 1000) && !ThirdPerson.yields(false, false, 5000, 0, 1000) : "the echo of ours, or no vanilla swing";

        // A modded mob's attack animation is learned and played at the tell; vanilla's keep our poses.
        assert MobAttackAnims.vanilla("minecraft") && !MobAttackAnims.vanilla("hominid") : "only modded mobs";

        // Over-the-shoulder aim: facing a point straight ahead (+z) is yaw 0, east (+x) is -90, up is a negative pitch.
        float[] f1 = ThirdView.facing(new net.minecraft.world.phys.Vec3(0, 0, 0), new net.minecraft.world.phys.Vec3(0, 0, 5));
        float[] f2 = ThirdView.facing(new net.minecraft.world.phys.Vec3(0, 0, 0), new net.minecraft.world.phys.Vec3(5, 0, 0));
        float[] f3 = ThirdView.facing(new net.minecraft.world.phys.Vec3(0, 0, 0), new net.minecraft.world.phys.Vec3(0, 5, 5));
        assert Math.abs(f1[0]) < 1e-3 && Math.abs(f2[0] + 90) < 1e-3 && Math.abs(f3[1] + 45) < 1e-3 : "facing angles";

        // The refresh's recovery ends no sooner than the clip and lasts at least RECOVER_MIN after the blade's path; CLASSIC is the clip's own end.
        for (double[] lt : new double[][] {{0.45, 0.25}, {0.6, 0.2}, {0.3, 0.28}}) { // clip length, trail end
            Anim clip = Anim.parse(com.google.gson.JsonParser.parseString("{'id':'t','length':" + lt[0] + ",'trail':{'kind':'h','from':[0,0],'to':[90,0],'start':0.1,'end':" + lt[1] + "}}").getAsJsonObject());
            assert ThirdPerson.poseEnd(clip, false) == clip.length() : "classic is the clip's end";
            assert ThirdPerson.poseEnd(clip, true) >= clip.length() && ThirdPerson.poseEnd(clip, true) >= clip.trail().end() + ThirdPerson.HOLD + ThirdPerson.RECOVER_MIN - 1e-9 : "a longer settle";
            assert ThirdPerson.weight(clip, clip.length(), false) < 1e-6 : "classic is out by the clip's end";
        }

        // The third-person assist: the angle off the crosshair, and the cones.
        net.minecraft.world.phys.Vec3 o0 = net.minecraft.world.phys.Vec3.ZERO, ahead = new net.minecraft.world.phys.Vec3(0, 0, 1);
        assert Math.abs(ThirdView.angle(ahead, o0, new net.minecraft.world.phys.Vec3(0, 0, 3))) < 1e-6 && Math.abs(ThirdView.angle(ahead, o0, new net.minecraft.world.phys.Vec3(3, 0, 3)) - 45) < 1e-6 : "angles";
        assert ThirdView.cone(ClientConfig.ThirdPersonAssist.OFF) == 0 && ThirdView.cone(ClientConfig.ThirdPersonAssist.LIGHT) == 20 && ThirdView.cone(ClientConfig.ThirdPersonAssist.STRONG) == 35 : "cones";

        // Developer tools only in development builds.
        assert Dev.devVersion("0.2.0-diag5") && Dev.devVersion("1.0.0-dev") && !Dev.devVersion("1.0.0-beta.1") && !Dev.devVersion("1.0.0") : "dev versions";

        // The setting: AUTO steps aside for Shield Expansion, ON and OFF do not.
        assert Guard.on(CommonConfig.GuardMode.AUTO, false) && !Guard.on(CommonConfig.GuardMode.AUTO, true) : "AUTO follows the other mod";
        assert Guard.on(CommonConfig.GuardMode.ON, true) && !Guard.on(CommonConfig.GuardMode.OFF, false) : "ON and OFF ignore it";
        assert Guard.OTHER.equals("shieldexp") : "the id of Shield Expansion";

        // The refund: the clock a swing is accepted by (Strike.FRESH) read from ceil(delay) ticks back says fully recharged, for every weapon speed.
        for (float delay = 4; delay <= 80; delay += 0.5f) {
            long now = 1000, since = now - (long) Math.ceil(delay);
            assert Strike.clockStrength(now, since, delay) >= 1f : "a refunded weapon with a recharge of " + delay + " ticks is ready";
            assert Strike.clockStrength(now, now, delay) < 1f : "and an unrefunded one that just swung is not";
        }

        // Under many hits: a recoil is sent at most every BLOCK_GAP ticks (a slime that blocks every tick would otherwise restart it every tick); only the first parry of a
        // raise is the whole show; and a clip that is playing is not restarted by a recoil or a parry.
        assert Guard.blockDue(-1_000_000, 5000) && !Guard.blockDue(5000, 5000) && !Guard.blockDue(5000, 5000 + Guard.BLOCK_GAP - 1) && Guard.blockDue(5000, 5000 + Guard.BLOCK_GAP) : "a recoil every 6 ticks at most";
        int sent = 0;
        for (long t = 100; t < 140; t++) { // contact that blocks on every tick for 2 seconds
            if (Guard.blockDue(sent == 0 ? -1_000_000 : lastSent, t)) {
                sent++;
                lastSent = t;
            }
        }
        assert sent == 7 : "40 ticks of contact send " + sent + " recoils, one per 6 ticks";
        Guard.Raise crowd = new Guard.Raise();
        crowd.look(10, true, 10);
        assert crowd.claim() && !crowd.claim() && !crowd.claim() : "the first parry of a raise is the show, every later one is just another attacker";
        crowd.look(11, false, 10);
        crowd.look(30, true, 10);
        assert crowd.claim() && !crowd.claim() : "a new raise starts over";
        assert GuardClient.replaces("guard_raise", true, "guard_lower") && GuardClient.replaces("guard_parry", true, "guard_raise_right") && GuardClient.replaces("", false, "guard_hit") : "raising and lowering always play, anything plays over nothing";
        assert !GuardClient.replaces("guard_hit", true, "guard_hit") && !GuardClient.replaces("guard_parry", true, "guard_hit") && !GuardClient.replaces("guard_raise", true, "guard_hit") : "a recoil never restarts or interrupts a clip";
        assert !GuardClient.replaces("guard_parry", true, "guard_parry") && GuardClient.replaces("guard_hit", true, "guard_parry") && GuardClient.replaces("guard_raise", true, "guard_parry") && GuardClient.replaces("guard_parry", false, "guard_parry") : "a parry takes over from a recoil or a raise, never restarts a parry, and plays again when the last one is over";

        // The payload round-trips, a parry that held the mob, a block that did not and one more attacker of a parry.
        for (GuardPayload p : new GuardPayload[] {new GuardPayload(7, 1234, GuardPayload.PARRY, Guard.FREEZE_TICKS, 5, true), new GuardPayload(7, -1, GuardPayload.BLOCK, 0, 2, false),
                new GuardPayload(7, 99, GuardPayload.ALSO, Guard.FREEZE_TICKS, 3, false)}) {
            ByteBuf buf = Unpooled.buffer();
            GuardPayload.CODEC.encode(buf, p);
            GuardPayload back = GuardPayload.CODEC.decode(buf);
            assert back.equals(p) && buf.readableBytes() == 0 : "round trip of " + p + ", got " + back;
        }
        assert Guard.FREEZE_TICKS == 4 && Guard.SHOVE == 0.5 : "the shipped constants";
        System.out.println("GuardTest ok");
    }
}
