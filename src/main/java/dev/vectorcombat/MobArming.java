package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Weapons for newly spawned mobs, so zombies are armed even without a mod that hands weapons out: each mob new to the world (not one loaded from disk), its
 * main hand empty, rolls once against its {@code mob_arming} row: armed with {@code chance}, then one item by weight. AUTO ({@code [mobs] arming}) leaves it to Jaam's Weaponry
 * when that is installed (it arms mobs by its own tables); ON always, OFF never. A rolled mob is tagged so it never rolls again.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobArming {
    private MobArming() {
    }

    static final String ROLLED = "vectorcombat.armed_roll";

    /** A row: the mob ids or tags ({@code #}) it arms, the chance one is armed, the items and their weights, and whether babies are left bare. */
    record Entry(List<String> entities, double chance, List<String> items, List<Integer> weights, boolean adultsOnly) {
    }

    private static volatile List<Entry> entries = List.of();

    public static void load(List<JsonElement> files) {
        List<Entry> out = new ArrayList<>();
        for (JsonElement f : files) {
            for (JsonObject o : Attacks.objects(f)) {
                try {
                    List<String> items = new ArrayList<>();
                    List<Integer> weights = new ArrayList<>();
                    for (JsonElement i : o.getAsJsonArray("items")) {
                        JsonObject io = i.getAsJsonObject();
                        items.add(io.get("item").getAsString());
                        weights.add(io.has("weight") ? io.get("weight").getAsInt() : 1);
                    }
                    out.add(new Entry(Profiles.strings(o, "entities"), o.get("chance").getAsDouble(), List.copyOf(items), List.copyOf(weights),
                            !o.has("adults_only") || o.get("adults_only").getAsBoolean()));
                } catch (RuntimeException ex) {
                    VectorCombat.LOG.warn("Skipping bad mob_arming row {}", o, ex);
                }
            }
        }
        entries = List.copyOf(out);
    }

    static List<Entry> entries() {
        return entries;
    }

    // ---- pure ----

    /** Whether arming is on: ON always, OFF never, AUTO unless an arming mod is installed. */
    static boolean active(CommonConfig.ArmingMode mode, boolean armingModLoaded) {
        return mode == CommonConfig.ArmingMode.ON || mode == CommonConfig.ArmingMode.AUTO && !armingModLoaded;
    }

    /** The index picked by {@code roll} (0 to 1) over {@code weights}. */
    static int pick(List<Integer> weights, double roll) {
        int total = 0;
        for (int w : weights) total += Math.max(0, w);
        double at = roll * total;
        for (int i = 0; i < weights.size(); i++) {
            at -= Math.max(0, weights.get(i));
            if (at < 0) return i;
        }
        return weights.size() - 1;
    }

    // ---- the game ----

    private static boolean matches(String id, EntityType<?> type) {
        if (id.startsWith("#")) return type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(id.substring(1))));
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(id);
    }

    @SubscribeEvent
    static void joined(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || !(e.getEntity() instanceof Mob mob) || entries.isEmpty()) return;
        if (mob.getTags().contains(ROLLED)) return;
        Entry en = null;
        for (Entry x : entries) {
            for (String id : x.entities()) if (matches(id, mob.getType())) en = x;
            if (en != null) break;
        }
        if (en == null) return;
        mob.addTag(ROLLED);
        if (!active(CommonConfig.MOB_ARMING.get(), ModList.get().isLoaded(JaamsCompat.ID)) || !mob.getMainHandItem().isEmpty() || en.adultsOnly() && mob.isBaby()) return;
        if (mob.getRandom().nextDouble() >= en.chance() || en.items().isEmpty()) return;
        String id = en.items().get(pick(en.weights(), mob.getRandom().nextDouble()));
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(null);
        if (item == null) return;
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        Diag.event("arming", "mob", mob.getType().getDescriptionId(), "item", id);
    }
}
