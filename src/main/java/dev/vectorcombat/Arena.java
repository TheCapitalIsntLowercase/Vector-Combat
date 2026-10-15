package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /vcarena}: a playtest arena (op only). {@code start [scenario|random]} builds a walled pen around the player (a stone brick floor, glass walls, the air inside
 * cleared; every block it changes is saved and put back on {@code stop}; it refuses to build over chests or any other block entity), heals and kits the player
 * ({@code loadout}: armour, the listed weapons plus random weapons of any installed mod, a shield the pack has not disabled, extras) and sends the scenario's waves one
 * after another, each as soon as the last is dead; the end gives the time, the damage taken and the kills. {@code kit} gives the loadout alone, {@code list} names the
 * scenarios, {@code stop} clears the mobs and the pen. Scenarios and the loadout are data: {@code data/<ns>/vectorcombat/arena/*.json} ({@code scenarios}: name, about,
 * waves of {@code {"id", "count", "shield"}}; {@code loadout}). Mobs spawn as fresh spawns (so weapon mods and spawn shields equip them), never despawn, go for the player,
 * and piglins and hoglins do not zombify outside the Nether.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Arena {
    private Arena() {
    }

    /** Half the inside of the pen, in blocks: 17 by 17 inside. */
    static final int HALF = 8;
    /** Wall height and the air cleared inside, in blocks. */
    static final int WALL = 3, CLEAR = 6;
    /** Ticks between one wave's last death and the next wave. */
    static final int BETWEEN = 60;

    record Spawn(String id, int count, boolean shield) {
    }

    record Scenario(String name, String about, List<List<Spawn>> waves) {
    }

    private static volatile List<Scenario> scenarios = List.of();
    private static volatile List<String> armor = List.of(), always = List.of(), extras = List.of();
    private static volatile int randomWeapons = 3;
    private static volatile boolean shield = true;

    static void load(List<JsonElement> files) {
        Map<String, Scenario> found = new LinkedHashMap<>();
        for (JsonElement f : files) {
            try {
                JsonObject o = f.getAsJsonObject();
                if (o.has("scenarios")) for (JsonElement e : o.getAsJsonArray("scenarios")) {
                    JsonObject s = e.getAsJsonObject();
                    List<List<Spawn>> waves = new ArrayList<>();
                    for (JsonElement w : s.getAsJsonArray("waves")) {
                        List<Spawn> wave = new ArrayList<>();
                        for (JsonElement m : w.getAsJsonArray()) {
                            JsonObject mo = m.getAsJsonObject();
                            wave.add(new Spawn(mo.get("id").getAsString(), mo.has("count") ? mo.get("count").getAsInt() : 1, mo.has("shield") && mo.get("shield").getAsBoolean()));
                        }
                        waves.add(List.copyOf(wave));
                    }
                    String name = s.get("name").getAsString();
                    found.put(name, new Scenario(name, s.has("about") ? s.get("about").getAsString() : "", List.copyOf(waves)));
                }
                if (o.has("loadout")) {
                    JsonObject l = o.getAsJsonObject("loadout");
                    if (l.has("armor")) armor = strings(l, "armor");
                    if (l.has("weapons")) always = strings(l, "weapons");
                    if (l.has("extras")) extras = strings(l, "extras");
                    if (l.has("random_weapons")) randomWeapons = l.get("random_weapons").getAsInt();
                    if (l.has("shield")) shield = l.get("shield").getAsBoolean();
                }
            } catch (RuntimeException ex) {
                VectorCombat.LOG.warn("Skipping a bad arena file: {}", ex.toString());
            }
        }
        scenarios = List.copyOf(found.values());
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> l = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> l.add(e.getAsString()));
        return List.copyOf(l);
    }

    /** {@code "minecraft:arrow*32"}: the id and the count (1 without a star). */
    static String[] idAndCount(String entry) {
        int star = entry.lastIndexOf('*');
        return star < 0 ? new String[] {entry, "1"} : new String[] {entry.substring(0, star), entry.substring(star + 1)};
    }

    /** Where a wave's mob stands: on a ring 4 to {@code HALF - 1} blocks from the centre, at angle {@code a} (radians) and distance fraction {@code d} (0 to 1). */
    static int[] ringSpot(double a, double d) {
        double r = 4 + d * (HALF - 5);
        return new int[] {(int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r)};
    }

    // ---- the session ----

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos center;
        final Map<BlockPos, BlockState> saved;
        final Scenario scenario;
        final List<Mob> mobs = new ArrayList<>();
        int wave = -1, kills;
        long started, nextAt = -1;
        float damage;
        boolean done;

        Session(ServerPlayer player, ServerLevel level, BlockPos center, Map<BlockPos, BlockState> saved, Scenario scenario) {
            this.player = player;
            this.level = level;
            this.center = center;
            this.saved = saved;
            this.scenario = scenario;
        }
    }

    private static Session session;

    @SubscribeEvent
    static void commands(RegisterCommandsEvent e) {
        if (!Dev.on()) return;
        e.getDispatcher().register(Commands.literal("vcarena").requires(s -> s.hasPermission(2))
                .then(Commands.literal("start").executes(c -> start(c.getSource(), "random"))
                        .then(Commands.argument("scenario", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "scenario")))))
                .then(Commands.literal("stop").executes(c -> stop(c.getSource())))
                .then(Commands.literal("kit").executes(c -> kit(c.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("list").executes(c -> list(c.getSource()))));
    }

    private static List<String> names() {
        List<String> n = new ArrayList<>(scenarios.stream().map(Scenario::name).toList());
        n.add("random");
        return n;
    }

    private static int list(CommandSourceStack src) {
        for (Scenario s : scenarios) src.sendSuccess(() -> Component.literal(s.name() + " (" + s.waves().size() + " waves): " + s.about()), false);
        return scenarios.size();
    }

    private static int start(CommandSourceStack src, String name) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            src.sendFailure(Component.literal("the arena needs difficulty Easy or higher: on Peaceful hostile mobs vanish"));
            return 0;
        }
        if (scenarios.isEmpty()) {
            src.sendFailure(Component.literal("no arena scenarios loaded (data/<ns>/vectorcombat/arena)"));
            return 0;
        }
        Scenario sc = name.equals("random") ? scenarios.get(p.getRandom().nextInt(scenarios.size()))
                : scenarios.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        if (sc == null) {
            src.sendFailure(Component.literal("no scenario " + name + "; /vcarena list"));
            return 0;
        }
        if (session != null) end(false);
        BlockPos center = p.blockPosition();
        Map<BlockPos, BlockState> saved = build(level, center);
        if (saved == null) {
            src.sendFailure(Component.literal("there is a chest or another block entity where the arena would go; move to open ground"));
            return 0;
        }
        p.teleportTo(level, center.getX() + 0.5, center.getY(), center.getZ() + 0.5, p.getYRot(), p.getXRot());
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(10);
        p.clearFire();
        kit(p, false);
        Session s = new Session(p, level, center, saved, sc);
        s.started = level.getGameTime();
        session = s;
        p.sendSystemMessage(Component.literal("arena: " + sc.name() + " (" + sc.waves().size() + " waves). " + sc.about() + " /vcarena stop to end."));
        Diag.event("arena_start", "scenario", sc.name(), "waves", sc.waves().size(), "blocks", saved.size());
        nextWave(s);
        return 1;
    }

    /** Builds the pen round {@code center} (the player's feet): the floor below, the walls, the air inside; returns what each changed block was, or null over a block entity. */
    private static Map<BlockPos, BlockState> build(ServerLevel level, BlockPos center) {
        int r = HALF + 1;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -1; y < CLEAR; y++) {
            if (level.getBlockEntity(center.offset(x, y, z)) != null) return null;
        }
        Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState(), wall = Blocks.GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            boolean edge = Math.abs(x) == r || Math.abs(z) == r;
            set(level, saved, center.offset(x, -1, z), floor);
            for (int y = 0; y < CLEAR; y++) set(level, saved, center.offset(x, y, z), edge && y < WALL ? wall : air);
        }
        return saved;
    }

    private static void set(ServerLevel level, Map<BlockPos, BlockState> saved, BlockPos pos, BlockState to) {
        BlockState was = level.getBlockState(pos);
        if (was == to) return;
        saved.put(pos.immutable(), was);
        level.setBlock(pos, to, 3);
    }

    private static void nextWave(Session s) {
        s.wave++;
        s.nextAt = -1;
        List<Spawn> wave = s.scenario.waves().get(s.wave);
        int n = 0;
        for (Spawn sp : wave) n += sp.count();
        int i = 0;
        double turn = s.level.random.nextDouble() * Math.PI * 2;
        for (Spawn sp : wave) {
            EntityType<?> type = EntityType.byString(sp.id()).orElse(null);
            if (type == null) {
                s.player.sendSystemMessage(Component.literal("arena: no mob " + sp.id() + ", skipped"));
                continue;
            }
            for (int k = 0; k < sp.count(); k++, i++) {
                int[] at = ringSpot(turn + i * Math.PI * 2 / Math.max(1, n), s.level.random.nextDouble());
                Entity en = type.spawn(s.level, s.center.offset(at[0], 0, at[1]), MobSpawnType.COMMAND);
                if (!(en instanceof Mob m)) {
                    if (en != null) en.discard();
                    continue;
                }
                m.setPersistenceRequired();
                m.addTag("vcarena");
                if (m instanceof AbstractPiglin pig) pig.setImmuneToZombification(true);
                if (m instanceof Hoglin hog) hog.setImmuneToZombification(true);
                if (sp.shield() && m.getOffhandItem().isEmpty()) {
                    ItemStack sh = MobShields.randomShield(s.level, m.getRandom());
                    if (!sh.isEmpty()) m.setItemSlot(EquipmentSlot.OFFHAND, sh);
                }
                m.setTarget(s.player);
                s.mobs.add(m);
            }
        }
        s.player.sendSystemMessage(Component.literal("wave " + (s.wave + 1) + "/" + s.scenario.waves().size() + ": " + describe(wave)));
        Diag.event("arena_wave", "scenario", s.scenario.name(), "wave", s.wave + 1, "mobs", s.mobs.size());
    }

    private static String describe(List<Spawn> wave) {
        List<String> parts = new ArrayList<>();
        for (Spawn sp : wave) parts.add(sp.count() + " " + sp.id().substring(sp.id().indexOf(':') + 1) + (sp.shield() ? " with shields" : ""));
        return String.join(", ", parts);
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        Session s = session;
        if (s == null || s.done) return;
        if (!s.player.isAlive() || s.player.hasDisconnected() || s.player.level() != s.level) {
            s.player.sendSystemMessage(Component.literal("arena: you fell after " + seconds(s) + " s, on wave " + (s.wave + 1) + ". /vcarena start to go again."));
            Diag.event("arena_end", "result", "died", "scenario", s.scenario.name(), "wave", s.wave + 1, "seconds", seconds(s), "damage", s.damage, "kills", s.kills);
            end(true);
            return;
        }
        s.mobs.removeIf(m -> !m.isAlive());
        for (Mob m : s.mobs) if (m.getTarget() == null) m.setTarget(s.player);
        if (!s.mobs.isEmpty()) return;
        long now = s.level.getGameTime();
        if (s.wave + 1 >= s.scenario.waves().size()) {
            s.done = true;
            s.player.sendSystemMessage(Component.literal("arena " + s.scenario.name() + " cleared: " + seconds(s) + " s, " + Math.round(s.damage * 10) / 10.0 + " damage taken, "
                    + s.kills + " kills. /vcarena start for another, /vcarena stop to take the arena down."));
            Diag.event("arena_end", "result", "cleared", "scenario", s.scenario.name(), "seconds", seconds(s), "damage", s.damage, "kills", s.kills);
            return;
        }
        if (s.nextAt < 0) {
            s.nextAt = now + BETWEEN;
            s.player.sendSystemMessage(Component.literal("wave cleared; the next in " + BETWEEN / 20 + " s"));
        } else if (now >= s.nextAt) nextWave(s);
    }

    private static long seconds(Session s) {
        return (s.level.getGameTime() - s.started) / 20;
    }

    @SubscribeEvent
    static void hurt(LivingDamageEvent.Post e) {
        Session s = session;
        if (s != null && !s.done && e.getEntity() == s.player) s.damage += e.getNewDamage();
    }

    @SubscribeEvent
    static void death(LivingDeathEvent e) {
        Session s = session;
        if (s != null && e.getEntity() instanceof Mob m && s.mobs.contains(m)) s.kills++;
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        if (session != null) end(false);
    }

    private static int stop(CommandSourceStack src) {
        if (session == null) {
            src.sendFailure(Component.literal("no arena running"));
            return 0;
        }
        end(true);
        return 1;
    }

    /** Clears the mobs and puts every block back; {@code say}: tell the player. */
    private static void end(boolean say) {
        Session s = session;
        session = null;
        if (s == null) return;
        for (Mob m : s.mobs) if (m.isAlive()) m.discard();
        List<Map.Entry<BlockPos, BlockState>> back = new ArrayList<>(s.saved.entrySet());
        Collections.reverse(back);
        for (Map.Entry<BlockPos, BlockState> b : back) s.level.setBlock(b.getKey(), b.getValue(), 3);
        if (say && !s.player.hasDisconnected()) s.player.sendSystemMessage(Component.literal("arena taken down (" + back.size() + " blocks put back)"));
        Diag.event("arena_stop", "scenario", s.scenario.name(), "restored", back.size());
    }

    // ---- the kit ----

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(null);
    }

    /** Gives the loadout: armour into empty slots (else the inventory), the listed weapons, random weapons from as many mods as possible, a shield, the extras. */
    static int kit(ServerPlayer p, boolean say) {
        ServerLevel level = p.serverLevel();
        List<String> given = new ArrayList<>();
        for (String id : armor) {
            Item it = item(id);
            if (it == null || !MobShields.available(it, level.enabledFeatures())) continue;
            ItemStack st = new ItemStack(it);
            if (it instanceof ArmorItem a && p.getItemBySlot(a.getEquipmentSlot()).isEmpty()) p.setItemSlot(a.getEquipmentSlot(), st);
            else p.getInventory().add(st);
        }
        Set<Item> weapons = new HashSet<>();
        for (String id : always) {
            Item it = item(id);
            if (it != null && MobShields.available(it, level.enabledFeatures()) && weapons.add(it)) given.add(BuiltInRegistries.ITEM.getKey(it).toString());
        }
        // Random weapons: one per mod first, round the mods in random order, then any.
        Map<String, List<Item>> byMod = new LinkedHashMap<>();
        for (Item it : BuiltInRegistries.ITEM) {
            ItemStack st = new ItemStack(it);
            if (!Attacks.isWeapon(st) || st.canPerformAction(ItemAbilities.PICKAXE_DIG) || st.canPerformAction(ItemAbilities.SHOVEL_DIG) || st.canPerformAction(ItemAbilities.HOE_DIG)
                    || weapons.contains(it) || !MobShields.available(it, level.enabledFeatures())) continue;
            byMod.computeIfAbsent(BuiltInRegistries.ITEM.getKey(it).getNamespace(), k -> new ArrayList<>()).add(it);
        }
        List<String> mods = new ArrayList<>(byMod.keySet());
        Collections.shuffle(mods, new java.util.Random(p.getRandom().nextLong()));
        int added = 0;
        for (int round = 0; added < randomWeapons && round < 64; round++) {
            boolean any = false;
            for (String mod : mods) {
                List<Item> pool = byMod.get(mod);
                if (pool.isEmpty() || added >= randomWeapons) continue;
                Item it = pool.remove(p.getRandom().nextInt(pool.size()));
                any = true;
                if (weapons.add(it)) {
                    added++;
                    given.add(BuiltInRegistries.ITEM.getKey(it).toString());
                }
            }
            if (!any) break;
        }
        for (Item it : weapons) p.getInventory().add(new ItemStack(it));
        if (shield && p.getOffhandItem().isEmpty()) {
            ItemStack sh = MobShields.randomShield(level, p.getRandom());
            if (!sh.isEmpty()) {
                p.setItemSlot(EquipmentSlot.OFFHAND, sh);
                given.add(BuiltInRegistries.ITEM.getKey(sh.getItem()) + " (off hand)");
            }
        }
        for (String e : extras) {
            String[] ic = idAndCount(e);
            Item it = item(ic[0]);
            if (it != null && MobShields.available(it, level.enabledFeatures())) p.getInventory().add(new ItemStack(it, Math.max(1, Integer.parseInt(ic[1]))));
        }
        p.sendSystemMessage(Component.literal("kit: " + String.join(", ", given) + (extras.isEmpty() ? "" : ", and extras")));
        Diag.event("arena_kit", "weapons", String.join(",", given));
        return 1;
    }
}
