package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * What weapon mods ship about their weapons and how it is used: Better Combat format files read as plain data (parents, overrides, cycles, missing presets), the two-handed
 * decision (our sets first, then the data), the item's own speed and damage, and the attack tooltip's words. Run with {@code java -ea <attacks dir>}.
 */
public final class WeaponDataTest {
    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    private static void near(double actual, double expected, double tolerance, String what) {
        assert Math.abs(actual - expected) <= tolerance : what + ": " + actual + " instead of " + expected;
    }

    private static void eq(Object actual, Object expected, String what) {
        assert java.util.Objects.equals(actual, expected) : what + ": " + actual + " instead of " + expected;
    }

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        // ---- reading the files: the shapes real mods ship ----
        Map<ResourceLocation, JsonElement> files = new HashMap<>();
        // Jaam's Weaponry: a preset and an item that only names it as its parent
        files.put(id("jaams_weaponry:jaams_presets/greatsword"), JsonParser.parseString("{\"attributes\": {\"attack_range\": 2.75, \"two_handed\": false, \"category\": \"claymore\", \"attacks\": [{\"hitbox\": \"HORIZONTAL_PLANE\", \"damage_multiplier\": 0.75}]}}"));
        files.put(id("jaams_weaponry:copper_greatsword"), JsonParser.parseString("{\"parent\": \"jaams_weaponry:jaams_presets/greatsword\"}"));
        // a child that overrides one field of its parent and keeps the rest
        files.put(id("test:claymore"), JsonParser.parseString("{\"attributes\": {\"category\": \"Claymore\", \"two_handed\": true, \"attack_range\": 3.5}}"));
        files.put(id("test:light"), JsonParser.parseString("{\"parent\": \"test:claymore\", \"attributes\": {\"two_handed\": false}}"));
        files.put(id("test:bare"), JsonParser.parseString("{}"));
        // a cycle ends; a parent that is not there is ignored, and a Better Combat preset that is not there still names the type
        files.put(id("test:a"), JsonParser.parseString("{\"parent\": \"test:b\"}"));
        files.put(id("test:b"), JsonParser.parseString("{\"parent\": \"test:a\", \"attributes\": {\"category\": \"x\"}}"));
        files.put(id("test:orphan"), JsonParser.parseString("{\"parent\": \"gone:thing\", \"attributes\": {\"category\": \"axe\"}}"));
        files.put(id("test:staffy"), JsonParser.parseString("{\"parent\": \"bettercombat:glaive\"}"));
        files.put(id("test:array"), JsonParser.parseString("[1, 2]"));
        files.put(id("test:odd"), JsonParser.parseString("{\"parent\": 5, \"attributes\": {\"category\": [], \"two_handed\": \"yes\"}}"));
        // two real vanilla items for the item-keyed checks below
        files.put(id("minecraft:iron_sword"), JsonParser.parseString("{\"attributes\": {\"category\": \"sword\", \"two_handed\": false, \"attack_range\": 2.5}}"));
        files.put(id("minecraft:diamond_sword"), JsonParser.parseString("{\"attributes\": {\"two_handed\": true}}"));
        files.put(id("minecraft:netherite_sword"), JsonParser.parseString("{\"attributes\": {\"category\": \"claymore\", \"two_handed\": true}}"));
        WeaponData.load(files);
        Map<ResourceLocation, WeaponData.Entry> all = new HashMap<>();
        // resolved entries are read through the item lookup, so the preset names are checked by loading a map of raw files directly
        Map<ResourceLocation, WeaponData.Raw> raw = new HashMap<>();
        files.forEach((k, v) -> {
            if (v.isJsonObject()) {
                try {
                    raw.put(k, WeaponData.parse(v.getAsJsonObject()));
                } catch (RuntimeException e) {
                    // the odd file is skipped, as load does
                }
            }
        });
        all.putAll(WeaponData.resolveAll(raw));
        WeaponData.Entry greatsword = all.get(id("jaams_weaponry:copper_greatsword"));
        eq(greatsword.type(), "claymore", "an item takes its parent preset's category");
        eq(greatsword.twoHanded(), false, "and its two_handed false, which stays false");
        near(greatsword.range(), 2.75, 1e-9, "and its attack range");
        WeaponData.Entry light = all.get(id("test:light"));
        eq(light.type(), "claymore", "a child keeps what it does not override (lower case)");
        eq(light.twoHanded(), false, "a child overrides its parent's two_handed");
        near(light.range(), 3.5, 1e-9, "and keeps the range");
        assert all.get(id("test:bare")) == null : "a file with nothing to say has no entry";
        eq(all.get(id("test:a")).type(), "x", "a cycle ends with what the chain gave");
        eq(all.get(id("test:b")).type(), "x", "from either end");
        eq(all.get(id("test:orphan")).type(), "axe", "a parent that is missing is ignored");
        eq(all.get(id("test:staffy")).type(), "glaive", "a missing bettercombat preset names the type");
        assert all.get(id("test:staffy")).twoHanded() == null && all.get(id("test:staffy")).range() == null : "and says nothing else";
        assert all.get(id("test:array")) == null && all.get(id("test:odd")) == null : "files that are not shaped like weapon data say nothing";

