package dev.vectorcombat;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Gameplay settings both sides read (in singleplayer it is one file: {@code config/vectorcombat-common.toml}). Every entry and section has an explicit translation
 * key ({@code vectorcombat.configuration.<section>.<name>}) with a short label and a one-line tooltip in {@code en_us.json}; the comments here are the file's own documentation.
 */
public final class CommonConfig {
    /** Whether the shield parry rules apply: AUTO is on unless another mod that does its own shield parry is installed. */
    public enum GuardMode {
        AUTO, ON, OFF
    }

    /** Whether mobs are handed weapons as they spawn: AUTO is on unless a mod that arms mobs itself (Jaam's Weaponry) is installed. */
    public enum ArmingMode {
        AUTO, ON, OFF
    }

    private static final String KEY = "vectorcombat.configuration.";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue HITSTOP_ENABLED;
    public static final ModConfigSpec.DoubleValue HITSTOP_SCALE;
    public static final ModConfigSpec.IntValue HITSTOP_MAX_TICKS;
    public static final ModConfigSpec.DoubleValue HITFX_VOLUME;
    public static final ModConfigSpec.BooleanValue HITFX_PARTICLES;
    public static final ModConfigSpec.DoubleValue HITFX_PARTICLE_SCALE;
    public static final ModConfigSpec.DoubleValue PENALTY_REFERENCE_SPEED;
    public static final ModConfigSpec.DoubleValue PENALTY_FATIGUE;
    public static final ModConfigSpec.BooleanValue PENALTY_MISSES;
    public static final ModConfigSpec.IntValue PENALTY_MAX_STACKS;
    public static final ModConfigSpec.BooleanValue PENALTY_CLEAR_ON_KILL;
    public static final ModConfigSpec.BooleanValue LORIS_WEAPON_SCALE;
    public static final ModConfigSpec.BooleanValue GUARD_BREAK_STAGGERS_PLAYERS;
    public static final ModConfigSpec.EnumValue<GuardMode> GUARD_MODE;
    public static final ModConfigSpec.IntValue GUARD_PARRY_TICKS;
    public static final ModConfigSpec.IntValue GUARD_REARM_TICKS;
    public static final ModConfigSpec.DoubleValue GUARD_STAGGER_BASE;
    public static final ModConfigSpec.DoubleValue GUARD_STAGGER_PER_DAMAGE;
    public static final ModConfigSpec.BooleanValue GUARD_REFUND;
    public static final ModConfigSpec.BooleanValue GUARD_BREAK;
    public static final ModConfigSpec.BooleanValue DEFLECT;
    public static final ModConfigSpec.IntValue ATTACK_TOKENS;
    public static final ModConfigSpec.BooleanValue CIRCLING;
    public static final ModConfigSpec.DoubleValue CIRCLE_RADIUS;
    public static final ModConfigSpec.DoubleValue CIRCLE_SPEED;
    public static final ModConfigSpec.IntValue ATTACKER_SPACING;
    public static final ModConfigSpec.IntValue PACK_SURGE_SIZE;
    public static final ModConfigSpec.BooleanValue SPARE_MOUNT;
    public static final ModConfigSpec.BooleanValue SPARE_BYSTANDERS;
    public static final ModConfigSpec.BooleanValue WAITERS_FIGHT;
    public static final ModConfigSpec.BooleanValue MOB_VS_MOB;
    public static final ModConfigSpec.DoubleValue MOB_VS_MOB_RANGE;
    public static final ModConfigSpec.BooleanValue MOD_ATTACK_ANIMATIONS;
    public static final ModConfigSpec.BooleanValue MOB_GUARD;
    public static final ModConfigSpec.DoubleValue MOB_GUARD_WEAPON_BLOCK;
    public static final ModConfigSpec.IntValue MOB_GUARD_DISABLE_TICKS;
    public static final ModConfigSpec.BooleanValue MOB_GUARD_REACT;
    public static final ModConfigSpec.BooleanValue MOB_GUARD_RIPOSTE;
    public static final ModConfigSpec.IntValue MOB_GUARD_RIPOSTE_TICKS;
    public static final ModConfigSpec.BooleanValue MOB_PASSIVES;
    public static final ModConfigSpec.EnumValue<ArmingMode> MOB_ARMING;
    public static final ModConfigSpec.BooleanValue MOB_SHIELDS;
    public static final ModConfigSpec.DoubleValue MOB_GUARD_PARRIED_LOCKOUT;
    public static final ModConfigSpec.BooleanValue MOB_WIND_UPS;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.translation(KEY + "hitstop").push("hitstop");
        HITSTOP_ENABLED = b.comment("A landed hit pauses the attacker's swing for a few ticks, heavier weapons longer.")
                .translation(KEY + "hitstop.enabled").define("enabled", true);
        HITSTOP_SCALE = b.comment("Multiplies the pause: 0 is none, 1 the default, 2 doubles it. One tick is 50 ms.")
                .translation(KEY + "hitstop.scale").defineInRange("scale", 1.0, 0.0, 3.0);
        HITSTOP_MAX_TICKS = b.comment("The longest pause in ticks (20 ticks = 1 second).")
                .translation(KEY + "hitstop.maxTicks").defineInRange("maxTicks", 3, 1, 10);
        b.pop();
        b.translation(KEY + "hitfx").push("hitfx");
        HITFX_VOLUME = b.comment("Loudness of the contact sound of a landed hit: 0 is silent, 1 the default. Minecraft caps every sound at full volume, so above about 1.1 the main layers do not get louder, only the quiet ones.")
                .translation(KEY + "hitfx.volume").defineInRange("volume", 1.0, 0.0, 2.0);
        HITFX_PARTICLES = b.comment("Sparks flying from a landed hit.")
                .translation(KEY + "hitfx.particles").define("particles", true);
        HITFX_PARTICLE_SCALE = b.comment("How many sparks: 0 is none, 1 the default, 2 doubles them.")
                .translation(KEY + "hitfx.particleScale").defineInRange("particleScale", 1.0, 0.0, 3.0);
        b.pop();
        b.translation(KEY + "penalty").push("penalty");
        PENALTY_REFERENCE_SPEED = b.comment("The attack-speed penalty windows (how long a swing slows the next one, and how soon the same attack must repeat to stack) are in seconds for a weapon of this attack speed, "
                        + "and stretch for slower weapons (shrink a little for faster ones) in proportion to their recharge, so spamming one attack costs the same number of cooldowns with any weapon. 1.6 is the sword's. 0 turns it off: plain seconds for every weapon.")
                .translation(KEY + "penalty.referenceSpeed").defineInRange("referenceSpeed", 1.6, 0.0, 4.0);
        PENALTY_FATIGUE = b.comment("What every hit inside the penalty window adds to the attack-speed penalty, whatever the attack, so a steady stream of hits slows you even when you switch attacks; "
                        + "repeating the same attack adds its own penalty on top. 0.08 is 8%; 0 turns it off. A pause as long as the window clears it.")
                .translation(KEY + "penalty.fatigue").defineInRange("fatigue", 0.08, 0.0, 0.5);
        PENALTY_MISSES = b.comment("A swing that hits nothing costs the same penalty as one that lands, so swinging at the air is no free reset.")
                .translation(KEY + "penalty.misses").define("misses", true);
        PENALTY_MAX_STACKS = b.comment("At most this many hits stack the penalty (the same attack repeated, and hits in a row), so a long fight against a crowd does not grind your attack speed "
                + "down to a crawl (0.32 attacks a second after 14 stacks). 0 is no cap.").translation(KEY + "penalty.maxStacks").defineInRange("maxStacks", 4, 0, 20);
        PENALTY_CLEAR_ON_KILL = b.comment("A kill clears the attack-speed penalty at once.").translation(KEY + "penalty.clearOnKill").define("clearOnKill", true);
        LORIS_WEAPON_SCALE = b.comment("With Loris Energy: a swing's stamina follows the weapon's speed, a sword's being Loris's own attackExhaustion; a dagger costs less, a hammer more (half to one and a half times).")
                .translation(KEY + "penalty.lorisWeaponScale").define("lorisWeaponScale", true);
        b.pop();
        b.translation(KEY + "guard").push("guard");
        GUARD_MODE = b.comment("Shield timing. Raising a shield just before a melee hit or an arrow lands parries it: no damage, no durability, no axe disable, the attacker is staggered and your weapon's cooldown is "
                + "refunded. Holding the shield up is vanilla's block. AUTO is on unless Shield Expansion (shieldexp), which has its own parry, is installed and not in its item only mode; ON always; OFF never.")
                .translation(KEY + "guard.mode").defineEnum("mode", GuardMode.AUTO);
        GUARD_PARRY_TICKS = b.comment("How long after the shield goes up a hit counts as a parry, in ticks (20 = 1 second). Vanilla's shield does not block at all for its first 5 ticks, so any value up to 5 only adds protection.")
                .translation(KEY + "guard.parryTicks").defineInRange("parryTicks", 5, 1, 10);
        GUARD_REARM_TICKS = b.comment("The shield must have been down this many ticks before a raise opens a parry window, so tapping it over and over parries nothing. 0 is no limit.")
                .translation(KEY + "guard.rearmTicks").defineInRange("rearmTicks", 10, 0, 60);
        GUARD_STAGGER_BASE = b.comment("Seconds a parried attacker is stunned for, before the hit's damage adds to it (at least 0.6 s, at most 1.6 s).")
                .translation(KEY + "guard.staggerBase").defineInRange("staggerBase", 0.4, 0.0, 3.0);
        GUARD_STAGGER_PER_DAMAGE = b.comment("Extra seconds of stagger per point of damage the parried hit would have done.")
                .translation(KEY + "guard.staggerPerDamage").defineInRange("staggerPerDamage", 0.08, 0.0, 0.5);
        GUARD_REFUND = b.comment("A parry refunds your weapon's attack cooldown, so the counter is ready the moment you lower the shield.")
                .translation(KEY + "guard.refund").define("refund", true);
        GUARD_BREAK = b.comment("A heavy hit breaks the guard it lands on (yours or a mob's): it is still blocked, then the guard goes down for a few seconds and its holder staggers. "
                + "Heavy: a fully charged Falling Slash or a heavy weapon (maces, greatswords, hammers...), or a heavy mob (ravager, iron golem, brute...), by the guard_break data. A parry still beats it.")
                .translation(KEY + "guard.guardBreak").define("guardBreak", true);
        GUARD_BREAK_STAGGERS_PLAYERS = b.comment("A mob that breaks your guard also staggers you. Off (the default): your guard only goes down; your breaks on mobs still stagger them.")
                .translation(KEY + "guard.guardBreakStaggersPlayers").define("guardBreakStaggersPlayers", false);
        DEFLECT = b.comment("A swing knocks projectiles out of the air: one in the swing's arc as it lands is sent back the way it came (now yours), one a tick off is knocked away. "
                + "Entity types in #vectorcombat:undeflectable never are.")
                .translation(KEY + "guard.deflect").define("deflect", true);
        b.pop();
        b.translation(KEY + "mobGuard").push("mobGuard");
        MOB_GUARD = b.comment("Mobs holding a shield (off hand) or a weapon that blocks (one used in the block pose, such as Jaam's broadswords) guard against your hits by the same rules as yours: "
                + "a hit just after the guard goes up is parried (cancelled, you are shoved back), later it is blocked. Off: mobs never parry or block with a weapon (a mob's shield is vanilla's).")
                .translation(KEY + "mobGuard.enabled").define("enabled", true);
        MOB_GUARD_WEAPON_BLOCK = b.comment("How much of a hit a mob's blocking weapon stops once its guard is up past the parry window (1 = all of it, as a shield). The weapon wears as a shield does.")
                .translation(KEY + "mobGuard.weaponBlock").defineInRange("weaponBlock", 0.7, 0.0, 1.0);
        MOB_GUARD_DISABLE_TICKS = b.comment("An axe hit on a mob's shield or blocking weapon knocks its guard down for this many ticks (vanilla does this to players' shields for 100). 0 is never.")
                .translation(KEY + "mobGuard.disableTicks").defineInRange("disableTicks", 100, 0, 400);
        MOB_GUARD_REACT = b.comment("Mobs that are after you, see you and face you read your swing and may raise their guard to parry or block it, by the odds in the mob_guard data (by mob, shield or weapon, "
                + "weapon type and difficulty), then wait a cooldown before choosing again. Off: mobs never raise a guard.")
                .translation(KEY + "mobGuard.react").define("react", true);
        MOB_GUARD_PARRIED_LOCKOUT = b.comment("Seconds you cannot swing after a mob parries your hit (your cooldown bar empties and your view takes a knock). Lighter than the stagger a mob gets from your parry.")
                .translation(KEY + "mobGuard.parriedLockout").defineInRange("parriedLockout", 0.5, 0.0, 2.0);
        MOB_GUARD_RIPOSTE = b.comment("A mob that parries your hit ripostes: it lowers its guard and starts its next attack at once instead of waiting out its cooldown (its wind-up still tells it).")
                .translation(KEY + "mobGuard.riposte").define("riposte", true);
        MOB_GUARD_RIPOSTE_TICKS = b.comment("Ticks after a mob's parry before its riposte starts (20 = 1 second). Your lockout after being parried is parriedLockout.")
                .translation(KEY + "mobGuard.riposteTicks").defineInRange("riposteTicks", 4, 0, 40);
        b.pop();
        b.translation(KEY + "mobs").push("mobs");
        MOB_WIND_UPS = b.comment("Melee mobs that attack a player stop, whoosh and rear back before each hit, and hit only if you are still in reach when it lands (as the lean ends), so a parry or a step back beats them; a spider rears back, then pounces, and bites as it lands. "
                + "How long follows the weapon in the mob's hand (a slower weapon winds up longer, 0.3 to 0.8 s), else its mob_attacks entry (wind_up, seconds), else 0.4 s. Off is vanilla's instant hit.").translation(KEY + "mobs.windUps").define("windUps", true);
        MOB_PASSIVES = b.comment("A mob's melee hit runs its weapon's own on-hit effects, as yours does (Basic Weapons' club slows, its hammer knocks up), without wearing the weapon. "
                + "Mods that give mobs their passives themselves (Jaam's Weaponry) are left to it, by the mob_passives data. Off: a mob's weapon only deals damage and enchantments, as in vanilla.")
                .translation(KEY + "mobs.weaponPassives").define("weaponPassives", true);
        MOB_ARMING = b.comment("Mobs that spawn empty-handed are sometimes handed a weapon, by the mob_arming data (zombies, husks and zombie villagers: wooden, stone and iron swords and axes, an iron shovel). "
                + "AUTO is on unless Jaam's Weaponry, which arms mobs by its own tables, is installed; ON always; OFF never.").translation(KEY + "mobs.arming").defineEnum("arming", ArmingMode.AUTO);
        ATTACK_TOKENS = b.comment("In a crowd, at most this many mobs wind up at you at once; the rest wait their turn. 0 is no limit. Special attacks (pounces, charges) keep their own timing.")
                .translation(KEY + "mobs.attackTokens").defineInRange("attackTokens", 2, 0, 16);
        CIRCLING = b.comment("A mob waiting its turn circles you at its distance, backs off when close and now and then feints a step in. Off: it just waits.")
                .translation(KEY + "mobs.circling").define("circling", true);
        CIRCLE_RADIUS = b.comment("Blocks from you that mobs waiting their turn hold, spread round the side away from the attackers so you are boxed in.")
                .translation(KEY + "mobs.circleRadius").defineInRange("circleRadius", 5.5, 2.5, 12.0);
        CIRCLE_SPEED = b.comment("How fast waiting mobs move to their place in the ring, times their chase speed (1 is the chase's).")
                .translation(KEY + "mobs.circleSpeed").defineInRange("circleSpeed", 1.4, 0.5, 2.5);
        ATTACKER_SPACING = b.comment("Ticks at least between two mobs starting a wind-up at you, so two hits never land at the same moment (20 = 1 second). 0 is off.")
                .translation(KEY + "mobs.attackerSpacingTicks").defineInRange("attackerSpacingTicks", 6, 0, 20);
        PACK_SURGE_SIZE = b.comment("Every 5 to 10 seconds, this many mobs waiting their turn rush in together from the ring's flanks, on top of the attackers, so a crowd attacks as a pack "
                + "(their wind-ups still attackerSpacingTicks apart). 0: no surges, only the turns.").translation(KEY + "mobs.packSurgeSize").defineInRange("packSurgeSize", 2, 0, 8);
        SPARE_MOUNT = b.comment("Your swings never hit the mob you ride (or one riding you).").translation(KEY + "mobs.spareMount").define("spareMount", true);
        SPARE_BYSTANDERS = b.comment("A swing's sweep (every target but the one you aim at) spares your tamed animals and bystanders: mobs that are neither enemies nor going for you "
                + "(horses, villagers, a calm wolf), and anything in #vectorcombat:sweep_spared. Aimed at, they are hit as ever.").translation(KEY + "mobs.spareBystanders").define("spareBystanders", true);
        WAITERS_FIGHT = b.comment("A mob waiting its turn still fights when you come within its reach: the turns limit who comes in, not who may hit. Off: it waits regardless.")
                .translation(KEY + "mobs.waitersFight").define("waitersFight", true);
        MOB_VS_MOB = b.comment("Mobs wind up (and show the tell) at other mobs too, not only at players: a golem at a zombie, a zombie at a villager, wolves, raids; a mob with a guard reads "
                + "another's tell as it reads your swing. Mobs tagged #vectorcombat:no_mob_wind_up never do, as attacker or target.")
                .translation(KEY + "mobs.mobVsMob").define("mobVsMob", true);
        MOB_VS_MOB_RANGE = b.comment("Mob-vs-mob wind-ups only within this many blocks of a player; farther fights (a farm, a far raid) keep vanilla's instant hits, with no sound or tell to send. 0 is everywhere.")
                .translation(KEY + "mobs.mobVsMobRange").defineInRange("mobVsMobRange", 32.0, 0.0, 128.0);
        MOD_ATTACK_ANIMATIONS = b.comment("A modded mob that has its own attack animation plays it as its wind-up's tell (learned from its first hit), so its swing leads into the hit and shows on a miss too. "
                + "Off: its animation plays only when the hit lands.").translation(KEY + "mobs.modAttackAnimations").define("modAttackAnimations", true);
        MOB_SHIELDS = b.comment("Some melee humanoids (zombies, husks, drowned, wither skeletons, piglins, brutes, vindicators) spawn with a shield in the off hand, which they guard with. "
                + "How often (15% by default), which mobs and which shields (any mod's, less those the pack disables, Item Obliterator included) are in the mob_shields data. Never over another mod's equipment: only an empty off hand, and not with a bow, "
                + "a crossbow, a trident or a two-handed weapon. Off: mobs keep only what they spawn with.")
                .translation(KEY + "mobs.spawnShields").define("spawnShields", true);
        b.pop();
        b.translation(KEY + "debug").push("debug");
        DEBUG_LOG = b.comment("Writes what the mod does (swings, hits, mob wind-ups, parries) to logs/vectorcombat-diag.jsonl, one line per event, rewritten each launch, "
                + "to attach to a bug report. Off by default: nothing is written.").translation(KEY + "debug.debugLog").define("debugLog", false);
        b.pop();
        SPEC = b.build();
    }

    private CommonConfig() {
    }
}
