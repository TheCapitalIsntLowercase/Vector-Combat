package dev.vectorcombat;

import java.util.List;

/**
 * One attack a weapon can perform. {@code when} is an OR of AND-groups of movement conditions (empty for a weapon's
 * default attack). {@code range} multiplies the player's reach, {@code angle} is the horizontal arc in degrees (0 is a
 * thrust), {@code damage} multiplies the weapon's attack damage, and {@code hitAt} is seconds from swing start to
 * impact. {@code arrow} is the crosshair arrow for this swing (forward, back, left, right, rising, falling; empty for a dot). {@code props} is fully resolved (attack over set defaults over {@link Props#BASE}).
 * {@code ownCategories} is true when the attack names its own categories; otherwise they come from the weapon ({@link Attacks#propsFor}).
 * {@code hitstop} is the pause in ticks on a landed hit, or -1 to derive it from the weapon's attack speed ({@link Hitstop#weightTicks}); {@code hitstopBonus}
 * adds ticks to a derived pause (a heavy slam), and is ignored when {@code hitstop} is set. {@code alternate} is a second clip the swing alternates with
 * ({@link Swing#alternates}), empty for none.
 */
public record AttackDef(String name, List<List<Cond>> when, double range, double angle, double damage, double hitAt,
                        String animation, String sound, float pitch, Props props, String arrow, boolean ownCategories, int hitstop, int hitstopBonus, String alternate) {
    public boolean matches(MoveState m) {
        return when.stream().anyMatch(and -> and.stream().allMatch(c -> c.test(m)));
    }

    /**
     * How well movement backs a claim to have swung this attack: the index of the first of {@code states} (newest first) its conditions hold in, 0 for an attack with
     * no conditions (a weapon's default attack, which any movement allows), or -1 when none of them does.
     */
    public int supportedBy(List<MoveState> states) {
        if (when.isEmpty()) return 0;
        for (int i = 0; i < states.size(); i++) {
            if (matches(states.get(i))) return i;
        }
        return -1;
    }
}
