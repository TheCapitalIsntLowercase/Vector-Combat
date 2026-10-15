package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Some melee humanoids spawn with a shield in the off hand, which {@link MobGuard} then guards with ({@code [mobs] spawnShields}). Which mobs,
 * how often and which shields come from data ({@code data/<ns>/vectorcombat/mob_shields/*.json}: {@code mobs}, ids or {@code #tags}; {@code chance}; {@code items}, {@code "auto"}
 * or a list of ids and {@code #tags}; {@code weights} by id, 1 when absent; {@code exclude}, ids or {@code #tags}; the last file that sets each wins).
 * <p>
 * {@code "auto"} is every registered shield, any mod's: an item that can block as a shield ({@code SHIELD_BLOCK}) or is in {@code #c:tools/shield}. Whatever the list, a shield the
 * pack has turned off is left out: one disabled by a feature flag, or by Item Obliterator ({@link ItemObliteratorCompat}); with none left, no mob gets one. The pool is worked
 * out once after each data load, at the first spawn, and logged ({@code mob_shield_pool}). Only on a fresh spawn (not a mob loaded from disk), after every other mod has
 * equipped it (the lowest priority of the join, which comes after {@code finalizeSpawn}), and never over another mod's equipment: the off hand must be empty, and a mob holding a
 * bow, a crossbow, a trident or a two-handed weapon ({@link Attacks#twoHanded}) is skipped, as is a baby.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobShields {
    private MobShields() {
    }

    static final TagKey<Item> SHIELDS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "tools/shield"));

    private static volatile List<String> mobs = List.of(), items = List.of(), exclude = List.of();
    private static volatile boolean auto = true;
    private static volatile Map<String, Integer> weights = Map.of();
    private static volatile double chance;

    /** The shields a mob may get and their weights, worked out at the first spawn after a load; null until then. */
    private record Pool(List<Item> items, int[] weights) {
    }

    private static volatile Pool pool;

    static void load(List<JsonElement> files) {
        List<String> m = new ArrayList<>(), it = new ArrayList<>(), ex = new ArrayList<>();
        boolean a = true;
        Map<String, Integer> w = new HashMap<>();
        double c = 0;
        for (JsonElement f : files) {
            try {
                JsonObject o = f.getAsJsonObject();
                if (o.has("mobs")) {
                    m.clear();
                    o.getAsJsonArray("mobs").forEach(e -> m.add(e.getAsString()));
                }
                if (o.has("chance")) c = o.get("chance").getAsDouble();
                if (o.has("item")) { // the first format: one item
                    a = false;
                    it.clear();
                    it.add(o.get("item").getAsString());
                }
                if (o.has("items")) {
                    JsonElement i = o.get("items");
                    it.clear();
                    a = i.isJsonPrimitive() && i.getAsString().equals("auto");
                    if (!a) i.getAsJsonArray().forEach(e -> it.add(e.getAsString()));
                }
                if (o.has("weights")) o.getAsJsonObject("weights").entrySet().forEach(e -> w.put(e.getKey(), e.getValue().getAsInt()));
                if (o.has("exclude")) {
                    ex.clear();
                    o.getAsJsonArray("exclude").forEach(e -> ex.add(e.getAsString()));
                }
            } catch (RuntimeException e) {
                VectorCombat.LOG.warn("Skipping a bad mob_shields file: {}", e.toString());
            }
        }
        mobs = List.copyOf(m);
        items = List.copyOf(it);
        exclude = List.copyOf(ex);
        auto = a;
        weights = Map.copyOf(w);
        chance = Math.max(0, Math.min(1, c));
        pool = null; // worked out again at the next spawn, when the tags of this load are bound
    }

    private static boolean matches(String entry, Item item, String id) {
        return entry.startsWith("#") ? new ItemStack(item).is(TagKey.create(Registries.ITEM, ResourceLocation.parse(entry.substring(1)))) : entry.equals(id);
    }

    /** Whether a mob of this kind may be given a shield: the listed ids or tags. */
    static boolean listed(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        for (String m : mobs) {
            if (m.startsWith("#") ? type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(m.substring(1)))) : m.equals(id)) return true;
        }
        return false;
    }

    /** Whether what the mob holds leaves room for a shield: an empty off hand, and nothing ranged or two-handed in the main hand. */
    static boolean room(boolean offEmpty, boolean ranged, boolean twoHanded) {
        return offEmpty && !ranged && !twoHanded;
    }

    /** The index a roll {@code r} (0 to 1) lands on among {@code weights}; -1 when they add up to nothing. */
    static int pick(int[] weights, double r) {
        int total = 0;
        for (int w : weights) total += Math.max(0, w);
        if (total <= 0) return -1;
        double at = r * total;
        for (int i = 0; i < weights.length; i++) {
            at -= Math.max(0, weights[i]);
            if (at < 0) return i;
        }
        return weights.length - 1;
    }

    private static Boolean obliterator;

    /** Whether the pack lets this item be used: enabled by its feature flags, and not disabled by Item Obliterator. Also asked by the arena's kit ({@link Arena}). */
    static boolean available(Item item, FeatureFlagSet features) {
        if (!item.isEnabled(features)) return false;
        if (obliterator == null) obliterator = ModList.get().isLoaded(ItemObliteratorCompat.ID);
        return !obliterator || !ItemObliteratorCompat.disabled(new ItemStack(item));
    }

    /** One shield from this load's pool, by its weights; empty when the pack has none. */
    static ItemStack randomShield(net.minecraft.server.level.ServerLevel level, net.minecraft.util.RandomSource random) {
        Pool p = pool(level.enabledFeatures());
        int i = pick(p.weights(), random.nextDouble());
        return i < 0 ? ItemStack.EMPTY : new ItemStack(p.items().get(i));
    }

    /** The shields for this load: the listed ones, or every shield of every mod, less the excluded and the ones the pack turned off. */
    private static Pool pool(FeatureFlagSet features) {
        Pool p = pool;
        if (p != null) return p;
        Set<Item> found = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            ItemStack stack = new ItemStack(item);
            boolean wanted = auto ? stack.canPerformAction(ItemAbilities.SHIELD_BLOCK) || stack.is(SHIELDS) : items.stream().anyMatch(e -> matches(e, item, id));
            if (!wanted || !MobGuard.guardItem(stack)) continue;
            if (exclude.stream().anyMatch(e -> matches(e, item, id)) || !available(item, features)) {
                dropped.add(id);
                continue;
            }
            found.add(item);
        }
        List<Item> list = List.copyOf(found);
        int[] w = new int[list.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.getOrDefault(BuiltInRegistries.ITEM.getKey(list.get(i)).toString(), 1);
        p = new Pool(list, w);
        pool = p;
        List<String> ids = list.stream().map(i -> BuiltInRegistries.ITEM.getKey(i).toString()).toList();
        VectorCombat.LOG.info("mob shields: chance {}, {} shields {} (left out: {})", chance, auto ? "auto," : "listed,", ids, dropped);
        Diag.event("mob_shield_pool", "auto", auto, "chance", chance, "shields", String.join(",", ids), "leftOut", dropped.isEmpty() ? null : String.join(",", dropped));
        return p;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || !(e.getEntity() instanceof Mob m) || !CommonConfig.MOB_SHIELDS.get() || chance <= 0 || m.isBaby()) return;
        if (m.isOnPortalCooldown()) return; // through a portal it joins the new level fresh, but it is no new spawn: no second roll
        if (!listed(m.getType())) return;
        ItemStack main = m.getMainHandItem();
        Item held = main.getItem();
        if (!room(m.getOffhandItem().isEmpty(), held instanceof ProjectileWeaponItem || held instanceof TridentItem, !main.isEmpty() && Attacks.twoHanded(main))) return;
        if (m.getRandom().nextDouble() >= chance) return;
        Pool p = pool(e.getLevel().enabledFeatures());
        int i = pick(p.weights(), m.getRandom().nextDouble());
        if (i < 0) return;
        Item shield = p.items().get(i);
        m.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(shield));
        Diag.event("mob_shield", "mob", m.getType().getDescriptionId(), "item", BuiltInRegistries.ITEM.getKey(shield).toString(), "main", BuiltInRegistries.ITEM.getKey(held).toString());
    }
}
