package dev.vectorcombat;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A melee mob that attacks a player stops, shows a tell and only then hits, if the player is still in reach: vanilla's mobs hit the instant they are ready, so a parry
 * or a step back was a guess. The hook is {@code MeleeAttackGoal.checkAndPerformAttack} ({@link dev.vectorcombat.mixin.MeleeAttackGoalMixin}); this class holds the
 * decision, which is pure so it can be checked without a game, the word to the clients and the lean they draw ({@link WindUpClient}): the mob rears back over the wind-up and
 * snaps forward as the hit lands, so the moment of the hit is the moment it stops leaning. The attack rate stays vanilla's: the wind-up is taken off the cooldown that follows.
 */
public final class WindUp {
    /** How long a mob winds up unless its {@code mob_attacks} entry says otherwise: the stand drill's 0.4 s, which the parry was tuned against. */
    static final int DEFAULT_TICKS = 8;

    /** The vanilla sword's attack speed (4 less 2.4): the weapon {@link #DEFAULT_TICKS} was tuned against, so every other weapon's wind-up is scaled from it ({@link #forWeapon}). A test pins it to the item. */
    static final double SWORD_SPEED = 1.6;

    /** A wind-up older than this past its end was stalled (stun or hitstop stop the AI) and is dropped. */
    public static final int STALE = 3;

    private WindUp() {
    }

    /** What one look at the goal's attack check decides: hold it (cancel the vanilla attack for now), when the wind-up ends (0 for none) and what happened, for the log. */
    public record Step(boolean hold, long until, String event) {
    }

    /**
     * One look at the attack check at game time {@code now}, the wind-up in progress ending at {@code until} (0 for none). {@code ready} is vanilla's cooldown being over,
     * {@code inReach} the target being within reach and in sight, {@code hurt} the mob having just been damaged. Not winding up: a ready mob in reach starts one (unless it
     * was just hurt, then it waits); anything else is left to vanilla, which does nothing. Winding up: hold until the end; at the end land the hit if the target is still
     * in reach (vanilla's attack runs) or let it whiff; a hit on the mob or a stall cancels it.
     */
    public static Step next(long now, long until, boolean ready, boolean inReach, boolean hurt, int ticks) {
        if (until == 0) {
            if (!ready || !inReach) return new Step(false, 0, null);
            return hurt ? new Step(true, 0, null) : new Step(true, now + ticks, "start");
        }
        if (hurt) return new Step(true, 0, "interrupted");
        if (now > until + STALE) return new Step(true, 0, "stale");
        if (now < until) return new Step(true, until, null);
        return inReach ? new Step(false, 0, "land") : new Step(true, 0, "whiff");
    }

    /** The cooldown left after a landed hit: vanilla's, less the wind-up, so a mob still attacks as often as it did. */
    public static int afterLanding(int cooldown, int windUp) {
        return Math.max(0, cooldown - windUp);
    }

    /** The tell's pitch: a longer wind-up (a heavier mob) is lower. */
    static float pitch(int ticks) {
        return Math.max(0.6f, Math.min(1.2f, 0.9f * DEFAULT_TICKS / ticks));
    }

    // ---- the lean: what the clients draw (pure, so the shape is checked without a game) ----

    /**
     * Ticks the snap forward takes, how long it is held and how long the mob then takes to straighten; how far it snaps past upright, as a fraction of how far it reared back.
     * A quarter keeps the head stopping short of your camera at melee range (a zombie's face at a block away is 322 px wide after the snap, 229 at rest, 122 at the top of the
     * rear-back, on 854; 0.4 filled half the screen) and still reads as a snap, the head growing 2.6 times in two ticks.
     */
    static final float SNAP = 2, HOLD = 1, RECOVER = 8, LUNGE = 0.25f;

    /** Ticks a cancelled lean takes to straighten. */
    static final float CANCEL_TICKS = 4;

    /** A START the server never followed up (no strike, no cancel) is given this many ticks past its length, then fades like a cancel. */
    static final int LOST = 20;

    /** Vanilla's melee reach beyond a mob's own box ({@code Mob.DEFAULT_ATTACK_REACH}, private): the attack box is the mob's inflated by this. */
    static final double REACH = Math.sqrt(2.04f) - 0.6f;

    private static float easeOut(float u) {
        u = Math.max(0, Math.min(1, u));
        return 1 - (1 - u) * (1 - u);
    }

