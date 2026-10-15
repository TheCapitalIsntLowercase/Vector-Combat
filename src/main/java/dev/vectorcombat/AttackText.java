package dev.vectorcombat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The words of a weapon's attack tooltip, with no game classes so they can be checked in a test: one attack at a time, as at most four short lines instead of the raw
 * condition strings and numbers the data is written in ({@link AttackTooltip} puts them on screen).
 */
public final class AttackText {
    private AttackText() {
    }

    /** The tooltip's lines: {@code kind} (only for a two-handed weapon, else empty), the attack's {@code title}, one gray {@code detail} line, {@code effects} (empty for none) and the {@code hint}. */
    public record Block(String kind, String title, String detail, String effects, String hint) {
        /** Every non-empty line in order. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (String s : new String[] {kind, title, detail, effects, hint}) if (!s.isEmpty()) out.add(s);
            return out;
        }
    }

    /** The block for attack {@code index} of {@code count} of a weapon of this {@code type} (Better Combat's word, or null), two-handed or not. */
    public static Block block(AttackDef a, int index, int count, String type, boolean twoHanded) {
        return new Block(kind(type, twoHanded), title(a, index, count), detail(a), effects(a), hint(count));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 1.0 is "1", 1.6 is "1.6", 2.50 is "2.5". */
    static String number(double v) {
        return BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    /** Worth a line only when it matters: a two-handed weapon locks the off hand. {@code Claymore · two-handed}, or {@code Two-handed} when the type is unknown. */
    static String kind(String type, boolean twoHanded) {
        if (!twoHanded) return "";
        String t = type == null ? "" : capital(type.replace('_', ' '));
        return t.isEmpty() ? "Two-handed" : t + " · two-handed";
    }

    /** The attack's name, with where it is among the weapon's when it has several. */
    static String title(AttackDef a, int index, int count) {
        return count > 1 ? a.name() + "  " + (index + 1) + "/" + count : a.name();
    }

    /** When, which category (unless it is the name), how much damage and what shape: {@code Falling · Cleave · 1.6x damage · 120° arc}. */
    static String detail(AttackDef a) {
        List<String> parts = new ArrayList<>();
        parts.add(when(a.when()));
        String name = a.name().toLowerCase(Locale.ROOT);
        String categories = a.props().categories().stream().filter(c -> !c.equals(name)).map(AttackText::capital).collect(Collectors.joining(" / "));
        if (!categories.isEmpty()) parts.add(categories);
        parts.add(number(a.damage()) + "x damage");
        parts.add(a.angle() > 0 ? Math.round(a.angle()) + "° arc" : "thrust");
        return String.join(" · ", parts);
    }

    /** The movement an attack wants, in words: groups are alternatives. No condition is what the weapon does when no other attack applies. */
    static String when(List<List<Cond>> when) {
        if (when.isEmpty()) return "Standing or walking";
        return capital(when.stream().map(AttackText::group).collect(Collectors.joining(" or ")));
    }

    private static String group(List<Cond> and) {
        Set<String> names = new LinkedHashSet<>();
        for (Cond c : and) if (!c.not()) names.add(c.name());
        boolean vertical = names.contains("up_speed") || names.contains("down_speed"); // rising and falling already say it is in the air
        boolean moving = names.stream().anyMatch(n -> !n.equals("on_ground")); // any movement word already says it is on the ground
        List<String> words = new ArrayList<>();
        for (Cond c : and) {
            String w = switch (c.name()) {
                case "on_ground" -> c.not() ? "in the air" : moving ? null : "on the ground";
                case "in_air" -> c.not() ? "on the ground" : vertical ? null : "in the air";
                case "sprinting" -> "sprinting";
                case "sneaking" -> "sneaking";
                case "forward_speed" -> names.contains("sprinting") ? null : "moving forward"; // sprinting is moving forward
                case "backward_speed" -> "moving back";
                case "left_speed" -> "strafing left";
                case "right_speed" -> "strafing right";
                case "up_speed" -> "rising";
                case "down_speed" -> "falling";
                default -> c.name();
            };
            if (w != null) words.add(c.not() && !c.name().equals("on_ground") && !c.name().equals("in_air") ? "not " + w : w);
        }
        return String.join(", ", words);
    }

    /** What the hit does besides damage: {@code Stuns 1.5 s · Knocks back · Lunges you}. */
    static String effects(AttackDef a) {
        Set<String> out = new LinkedHashSet<>();
        for (Effect fx : a.props().effects()) {
            out.add(switch (fx) {
                case Effect.Stun s -> "Stuns " + number(s.seconds()) + " s";
                case Effect.Motion m -> push(m);
                case Effect.Exhaustion x -> "Tires you";
            });
        }
        return String.join(" · ", out);
    }

    /** A motion effect by its strongest part: up launches, sideways pushes aside, forward knocks back (or lunges the attacker). */
    private static String push(Effect.Motion m) {
        double right = Math.abs(m.motion().x), up = Math.abs(m.motion().y), forward = Math.abs(m.motion().z);
        if (m.user()) return up > Math.max(right, forward) ? "Lifts you" : right > forward ? "Slides you" : "Lunges you";
        return up > Math.max(right, forward) ? "Launches" : right > forward ? "Pushes aside" : "Knocks back";
    }

    static String hint(int count) {
        return count > 1 ? "Space next · Shift hide" : "Shift hide";
    }
}
