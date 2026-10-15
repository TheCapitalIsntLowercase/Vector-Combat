package dev.vectorcombat;

import java.lang.reflect.Field;
import net.minecraft.world.entity.player.Player;

/**
 * Basic Weapons gates its club's slowness and its hammer's knock-up on a full swing, read from a value its mixin stores at the start of vanilla's {@code Player.attack}
 * ({@code basic_weapons$attackCooldownProgress}, 0 until set), which a Vector swing never runs, so with this mod they never fired. Before a swing's hit calls the weapon's
 * {@code hurtEnemy}, {@link Strike} stores the swing's own strength there. Found by name at run time; if Basic Weapons renames it, this logs once and does nothing.
 */
final class BasicWeaponsCompat {
    static final String ID = "basicweapons";
    private static final String FIELD = "basic_weapons$attackCooldownProgress";

    private BasicWeaponsCompat() {
    }

    private static Field field;
    private static boolean missing;

    /** Below the hammer's full-swing threshold (0.9). */
    static final float NO_LAUNCH = 0.5f;

    /**
     * The strength Basic Weapons should see for a hit with {@code held}: the hammer knocks its target up on any full swing, which would make every attack a launcher, so only
     * the upward attack ({@code uppercut}) keeps it; any other attack reads as less than full there. Other weapons see the swing's own strength.
     */
    static float strength(net.minecraft.world.item.ItemStack held, AttackDef def, float strength) {
        boolean hammer = held.getItem().getClass().getName().equals("com.khazoda.basicweapons.item.HammerItem");
        return hammer && !"uppercut".equals(def.animation()) ? Math.min(strength, NO_LAUNCH) : strength;
    }

    /** Stores {@code strength} (0 to 1) as the swing Basic Weapons reads in {@code hurtEnemy}. Returns whether it could. */
    static boolean strength(Player p, float strength) {
        if (missing) return false;
        try {
            if (field == null) {
                Field f = Player.class.getDeclaredField(FIELD);
                f.setAccessible(true);
                field = f;
            }
            field.setFloat(p, strength);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            missing = true;
            VectorCombat.LOG.warn("Basic Weapons compat off: no {} on Player ({})", FIELD, e.toString());
            return false;
        }
    }
}
