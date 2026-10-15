package dev.vectorcombat;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Diagnostics: one JSON object per line in {@code logs/vectorcombat-diag.jsonl}, rewritten each launch. Events carry a
 * {@code flag} field when something looks wrong, so {@code grep flag} finds the problems. {@code /vcdiag} adds test
 * markers, spawns targets and prints a summary. Client and server share this file in singleplayer ({@code thread}
 * tells them apart).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Diag {
    private static final long START = System.currentTimeMillis();
    private static final Map<String, long[]> STATS = new ConcurrentHashMap<>(); // count, sum, max
    private static BufferedWriter out;

    /** Wall time of the last client click, so the server can measure click-to-hit (valid in singleplayer only). */
    public static volatile long clickMs;
    public static volatile boolean hud;

    /**
     * Whether events are written: only with {@code [debug] debugLog} on (off by default: a player turns it on for a bug report; the file is never rotated and every line is
     * flushed on the thread that logs it), or {@code -Dvectorcombat.diag=true}. Singleplayer's client and integrated server share the file.
     */
    static boolean writing() {
        if (Boolean.getBoolean("vectorcombat.diag")) return true;
        try {
            return CommonConfig.DEBUG_LOG.get();
        } catch (IllegalStateException e) { // the config is not loaded yet (or a test)
            return false;
        }
    }

    public static synchronized void event(String type, Object... kv) {
        if (!writing()) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", System.currentTimeMillis() - START);
        o.addProperty("thread", Thread.currentThread().getName());
        o.addProperty("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            String k = String.valueOf(kv[i]);
            if (v == null) continue; // optional fields (e.g. flag) are simply absent
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, v.toString());
        }
        if (o.has("flag")) count("flag:" + o.get("flag").getAsString());
        try {
            if (out == null) {
                Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("vectorcombat-diag.jsonl");
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            }
            out.write(o.toString());
            out.newLine();
            out.flush();
        } catch (IOException e) {
            VectorCombat.LOG.error("diag write failed", e);
        }
    }

    public static void count(String key) {
        add(key, 1);
    }

    /** Running count / sum / max for a measurement (latency ms, tick cost in microseconds). */
    public static void add(String key, long value) {
        STATS.compute(key, (k, s) -> {
            if (s == null) s = new long[3];
            s[0]++;
            s[1] += value;
            s[2] = Math.max(s[2], value);
            return s;
        });
    }

    public static String enchants(net.minecraft.world.item.ItemStack stack) {
        StringBuilder b = new StringBuilder();
        stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet().forEach(e -> b.append(e.getKey().getRegisteredName()).append('=').append(e.getIntValue()).append(' '));
        return b.toString().trim();
    }

    public static long now() {
        return System.currentTimeMillis() - START;
    }

    public static long sinceClick() {
        return clickMs == 0 ? -1 : System.currentTimeMillis() - clickMs;
    }

    // ---- victim motion after a hit: does vanilla knockback fight set_motion? ----

    private record Watch(LivingEntity e, String label, long start) {
    }

    private static final List<Watch> WATCHES = new ArrayList<>();

    public static void watch(LivingEntity e, String label) {
        sample(e, label, 0);
        synchronized (WATCHES) {
            WATCHES.add(new Watch(e, label, e.level().getGameTime()));
        }
    }

    private static void sample(LivingEntity e, String label, long dt) {
        Vec3 m = e.getDeltaMovement();
        event("motion", "label", label, "victim", e.getType().getDescriptionId(), "dt", dt,
                "mx", round(m.x), "my", round(m.y), "mz", round(m.z), "speedXZ", round(Math.hypot(m.x, m.z)), "onGround", e.onGround());
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        synchronized (WATCHES) {
            WATCHES.removeIf(w -> {
                long dt = e.getServer().overworld().getGameTime() - w.start();
                if (dt == 1 || dt == 3 || dt == 5) sample(w.e(), w.label(), dt);
                return dt >= 5 || !w.e().isAlive();
            });
        }
    }

    /**
     * A mob's hit on a player once armour, enchantments, effects and absorption have had their share: what the player actually took. A wind-up's {@code land} row says the hit
     * was due; this says what it did (a hit that was parried or blocked never gets here, or comes with {@code blocked}).
     */
    @SubscribeEvent
    static void mobHit(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getSource().getDirectEntity() instanceof Mob m)) return;
        event("mob_hit", "mob", m.getType().getDescriptionId(), "damage", e.getOriginalDamage(), "final", e.getNewDamage(), "blocked", e.getBlockedDamage(),
                "armor", e.getReduction(DamageContainer.Reduction.ARMOR), "effects", e.getReduction(DamageContainer.Reduction.MOB_EFFECTS), "absorbed", e.getReduction(DamageContainer.Reduction.ABSORPTION),
                "hp", p.getHealth(), "absorb", p.getAbsorptionAmount(), "tick", p.level().getGameTime());
    }

    // ---- header ----

    @SubscribeEvent
    static void started(ServerStartedEvent e) {
        List<String> mods = ModList.get().getMods().stream().map(m -> m.getModId() + "@" + m.getVersion()).toList();
        ServerLevel level = e.getServer().overworld();
        event("header", "mods", String.join(",", mods), "superflat", level.isFlat(), "difficulty", e.getServer().getWorldData().getDifficulty(),
                "java", System.getProperty("java.version"));
        for (AttackDef a : Attacks.all()) {
            event("attack_def", "name", a.name(), "hit_at", a.hitAt(), "range", a.range(), "angle", a.angle(), "damage", a.damage(), "animation", a.animation());
        }
    }

    // ---- /vcdiag ----

    @SubscribeEvent
    static void commands(RegisterCommandsEvent e) {
        if (!Dev.on()) return;
        e.getDispatcher().register(Commands.literal("vcdiag").requires(s -> s.hasPermission(2))
                .then(Commands.literal("mark").then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    String text = StringArgumentType.getString(c, "text");
                    event("mark", "text", text);
                    return say(c.getSource(), "mark: " + text);
                })))
                .then(Commands.literal("give").executes(c -> give(c.getSource())))
                .then(Commands.literal("range").executes(c -> range(c.getSource())))
                .then(Commands.literal("dummies").executes(c -> dummies(c.getSource())))
                .then(Commands.literal("live").executes(c -> live(c.getSource())))
                .then(Commands.literal("weapons").executes(c -> weapons(c.getSource())))
                .then(Commands.literal("tells").executes(c -> tells(c.getSource())))
                .then(Commands.literal("drill").executes(c -> Guard.drill(c.getSource(), 8))
                        .then(Commands.argument("rounds", IntegerArgumentType.integer(1, 30)).executes(c -> Guard.drill(c.getSource(), IntegerArgumentType.getInteger(c, "rounds")))))
                .then(Commands.literal("mobguard").then(Commands.literal("parry").executes(c -> MobGuard.drill(c.getSource(), "parry")))
                        .then(Commands.literal("hold").executes(c -> MobGuard.drill(c.getSource(), "hold")))
                        .then(Commands.literal("off").executes(c -> MobGuard.drill(c.getSource(), "off"))))
                .then(Commands.literal("fx").executes(c -> fx(c.getSource(), null, null))
                        .then(Commands.argument("weapon", StringArgumentType.word()).executes(c -> fx(c.getSource(), StringArgumentType.getString(c, "weapon"), "plain"))
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .executes(c -> fx(c.getSource(), StringArgumentType.getString(c, "weapon"), StringArgumentType.getString(c, "kind"))))))
                .then(Commands.literal("clear").executes(c -> {
                    int n = 0;
                    for (Mob m : c.getSource().getLevel().getEntitiesOfClass(Mob.class, c.getSource().getPlayerOrException().getBoundingBox().inflate(40), m -> m.getTags().contains("vcdiag"))) {
                        m.discard();
                        n++;
                    }
                    for (var s : c.getSource().getLevel().getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, c.getSource().getPlayerOrException().getBoundingBox().inflate(40), s -> s.getTags().contains("vcdiag"))) {
                        s.discard();
                        n++;
                    }
                    return say(c.getSource(), "removed " + n);
                }))
                .then(Commands.literal("hud").executes(c -> say(c.getSource(), "hud " + ((hud = !hud) ? "on" : "off"))))
                .then(Commands.literal("summary").executes(c -> {
                    Map<String, String> s = new TreeMap<>();
                    STATS.forEach((k, v) -> s.put(k, v[0] + " (avg " + (v[1] / Math.max(1, v[0])) + ", max " + v[2] + ")"));
                    event("summary", "stats", s.toString());
                    say(c.getSource(), "diag summary:");
                    s.forEach((k, v) -> say(c.getSource(), k + " = " + v));
                    return 1;
                })));
    }

    /** Two enchanted iron swords to check enchantments against vanilla's numbers. */
    private static int give(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        var ench = src.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        var main = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        for (var e : java.util.Map.of(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 5, net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT, 2,
                net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK, 2, net.minecraft.world.item.enchantment.Enchantments.LOOTING, 3,
                net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 3).entrySet()) {
            main.enchant(ench.getOrThrow(e.getKey()), e.getValue());
        }
        var smite = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        smite.enchant(ench.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SMITE), 5);
        p.getInventory().add(main);
        p.getInventory().add(smite);
        // every vanilla weapon class the all-weapons rule covers: axe, mace, trident and a mining tool
        for (var item : List.of(net.minecraft.world.item.Items.IRON_AXE, net.minecraft.world.item.Items.MACE, net.minecraft.world.item.Items.TRIDENT, net.minecraft.world.item.Items.IRON_PICKAXE)) {
            p.getInventory().add(new net.minecraft.world.item.ItemStack(item));
        }
        return say(src, "gave iron swords (Sharpness V/Fire Aspect II/Knockback II/Looting III/Unbreaking III, and Smite V) plus an iron axe, mace, trident and iron pickaxe");
    }

    /**
     * Every item that is a weapon to Vector Combat (a main-hand attack-damage modifier, a set that covers it) or to the weapon data ({@link WeaponData}), with what is resolved for it:
     * one {@code weapon} row each and a {@code weapons} summary. Run once with a weapon mod installed to see what it really gives and which items the {@code @weapon} match
     * should leave alone ({@code ranged} shoots, {@code use} has a use action such as blocking).
     */
    private static int weapons(CommandSourceStack src) {
        int items = 0, weapons = 0, withData = 0, twoHanded = 0, flagged = 0;
        Map<String, Integer> types = new TreeMap<>();
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            boolean weapon = Attacks.isWeapon(stack);
            WeaponData.Entry data = WeaponData.of(item);
            boolean covered = !Attacks.attacksFor(stack).isEmpty();
            if (!weapon && data == null && !covered) continue;
            items++;
            if (weapon) weapons++;
            if (data != null) withData++;
            boolean two = Attacks.twoHanded(stack);
            if (two) twoHanded++;
            boolean ranged = item instanceof net.minecraft.world.item.ProjectileWeaponItem;
            net.minecraft.world.item.UseAnim use = stack.getUseAnimation();
            if (ranged || use != net.minecraft.world.item.UseAnim.NONE) flagged++;
            String type = data == null ? null : data.type();
            if (type != null) types.merge(type, 1, Integer::sum);
            WeaponData.Stats stats = WeaponData.stats(stack);
            Props defaults = Attacks.defaultsFor(stack);
            event("weapon", "item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(), "weapon", weapon, "covered", covered, "damage", round(stats.damage()),
                    "speed", round(stats.speed()), "reach", round(stats.reach()), "categories", defaults == null ? null : defaults.categories(), "kind", type,
                    "dataTwoHanded", data == null ? null : data.twoHanded(), "twoHanded", two, "ranged", ranged ? true : null, "use", use == net.minecraft.world.item.UseAnim.NONE ? null : use.name());
        }
        event("weapons", "items", items, "weapons", weapons, "withData", withData, "twoHanded", twoHanded, "flagged", flagged, "types", types.toString());
        return say(src, "weapons: " + items + " items (" + weapons + " weapons to Vector Combat), " + withData + " with weapon data, " + twoHanded + " two-handed, " + flagged
                + " shoot or have a use action; types " + types + "; rows in the diag log");
    }

    /**
     * What each loaded mob that holds something would wind up for ({@link WindUp#ticks(net.minecraft.world.entity.Mob)}): one {@code tell} row each and a one-line summary, so the
     * spread of the weapon-aware tell shows without a fight. {@code basis} says where the number came from: the weapon in hand, the mob's {@code mob_attacks} entry, or the derived default.
     */
    private static int tells(CommandSourceStack src) {
        StringBuilder line = new StringBuilder();
        int n = 0;
        for (net.minecraft.world.entity.Entity e : src.getLevel().getAllEntities()) {
            if (!(e instanceof net.minecraft.world.entity.Mob mob) || mob.getMainHandItem().isEmpty()) continue;
            Double speed = WeaponData.weaponSpeed(mob.getMainHandItem());
            int ticks = WindUp.ticks(mob);
            String basis = speed != null ? "weapon" : Profiles.windUp(mob) != null ? "entry" : "derived";
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
            String held = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem()).toString();
            double extra = WeaponData.extraReach(mob.getMainHandItem());
            double reach = reachBlocks(mob);
            event("tell", "mob", type, "held", held, "speed", speed == null ? null : round(speed), "ticks", ticks, "basis", basis, "reach", extra > 0 ? round(extra) : null, "reachBlocks", round(reach));
            if (n++ < 12) line.append(n > 1 ? ", " : "").append(type).append(' ').append(held).append(' ').append(ticks).append(" ticks, reach ").append(round(reach));
        }
        return say(src, n == 0 ? "tells: no loaded mob holds anything" : "tells: " + line + (n > 12 ? ", and " + (n - 12) + " more" : "") + "; rows in the diag log");
    }

    /**
     * How far east of {@code mob}, centre to centre, a throwaway armor stand can stand and still be in the mob's melee reach ({@code isWithinMeleeAttackRange}, the check every melee
     * mob asks): a whole-game measurement of the weapon's reach with no player, in steps of a tenth of a block up to 8.
     */
    private static double reachBlocks(net.minecraft.world.entity.Mob mob) {
        net.minecraft.world.entity.decoration.ArmorStand stand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mob.level());
        if (stand == null) return 0;
        double far = 0;
        for (int i = 0; i <= 80; i++) {
            stand.setPos(mob.getX() + i / 10.0, mob.getY(), mob.getZ());
            if (mob.isWithinMeleeAttackRange(stand)) far = i / 10.0;
        }
        return far;
    }

    private static int say(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    static <T extends net.minecraft.world.entity.Entity> T spawn(ServerLevel level, EntityType<T> type, ServerPlayer p, double forward, double right, String name, boolean ai) {
        double yaw = Math.toRadians(p.getYRot());
        Vec3 f = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 r = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 at = p.position().add(f.scale(forward)).add(r.scale(right));
        T e = type.create(level);
        e.moveTo(at.x, at.y, at.z, p.getYRot() + 180, 0);
        e.addTag("vcdiag");
        e.setCustomName(Component.literal(name));
        if (e instanceof Mob m) {
            m.setPersistenceRequired();
            m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            m.setHealth(1000);
            if (ai) { // lives and can be knocked back (NoAI mobs cannot), but never moves on its own or hurts
                m.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                if (m.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) m.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
            } else {
                m.setNoAi(true);
            }
        }
        level.addFreshEntity(e);
        return e;
    }

    /** Husks at known distances and angles from where the player faces: which swings reach what. */
    private static int range(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        double yaw = Math.toRadians(p.getYRot());
        for (double d : new double[] {1.5, 2.5, 3.5, 4.5}) spawn(src.getLevel(), EntityType.HUSK, p, d, 0, "d" + d + "@0", false);
        for (double deg : new double[] {45, -45, 90, -90}) {
            double a = Math.toRadians(deg);
            spawn(src.getLevel(), EntityType.HUSK, p, 2.5 * Math.cos(a), 2.5 * Math.sin(a), "d2.5@" + (int) deg, false);
        }
        event("spawn", "kind", "range", "yaw", p.getYRot());
        return say(src, "range targets spawned (NoAI: reach/arc only, no knockback)");
    }

    /** Armour stands, golem, vindicator, pillager, skeleton in a row for category and stun beats. */
    private static int dummies(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        ServerLevel l = src.getLevel();
        spawn(l, EntityType.ARMOR_STAND, p, 3, -6, "stand", false);
        spawn(l, EntityType.ARMOR_STAND, p, 3, -4, "stand", false);
        spawn(l, EntityType.IRON_GOLEM, p, 3, -2, "golem", false);
        spawn(l, EntityType.VINDICATOR, p, 3, 0, "vindicator", false);
        spawn(l, EntityType.PILLAGER, p, 3, 2, "pillager", false);
        spawn(l, EntityType.SKELETON, p, 3, 4, "skeleton", false);
        event("spawn", "kind", "dummies");
        return say(src, "dummies spawned (NoAI: stun and categories)");
    }

    // ---- /vcdiag fx: every contact sound and its sparks in turn, 3.5 blocks ahead, with nothing to hit (so no hurt sounds in the way) ----

    /** Weapon name, the category it sounds as, its attack speed. */
    private static final String[][] FX_WEAPONS = {{"sword", "slash", "1.6"}, {"axe", "cleave", "0.9"}, {"spear", "pierce", "1.1"}, {"mace", "bash", "0.6"}};
    /** The attack kinds to the category their own definition names (empty: the attack takes the weapon's). {@code group} is a plain hit on three victims side by side. */
    private static final Map<String, String> FX_KINDS = Map.of("plain", "", "kill", "", "falling", "cleave", "lunge", "pierce", "rising", "slash", "group", "");

    /** All 16 steps 1.5 s apart (each weapon plain then kill, the Falling, Lunge and Rising accents on sword and mace, then a group hit with sword and mace), or just one. */
    private static int fx(CommandSourceStack src, String weapon, String kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        List<String[]> steps = new ArrayList<>();
        if (weapon == null) {
            for (String[] w : FX_WEAPONS) {
                steps.add(new String[] {w[0], "plain"});
                steps.add(new String[] {w[0], "kill"});
            }
            for (String w : new String[] {"sword", "mace"}) {
                for (String k : new String[] {"falling", "lunge", "rising"}) steps.add(new String[] {w, k});
            }
            for (String w : new String[] {"sword", "mace"}) steps.add(new String[] {w, "group"});
        } else if (java.util.Arrays.stream(FX_WEAPONS).noneMatch(w -> w[0].equals(weapon)) || !FX_KINDS.containsKey(kind)) {
            return say(src, "usage: /vcdiag fx [sword|axe|spear|mace [plain|kill|falling|lunge|rising|group]]");
        } else {
            steps.add(new String[] {weapon, kind});
        }
        for (int i = 0; i < steps.size(); i++) {
            int n = i + 1;
            String[] s = steps.get(i);
            Combat.later(i * 30, () -> fxStep(p, n, steps.size(), s[0], s[1]));
        }
        return say(src, "fx preview: " + steps.size() + " step(s), 1.5 s apart");
    }

    private static void fxStep(ServerPlayer p, int n, int total, String weapon, String kind) {
        String[] w = java.util.Arrays.stream(FX_WEAPONS).filter(x -> x[0].equals(weapon)).findFirst().get();
        String own = FX_KINDS.get(kind);
        String accent = HitFx.accentFor(w[1], own.isEmpty() ? null : own);
        boolean kill = kind.equals("kill");
        Vec3 look = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z).normalize();
        Vec3 at = new Vec3(p.getX(), p.getY(0.75), p.getZ()).add(look.scale(3.5));
        HitFx.Played played = HitFx.sound(p.serverLevel(), at, w[1], accent, Double.parseDouble(w[2]), 3, kill, false);
        int shown = kind.equals("group") ? 3 : 1, sparks = 0;
        Vec3 side = new Vec3(-look.z, 0, look.x);
        for (int i = 0; i < shown; i++) sparks += HitFx.sparks(p.serverLevel(), at.add(side.scale((i - (shown - 1) / 2.0) * 1.2)), look, w[1], 3, kill, shown);
        p.sendSystemMessage(Component.literal("fx " + n + "/" + total + ": " + weapon + " " + kind + (!own.isEmpty() && accent == null ? " (no accent: same category)" : "")));
        event("fx_preview", "step", n, "weapon", weapon, "kind", kind, "profile", w[1], "accent", accent, "layers", played.layers(), "particles", sparks,
                "flag", played.missing() == null ? null : "hitfx_missing_sound");
    }

    /** Harmless mobs that can be knocked back, to see what a hit really does to motion. */
    private static int live(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        for (int i = -1; i <= 1; i++) spawn(src.getLevel(), EntityType.HUSK, p, 2, i * 2, "live", true);
        event("spawn", "kind", "live");
        return say(src, "live husks spawned (immobile, 0 damage, knockback works)");
    }
}