    /**
     * How far a mob leans, as a fraction of its full rear-back (1 all the way back, 0 upright, negative leaning forward), {@code t} ticks into {@code phase} (a
     * {@link WindUpPayload} phase) that began when it leaned {@code from}; {@code ticks} is the wind-up's length. START eases back over {@code ticks} (quick to show, slow
     * to settle) and stays there; STRIKE snaps forward past upright, holds a moment and straightens; CANCEL straightens.
     */
    public static float lean(int phase, float from, float t, int ticks) {
        switch (phase) {
            case WindUpPayload.START:
                if (t <= ticks + LOST) return from + (1 - from) * easeOut(t / Math.max(1, ticks));
                return lean(WindUpPayload.CANCEL, 1, t - ticks - LOST, ticks);
            case WindUpPayload.STRIKE:
                if (t < SNAP) return from + (-LUNGE - from) * (t / SNAP) * (t / SNAP);
                if (t < SNAP + HOLD) return -LUNGE;
                return -LUNGE * (1 - easeOut((t - SNAP - HOLD) / RECOVER));
            default:
                return from * (1 - easeOut(t / CANCEL_TICKS));
        }
    }

    /** How many ticks a phase lasts before the mob is upright again for good (a START waits for the server, then fades). */
    public static float length(int phase, int ticks) {
        return switch (phase) {
            case WindUpPayload.START -> ticks + LOST + CANCEL_TICKS;
            case WindUpPayload.STRIKE -> SNAP + HOLD + RECOVER;
            default -> CANCEL_TICKS;
        };
    }

    /**
     * Below this height a mob is a scuttler (silverfish and endermite are 0.3): it rears {@link #SMALL_DEGREES} (25; 15 was too easy to miss; 35 read
     * as a flip), and its data gives it a hop as well ({@code "signature": "hop"}).
     */
    static final float SMALL_HEIGHT = 0.5f, SMALL_DEGREES = 25;

    /** A hop's upward speed (blocks per tick): a scuttler's feet leave the floor by about a third of a block as it rears, which is what catches the eye at that size. */
    static final double HOP = 0.3;

    /**
     * How far back, in degrees, a mob of this height leans at the top of its wind-up: its head travels back by its own melee reach, so a taller mob leans less
     * (zombie 25, iron golem 18), within 8 and 35 degrees (a spider rears as far as it can); a scuttler below {@link #SMALL_HEIGHT} rears {@link #SMALL_DEGREES}.
     */
    public static float peakDegrees(float height) {
        if (height < SMALL_HEIGHT) return SMALL_DEGREES;
        double degrees = Math.toDegrees(Math.asin(Math.min(1.0, REACH / Math.max(height, 0.01f))));
        return (float) Math.max(8, Math.min(35, degrees));
    }

    // ---- the shiver: an enderman's cue before a juke, a fast side to side shake that grows over the tell (pure) ----

    /** The shake's largest roll in degrees, and how many shakes a tick (two full shakes over a tick and a half reads as a buzz, not a sway). */
    static final float SHIVER_DEGREES = 4, SHIVER_RATE = (float) (Math.PI * 4 / 3);

    /** The roll, in degrees, {@code t} ticks into a shiver of {@code ticks}: growing over the first half, full to the end, none after (the teleport takes over). */
    public static float shiver(float t, int ticks) {
        if (t < 0 || t > ticks) return 0;
        return SHIVER_DEGREES * Math.min(1, 2 * t / Math.max(1, ticks)) * (float) Math.sin(t * SHIVER_RATE);
    }

    // ---- the slime's squash and wobble: a cube has no limbs, so its tell is its shape (pure) ----

    /** How far the squash goes: this much wider and this much flatter at the end of the tell; the tremble on top; the stretch of the leap's take-off. */
    static final float SQUASH_WIDE = 0.35f, SQUASH_FLAT = 0.45f, TREMBLE = 0.04f, STRETCH_TALL = 0.4f, STRETCH_THIN = 0.2f, WOBBLE = 0.15f;

