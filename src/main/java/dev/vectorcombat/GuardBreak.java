package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * A heavy hit breaks a guard it lands on: the hit is still blocked, then the guard is knocked down for {@code break_ticks} and its holder staggers {@code stagger} seconds
 * ({@code [guard] guardBreak}). A player's hit is heavy when the swing is at least {@code min_strength} charged and its attack is listed ({@code attacks}, by name: the
 * Falling Slash) or its weapon is ({@code items}, ids or {@code #tags}; {@code weapon_types}, the type {@link WeaponData#type} reads); a mob's when the mob is listed
 * ({@code mobs}) or its weapon is. Data: {@code data/<ns>/vectorcombat/guard_break/*.json} (the last file that sets each wins). A parry still beats any hit; an axe's
 * disable is vanilla's and {@link MobGuard}'s own. Both sides: a mob's guard ({@link MobGuard}) and a player's ({@link Guard}).
 */
final class GuardBreak {
    private GuardBreak() {
    }

    private static volatile Set<String> attacks = Set.of(), types = Set.of();
    private static volatile List<String> items = List.of(), mobs = List.of();
    private static volatile double minStrength = 0.9, stagger = 0.5;
    private static volatile int breakTicks = 60;

    static void load(List<JsonElement> files) {
        Set<String> a = new HashSet<>(), t = new HashSet<>();
        List<String> i = new ArrayList<>(), m = new ArrayList<>();
        double min = 0.9, st = 0.5;
        int ticks = 60;
        for (JsonElement f : files) {
            try {
                JsonObject o = f.getAsJsonObject();
                if (o.has("attacks")) { a.clear(); o.getAsJsonArray("attacks").forEach(e -> a.add(e.getAsString())); }
                if (o.has("weapon_types")) { t.clear(); o.getAsJsonArray("weapon_types").forEach(e -> t.add(e.getAsString())); }
                if (o.has("items")) { i.clear(); o.getAsJsonArray("items").forEach(e -> i.add(e.getAsString())); }
                if (o.has("mobs")) { m.clear(); o.getAsJsonArray("mobs").forEach(e -> m.add(e.getAsString())); }
                if (o.has("min_strength")) min = o.get("min_strength").getAsDouble();
                if (o.has("stagger")) st = o.get("stagger").getAsDouble();
                if (o.has("break_ticks")) ticks = o.get("break_ticks").getAsInt();
            } catch (RuntimeException e) {
                VectorCombat.LOG.warn("Skipping a bad guard_break file: {}", e.toString());
            }
        }
        attacks = Set.copyOf(a);
        types = Set.copyOf(t);
        items = List.copyOf(i);
        mobs = List.copyOf(m);
        minStrength = min;
        stagger = st;
        breakTicks = ticks;
    }

    // ---- the player's swing being struck now (set by Strike around its hits) ----

    private static ServerPlayer swinger;
    private static String attack;
    private static float strength;

    static void swinging(ServerPlayer p, String name, float s) {
        swinger = p;
        attack = name;
        strength = s;
    }

    static void done() {
        swinger = null;
        attack = null;
    }

    /** The rule for a player's swing: charged enough, and a heavy attack or a heavy weapon. */
    static boolean heavySwing(boolean listedAttack, boolean heavyWeapon, float strength, double min) {
        return strength >= min && (listedAttack || heavyWeapon);
    }

    private static boolean heavyWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String type = WeaponData.type(stack.getItem());
        if (type != null && types.contains(type)) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (String e : items) {
            if (e.startsWith("#") ? stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(e.substring(1)))) : e.equals(id)) return true;
        }
        return false;
    }

    private static boolean listedMob(Mob m) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
        for (String e : mobs) {
            if (e.startsWith("#") ? m.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(e.substring(1)))) : e.equals(id)) return true;
        }
        return false;
    }

    /** Whether the hit of {@code src} breaks a guard: off with the setting; a player's swing by its charge, attack and weapon; a mob by its kind and weapon. */
    static boolean breaks(DamageSource src) {
        if (!CommonConfig.GUARD_BREAK.get() || !(src.getDirectEntity() instanceof LivingEntity attacker) || src.getEntity() != attacker) return false;
        if (attacker == swinger) return heavySwing(attack != null && attacks.contains(attack), heavyWeapon(swinger.getMainHandItem()), strength, minStrength);
        return attacker instanceof Mob m && (listedMob(m) || heavyWeapon(m.getMainHandItem()));
    }

    static int breakTicks() {
        return breakTicks;
    }

    /** The guard of {@code defender} goes down after the blocked hit: lowered, its item on cooldown (a player's) or held down ({@link MobGuard}), a crack, a stagger. */
    static void breakGuard(LivingEntity defender, DamageSource src) {
        int ticks = breakTicks;
        boolean mobOnPlayer = defender instanceof net.minecraft.world.entity.player.Player && !(src.getDirectEntity() instanceof net.minecraft.world.entity.player.Player);
        double st = mobOnPlayer && !CommonConfig.GUARD_BREAK_STAGGERS_PLAYERS.get() ? 0 : stagger; // in a crowd a stun is free hits: a mob's break only lowers your guard
        Combat.later(0, () -> { // after the hit: it was blocked, the break comes with it
            if (!defender.isAlive()) return;
            ItemStack used = defender.getUseItem();
            if (defender instanceof ServerPlayer p && !used.isEmpty()) p.getCooldowns().addCooldown(used.getItem(), ticks);
            if (defender instanceof Mob m) MobGuard.knockDown(m, ticks);
            defender.stopUsingItem();
            defender.level().playSound(null, defender.getX(), defender.getY(), defender.getZ(), SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1f, 0.7f + defender.getRandom().nextFloat() * 0.2f);
            if (st > 0) Stuns.apply(defender, st);
        });
        Diag.event("guard_break", "defender", defender.getType().getDescriptionId(), "attacker", src.getDirectEntity() == null ? null : src.getDirectEntity().getType().getDescriptionId(),
                "attack", src.getDirectEntity() == swinger ? attack : null, "breakTicks", ticks, "staggerS", st, "tick", defender.level().getGameTime());
    }
}