        // ---- by item, and to the client and back ----
        eq(WeaponData.type(Items.IRON_SWORD), "sword", "the entry of a real item");
        assert !WeaponData.twoHanded(Items.IRON_SWORD) && WeaponData.twoHanded(Items.DIAMOND_SWORD) && WeaponData.twoHanded(Items.NETHERITE_SWORD) && !WeaponData.twoHanded(Items.STONE_SWORD) : "two-handed by data";
        List<String> lines = WeaponData.lines();
        assert lines.size() == 3 : "only registered items with something to say are sent: " + lines;
        WeaponData.loadLines(lines);
        eq(WeaponData.type(Items.NETHERITE_SWORD), "claymore", "the client reads the lines back");
        near(WeaponData.of(Items.IRON_SWORD).range(), 2.5, 1e-9, "range too");
        assert WeaponData.of(Items.DIAMOND_SWORD).type() == null && WeaponData.twoHanded(Items.DIAMOND_SWORD) : "unknown stays unknown";
        WeaponData.loadLines(List.of("garbage", "minecraft:stone_sword|||", "minecraft:stone_sword|axe|x|nope", "minecraft:wooden_sword|axe|1|3.0"));
        assert WeaponData.of(Items.STONE_SWORD) == null && WeaponData.twoHanded(Items.WOODEN_SWORD) : "lines that do not parse are skipped";
        WeaponData.load(files); // back to the full data

        // ---- two-handed: our sets first (true or false), then the data, else no ----
        Attacks.load(List.of("[{\"match\": [\"minecraft:iron_sword\"], \"two_handed\": true}, {\"match\": [\"minecraft:diamond_sword\"], \"two_handed\": false},"
                + " {\"match\": [\"minecraft:golden_sword\"], \"defaults\": {\"categories\": [\"slash\"]}}]"));
        assert Attacks.twoHanded(new ItemStack(Items.IRON_SWORD)) : "a set saying true wins over data saying false";
        assert !Attacks.twoHanded(new ItemStack(Items.DIAMOND_SWORD)) : "a set saying false wins over data saying true";
        assert Attacks.twoHanded(new ItemStack(Items.NETHERITE_SWORD)) : "data alone decides when no set says";
        assert !Attacks.twoHanded(new ItemStack(Items.STONE_SWORD)) : "no word anywhere is one-handed";
        assert !Attacks.twoHanded(new ItemStack(Items.GOLDEN_SWORD)) : "a set with no two_handed does not say";

