package dev.vectorcombat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Mobs that throw a mob (from data: {@code vectorcombat/mob_throws}). A thrower with a player target in
 * range rears its throwing arm back over its head ({@link #TELL} ticks, the {@code WEAPON} arm), then snaps it forward and a real mob of the thrown kind is launched from its hand on
 * a high arc ({@link #FLIGHT} ticks, the slime's exact launch) to land {@link #SHORT} short of the player, a ring marking the spot; it joins the fight. One thrown mob of its own
 * alive at a time, a wait after it dies, a cooldown between throws. {@code "thrown": "target"} instead grabs the mob it is fighting (never a player), heaves it overhead ({@code
 * OVERHEAD}) and hurls it at the nearest other hostile mob, or away (the iron golem). A hit during the tell interrupts it (poise applies). The shipped table: the husk throws a
 * silverfish, the bogged a small slime, the piglin brute a small magma cube, the iron golem its target.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobThrows {
    private MobThrows() {
    }

    static final int TELL = 14, FLIGHT = 20, HEAVE_FLIGHT = 16, LANDED_LOST = 60;
    static final double SHORT = 1.5, GRAB_REACH = 2.5, HURL_RANGE = 10, HURL_AWAY = 8, IMPACT = 4, IMPACT_RADIUS = 2;

    /** One row: who throws ({@code thrower}: an id or {@code #tag}), what ({@code thrown}: an id, or {@code target}), a slime's {@code size}, the range, the cooldown, the wait after
     * the thrown one dies (seconds), and whether the thrower must be bare-handed. */
    record Entry(String thrower, String thrown, int size, double min, double max, int cooldown, int waitAfter, boolean unarmed) {
        boolean heaves() {
            return "target".equals(thrown);
        }
    }

    private static volatile List<Entry> entries = List.of();

    public static void load(List<JsonElement> files) {
        List<Entry> out = new ArrayList<>();
        for (JsonElement f : files) {
            for (JsonObject o : Attacks.objects(f)) {
                try {
                    out.add(new Entry(o.get("thrower").getAsString(), o.get("thrown").getAsString(), o.has("size") ? o.get("size").getAsInt() : 1,
                            o.has("min") ? o.get("min").getAsDouble() : 5, o.has("max") ? o.get("max").getAsDouble() : 14,
                            (int) Math.round((o.has("cooldown") ? o.get("cooldown").getAsDouble() : 10) * 20), (int) Math.round((o.has("wait") ? o.get("wait").getAsDouble() : 6) * 20),
                            !o.has("unarmed") || o.get("unarmed").getAsBoolean()));
                } catch (RuntimeException ex) {
                    VectorCombat.LOG.warn("Skipping bad mob_throws row {}", o, ex);
                }
            }
        }
        entries = List.copyOf(out);
        CACHE.clear();
    }

    /** Each mob type's row, looked up once (the table was scanned, tags and all, for every mob every tick). */
    private static final Map<EntityType<?>, java.util.Optional<Entry>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static List<Entry> entries() {
        return entries;
    }

    private static boolean matches(String id, EntityType<?> type) {
        if (id.startsWith("#")) return type.is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(id.substring(1))));
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().equals(id);
    }

    static Entry entry(EntityType<?> type) {
        return CACHE.computeIfAbsent(type, t -> {
            for (Entry en : entries) if (matches(en.thrower(), t)) return java.util.Optional.of(en);
            return java.util.Optional.empty();
        }).orElse(null);
    }

    // ---- pure ----

    /** Whether a thrower with this row throws now: in range and sight, bare-handed if it must be, its last thrown mob gone and waited for, off cooldown. */
    static boolean ready(Entry en, double distance, boolean sees, boolean bareHanded, boolean childAlive, long now, long next, long childDied) {
        return sees && distance >= en.min() && distance <= en.max() && (!en.unarmed() || bareHanded) && !childAlive && now >= next && now >= childDied + en.waitAfter();
    }

    /** The largest mob a heaver lifts (a humanoid, not a ravager or a hoglin). */
    static final double LIFT_WIDTH = 1.0, LIFT_HEIGHT = 2.1;

    /** Whether a heaver grabs its target now: a mob (never a player) of humanoid size within {@link #GRAB_REACH}, off cooldown. */
    static boolean grabs(boolean targetIsPlayer, double distance, long now, long next) {
        return grabs(targetIsPlayer, distance, now, next, 0.6, 1.8);
    }

    static boolean grabs(boolean targetIsPlayer, double distance, long now, long next, double width, double height) {
        return !targetIsPlayer && distance <= GRAB_REACH && now >= next && width <= LIFT_WIDTH && height <= LIFT_HEIGHT;
    }

    /** Where a thrown mob is aimed: {@link #SHORT} short of the player, along the line from the thrower. */
    static Vec3 landing(Vec3 thrower, Vec3 player) {
        Vec3 d = new Vec3(player.x - thrower.x, 0, player.z - thrower.z);
        return d.lengthSqr() < 1e-6 ? player : player.subtract(d.normalize().scale(SHORT));
    }

    // ---- the game ----

    private static final class State {
        final Entry entry;
        int age;
        final LivingEntity target;

        State(Entry entry, LivingEntity target) {
            this.entry = entry;
            this.target = target;
        }
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT = new WeakHashMap<>(), DIED = new WeakHashMap<>();
    private static final Map<Mob, Entity> CHILD = new WeakHashMap<>();
    /** Mobs a heaver holds overhead (immobile while held), and mobs it hurled with what they were hurled at, for the impact. */
    private static final Map<LivingEntity, Mob> HELD = new WeakHashMap<>();
    private static final Map<LivingEntity, Object[]> HURLED = new WeakHashMap<>();

    static {
        Holds.onClear(() -> {
            STATES.clear();
            HELD.clear();
            HURLED.clear();
        });
    }

    /** A thrower in its tell, or a mob held overhead, is immobile to its own AI ({@code LivingEntityMixin}). */
    public static boolean holding(LivingEntity e) {
        if (e instanceof Mob m && !STATES.isEmpty() && STATES.containsKey(m)) return true;
        if (HELD.isEmpty()) return false;
        Mob by = HELD.get(e);
        if (by == null) return false;
        if (heldValid(Holds.live(by), STATES.containsKey(by))) return true;
        HELD.remove(e); // its heaver is gone (unloaded, dead) or done: let it go
        return false;
    }

    /** Whether a held mob stays held: its heaver still in the world and still in its heave. */
    static boolean heldValid(boolean heaverLive, boolean heaving) {
        return heaverLive && heaving;
    }

    private static LivingEntity target(Mob mob) {
        if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) return mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        return mob.getTarget();
    }

    private static ParticleOptions dust(String thrown) {
        return switch (thrown) {
            case "minecraft:silverfish" -> new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
            case "minecraft:slime" -> ParticleTypes.ITEM_SLIME;
            case "minecraft:magma_cube" -> ParticleTypes.FLAME;
            default -> ParticleTypes.POOF;
        };
    }

    private static Vec3 hand(Mob mob) {
        return mob.getEyePosition().add(mob.getLookAngle().multiply(1, 0, 1).normalize().scale(0.5));
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        if (!HURLED.isEmpty() && HURLED.containsKey(mob)) impact(mob, level);
        if (entries.isEmpty()) return;
        long now = level.getGameTime();
        Entity child = CHILD.get(mob);
        if (child != null && !child.isAlive()) { // its thrown mob died: the wait starts
            CHILD.remove(mob);
            DIED.put(mob, now);
        }
        State st = STATES.get(mob);
        if (st == null) {
            Entry en = entry(mob.getType());
            if (en == null || !CommonConfig.MOB_WIND_UPS.get() || Stuns.isStunned(mob) || Charge.holding(mob)) return;
            LivingEntity t = target(mob);
            if (t == null || !t.isAlive() || t instanceof Player pl && (pl.isSpectator() || pl.isCreative())) return;
            double d = mob.distanceTo(t);
            boolean go = en.heaves() ? grabs(t instanceof Player, d, now, NEXT.getOrDefault(mob, 0L), t.getBbWidth(), t.getBbHeight())
                    : t instanceof Player && ready(en, d, mob.getSensing().hasLineOfSight(t), mob.getMainHandItem().isEmpty(), CHILD.containsKey(mob), now, NEXT.getOrDefault(mob, 0L),
                    DIED.getOrDefault(mob, Long.MIN_VALUE / 2));
            if (!go) return;
            STATES.put(mob, new State(en, t));
            if (en.heaves()) {
                if (t.isPassenger()) t.stopRiding(); // a rider is pulled off its mount (a pillager off its ravager)
                t.ejectPassengers();
                HELD.put(t, mob);
            }
            WindUp.signal(mob, t, en.heaves() ? "heave" : "throw", "start", TELL);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), en.heaves() ? SoundEvents.IRON_GOLEM_ATTACK : SoundEvents.HUSK_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.7f);
            Diag.event("throw", "result", "tell", "mob", mob.getType().getDescriptionId(), "thrown", en.thrown(), "dist", Math.round(d * 100) / 100.0, "tick", now);
            return;
        }
        st.age++;
        LivingEntity t = st.target;
        if (!mob.isAlive() || t == null || !t.isAlive() || t.isRemoved() || t.level() != level) {
            end(mob, st, "lost");
            return;
        }
        if (WindUp.hurt(mob)) {
            end(mob, st, "interrupted");
            return;
        }
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
        mob.lookAt(t, 30, 30);
        if (st.entry.heaves()) { // held overhead
            Vec3 over = mob.position().add(0, mob.getBbHeight() + 0.2, 0);
            t.setPos(over.x, over.y, over.z);
            t.setDeltaMovement(Vec3.ZERO);
        } else if (st.age % 2 == 0) {
            Vec3 h = hand(mob).add(0, 0.4, 0);
            level.sendParticles(dust(st.entry.thrown()), h.x, h.y, h.z, 2, 0.1, 0.1, 0.1, 0.02);
        }
        if (st.age < TELL) return;
        if (st.entry.heaves()) hurl(mob, st, level);
        else throwOne(mob, st, level);
    }

    /** Launches a new mob of the row's kind from the thrower's hand to land beside the player. */
    private static void throwOne(Mob mob, State st, ServerLevel level) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(st.entry.thrown())).orElse(null);
        Entity made = type == null ? null : type.create(level);
        if (!(made instanceof Mob thrown)) {
            if (made != null) made.discard();
            end(mob, st, "bad_thrown");
            return;
        }
        Vec3 from = hand(mob), to = landing(mob.position(), st.target.position());
        thrown.moveTo(from.x, from.y, from.z, mob.getYRot(), 0);
        EventHooks.finalizeMobSpawn(thrown, level, level.getCurrentDifficultyAt(thrown.blockPosition()), MobSpawnType.MOB_SUMMONED, null);
        if (thrown instanceof Slime slime) slime.setSize(st.entry.size(), true);
        thrown.goalSelector.removeAllGoals(g -> g.getClass().getName().contains("WakeUpFriends") || g.getClass().getName().contains("MergeWithStone")); // no stone infestation from a thrown silverfish
        thrown.setDeltaMovement(Engulf.launch(from, to, FLIGHT));
        thrown.setTarget(st.target);
        level.addFreshEntity(thrown);
        CHILD.put(mob, thrown);
        SpiderMoves.ring(mob, to, 0.8, FLIGHT + 2, LinePayload.SAND);
        WindUp.signal(mob, st.target, "throw", "leap", TELL);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 1.2f, 0.6f);
        end(mob, st, "thrown");
    }

    /** Hurls the held mob at the nearest other hostile mob within {@link #HURL_RANGE}, else {@link #HURL_AWAY} blocks the way the heaver faces; the hit lands on the impact. */
    private static void hurl(Mob mob, State st, ServerLevel level) {
        LivingEntity t = st.target;
        HELD.remove(t);
        Mob at = level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(HURL_RANGE), m -> m != t && m != mob && m.isAlive() && m instanceof Enemy)
                .stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(mob))).orElse(null);
        Vec3 to = at != null ? at.position() : mob.position().add(mob.getLookAngle().multiply(1, 0, 1).normalize().scale(HURL_AWAY));
        mob.doHurtTarget(t); // the golem's own hit, then the throw replaces its knock-up
        t.setDeltaMovement(Engulf.launch(t.position(), to, HEAVE_FLIGHT));
        t.hurtMarked = true;
        HURLED.put(t, new Object[] {at, level.getGameTime(), mob});
        WindUp.signal(mob, t, "heave", "leap", TELL);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.NEUTRAL, 1.5f, 0.5f);
        end(mob, st, "hurled");
    }

    /** A hurled mob landing: it and anything hostile within {@link #IMPACT_RADIUS} take {@link #IMPACT}. */
    private static void impact(Mob hurled, ServerLevel level) {
        Object[] h = HURLED.get(hurled);
        long since = level.getGameTime() - (long) h[1];
        if (since < 3 || !hurled.onGround() && !hurled.isInWater() && since < LANDED_LOST) return;
        HURLED.remove(hurled);
        Mob by = (Mob) h[2];
        for (LivingEntity v : level.getEntitiesOfClass(LivingEntity.class, hurled.getBoundingBox().inflate(IMPACT_RADIUS), v -> v.isAlive() && (v == hurled || v instanceof Enemy))) {
            v.hurt(by != null ? hurled.damageSources().mobAttack(by) : hurled.damageSources().fall(), (float) IMPACT);
        }
        level.sendParticles(ParticleTypes.POOF, hurled.getX(), hurled.getY() + 0.2, hurled.getZ(), 12, 0.4, 0.1, 0.4, 0.05);
        Diag.event("throw", "result", "impact", "mob", hurled.getType().getDescriptionId(), "tick", level.getGameTime());
    }

    private static void end(Mob mob, State st, String result) {
        STATES.remove(mob);
        if (st.target != null) HELD.remove(st.target);
        NEXT.put(mob, mob.level().getGameTime() + st.entry.cooldown());
        if (!"thrown".equals(result) && !"hurled".equals(result)) WindUp.signal(mob, st.target, st.entry.heaves() ? "heave" : "throw", "interrupted".equals(result) ? "interrupted" : "ended", TELL);
        Diag.event("throw", "result", result, "mob", mob.getType().getDescriptionId(), "tick", mob.level().getGameTime());
    }
}