    /**
     * The scale (width and depth, height) a slime is drawn at {@code t} ticks into {@code phase} of a {@code style} ({@link WindUpPayload#SQUASH} or {@link WindUpPayload#WOBBLE})
     * wind-up of {@code ticks}: the squash grows over the tell (quick to show, slow to settle) with a growing tremble; the leap snaps to a tall, thin stretch in two ticks and eases
     * back over eight; the hold wobbles, flat and tall in turn; anything else is the slime's own shape.
     */
    public static float[] shape(int style, int phase, float t, int ticks) {
        if (style == WindUpPayload.WOBBLE && phase == WindUpPayload.START && t <= ticks) {
            float w = WOBBLE * (float) Math.sin(t * 0.9);
            return new float[] {1 + w, 1 - w};
        }
        if (style != WindUpPayload.SQUASH) return new float[] {1, 1};
        if (phase == WindUpPayload.START) {
            float e = easeOut(t / Math.max(1, ticks)), shake = TREMBLE * e * (float) Math.sin(t * 3.1);
            return new float[] {1 + SQUASH_WIDE * e + shake, 1 - SQUASH_FLAT * e - shake};
        }
        if (phase == WindUpPayload.STRIKE) {
            float k = t < 2 ? t / 2 : 1 - easeOut((t - 2) / 8);
            if (t < 2) { // from the full squash to the stretch
                float wide = 1 + SQUASH_WIDE + (-STRETCH_THIN - SQUASH_WIDE) * k, tall = 1 - SQUASH_FLAT + (STRETCH_TALL + SQUASH_FLAT) * k;
                return new float[] {wide, tall};
            }
            return new float[] {1 - STRETCH_THIN * k, 1 + STRETCH_TALL * k};
        }
        return new float[] {1, 1};
    }

    // ---- a feral mob's bite: the head rears up and back over the tell and snaps forward and down with the hit; any model with a head (pure) ----

    /** How far the head goes, in radians: up and back at the top of the tell, down past its own pose at the snap. */
    static final float BITE_UP = 0.9f, BITE_SNAP = 1.1f;

    /** How far the head thrusts forward at the snap, in model pixels (a lunge, so the bite does not read as a nod), and how much of its plain lean the body keeps. */
    static final float BITE_THRUST = 2.5f, BITE_LEAN = 0.5f;

    /** The head's forward thrust at lean {@code f}: none over the rear-back, full at the snap, gone as it straightens. */
    public static float biteThrust(float f) {
        return f >= 0 ? 0 : BITE_THRUST * Math.min(1, -f / LUNGE);
    }

    public static float biteHead(float base, float f) {
        return toward(base, f, base - BITE_UP, base + BITE_SNAP);
    }

    /**
     * Whether a goal mob should path to its target again now: not winding up, out of reach, and its path done. Vanilla re-paths only when the target has moved a block from
     * where the path was made (or by a 5% chance), so a mob shoved back by a parry or a block from a player who stands still just stared.
     */
    public static boolean repath(boolean windingUp, boolean inReach, boolean pathDone) {
        return !windingUp && !inReach && pathDone;
    }

    // ---- dazed: a charger that hit terrain, its body tilted down onto its front, head low, front legs forward (pure) ----

    /** How far forward a dazed charger is pitched, in degrees, and the ticks it takes to go over and to come back. */
    static final float DAZE_DEGREES = 15, DAZE_IN = 3, DAZE_OUT = 4;

    public static float daze(float t, int ticks) {
        if (t <= 0 || t >= ticks) return 0;
        if (t < DAZE_IN) return DAZE_DEGREES * easeOut(t / DAZE_IN);
        if (t > ticks - DAZE_OUT) return DAZE_DEGREES * easeOut((ticks - t) / DAZE_OUT);
        return DAZE_DEGREES;
    }

    // ---- a spider's threat display (pure): up fast, held to the leap, eased down in the air; the hoglin's rear comes down before it ends ----

    /** Ticks the display takes to rise, and the leap's ease down. */
    static final float DISPLAY_RISE = 4, DISPLAY_FALL = 6;

    /** How much of the display is up (0 to 1) {@code t} ticks into {@code phase}: a START rises and holds, a STRIKE (the leap) eases out of it. */
    public static float display(int phase, float t, int ticks) {
        if (phase == WindUpPayload.START) return t <= 0 ? 0 : easeOut(Math.min(1, t / DISPLAY_RISE));
        if (phase == WindUpPayload.STRIKE) return t >= DISPLAY_FALL ? 0 : 1 - easeOut(t / DISPLAY_FALL);
        return 0;
    }

    // ---- a spider flipped on its back, or hanging before its drop (pure) ----

    /** The {@code zRot} offset of leg {@code i} (0 to 7) {@code t} ticks in: a flailing spider's wide, out-of-step kicks; a hanging one's small fast twitches. */
    public static float legFlail(int style, int i, float t) {
        float phase = i * 0.8f;
        return switch (style) {
            case WindUpPayload.FLIPPED -> 0.6f * (float) Math.sin(t * 1.2 + phase);
            case WindUpPayload.HANG -> 0.12f * (float) Math.sin(t * 3.1 + phase);
            default -> 0;
        };
    }

