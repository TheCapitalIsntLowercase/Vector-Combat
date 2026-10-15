package dev.vectorcombat;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings for what you see and how swings feel, in two sections. Every entry and section has an explicit translation key
 * ({@code vectorcombat.configuration.<section>.<name>}) with a short label and a one-line tooltip in {@code en_us.json}; the comments here are the file's own documentation.
 */
public final class ClientConfig {
    /** How a swing shows its sweep: the swing's own crescent trail, or Minecraft's sweep-attack crescent. */
    public enum TrailStyle {
        CUSTOM, VANILLA
    }

    /** How much a third-person swing turns toward a foe near the crosshair ({@link ThirdView#aimSwing}). */
    public enum ThirdPersonAssist {
        OFF, LIGHT, STRONG
    }

    /** The third-person refresh, or everything as it was before it ({@link ThirdView}). */
    public enum ThirdPersonStyle {
        REFRESHED, CLASSIC
    }

    /** How your own swings look in first person: the sword shown broadside and carried across the view, or the original blade-path clips. */
    public enum FirstPersonStyle {
        SHOWCASE, CLASSIC
    }

    private static final String KEY = "vectorcombat.configuration.";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_DIRECTION_ARROW;
    public static final ModConfigSpec.EnumValue<FirstPersonStyle> FIRST_PERSON_STYLE;
    public static final ModConfigSpec.EnumValue<TrailStyle> TRAIL_STYLE;
    public static final ModConfigSpec.DoubleValue TRAIL_OPACITY;
    public static final ModConfigSpec.BooleanValue THIRD_PERSON;
    public static final ModConfigSpec.EnumValue<ThirdPersonStyle> THIRD_PERSON_STYLE;
    public static final ModConfigSpec.BooleanValue THIRD_PERSON_CROSSHAIR;
    public static final ModConfigSpec.EnumValue<ThirdPersonAssist> THIRD_PERSON_ASSIST;
    public static final ModConfigSpec.BooleanValue HANDS;
    public static final ModConfigSpec.BooleanValue GUARD_ANIMATIONS;
    public static final ModConfigSpec.BooleanValue FIT_HELD_WEAPONS;
    public static final ModConfigSpec.BooleanValue LINE_TELEGRAPHS;
    public static final ModConfigSpec.BooleanValue RING_TELEGRAPHS;
    public static final ModConfigSpec.DoubleValue IMPACT_INTENSITY;
    public static final ModConfigSpec.BooleanValue HOLD_TO_ATTACK;
    public static final ModConfigSpec.BooleanValue SWING_THROUGH_GRASS;
    public static final ModConfigSpec.DoubleValue HOLD_MIN_STRENGTH;
    public static final ModConfigSpec.BooleanValue ALTERNATE_SLASH;
    public static final ModConfigSpec.BooleanValue KEEP_COOLDOWN_DIP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.translation(KEY + "display").push("display");
        SHOW_DIRECTION_ARROW = b.comment("Show a small arrow beside the crosshair pointing the way you were moving on your last melee attack.")
                .translation(KEY + "display.directionArrow").define("directionArrow", true);
        FIRST_PERSON_STYLE = b.comment("How your own swings look in first person. SHOWCASE shows the weapon broadside, carried across the view with a snap and a glide. "
                + "CLASSIC plays the earlier clips, where the blade follows its real path out from the eye. Only the look changes: timing, hits, trails and third person are the same.")
                .translation(KEY + "display.firstPersonStyle").defineEnum("firstPersonStyle", FirstPersonStyle.SHOWCASE);
        TRAIL_STYLE = b.comment("What every swing you see leaves in the air, yours and other players': CUSTOM draws a crescent that follows the blade through the swing, "
                + "VANILLA is Minecraft's sweep-attack crescent.").translation(KEY + "display.trailStyle").defineEnum("trailStyle", TrailStyle.CUSTOM);
        TRAIL_OPACITY = b.comment("How solid the custom trail is: 0 is invisible, 1 fully solid.").translation(KEY + "display.trailOpacity").defineInRange("trailOpacity", 0.9, 0.0, 1.0);
        THIRD_PERSON = b.comment("Third-person swings: the weapon arm of every swinging player (you in F5 included) points along the blade of the swing and the weapon lies along it, "
                + "the chest turns toward the blade and, standing, the foot opposite the arm steps forward. Off keeps vanilla's generic arm swing.").translation(KEY + "display.thirdPersonSwings").define("thirdPersonSwings", true);
        THIRD_PERSON_STYLE = b.comment("REFRESHED: the third-person refresh (the direction arrow and stun ring where a third-person crosshair shows; with Shoulder Surfing, swings aimed where its crosshair points). "
                + "CLASSIC: third person exactly as before the refresh, to go back to if it is not as good.").translation(KEY + "display.thirdPersonStyle").defineEnum("thirdPersonStyle", ThirdPersonStyle.REFRESHED);
        THIRD_PERSON_CROSSHAIR = b.comment("With the refresh: a crosshair in vanilla's third person (F5), which has none, split by the attack-speed penalty as in first person. Shoulder Surfing draws its own; this adds nothing there.")
                .translation(KEY + "display.thirdPersonCrosshair").define("thirdPersonCrosshair", false);
        THIRD_PERSON_ASSIST = b.comment("With the refresh, in third person from behind: a swing with no mob under the crosshair turns you to the foe nearest it, inside a cone of 20 degrees (LIGHT) or 35 (STRONG) "
                + "and within the swing's reach. Never your pets, calm mobs or other players. First person is never assisted. OFF: you swing where you aim.")
                .translation(KEY + "display.thirdPersonAssist").defineEnum("thirdPersonAssist", ThirdPersonAssist.LIGHT);
        HANDS = b.comment("Your own arm in first person, holding what you hold: it reaches from the shoulder to the item and follows every swing, the equip, eating and the shield. "
                + "Off (the default) is vanilla's look. It stays off while a mod that already draws hands is installed (Punchy, Hold My Items, 3D Hands, First Person Model).")
                .translation(KEY + "display.hands").define("hands", false);
        GUARD_ANIMATIONS = b.comment("The shield's first-person motion: it lifts when you raise it and drops when you lower it, snaps forward on a parry (the view pinches in and shakes for a moment while the staggered mob is held still) "
                + "and recoils on a plain block. Off leaves vanilla's instant switch between the idle and raised shield.").translation(KEY + "display.shieldMotion").define("shieldMotion", true);
        FIT_HELD_WEAPONS = b.comment("A weapon whose model sits at another angle than a sword's (Basic Weapons' spears, quarterstaves and glaives, Jaam's claws and katars) swings along the same path as a sword: "
                + "it is turned about your hand through the swing and back at rest. Off plays the sword's clips as they are.").translation(KEY + "display.fitHeldWeapons").define("fitHeldWeapons", true);
        LINE_TELEGRAPHS = b.comment("The line of dust a charging mob (hoglin, ravager, cow...) draws on the ground before it charges: red for a hostile mob, yellow for a neutral one. "
                + "Off hides the line; the charge and its other tells are the same.").translation(KEY + "display.lineTelegraphs").define("lineTelegraphs", true);
        RING_TELEGRAPHS = b.comment("The ring of dust that marks where a leaping mob will land (the spider's pounce and drop, the slime's and magma cube's leap). Off hides the ring; "
                + "the leap and its other tells are the same.").translation(KEY + "display.ringTelegraphs").define("ringTelegraphs", true);
        b.pop();
        b.translation(KEY + "feel").push("feel");
        IMPACT_INTENSITY = b.comment("How hard a landed hit shakes your camera, pinches the view in, rattles the weapon in your hand and shakes the mob that was hit. "
                + "0 is none, 1 the default, 2 doubles it.").translation(KEY + "feel.impact").defineInRange("impact", 1.0, 0.0, 2.0);
        HOLD_TO_ATTACK = b.comment("Holding the attack key keeps swinging a Vector weapon: the next swing starts as soon as the weapon has recharged. "
                + "Blocks a mining tool should mine and targets vanilla keeps stay vanilla.").translation(KEY + "feel.holdToAttack").define("holdToAttack", true);
        SWING_THROUGH_GRASS = b.comment("A swing at a mob behind grass, flowers or anything else you can walk through hits the mob instead of cutting the plant. Without a mob behind it, "
                + "the click breaks the plant as usual.").translation(KEY + "feel.swingThroughGrass").define("swingThroughGrass", true);
        HOLD_MIN_STRENGTH = b.comment("How recharged the weapon must be before a held key swings again: 1 is fully (full damage), down to 0.9, the least a swing is accepted at.")
                .translation(KEY + "feel.holdRecharge").defineInRange("holdRecharge", 1.0, 0.9, 1.0);
        ALTERNATE_SLASH = b.comment("The standing slash alternates between a forehand and a backhand, swing by swing, for weapons whose clips fit it. Off plays the forehand every time.")
                .translation(KEY + "feel.alternateSlash").define("alternateSlash", true);
        KEEP_COOLDOWN_DIP = b.comment("Vanilla lowers the held weapon while the attack cooldown refills (and again when its durability changes). "
                + "Vector weapons cancel that so the swing animations play where they were authored. Set true to keep the vanilla dip.")
                .translation(KEY + "feel.cooldownDip").define("cooldownDip", false);
        b.pop();
        SPEC = b.build();
    }
}
