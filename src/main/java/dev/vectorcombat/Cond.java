package dev.vectorcombat;

import java.util.Set;

/** One movement condition: {@code name[:minSpeed]}, optionally prefixed with {@code !} to invert. */
public record Cond(String text, String name, double min, boolean not) {
    private static final Set<String> NAMES = Set.of("on_ground", "in_air", "sprinting", "sneaking",
            "forward_speed", "backward_speed", "left_speed", "right_speed", "up_speed", "down_speed");

    public static Cond parse(String text) {
        String s = text.startsWith("!") ? text.substring(1) : text;
        int i = s.indexOf(':');
        String name = i < 0 ? s : s.substring(0, i);
        if (!NAMES.contains(name)) throw new IllegalArgumentException("unknown condition: " + text);
        return new Cond(text, name, i < 0 ? 0.1 : Double.parseDouble(s.substring(i + 1)), text.startsWith("!"));
    }

    public boolean test(MoveState m) {
        boolean r = switch (name) {
            case "on_ground" -> m.onGround();
            case "in_air" -> !m.onGround();
            case "sprinting" -> m.sprinting();
            case "sneaking" -> m.sneaking();
            case "forward_speed" -> m.forward() >= min;
            case "backward_speed" -> -m.forward() >= min;
            case "right_speed" -> m.right() >= min;
            case "left_speed" -> -m.right() >= min;
            case "up_speed" -> m.up() >= min;
            default -> -m.up() >= min; // down_speed
        };
        return r != not;
    }
}