    // ---- the hoglin's charge (pure) ----

    /** The rear-up's peak pitch, in degrees, and the ticks it takes to rise and to come down. */
    static final float REAR_DEGREES = 50, REAR_UP = 6, REAR_DOWN = 5;

    /** How far up a rearing hoglin is pitched, in degrees, {@code t} ticks into a rear of {@code ticks}: up over {@link #REAR_UP} (ease-out), held, down over the last {@link #REAR_DOWN}. */
    public static float rear(float t, int ticks) {
        if (t <= 0 || t >= ticks) return 0;
        if (t < REAR_UP) return REAR_DEGREES * easeOut(t / REAR_UP);
        if (t > ticks - REAR_DOWN) return REAR_DEGREES * easeOut((ticks - t) / REAR_DOWN);
        return REAR_DEGREES;
    }

    /** What the head's {@code xRot} gains (down) and the scraping or pawing front leg's, {@code t} ticks into a charge style. */
    public static float chargeHead(int style) {
        return switch (style) {
            case WindUpPayload.LINE -> 0.45f;
            case WindUpPayload.CHARGE -> 0.6f;
            case WindUpPayload.TIRED -> 0.75f;
            case WindUpPayload.DAZED -> 0.8f;
            case WindUpPayload.DISPLAY -> -0.5f;
            case WindUpPayload.REAR -> -0.4f;
            default -> 0;
        };
    }

    /** A front leg's {@code xRot} offset: a scrape back and forth under the line, pawing the air while rearing ({@code side} +1 or -1 puts the two legs out of step). */
    public static float chargeLeg(int style, float t, int side) {
        return switch (style) {
            case WindUpPayload.LINE -> side > 0 ? 0.5f * (float) Math.sin(t * 0.9) : 0;
            case WindUpPayload.REAR -> -0.9f + 0.6f * (float) Math.sin(t * 0.9 + (side > 0 ? 0 : Math.PI));
            case WindUpPayload.DAZED -> -0.6f; // both front legs out forward, propping it up
            case WindUpPayload.DISPLAY -> -1.1f + 0.15f * (float) Math.sin(t * 1.4 + (side > 0 ? 0 : Math.PI)); // both raised high, waving a little
            default -> 0;
        };
    }

    // ---- the overhead: a signature tell, both arms raised over the head through the wind-up and slammed down at the strike (pure) ----

    /** The arms' {@code xRot} (radians) at the top of the overhead, straight up and a little back (166 degrees), and at the bottom of the slam, forward and down (29 degrees below level). */
    static final float OVERHEAD = -2.9f, SLAM = -0.5f;

    /** The body leans this much of its plain lean under an overhead: the raised arms carry most of the tell. */
    static final float OVERHEAD_LEAN = 0.6f;

    /**
     * An arm's {@code xRot} at lean {@code f} ({@link #lean}) of an overhead, from {@code base}, the value the model's own animation gave it: up toward {@link #OVERHEAD} as the
     * mob rears back, down through its own pose to {@link #SLAM} as the snap goes past upright, and back to {@code base} as it straightens. The lean already has the shape (ease-out
     * up, ease-in snap, hold, recovery), so the arms follow it rather than a curve of their own.
     */
    public static float overheadArm(float base, float f) {
        return toward(base, f, OVERHEAD, SLAM);
    }

    /** A part's angle at lean {@code f} from {@code base}: toward {@code up} as the mob rears back, toward {@code down} as the snap goes past upright, back to {@code base} after. */
    static float toward(float base, float f, float up, float down) {
        if (f >= 0) return base + (up - base) * Math.min(1, f);
        return base + (down - base) * Math.min(1, -f / LUNGE);
    }

    // ---- the enderman's limbs: each attack is an arm flail or a front kick, picked at random (pure) ----

    /** A flail: both arms straight up to 149 degrees over the rear-back, then straight down to 23 below level, on one axis (no spread or cross, which read as the arms crossing). */
    static final float FLAIL_UP = -2.6f, FLAIL_SWEEP = -0.4f;

    /** A kick: the kicking (right) leg cocked {@link #KICK_CHAMBER} radians back over the rear-back, then snapped forward and up to 97 degrees (a front kick at the player's chest). */
    static final float KICK_CHAMBER = 0.5f, KICK_OUT = -1.7f;

