package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Gameplay properties of an attack. Parsed form leaves unset fields null; {@link #over} fills them from a base, so an
 * attack inherits from its weapon set's {@code defaults}, which inherit from {@link #BASE}.
 */
public record Props(List<String> categories, List<Effect> effects, Penalty penalty, Penalty consecutive) {
    public static final Props EMPTY = new Props(null, null, null, null);
    /** Every hit costs 15% attack speed for 1s; repeating the same attack adds 15% per repeat and holds for 2s. */
    public static final Props BASE = new Props(List.of(), List.of(), new Penalty(0.15, 1.0), new Penalty(0.15, 2.0));

    public static Props parse(JsonObject o) {
        List<String> categories = null;
        if (o.has("categories")) {
            categories = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("categories")) categories.add(e.getAsString());
        }
        List<Effect> effects = null;
        if (o.has("effects")) {
            effects = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("effects")) effects.add(Effect.parse(e.getAsJsonObject()));
        }
        return new Props(categories, effects, penalty(o, "attack_speed_penalty"), penalty(o, "consecutive_use_penalty"));
    }

    private static Penalty penalty(JsonObject o, String key) {
        if (!o.has(key)) return null;
        JsonObject p = o.getAsJsonObject(key);
        return new Penalty(p.get("amount").getAsDouble(), p.get("duration").getAsDouble());
    }

    public Props over(Props base) {
        return new Props(categories != null ? categories : base.categories, effects != null ? effects : base.effects,
                penalty != null ? penalty : base.penalty, consecutive != null ? consecutive : base.consecutive);
    }
}
