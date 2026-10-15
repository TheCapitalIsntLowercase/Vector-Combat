package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbility;

/**
 * Datapack-loaded weapon sets ({@code data/<ns>/vectorcombat/attacks/*.json}, one set or an array of sets per file; {@code two_handed} true or false overrides what the weapon's own data says).
 * A set says which items it covers ({@code match}: item id (a {@code *} in it is a wildcard: {@code *spear*}), {@code #tag}, {@code @type:<word>} for every item the weapon data
 * ({@link WeaponData}) calls that type ({@code @type:mace}), {@code @can:<ability>} for every item that answers to a NeoForge item ability ({@code @can:axe_dig}: any mod's axe,
 * tagged or not), or {@code @weapon} for any item with a
 * main-hand attack-damage modifier that is not tagged {@code vectorcombat:ignored}), the movement attacks they gain, a
 * {@code default_attack} used when no movement attack applies, and {@code defaults} for categories and penalties.
 * A set with only {@code defaults} (no attacks) just tags the weapon's swings with a category; it never replaces the attacks of a lower-priority set.
 * A set may also carry {@code animations}: a clip id to the clip this weapon plays instead (the trident's lunge is {@code joust_trident}), so a weapon
 * whose model does not suit a clip gets its own without repeating the attack.
 */
public final class Attacks {
    private record AttackSet(List<String> match, int priority, Boolean twoHanded, Props defaults, AttackDef fallback, List<AttackDef> attacks, Map<String, String> animations) {
    }

    private static volatile List<AttackSet> sets = List.of();
    private static volatile List<String> raw = List.of();

    /** The JSON as loaded, for syncing to clients. */
    public static List<String> raw() {
        return raw;
    }

    /** A file holds one JSON object, or an array of them. */
    public static List<JsonObject> objects(JsonElement file) {
        List<JsonObject> out = new ArrayList<>();
        if (file.isJsonArray()) file.getAsJsonArray().forEach(e -> out.add(e.getAsJsonObject()));
        else out.add(file.getAsJsonObject());
        return out;
    }

    /**
     * The attack a hit counts as for the repeat penalty, by attack name: its {@code penalty_group} when the data names one (the two sweeps are one {@code sweep}, so
     * alternating them stacks like repeating one), else its own name.
     */
    private static volatile java.util.Map<String, String> groups = java.util.Map.of();
    private static java.util.Map<String, String> loading;

    public static String penaltyKey(String attack) {
        return groups.getOrDefault(attack, attack);
    }

    static java.util.Map<String, String> groups() {
        return groups;
    }

    public static void load(List<String> jsons) {
        loading = new java.util.LinkedHashMap<>();
        List<AttackSet> out = new ArrayList<>();
        for (String json : jsons) {
            try {
                for (JsonObject o : objects(JsonParser.parseString(json))) out.add(parse(o));
            } catch (RuntimeException e) {
                VectorCombat.LOG.error("Skipping bad attack file: {}", json, e);
            }
        }
        out.sort(Comparator.comparingInt(AttackSet::priority).reversed());
        sets = List.copyOf(out);
        raw = List.copyOf(jsons);
        groups = java.util.Map.copyOf(loading);
        loading = null;
    }

