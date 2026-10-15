package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * What weapon mods already say about their weapons, read as plain data so any of them works without work on their side: Better Combat's
 * {@code data/<namespace>/weapon_attributes/<item>.json} (the weapon's {@code category}, {@code two_handed} flag and {@code attack_range}, with {@code parent} presets merged child
 * over parent; nothing of its attacks or animations is used, and Better Combat itself is not needed). A pack that overrides those files, such as one that makes weapons one-handed,
 * changes what is read. A {@code parent} that is missing because it is one of Better Combat's own presets ({@code bettercombat:glaive}) still tells the type: the preset is named
 * after it. Our own sets ({@link Attacks}) are asked first and can say otherwise.
 */
public final class WeaponData {
    private WeaponData() {
    }

    /** What is known about one weapon; any field may be null for unknown. */
    public record Entry(String type, Boolean twoHanded, Double range) {
        static final Entry NONE = new Entry(null, null, null);

        boolean empty() {
            return type == null && twoHanded == null && range == null;
        }
    }

    /** One file's own fields, before its parent is merged in. */
    record Raw(ResourceLocation parent, Entry own) {
    }

    private static volatile Map<ResourceLocation, Entry> entries = Map.of();

    /** A parent chain longer than this is cut (Better Combat's own are two deep). */
    private static final int DEPTH = 16;

    /** Reads one {@code weapon_attributes} object: its {@code parent} and the fields of {@code attributes} that matter here; a field of the wrong kind says nothing. */
    static Raw parse(JsonObject o) {
        String parent = string(o, "parent");
        JsonObject a = o.has("attributes") && o.get("attributes").isJsonObject() ? o.getAsJsonObject("attributes") : new JsonObject();
        String type = string(a, "category");
        JsonElement two = a.get("two_handed"), range = a.get("attack_range");
        return new Raw(parent == null ? null : ResourceLocation.tryParse(parent), new Entry(type == null ? null : type.toLowerCase(Locale.ROOT),
                two != null && two.isJsonPrimitive() && two.getAsJsonPrimitive().isBoolean() ? two.getAsBoolean() : null,
                range != null && range.isJsonPrimitive() && range.getAsJsonPrimitive().isNumber() ? range.getAsDouble() : null));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /** {@code id} with its parents merged under it. {@code path} is the chain so far (a parent already on it is ignored, so a cycle ends). */
    static Entry resolve(ResourceLocation id, Map<ResourceLocation, Raw> raw, Set<ResourceLocation> path) {
        Raw r = raw.get(id);
        if (r == null) { // a missing parent: Better Combat's own presets are named after the weapon type
            return id.getNamespace().equals("bettercombat") ? new Entry(id.getPath(), null, null) : Entry.NONE;
        }
        Entry base = Entry.NONE;
        if (r.parent() != null && path.size() < DEPTH && path.add(r.parent())) base = resolve(r.parent(), raw, path);
        Entry own = r.own();
        return new Entry(own.type() != null ? own.type() : base.type(), own.twoHanded() != null ? own.twoHanded() : base.twoHanded(), own.range() != null ? own.range() : base.range());
    }

    /** Every file's resolved entry. */
    static Map<ResourceLocation, Entry> resolveAll(Map<ResourceLocation, Raw> raw) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        for (ResourceLocation id : raw.keySet()) {
            Set<ResourceLocation> path = new HashSet<>();
            path.add(id);
            Entry e = resolve(id, raw, path);
            if (!e.empty()) out.put(id, e);
        }
        return out;
    }

    /** From the reload listener: the files of the {@code weapon_attributes} directory by id. A file that is not an object is skipped. */
    public static void load(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, Raw> raw = new HashMap<>();
        files.forEach((id, json) -> {
            try {
                if (json.isJsonObject()) raw.put(id, parse(json.getAsJsonObject()));
            } catch (RuntimeException e) {
                VectorCombat.LOG.warn("Skipping bad weapon_attributes file {}", id, e);
            }
        });
        entries = Map.copyOf(resolveAll(raw));
    }

    /** What is known about this item, or null. */
    public static Entry of(Item item) {
        return entries.get(BuiltInRegistries.ITEM.getKey(item));
    }