        // ---- @type: matches the type word the weapon data gives an item, whatever the case ----
        WeaponData.loadLines(List.of("minecraft:iron_sword|mace||", "minecraft:golden_sword|axe||", "minecraft:stone_sword|sword||"));
        Attacks.load(List.of("[{\"match\": [\"@type:MACE\"], \"defaults\": {\"categories\": [\"bash\"]}}, {\"match\": [\"@type:axe\", \"@type:lance\"], \"defaults\": {\"categories\": [\"cleave\"]}}]"));
        eq(Attacks.defaultsFor(new ItemStack(Items.IRON_SWORD)).categories(), List.of("bash"), "an item the data calls a mace matches @type:mace, whatever the case");
        eq(Attacks.defaultsFor(new ItemStack(Items.GOLDEN_SWORD)).categories(), List.of("cleave"), "a set may list several types");
        assert Attacks.defaultsFor(new ItemStack(Items.STONE_SWORD)) == null : "a type no set names matches nothing";
        assert Attacks.defaultsFor(new ItemStack(Items.WOODEN_SWORD)) == null && Attacks.defaultsFor(new ItemStack(Items.STICK)) == null : "an item with no weapon data has no type";
        WeaponData.load(files); // back to the full data

        // ---- the item's own numbers: vanilla's bases plus the additive modifiers on the component ----
        WeaponData.Stats sword = WeaponData.stats(new ItemStack(Items.IRON_SWORD));
        near(sword.speed(), 1.6, 1e-6, "iron sword speed");
        near(sword.damage(), 6, 1e-6, "iron sword damage");
        near(sword.reach(), 3, 1e-6, "no reach modifier");
        near(WeaponData.stats(new ItemStack(Items.IRON_AXE)).speed(), 0.9, 1e-6, "iron axe speed");
        near(WeaponData.stats(new ItemStack(Items.MACE)).speed(), 0.6, 1e-6, "mace speed");
        near(WeaponData.stats(new ItemStack(Items.TRIDENT)).speed(), 1.1, 1e-6, "trident speed");
        near(WeaponData.stats(new ItemStack(Items.STICK)).speed(), 4, 1e-6, "an item with no modifiers is the bare hand");