    private static double num(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    private static AttackDef attack(JsonObject a, Props base, boolean conditional) {
        List<List<Cond>> when = new ArrayList<>();
        if (conditional) {
            a.getAsJsonArray("when").forEach(group -> {
                List<Cond> and = new ArrayList<>();
                group.getAsJsonArray().forEach(c -> and.add(Cond.parse(c.getAsString())));
                when.add(List.copyOf(and));
            });
        }
        String animation = a.has("animation") ? a.get("animation").getAsString() : "slash_right";
        if (a.has("penalty_group") && loading != null) loading.put(a.has("name") ? a.get("name").getAsString() : animation, a.get("penalty_group").getAsString());
        return new AttackDef(a.has("name") ? a.get("name").getAsString() : animation, List.copyOf(when),
                num(a, "range", 1.0), num(a, "angle", 120), num(a, "damage", 1.0), num(a, "hit_at", 0.2), animation,
                a.has("sound") ? a.get("sound").getAsString() : "minecraft:entity.player.attack.sweep",
                (float) num(a, "pitch", 1.0), Props.parse(a).over(base), a.has("arrow") ? a.get("arrow").getAsString() : "", a.has("categories"), (int) num(a, "hitstop", -1),
                (int) num(a, "hitstop_bonus", 0), a.has("alternate") ? a.get("alternate").getAsString() : "");
    }

    private static AttackSet parse(JsonObject o) {
        List<String> match = new ArrayList<>();
        o.getAsJsonArray("match").forEach(e -> match.add(e.getAsString()));
        Props defaults = o.has("defaults") ? Props.parse(o.getAsJsonObject("defaults")) : null;
        Props base = (defaults == null ? Props.EMPTY : defaults).over(Props.BASE);
        List<AttackDef> attacks = new ArrayList<>();
        if (o.has("attacks")) {
            for (JsonElement e : o.getAsJsonArray("attacks")) attacks.add(attack(e.getAsJsonObject(), base, true));
        }
        AttackDef fallback = o.has("default_attack") ? attack(o.getAsJsonObject("default_attack"), base, false) : null;
        Map<String, String> animations = new HashMap<>();
        if (o.has("animations")) o.getAsJsonObject("animations").entrySet().forEach(e -> animations.put(e.getKey(), e.getValue().getAsString()));
        return new AttackSet(match, o.has("priority") ? o.get("priority").getAsInt() : 0,
                o.has("two_handed") ? o.get("two_handed").getAsBoolean() : null, defaults, fallback, List.copyOf(attacks), Map.copyOf(animations));
    }

    /** Items a pack tags {@code vectorcombat:ignored} are never matched by {@code @weapon}, so they keep their own attack code. */
    private static final TagKey<Item> IGNORED = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "ignored"));

    /** The {@code @weapon} match: swords, axes, mace, trident, tools and modded weapons all carry a main-hand attack-damage modifier. */
    static boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty() || stack.is(IGNORED)) return false;
        boolean[] found = {false};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> found[0] |= attribute.value() == Attributes.ATTACK_DAMAGE.value());
        return found[0];
    }

    /** True when {@code id} equals {@code pattern}, where each {@code *} stands for any run of characters: {@code *spear*} is every item with spear in its id. */
    static boolean glob(String pattern, String id) {
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) return id.equals(pattern);
        String first = parts[0], last = parts[parts.length - 1];
        if (id.length() < first.length() + last.length() || !id.startsWith(first) || !id.endsWith(last)) return false;
        int at = first.length(), end = id.length() - last.length();
        for (int i = 1; i < parts.length - 1; i++) {
            int found = id.indexOf(parts[i], at);
            if (found < 0 || found + parts[i].length() > end) return false;
            at = found + parts[i].length();
        }
        return true;
    }

    private static boolean matches(AttackSet set, ItemStack stack) {
        for (String m : set.match()) {
            if (m.equals("@weapon") ? isWeapon(stack)
                    : m.startsWith("@type:") ? m.substring(6).equalsIgnoreCase(WeaponData.type(stack.getItem()))
                    : m.startsWith("@can:") ? stack.canPerformAction(ItemAbility.get(m.substring(5)))
                    : m.startsWith("#") ? stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(m.substring(1))))
                    : glob(m, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The attack this weapon performs for this movement: the first movement attack that passes, else the set's
     * default attack. Null if the item is not a Vector weapon, which then swings like vanilla.
     */
    public static AttackDef select(ItemStack stack, MoveState move) {
        for (AttackSet set : sets) {
            if (!matches(set, stack)) continue;
            for (AttackDef a : set.attacks()) {
                if (a.matches(move)) return a;
            }
            if (set.fallback() != null) return set.fallback();
        }
        return null;
    }

    /** The clip this weapon plays for {@code def}: the first matching set's replacement for the attack's own clip, else the attack's own. */
    public static String animationFor(ItemStack stack, AttackDef def) {
        return animationFor(stack, def.animation());
    }

    /** The clip this weapon plays in place of {@code clip} (an attack's own or its alternate): the first matching set's replacement, else {@code clip}. */
    public static String animationFor(ItemStack stack, String clip) {
        for (AttackSet set : sets) {
            String mapped = set.animations().get(clip);
            if (mapped != null && matches(set, stack)) return mapped;
        }
        return clip;
    }

    /** Category and penalty properties for swings with this item, or null if no set covers it. */
    public static Props defaultsFor(ItemStack stack) {
        for (AttackSet set : sets) {
            if (set.defaults() != null && matches(set, stack)) return set.defaults().over(Props.BASE);
        }
        return null;
    }

    /** The attack's props, with the weapon's own categories (axes cleave, trident pierce, mace bash) when the attack names none. */
    public static Props propsFor(ItemStack stack, AttackDef def) {
        return withCategories(def.props(), def.ownCategories() ? null : defaultsFor(stack));
    }

    /** {@code attack} carrying the categories of {@code weapon}; unchanged when there is no weapon set, it names none, or they already agree. */
    static Props withCategories(Props attack, Props weapon) {
        return weapon == null || weapon.categories().isEmpty() || weapon.categories().equals(attack.categories()) ? attack
                : new Props(weapon.categories(), attack.effects(), attack.penalty(), attack.consecutive());
    }

    /**
     * Whether this item is held in two hands (then the off hand, a shield included, is locked: {@link TwoHanded}): the first matching set that says so, true or false, wins,
     * else the weapon's own data ({@link WeaponData}: Better Combat's {@code two_handed}), else no.
     */
    public static boolean twoHanded(ItemStack stack) {
        for (AttackSet set : sets) {
            if (set.twoHanded() != null && matches(set, stack)) return set.twoHanded();
        }
        return WeaponData.twoHanded(stack.getItem());
    }

    /** Every attack loaded, default attacks included, for tests. */
    static List<AttackDef> all() {
        List<AttackDef> out = new ArrayList<>();
        for (AttackSet s : sets) {
            out.addAll(s.attacks());
            if (s.fallback() != null) out.add(s.fallback());
        }
        return out;
    }

    /** Every attack this weapon has, the default last, for the tooltip. */
    public static List<AttackDef> attacksFor(ItemStack stack) {
        List<AttackDef> out = new ArrayList<>();
        for (AttackSet set : sets) {
            if (!matches(set, stack)) continue;
            out.addAll(set.attacks());
            if (set.fallback() != null) out.add(set.fallback());
        }
        return out;
    }
}
