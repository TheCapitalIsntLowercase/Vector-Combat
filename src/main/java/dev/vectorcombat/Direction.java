package dev.vectorcombat;

/**
 * The crosshair arrow for a swing, taken from the attack itself so it can never disagree with what happened.
 * {@code octant} is clockwise from forward in steps of 2 (0 = up on screen, 2 = right, 4 = back, 6 = left);
 * {@code vertical} is +1 rising, -1 falling, 0 on the ground. Cardinal directions only.
 */
public record Direction(int octant, int vertical, boolean none) {
    public static final Direction NONE = new Direction(0, 0, true);

    public static Direction of(AttackDef def) {
        return switch (def.arrow()) {
            case "forward" -> new Direction(0, 0, false);
            case "right" -> new Direction(2, 0, false);
            case "back" -> new Direction(4, 0, false);
            case "left" -> new Direction(6, 0, false);
            case "rising" -> new Direction(0, 1, false);
            case "falling" -> new Direction(4, -1, false);
            default -> NONE;
        };
    }
}