        // ---- weapon, speed and reach, for a mob's wind-up and reach: only a weapon has them ----
        assert WeaponData.weapon(new ItemStack(Items.IRON_SWORD)) != null && WeaponData.weapon(new ItemStack(Items.STICK)) == null && WeaponData.weapon(ItemStack.EMPTY) == null : "a weapon carries a main-hand attack-damage modifier";
        near(WeaponData.weaponSpeed(new ItemStack(Items.IRON_SWORD)), 1.6, 1e-6, "a weapon's speed");
        assert WeaponData.extraReach(new ItemStack(Items.IRON_SWORD)) == 0 && WeaponData.extraReach(new ItemStack(Items.STICK)) == 0 && WeaponData.extraReach(ItemStack.EMPTY) == 0 : "no extra reach without a reach modifier";
        for (double[] c : new double[][] {{2.0, 2.0}, {1.25, 1.25}, {-0.5, 0.0}}) {
            ItemStack reaching = new ItemStack(Items.IRON_SWORD);
            reaching.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                    .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(id("test:damage"), 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                    .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(id("test:reach"), c[0], AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
            near(WeaponData.extraReach(reaching), c[1], 1e-9, "a reach modifier of " + c[0] + " adds " + c[1] + " (never less than none)");
        }
        ItemStack reachOnly = new ItemStack(Items.STICK);
        reachOnly.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(id("test:reach"), 2, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND).build());
        assert WeaponData.weapon(reachOnly) == null && WeaponData.extraReach(reachOnly) == 0 : "reach alone makes no weapon";
        ItemStack offHand = new ItemStack(Items.IRON_SWORD);
        offHand.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(id("test:damage"), 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(id("test:reach"), 2, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.OFFHAND).build());
        assert WeaponData.extraReach(offHand) == 0 : "a reach that applies in the other hand does not count";

        // ---- the tooltip's words, for every shipped attack ----
        List<String> shipped = new ArrayList<>();
        try (Stream<Path> dir = Files.list(Path.of(args[0]))) {
            for (Path p : dir.filter(f -> f.toString().endsWith(".json")).toList()) shipped.add(Files.readString(p));
        }
        Attacks.load(shipped);
        WeaponData.load(Map.of()); // no weapon data: what any mod's weapon gets with nothing written for it (the test has no tags bound either)
        eq(Attacks.defaultsFor(new ItemStack(Items.IRON_AXE)).categories(), List.of("cleave"), "an axe cleaves by its ability, tagged or not");
        eq(Attacks.defaultsFor(new ItemStack(Items.TRIDENT)).categories(), List.of("pierce"), "trident");
        assert Attacks.glob("*_axe", "jaams_weaponry:iron_battle_axe") && !Attacks.glob("*_axe", "minecraft:iron_pickaxe") && !Attacks.glob("*:lance*", "mod:balanced_blade")
                && !Attacks.glob("*_pike", "mod:iron_spike") && Attacks.glob("*_pike", "mod:iron_pike") : "id words that do not catch pickaxes, spikes or balance";
        List<AttackDef> attacks = Attacks.all();
        assert attacks.size() >= 6 : "the default weapon's attacks";
        for (int i = 0; i < attacks.size(); i++) {
            AttackText.Block b = AttackText.block(attacks.get(i), i, attacks.size(), null, false);
            assert b.lines().size() <= 4 : "at most four lines: " + b.lines();
            for (String line : b.lines()) {
                assert !line.contains("_") && !line.contains(":") && !line.contains("null") && !line.contains("[") : "no raw tokens: " + line;
            }
        }
        // the default weapon's six, in the order the tooltip cycles them
        int n = attacks.size();
        AttackText.Block falling = AttackText.block(attacks.get(0), 0, n, null, false);
        eq(falling.title(), "Falling Slash  1/" + n, "falling title");
        eq(falling.detail(), "Falling · Cleave · 1.6x damage · 120° arc", "falling detail");
        eq(falling.effects(), "Stuns 1.5 s · Tires you", "falling effects");
        eq(falling.hint(), "Space next · Shift hide", "hint");
        eq(AttackText.block(attacks.get(1), 1, n, null, false).detail(), "Rising · Slash · 1.2x damage · 100° arc", "rising detail");
        eq(AttackText.block(attacks.get(1), 1, n, null, false).effects(), "Launches", "rising effects");
        AttackText.Block lunge = AttackText.block(attacks.get(2), 2, n, null, false);
        eq(lunge.detail(), "Sprinting · Pierce · 1.3x damage · thrust", "lunge detail");
        eq(lunge.effects(), "Knocks back · Lunges you · Tires you", "lunge effects");
        AttackText.Block left = AttackText.block(attacks.get(3), 3, n, null, false);
        eq(left.detail(), "Strafing left · Slash · 0.9x damage · 150° arc", "left sweep detail");
        eq(left.effects(), "Pushes aside · Slides you", "left sweep effects");
        AttackText.Block standing = AttackText.block(attacks.get(n - 1), n - 1, n, null, false);
        eq(standing.title(), "Slash  " + n + "/" + n, "default title");
        eq(standing.detail(), "Standing or walking · 1x damage · 120° arc", "default detail: the category is the name, so it is left out");
        eq(standing.effects(), "", "no effects, no line");
        eq(AttackText.hint(1), "Shift hide", "a single attack has nothing to cycle");
        eq(AttackText.title(attacks.get(n - 1), 0, 1), "Slash", "and no count");
        eq(AttackText.kind("claymore", true), "Claymore · two-handed", "a two-handed weapon says so");
        eq(AttackText.kind("soul_knife", true), "Soul knife · two-handed", "type words read as words");
        eq(AttackText.kind(null, true), "Two-handed", "with no type");
        eq(AttackText.kind("dagger", false), "", "a one-handed weapon says nothing");
        eq(AttackText.block(attacks.get(0), 0, n, "claymore", true).lines().size(), 5, "kind adds its line");
        System.out.println("WeaponDataTest ok");
    }
}
