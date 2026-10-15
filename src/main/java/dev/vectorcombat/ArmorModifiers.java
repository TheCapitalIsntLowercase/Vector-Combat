package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/** Attribute modifiers on armor from {@code vectorcombat/armor} data, used for per-tier stun resistance. */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class ArmorModifiers {
    private record Rule(List<String> match, ResourceLocation attribute, double amount) {
    }

    private static volatile List<Rule> rules = List.of();

    public static void load(List<JsonElement> files) {
        List<Rule> out = new ArrayList<>();
        for (JsonElement file : files) {
            for (JsonObject o : Attacks.objects(file)) {
                out.add(new Rule(Profiles.strings(o, "match"), ResourceLocation.parse(o.get("attribute").getAsString()), o.get("amount").getAsDouble()));
            }
        }
        rules = List.copyOf(out);
    }

    private static boolean matches(Rule r, ItemStack stack) {
        for (String m : r.match()) {
            if (m.startsWith("#") ? stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(m.substring(1))))
                    : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(m)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    static void modifiers(ItemAttributeModifierEvent e) {
        if (!(e.getItemStack().getItem() instanceof ArmorItem armor)) return;
        for (Rule r : rules) {
            if (!matches(r, e.getItemStack())) continue;
            // One id per slot, so a full set stacks instead of colliding.
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, r.attribute().getPath() + "." + armor.getEquipmentSlot().getName());
            BuiltInRegistries.ATTRIBUTE.getHolder(r.attribute()).ifPresent(holder -> e.addModifier(holder,
                    new AttributeModifier(id, r.amount(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.bySlot(armor.getEquipmentSlot())));
        }
    }
}
