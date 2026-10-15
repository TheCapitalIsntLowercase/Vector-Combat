package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Vanilla's mob melee hit ({@code Mob.doHurtTarget}) applies the weapon's enchantments but never its item's own on-hit code ({@code ItemStack.hurtEnemy}), where most weapon mods
 * put their passives (Basic Weapons' club slows, its hammer knocks up), so a mob's weapon did only damage. After a mob's melee hit lands, this runs the weapon's {@code hurtEnemy}
 * ({@code Item.hurtEnemy}, the item's own code: {@code ItemStack.hurtEnemy}, where mixins such as Jaam's traits sit, takes a player) as a player's hit does
 * ({@code [mobs] weaponPassives}). Mods that already give mobs their passives themselves are skipped, by data ({@code data/<ns>/vectorcombat/mob_passives/*.json}:
 * {@code skip_namespaces}, items of those mods; {@code skip_tag_prefixes}, items in a tag starting so): Jaam's Weaponry handles mob hits itself. A mob's weapon takes no wear
 * from it (vanilla mobs' weapons never wear): its damage is put back.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobPassives {
    private MobPassives() {
    }

    private static volatile Set<String> skipNamespaces = Set.of();
    private static volatile List<String> skipTagPrefixes = List.of();
    private static boolean running;

    static void load(List<JsonElement> files) {
        Set<String> ns = new HashSet<>();
        List<String> tags = new ArrayList<>();
        for (JsonElement f : files) {
            try {
                JsonObject o = f.getAsJsonObject();
                if (o.has("skip_namespaces")) o.getAsJsonArray("skip_namespaces").forEach(e -> ns.add(e.getAsString()));
                if (o.has("skip_tag_prefixes")) o.getAsJsonArray("skip_tag_prefixes").forEach(e -> tags.add(e.getAsString()));
            } catch (RuntimeException ex) {
                VectorCombat.LOG.warn("Skipping a bad mob_passives file: {}", ex.toString());
            }
        }
        skipNamespaces = Set.copyOf(ns);
        skipTagPrefixes = List.copyOf(tags);
    }

    /** Whether an item of {@code namespace} in tags {@code tags} is left to its own mod: its namespace is listed, or one of its tags starts with a listed prefix. */
    static boolean skipped(String namespace, Iterable<String> tags, Set<String> namespaces, List<String> prefixes) {
        if (namespaces.contains(namespace)) return true;
        for (String t : tags) for (String p : prefixes) if (t.startsWith(p)) return true;
        return false;
    }

    @SubscribeEvent
    static void landed(LivingDamageEvent.Post e) {
        DamageSource src = e.getSource();
        if (running || !(src.getDirectEntity() instanceof Mob m) || src.getEntity() != m || !(src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.MOB_ATTACK_NO_AGGRO))
                || !CommonConfig.MOB_PASSIVES.get()) return;
        ItemStack weapon = m.getMainHandItem();
        if (weapon.isEmpty()) return;
        String id = BuiltInRegistries.ITEM.getKey(weapon.getItem()).toString();
        List<String> tags = weapon.getTags().map(t -> t.location().toString()).toList();
        if (skipped(id.substring(0, id.indexOf(':')), tags, skipNamespaces, skipTagPrefixes)) return;
        LivingEntity target = e.getEntity();
        running = true;
        try { // the item's own code (ItemStack.hurtEnemy takes a player), on a copy: wear it deals itself, even a break, never reaches the mob's real weapon
            weapon.getItem().hurtEnemy(weapon.copy(), target, m);
        } finally {
            running = false;
        }
        Diag.event("mob_passive", "mob", m.getType().getDescriptionId(), "weapon", id, "victim", target.getType().getDescriptionId(), "tick", m.level().getGameTime());
    }
}