    public static float flailArmX(float base, float f) {
        return toward(base, f, FLAIL_UP, FLAIL_SWEEP);
    }

    /**
     * How much of an arm's own sideways turn ({@code yRot}, {@code zRot}) an arm tell takes away at lean {@code f}: all of it at the top of the rear-back and at the snap, so the arm moves
     * on one axis; the vanilla swing that plays with the hit turns the arms and the body, and on the enderman's long arms that read as the arms crossing.
     */
    public static float square(float f) {
        return f >= 0 ? Math.min(1, f) : Math.min(1, -f / LUNGE);
    }

    // ---- arm tells for armed and unarmed humanoids (pure) ----

    /** A one-handed weapon: the weapon arm raised over the shoulder to 160 degrees, then chopped down to 29 below level. */
    static final float WEAPON_UP = -2.8f, WEAPON_CHOP = -0.5f;

    /** An unarmed mob with arms: both lifted this much above their own pose, then clawed down this much below it. */
    static final float CLAW_LIFT = 0.7f, CLAW_DROP = 0.5f;

    public static float weaponArmX(float base, float f) {
        return toward(base, f, WEAPON_UP, WEAPON_CHOP);
    }

    public static float clawArmX(float base, float f) {
        return toward(base, f, base - CLAW_LIFT, base + CLAW_DROP);
    }

    public static float kickLegX(float base, float f) {
        return toward(base, f, base + KICK_CHAMBER, KICK_OUT);
    }


    // ---- the legs: what the model's legs add to the lean (pure) ----

    /** The legs lean this much of what the torso does: a bend at the hip instead of one plank (the feet end 16 cm back at the top of a zombie's rear-back). */
    static final float HINGE = 0.5f;

    /** How far the lead leg swings forward at the snap, in degrees: its foot advances a third of the mob's reach, on the humanoid's 0.75 block leg (21.6). */
    static final float STRIDE = (float) Math.toDegrees(Math.asin(REACH / 3 / 0.75));

    /** What both legs add to their {@code xRot} (radians, positive swings the feet back) at lean {@code f}, which peaks at {@code peakDegrees}: {@link #HINGE} of the lean. */
    public static float legHinge(float f, float peakDegrees) {
        return (float) Math.toRadians(HINGE * f * peakDegrees);
    }

    /**
     * How much of the stride the legs have {@code t} ticks into {@code phase} (a {@link WindUpPayload} phase): none outside a strike; in one it rises with the snap, stays
     * through the hold and falls with the recovery, so the step comes with the lunge and goes with it.
     */
    public static float stride(int phase, float t) {
        if (phase != WindUpPayload.STRIKE) return 0;
        if (t < SNAP) return (t / SNAP) * (t / SNAP);
        if (t < SNAP + HOLD) return 1;
        return 1 - easeOut((t - SNAP - HOLD) / RECOVER);
    }

    /** What the lead (left) leg adds to its {@code xRot} at stride weight {@code w}: forward by {@link #STRIDE}. */
    public static float legLead(float w) {
        return (float) -Math.toRadians(STRIDE) * w;
    }

    /** What the other leg adds at stride weight {@code w}: back by half as much. */
    public static float legTrail(float w) {
        return (float) Math.toRadians(STRIDE) * 0.5f * w;
    }

    // ---- the mob side ----