    public static boolean twoHanded(Item item) {
        Entry e = of(item);
        return e != null && Boolean.TRUE.equals(e.twoHanded());
    }

    /** The weapon's type in Better Combat's words ({@code claymore}, {@code dagger}), or null. */
    public static String type(Item item) {
        Entry e = of(item);
        return e == null ? null : e.type();
    }

    public static int count() {
        return entries.size();
    }

    // ---- to the client ----

    /** One line per weapon item that has something to say, {@code id|type|two_handed|range} with empty fields for unknown, for {@link SyncPayload}. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        entries.forEach((id, e) -> {
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                out.add(id + "|" + (e.type() == null ? "" : e.type()) + "|" + (e.twoHanded() == null ? "" : e.twoHanded() ? "1" : "0") + "|" + (e.range() == null ? "" : String.valueOf(e.range())));
            }
        });
        return out;
    }

    /** The client's side of {@link #lines}; a line that does not parse is skipped. */
    public static void loadLines(List<String> lines) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        for (String line : lines) {
            try {
                String[] f = line.split("\\|", -1);
                ResourceLocation id = ResourceLocation.tryParse(f[0]);
                if (id == null || f.length < 4) continue;
                Entry e = new Entry(f[1].isEmpty() ? null : f[1], f[2].isEmpty() ? null : f[2].equals("1"), f[3].isEmpty() ? null : Double.parseDouble(f[3]));
                if (!e.empty()) out.put(id, e);
            } catch (RuntimeException e) {
                VectorCombat.LOG.warn("Skipping bad weapon line {}", line);
            }
        }
        entries = Map.copyOf(out);
    }

    // ---- what the item's own attributes say (needs no player) ----

    /** A bare hand's reach in blocks, vanilla's base for the player's entity interaction range: what a weapon's own reach modifier adds to. */
    public static final double HAND_REACH = 3.0;

    /**
     * The player's attack damage, attack speed and reach with this item in the main hand: vanilla's bases (1, 4 and 3) plus the item's additive modifiers. Read through NeoForge's
     * {@code getAttributeModifiers}, as the game does: the stack's component, else the item's own {@code getDefaultAttributeModifiers} (mods that compute their numbers in code), then
     * {@code ItemAttributeModifierEvent} (mods that add them by event), so a weapon counts whichever way its mod gives it damage.
     */
    public record Stats(double damage, double speed, double reach) {
    }

    public static Stats stats(ItemStack stack) {
        double damage = 1.0, speed = 4.0, reach = HAND_REACH;
        for (ItemAttributeModifiers.Entry e : stack.getAttributeModifiers().modifiers()) {
            if (!e.slot().test(EquipmentSlot.MAINHAND) || e.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) continue;
            double amount = e.modifier().amount();
            if (e.attribute().value() == Attributes.ATTACK_DAMAGE.value()) damage += amount;
            else if (e.attribute().value() == Attributes.ATTACK_SPEED.value()) speed += amount;
            else if (e.attribute().value() == Attributes.ENTITY_INTERACTION_RANGE.value()) reach += amount;
        }
        return new Stats(damage, speed, reach);
    }

    /**
     * The numbers of a stack that is a weapon (it carries a main-hand attack-damage modifier, as {@link Attacks#isWeapon} counts one), else null: what a mob's wind-up and reach
     * follow. A stick, a torch, a fishing rod and an empty hand are not weapons, so a mob holding one keeps its defaults.
     */
    public static Stats weapon(ItemStack stack) {
        for (ItemAttributeModifiers.Entry e : stack.getAttributeModifiers().modifiers()) {
            if (e.slot().test(EquipmentSlot.MAINHAND) && e.attribute().value() == Attributes.ATTACK_DAMAGE.value()) return stats(stack);
        }
        return null;
    }

    /** The attack speed of a weapon, else null ({@link #weapon}). */
    public static Double weaponSpeed(ItemStack stack) {
        Stats w = weapon(stack);
        return w == null ? null : w.speed();
    }

    /** How far beyond a bare hand's reach a weapon reaches (a spear's +2), 0 for anything else and for a weapon that reaches less. */
    public static double extraReach(ItemStack stack) {
        Stats w = weapon(stack);
        return w == null ? 0 : Math.max(0, w.reach() - HAND_REACH);
    }
}
