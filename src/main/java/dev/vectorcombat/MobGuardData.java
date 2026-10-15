package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * How readily mobs guard against a player's swing, from {@code data/<ns>/vectorcombat/mob_guard/*.json}: odds of a parry and of a block by mob ({@code mobs}: entries that
 * {@code match} entity ids or {@code #tags}, the first match wins, else {@code default}), scaled by what the mob guards with ({@code blockers}: {@code shield} or {@code weapon}),
 * by the weapon's type ({@code weapon_types}, the type {@link WeaponData#type} reads, e.g. {@code dagger}) and by the world's difficulty ({@code difficulty}); and seconds a mob waits
 * after a guard before it chooses again ({@code cooldown}). Packs add or override files; entries of later files come first.
 */
final class MobGuardData {
    private MobGuardData() {
    }

    /** Odds of parrying and of blocking a swing, and the cooldown in seconds after a guard. */
    record Odds(double parry, double block, double cooldown) {
        static final Odds NONE = new Odds(0, 0, 2);
    }

    /** Multipliers on the two odds. */
    record Scale(double parry, double block) {
        static final Scale ONE = new Scale(1, 1);
    }

    private record Entry(List<String> match, Odds odds) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile Odds fallback = Odds.NONE;
    private static volatile Map<String, Scale> blockers = Map.of(), types = Map.of();
    private static volatile Map<String, Double> difficulty = Map.of();

    static void load(List<JsonElement> files) {
        List<Entry> list = new ArrayList<>();
        Odds def = Odds.NONE;
        Map<String, Scale> b = new HashMap<>(), t = new HashMap<>();
        Map<String, Double> d = new HashMap<>();
        for (JsonElement f : files) {
            try {
                JsonObject o = f.getAsJsonObject();
                if (o.has("default")) def = odds(o.getAsJsonObject("default"), def);
                List<Entry> mine = new ArrayList<>();
                if (o.has("mobs")) for (JsonElement e : o.getAsJsonArray("mobs")) {
                    JsonObject m = e.getAsJsonObject();
                    List<String> match = new ArrayList<>();
                    for (JsonElement id : m.getAsJsonArray("match")) match.add(id.getAsString());
                    mine.add(new Entry(match, odds(m, def)));
                }
                list.addAll(0, mine);
                if (o.has("blockers")) o.getAsJsonObject("blockers").entrySet().forEach(e -> b.put(e.getKey(), scale(e.getValue().getAsJsonObject())));
                if (o.has("weapon_types")) o.getAsJsonObject("weapon_types").entrySet().forEach(e -> t.put(e.getKey(), scale(e.getValue().getAsJsonObject())));
                if (o.has("difficulty")) o.getAsJsonObject("difficulty").entrySet().forEach(e -> d.put(e.getKey(), e.getValue().getAsDouble()));
            } catch (RuntimeException ex) {
                VectorCombat.LOG.warn("Skipping a bad mob_guard file: {}", ex.toString());
            }
        }
        entries = List.copyOf(list);
        fallback = def;
        blockers = Map.copyOf(b);
        types = Map.copyOf(t);
        difficulty = Map.copyOf(d);
        VectorCombat.LOG.info("[diag] mob guard: {} mob entries, {} blockers, {} weapon types, {} difficulties", list.size(), b.size(), t.size(), d.size());
    }

    private static Odds odds(JsonObject o, Odds base) {
        return new Odds(o.has("parry") ? o.get("parry").getAsDouble() : base.parry(), o.has("block") ? o.get("block").getAsDouble() : base.block(),
                o.has("cooldown") ? o.get("cooldown").getAsDouble() : base.cooldown());
    }

    private static Scale scale(JsonObject o) {
        return new Scale(o.has("parry") ? o.get("parry").getAsDouble() : 1, o.has("block") ? o.get("block").getAsDouble() : 1);
    }

    /** The entry for a mob of {@code type}: the first whose ids or tags match, else the default. */
    static Odds base(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        for (Entry e : entries) {
            for (String m : e.match()) {
                if (m.startsWith("#") ? type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(m.substring(1)))) : m.equals(id)) return e.odds();
            }
        }
        return fallback;
    }

    static Scale blocker(String kind) {
        return blockers.getOrDefault(kind, Scale.ONE);
    }

    static Scale weaponType(String type) {
        return type == null ? Scale.ONE : types.getOrDefault(type, Scale.ONE);
    }

    static double difficulty(String key) {
        return difficulty.getOrDefault(key, 1.0);
    }

    /**
     * The odds a mob actually rolls: its entry times the blocker's, the weapon type's and the difficulty's multipliers, each within 0 and 1, and the block's cut so the two never
     * add up past 1 (the parry keeps its share).
     */
    static Odds roll(Odds base, Scale blocker, Scale type, double difficulty) {
        double parry = clamp(base.parry() * blocker.parry() * type.parry() * difficulty);
        double block = clamp(base.block() * blocker.block() * type.block() * difficulty);
        return new Odds(parry, Math.min(block, 1 - parry), Math.max(0, base.cooldown()));
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** What a roll of {@code r} (0 to 1) picks: {@code parry} below the parry's odds, {@code block} in the next share, else {@code none}. */
    static String choose(Odds odds, double r) {
        return r < odds.parry() ? "parry" : r < odds.parry() + odds.block() ? "block" : "none";
    }
}