    /** Mobs that never wind up ({@code #vectorcombat:no_wind_up}): the creeper, whose melee goal only walks it up to its fuse, and the swoopers, whose hit is their flight. A pack can add to it. */
    public static final TagKey<EntityType<?>> NO_WIND_UP = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "no_wind_up"));

    /** Mobs whose blocked hits disable the shield as an axe does ({@code #vectorcombat:disables_shields}: the enderman); read by {@code LivingEntityMixin}. */
    public static final TagKey<EntityType<?>> DISABLES_SHIELDS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "disables_shields"));

    /** Mobs that never wind up at other mobs, as attacker or target ({@code #vectorcombat:no_mob_wind_up}); empty, for packs. */
    public static final TagKey<EntityType<?>> NO_MOB_WIND_UP = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "no_mob_wind_up"));

    /**
     * Whether this mob winds up for this target, with the setting on, unless it is tagged {@link #NO_WIND_UP}: an enemy (or an iron golem) going for a player; or, with
     * {@code [mobs] mobVsMob}, any mob going for any other living thing (a golem at a zombie, a zombie at a villager, a wolf, a raid), unless either is tagged {@link #NO_MOB_WIND_UP}.
     */
    public static boolean applicable(Mob mob, LivingEntity target) {
        if (target == null || !target.isAlive() || !CommonConfig.MOB_WIND_UPS.get() || mob.getType().is(NO_WIND_UP) || Domestic.exempt(mob)) return false; // a pet or a bred animal: vanilla's
        if (target instanceof Player p) return !p.isSpectator() && (mob instanceof Enemy || mob instanceof IronGolem);
        double seen = CommonConfig.MOB_VS_MOB_RANGE.get(); // a farm's or a far raid's fights stay vanilla's instant hits: no whoosh, no tell to send, no one to see them
        return CommonConfig.MOB_VS_MOB.get() && !mob.getType().is(NO_MOB_WIND_UP) && !target.getType().is(NO_MOB_WIND_UP)
                && (seen <= 0 || mob.level().hasNearbyAlivePlayer(mob.getX(), mob.getY(), mob.getZ(), seen));
    }

    /** Server side: the game time before which a mob may not start another attack (the recovery after a pounce or a bite); no entry is open. */
    private static final java.util.Map<Mob, Long> GATES = new java.util.WeakHashMap<>();

    public static boolean open(Mob mob) {
        return GATES.getOrDefault(mob, 0L) <= mob.level().getGameTime();
    }

    public static void close(Mob mob, int ticks) {
        GATES.put(mob, mob.level().getGameTime() + ticks);
    }

    /** This mob's wind-up in ticks: the weapon in its hand, else its {@code wind_up} seconds from the data, else {@link #DEFAULT_TICKS}. */
    public static int ticks(Mob mob) {
        return ticks(mob, interval(mob));
    }

    /** Vanilla's attack interval for the goal mobs, in ticks. */
    static final int VANILLA_INTERVAL = 20;

    /** This goal mob's attack interval in ticks: its {@code interval} seconds from the data (the enderman's 1.2), else vanilla's 20. */
    public static int interval(Mob mob) {
        return interval(Profiles.interval(mob));
    }

    static int interval(Double seconds) {
        return seconds == null ? VANILLA_INTERVAL : Math.max(1, (int) Math.round(seconds * 20));
    }

    /** What a mob of attack interval {@code interval} adds to vanilla's cooldown after each attack (none for a faster one: the goal's cooldown is vanilla's own 20). */
    public static int slower(int interval) {
        return Math.max(0, interval - VANILLA_INTERVAL);
    }

    /**
     * The same for a mob whose behaviour gives its attack interval ({@code interval} ticks): the weapon in its main hand (read at the start of each wind-up, so a swapped weapon
     * counts), else the data's seconds, else {@link #derived}.
     */
    public static int ticks(Mob mob, int interval) {
        return pick(WeaponData.weaponSpeed(mob.getMainHandItem()), Profiles.windUp(mob), interval);
    }

    /** The data's {@code seconds} as ticks if there are any, else the wind-up derived from the attack interval. */
    static int pick(Double seconds, int interval) {
        return seconds == null ? derived(interval) : Math.max(1, (int) Math.round(seconds * 20));
    }

    /** A mob holding a weapon of attack speed {@code weaponSpeed} (null for none) winds up for the weapon's {@link #forWeapon}, else {@link #pick(Double, int)}. */
    static int pick(Double weaponSpeed, Double seconds, int interval) {
        return weaponSpeed != null ? forWeapon(interval, weaponSpeed) : pick(seconds, interval);
    }

    /**
     * The wind-up that is the same share of the weapon's own recharge as the zombie's with a sword (8 ticks of the 12.5 a 1.6 weapon takes to recharge, 64%): a slower weapon winds up
     * longer, a faster one shorter, within 6 ticks (a tell the eye still catches) and 80% of the mob's attack interval (16 of 20, the hoglin's), so the mob still attacks as often as
     * it did. No usable speed reads as the slowest.
     */
    static int forWeapon(int interval, double speed) {
        int cap = Math.max(6, (int) Math.round(0.8 * interval));
        if (!(speed > 0)) return cap;
        return Math.max(6, Math.min(cap, (int) Math.round(derived(interval) * SWORD_SPEED / speed)));
    }

    /**
     * A wind-up that fills 40% of a mob's own attack interval (the zombie's 8 of vanilla's 20), at least 6 ticks so a baby's short interval still shows a tell: the mob
     * attacks as often as it did, and the rhythm of the tell follows the rhythm of the mob.
     */
    static int derived(int interval) {
        return Math.max(6, (int) Math.round(0.4 * interval));
    }

    /**
     * One wind-up event ({@code start}, {@code land}, {@code whiff}, {@code interrupted}, {@code stale}, {@code goal_stopped}, and a pounce's {@code leap}): the log row, the
     * whoosh at the start, and the word to every client that sees the mob, which draws the lean ({@code land}, {@code whiff} and {@code leap} are the strike, the rest that it
     * ended without one). {@code kind} says which attack: the {@code bite}, a spider's {@code pounce}, an enderman's {@code juke_tell}, {@code juke}, {@code feint}
     * (whose {@code feint} event is a strike with no hit) and {@code followup} ({@link Juke}); a bite is drawn in the mob's signature style when its data names one
     * ({@link Profiles#signature}), and a {@code hop} signature hops.
     */
    public static void signal(Mob mob, LivingEntity target, String event, int ticks) {
        signal(mob, target, "bite", event, ticks);
    }

    /** The attacks of a juke (the follow-up of a feint included): a {@code limbs} signature draws them too. */
    private static final java.util.Set<String> ATTACKS = java.util.Set.of("bite", "juke", "feint", "followup");

    /** What a mob holds, for its tell: nothing that is a melee weapon, a one-handed weapon, or a two-handed one. */
    static final int UNARMED = 0, ONE_HANDED = 1, TWO_HANDED = 2;

    /**
     * How the clients draw a wind-up of {@code kind} for a mob whose data names {@code signature} (null for none), holding {@code held}: an enderman's shiver shivers; a {@code limbs}
     * mob's attacks flail ({@code kick} false) or kick; {@code lean} keeps the plain lean (no arms, the opt-out for a model whose arms look wrong); the vindicator's {@code overhead} and
     * any two-handed weapon raise both arms; a one-handed weapon raises the weapon arm; {@code feral} bites with the head (the hoglin, the zoglin; any feral mob a pack names);
     * anything else leans, and the client adds a claw where the model has arms.
     */
    static int style(String kind, String signature, boolean kick, int held) {
        if ("juke_tell".equals(kind)) return WindUpPayload.SHIVER;
        if ("engulf".equals(kind) || "slime_hop".equals(kind)) return WindUpPayload.SQUASH;
        switch (kind) {
            case "charge_line": return WindUpPayload.LINE;
            case "charge_dash": return WindUpPayload.CHARGE;
            case "charge_rear": return WindUpPayload.REAR;
            case "charge_tired": return WindUpPayload.TIRED;
            case "charge_dazed": return WindUpPayload.DAZED;
            case "throw": return WindUpPayload.WEAPON; // a thrower's arm back over its head, then the throw
            case "heave", "drag", "slam": return WindUpPayload.OVERHEAD; // both arms: a golem heaving a mob overhead, a drowned reaching up to grab
            case "pounce_display", "pounce_leap": return WindUpPayload.DISPLAY; // the spider's threat display: up and held, then the leap out of it
            case "spider_flipped": return WindUpPayload.FLIPPED;
            case "spider_hang": return WindUpPayload.HANG;
            default: break;
        }
        if ("engulf_hold".equals(kind)) return WindUpPayload.WOBBLE;
        if (!ATTACKS.contains(kind)) return WindUpPayload.LEAN;
        if ("limbs".equals(signature)) return kick ? WindUpPayload.KICK : WindUpPayload.FLAIL;
        if ("lean".equals(signature)) return WindUpPayload.PLAIN;
        if ("feral".equals(signature)) return WindUpPayload.BITE;
        if ("overhead".equals(signature) || held == TWO_HANDED) return WindUpPayload.OVERHEAD;
        return held == ONE_HANDED ? WindUpPayload.WEAPON : WindUpPayload.LEAN;
    }

    static int style(String kind, String signature, boolean kick) {
        return style(kind, signature, kick, UNARMED);
    }

    static int style(String kind, String signature) {
        return style(kind, signature, false);
    }

    /** What {@code mob} holds in its main hand, for {@link #style}: a melee weapon by our weapon data ({@link WeaponData#weaponSpeed}), two-handed by {@link WeaponData#twoHanded}. */
    static int held(Mob mob) {
        var stack = mob.getMainHandItem();
        if (stack.isEmpty() || WeaponData.weaponSpeed(stack) == null) return UNARMED;
        return WeaponData.twoHanded(stack.getItem()) ? TWO_HANDED : ONE_HANDED;
    }

    /** Server side: whether a {@code limbs} mob's wind-up in progress is a kick, picked at its start so its strike is drawn the same. */
    private static final java.util.Map<Mob, Boolean> KICKS = new java.util.WeakHashMap<>();

    // ---- poise: a mob is interrupted once in a row; the wind-up after an interrupted one takes hits without stopping ----

    /** Server side: the mobs whose next (or current) wind-up is poised. */
    private static final java.util.Set<Mob> POISED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Whether a hit stops this mob's wind-up (or holds back its next): not while it is poised. What the wind-up state machine is given as {@code hurt}. */
    public static boolean hurt(Mob mob) {
        return mob.hurtTime > 0 && !POISED.contains(mob);
    }

    /** Whether {@code mob} is poised now. */
    public static boolean poised(Mob mob) {
        return POISED.contains(mob);
    }

    /** A stun breaks poise: the hit that stuns is the heavy answer to it ({@link Stuns#apply}). */
    public static void breakPoise(LivingEntity e) {
        if (e instanceof Mob mob) POISED.remove(mob);
    }

    /** What poise becomes after {@code event} of a wind-up when it was {@code poised}: an interruption poises the next one, an attack's end (landed, whiffed, gone stale or dropped) spends it. */
    static boolean poiseAfter(String event, boolean poised) {
        return switch (event) {
            case "interrupted" -> true;
            case "land", "whiff", "stale", "goal_stopped", "no_room" -> false;
            default -> poised;
        };
    }

    public static void signal(Mob mob, LivingEntity target, String kind, String event, int ticks) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        boolean wasPoised = POISED.contains(mob);
        if (poiseAfter(event, wasPoised)) POISED.add(mob);
        else POISED.remove(mob);
        if (event.equals("start") && wasPoised) { // the cue: a faint white glint round the upper body, so it reads that this one will not stop
            level.sendParticles(ParticleTypes.END_ROD, mob.getX(), mob.getY(0.75), mob.getZ(), 6, mob.getBbWidth() * 0.5, mob.getBbHeight() * 0.2, mob.getBbWidth() * 0.5, 0.01);
        }
        Double speed = WeaponData.weaponSpeed(mob.getMainHandItem());
        double reach = WeaponData.extraReach(mob.getMainHandItem());
        Diag.event("windup", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), "kind", kind, "result", event, "ticks", ticks, "tick", level.getGameTime(),
                "dist", target == null ? null : Math.round(mob.distanceTo(target) * 100) / 100.0, "hp", target == null ? null : target.getHealth(),
                "absorb", target == null ? null : target.getAbsorptionAmount(), "held", mob.getMainHandItem().isEmpty() ? null : BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem()).toString(),
                "speed", speed == null ? null : Math.round(speed * 100) / 100.0, "reach", reach > 0 ? Math.round(reach * 100) / 100.0 : null, "poised", wasPoised ? true : null);
        if (event.equals("start") && target instanceof Mob defender) MobGuard.readTell(defender, mob, ticks); // a mob it goes for reads the tell, as it reads a player's swing
        if (event.equals("start") && target != null) MobTokens.started(target, level.getGameTime()); // the attacker spacing counts from a wind-up that really started
        if (event.equals("start")) MobAttackAnims.tell(mob); // a modded mob's own attack animation, learned from its hits, plays as the tell
        int phase = switch (event) {
            case "start" -> WindUpPayload.START;
            case "land", "whiff", "leap", "feint" -> WindUpPayload.STRIKE;
            default -> WindUpPayload.CANCEL;
        };
        String signature = Profiles.signature(mob);
        if (phase == WindUpPayload.START && !"juke_tell".equals(kind) && !kind.startsWith("charge_")) { // the charge has its own snorts and roars // the shiver has its own warp sound (Juke.shiver)
            level.playSound(null, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1f, pitch(ticks));
            if ("bite".equals(kind) && "hop".equals(signature) && mob.onGround()) { // a scuttler's tell: its own hiss and a hop as it rears
                mob.playAmbientSound();
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0).add(0, HOP, 0));
                mob.hurtMarked = true;
            }
        }
        if (phase == WindUpPayload.START && "limbs".equals(signature)) KICKS.put(mob, mob.getRandom().nextBoolean());
        PacketDistributor.sendToPlayersTrackingEntity(mob, new WindUpPayload(mob.getId(), phase, ticks, style(kind, signature, KICKS.getOrDefault(mob, false), held(mob))));
    }
}
