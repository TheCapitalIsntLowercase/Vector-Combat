package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Server-side mob data: {@code vectorcombat/mobs} (attribute tweaks plus damage-category weaknesses and resistances)
 * and {@code vectorcombat/mob_attacks} (categories and effects carried by a mob's own attacks and projectiles).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Profiles {
    record Mod(Set<String> categories, double multiplier) {
    }

    private record Profile(List<String> entities, List<Mod> modifiers, Map<ResourceLocation, Double> attributes) {
    }

    /** One mob_attacks entry: its entities, its props, and {@code age} ({@code "adult"}, {@code "baby"}, or null for any; a baby hoglin's hit does not stun). */
    private record MobAttack(List<String> entities, Props props, String age) {
    }

    /** The key a value of an entry with {@code age} is kept under for entity {@code id}: the id alone for any age, else with {@code |adult} or {@code |baby}. */
    static String key(String id, String age) {
        return age == null ? id : id + "|" + age;
    }

    /** Whether an entry of {@code age} (null for any) applies to an entity that {@code baby} or not. */
    static boolean ageMatches(String age, boolean baby) {
        return age == null || age.equals(baby ? "baby" : "adult");
    }

    /** A per-entity value for {@code e}: its age's own entry first, then the entry for any age. */
    private static <V> V lookup(Map<String, V> map, Entity e) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        boolean baby = e instanceof LivingEntity l && l.isBaby();
        V v = map.get(key(id, baby ? "baby" : "adult"));
        return v != null ? v : map.get(id);
    }

    private static volatile List<Profile> profiles = List.of();
    private static volatile List<MobAttack> mobAttacks = List.of();

    static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    public static void loadProfiles(List<JsonElement> files) {
        List<Profile> out = new ArrayList<>();
        for (JsonElement file : files) {
            for (JsonObject o : Attacks.objects(file)) {
                List<Mod> mods = new ArrayList<>();
                if (o.has("damage_modifiers")) {
                    for (JsonElement e : o.getAsJsonArray("damage_modifiers")) {
                        JsonObject m = e.getAsJsonObject();
                        mods.add(new Mod(new HashSet<>(strings(m, "categories")), m.get("multiplier").getAsDouble()));
                    }
                }
                Map<ResourceLocation, Double> attributes = new LinkedHashMap<>();
                if (o.has("attributes")) {
                    for (JsonElement e : o.getAsJsonArray("attributes")) {
                        JsonObject a = e.getAsJsonObject();
                        attributes.put(ResourceLocation.parse(a.get("attribute").getAsString()), a.get("value").getAsDouble());
                    }
                }
                out.add(new Profile(strings(o, "entities"), List.copyOf(mods), attributes));
            }
        }
        profiles = List.copyOf(out);
    }

    public static void loadMobAttacks(List<JsonElement> files) {
        List<MobAttack> out = new ArrayList<>();
        Map<String, Double> winds = new LinkedHashMap<>();
        Map<String, String> sigs = new LinkedHashMap<>();
        Map<String, Double> intervals = new LinkedHashMap<>(), reaches = new LinkedHashMap<>(), forwards = new LinkedHashMap<>();
        for (JsonElement file : files) {
            for (JsonObject o : Attacks.objects(file)) {
                String age = o.has("age") ? o.get("age").getAsString() : null;
                out.add(new MobAttack(strings(o, "entities"), Props.parse(o).over(Props.BASE), age));
                if (o.has("wind_up")) for (String id : strings(o, "entities")) winds.put(key(id, age), o.get("wind_up").getAsDouble());
                if (o.has("signature")) for (String id : strings(o, "entities")) sigs.put(key(id, age), o.get("signature").getAsString());
                if (o.has("interval")) for (String id : strings(o, "entities")) intervals.put(key(id, age), o.get("interval").getAsDouble());
                if (o.has("reach")) for (String id : strings(o, "entities")) reaches.put(key(id, age), o.get("reach").getAsDouble());
                if (o.has("reach_forward")) for (String id : strings(o, "entities")) forwards.put(key(id, age), o.get("reach_forward").getAsDouble());
            }
        }
        mobAttacks = List.copyOf(out);
        windUps = Map.copyOf(winds);
        signatures = Map.copyOf(sigs);
        Profiles.intervals = Map.copyOf(intervals);
        Profiles.reaches = Map.copyOf(reaches);
        Profiles.forwards = Map.copyOf(forwards);
    }

    /** Seconds between a goal mob's attacks, by exact entity id, from {@code interval} in mob_attacks (vanilla's 1 when absent); and blocks of reach beyond vanilla's, from {@code reach}. */
    private static volatile Map<String, Double> intervals = Map.of(), reaches = Map.of(), forwards = Map.of();

    static Map<String, Double> forwards() {
        return forwards;
    }

    /** How far ahead, in blocks, this mob's attack box is carried the way it faces ({@code reach_forward}), 0 for none. */
    public static double reachForward(EntityType<?> type) {
        return forwards.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), 0.0);
    }

    static Map<String, Double> intervals() {
        return intervals;
    }

    static Map<String, Double> reaches() {
        return reaches;
    }

    /** This mob's attack interval in seconds, or null for vanilla's. */
    public static Double interval(EntityType<?> type) {
        return intervals.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    /** This mob's reach beyond vanilla's melee reach in blocks, 0 for none. */
    public static double reach(EntityType<?> type) {
        return reaches.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), 0.0);
    }

    /** A mob's signature tell by exact entity id, from {@code signature} in mob_attacks ({@code "overhead"}: both arms raised, then a slam); none is the plain lean. */
    private static volatile Map<String, String> signatures = Map.of();

    static Map<String, String> signatures() {
        return signatures;
    }

    /** This mob's signature tell for its age, or null for the plain lean. */
    public static String signature(Entity e) {
        return lookup(signatures, e);
    }

    /** This mob's wind-up seconds for its age, or null for the default. */
    public static Double windUp(Entity e) {
        return lookup(windUps, e);
    }

    /** This mob's attack interval in seconds for its age, or null for its behaviour's own. */
    public static Double interval(Entity e) {
        return lookup(intervals, e);
    }

    /** This mob's extra reach and forward reach for its age, 0 for none. */
    public static double reach(Entity e) {
        Double v = lookup(reaches, e);
        return v == null ? 0 : v;
    }

    public static double reachForward(Entity e) {
        Double v = lookup(forwards, e);
        return v == null ? 0 : v;
    }

    /** This mob's signature tell, or null for the plain lean. */
    public static String signature(EntityType<?> type) {
        return signatures.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    /** Seconds a mob stops for before a melee hit, by exact entity id, from {@code wind_up} in mob_attacks (tags are not matched). */
    private static volatile Map<String, Double> windUps = Map.of();

    static Map<String, Double> windUps() {
        return windUps;
    }

    /** This mob's wind-up in seconds, or null for the default. */
    public static Double windUp(EntityType<?> type) {
        return windUps.get(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    private static boolean matches(List<String> ids, EntityType<?> type) {
        for (String id : ids) {
            if (id.startsWith("#") ? type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(id.substring(1))))
                    : BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Product of every matching modifier: above 1 is weak to the attack, below 1 resists it. */
    static double multiplier(List<Mod> mods, Collection<String> categories) {
        double m = 1;
        for (Mod mod : mods) {
            if (!Collections.disjoint(mod.categories(), categories)) m *= mod.multiplier();
        }
        return m;
    }

    public static double multiplier(LivingEntity victim, Collection<String> categories) {
        double m = 1;
        for (Profile p : profiles) {
            if (matches(p.entities(), victim.getType())) m *= multiplier(p.modifiers(), categories);
        }
        return m;
    }

    /** Props for a mob's own attack or projectile, matched on the attacker's or the damaging entity's type. */
    public static Props attackProps(Entity attacker, Entity direct) {
        boolean baby = attacker instanceof LivingEntity l && l.isBaby();
        for (MobAttack a : mobAttacks) {
            if (matches(a.entities(), attacker.getType()) && ageMatches(a.age(), baby) || (direct != null && direct != attacker && matches(a.entities(), direct.getType()))) {
                return a.props();
            }
        }
        return null;
    }

    /** Applies profile attributes (e.g. stun resistance) when an entity enters the world. */
    @SubscribeEvent
    static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof LivingEntity living)) return;
        for (Profile p : profiles) {
            if (!matches(p.entities(), living.getType())) continue;
            p.attributes().forEach((id, value) -> BuiltInRegistries.ATTRIBUTE.getHolder(id).ifPresent(holder -> {
                var instance = living.getAttribute(holder);
                ResourceLocation modId = ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "profile_" + id.getNamespace() + "_" + id.getPath());
                if (instance != null && !instance.hasModifier(modId)) {
                    instance.addTransientModifier(new AttributeModifier(modId, value, AttributeModifier.Operation.ADD_VALUE));
                }
            }));
        }
    }
}
