package dev.vectorcombat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.HoglinBase;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Charges, one engine with a {@link Profile} per mob. A charger with a player target in range (on first spotting them, then at most every
 * cooldown) telegraphs a line of red dust from it through the player to {@link #PAST} blocks beyond and charges along it, through anything in the way, every living thing it
 * touches hit once. While it runs the mob is immobile to its own AI ({@code LivingEntityMixin}) and moved by us; the rules and the line are pure.
 * <p>
 * {@link #HOGLIN} ({@code #vectorcombat:chargers}: hoglin, zoglin; PLAN.md "Hoglin charge"): plants and scrapes a hoof ({@link #LINE1} ticks), charges, stops, turns
 * ({@link #TURN}), rears up roaring ({@link #ROAR}), draws a second line through where the player is now ({@link #LINE2}) and dashes back; then it is tired ({@link #TIRED} ticks,
 * slowed, panting) and its base attacks are slower for {@link #SLOWED} more ({@link #attackScale}). A player an adult's dash runs over unblocked is thrown up and hard-stunned
 * {@link #HIT_STUN} s; stunned by the first dash either way, they stay stunned until the second reaches them. Adults' dashes cannot be parried; blocking the first stuns
 * {@link #BLOCK_STUN} s; blocking the second disables the shield. Babies' can be parried (the baby then flees) and a block disables the shield without the stun.
 * <p>
 * {@link #RAVAGER} ({@code #vectorcombat:rammers}: the ravager): backs up a moment ({@link #BACKUP} ticks), paws the ground with a front leg over the line,
 * then roars as it launches, slowing and weakening everyone within {@link #ROAR_RADIUS} (not its rider or other raiders), and charges once. Anyone it hits is stunned
 * {@link #RAM_STUN} s and flung up over its back by its horns in a steep arc ({@link #fling}); its charge cannot be parried and a block disables the shield. Terrain kicks it back,
 * stunning it and its rider {@link #WALL_STUN} s.
 * <p>
 * {@link #COW} ({@code #vectorcombat:stampeders}: cows, mooshrooms): retaliates instead of hunting. A player who hurts an adult makes it flee
 * ({@link #COW_FLEE} ticks), then turn, paw the dirt over a short yellow line ({@link #COW_PAW}) and moo as it dashes through them and on ({@link #COW_PAST} past), fleeing on in that
 * direction; up to {@link #COW_DASHES} dashes in {@link #COW_TIME} ticks. Every adult of the herd within {@link #HERD_RADIUS} flees the same way and dashes on its own as it is able, a
 * few ticks apart. Its hit is a flat {@link #COW_DAMAGE} and a shove (a cow has no attack stat); it can be parried; a block is vanilla's.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Charge {
    private Charge() {
    }

    public static final TagKey<EntityType<?>> CHARGERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "chargers"));
    public static final TagKey<EntityType<?>> RAMMERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "rammers"));
    public static final TagKey<EntityType<?>> STAMPEDERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "stampeders"));

    // ---- the hoglin's numbers ----
    static final double MIN = 4, MAX = 14, PAST = 4, DAMAGE = 1.5, SPEED = 0.55, BABY_SPEED = 0.65, BLOCK_STUN = 2.5;
    /** the lines are shorter (20 and 12 ticks were too long to react to) and it charges again sooner (8 s, was 15). */
    static final int LINE1 = 12, BABY_LINE1 = 9, TURN = 8, ROAR = 25, BABY_ROAR = 18, LINE2 = 8, BABY_LINE2 = 7, TIRED = 60, SLOWED = 100, COOLDOWN = 160, LOST = 80,
            WALL_STAGGER = 20;

    /** A dash that lands on a player unblocked: they are thrown up and back and hard-stunned {@link #HIT_STUN} s, so being run over blind is punished. */
    static final double HIT_STUN = 2.0, LAUNCH_UP = 0.85, LAUNCH_BACK = 0.6;

    /** How much longer a slowed hoglin's base attack takes (its interval and its tell). */
    static final double SLOW_ATTACKS = 1.5;

    // ---- the ravager's numbers ----
    static final double RAM_MIN = 5, RAM_MAX = 16, RAM_SPEED = 0.6, BACKUP_SPEED = 0.12, ROAR_RADIUS = 6, RAM_STUN = 1.5, WALL_STUN = 1.5, FLING_UP = 1.15, FLING_BACK = 0.45,
            KICK_BACK = 0.6;
    static final int BACKUP = 12, PAW = 20, RAM_COOLDOWN = 240, ROAR_SLOW = 60, ROAR_WEAK = 100;

    // ---- the cow's numbers ----
    static final double COW_MIN = 3, COW_MAX = 12, COW_SPEED = 0.5, FLEE_SPEED = 0.28, COW_PAST = 7, COW_DAMAGE = 3, COW_SHOVE = 0.8, HERD_RADIUS = 12;
    static final int COW_PAW = 10, COW_FLEE = 30, COW_FLEE_MAX = 60, COW_DASHES = 2, COW_TIME = 300, COW_STAGGER = 10, COW_WALL = 15;

    /**
     * A struck cow bites back {@link #BITE_CHANCE} of the time instead of running (a {@link #BUNT_TELL} tick head-down tell, then a light
     * {@link #BUNT_DAMAGE} bunt and a {@link #BUNT_SHOVE} shove); one that runs panics, veering up to {@link #VEER} degrees from straight away every {@link #VEER_MIN} to
     * {@link #VEER_MAX} ticks, for {@link #COW_FLEE} ticks, then stampedes a player 3 to 12 off or bunts one closer. After a dash it runs {@link #FLEE_AFTER} ticks before turning again.
     */
    static final double BITE_CHANCE = 0.4, BUNT_DAMAGE = 1.5, BUNT_SHOVE = 0.4, BUNT_REACH = 2.4, VEER = 50;
    static final int BUNT_TELL = 6, VEER_MIN = 8, VEER_MAX = 12, FLEE_AFTER = 20;

    /**
     * How a mob charges: its range and cooldown, its back-up, a roar at launch, a second dash, tiring, a baby's parry; and whether it charges only to retaliate (fleeing
     * between dashes, with its herd), whether an adult's charge can be parried, and how many dashes a retaliation has.
     */
    record Profile(String name, double min, double max, int cooldown, int backup, boolean roarAtLaunch, boolean twice, boolean tires, boolean babyParry, boolean retaliates,
                   boolean parryable, int dashes) {
    }

    static final Profile HOGLIN = new Profile("hoglin", MIN, MAX, COOLDOWN, 0, false, true, true, true, false, false, 2);
    static final Profile RAVAGER = new Profile("ravager", RAM_MIN, RAM_MAX, RAM_COOLDOWN, BACKUP, true, false, false, false, false, false, 1);
    static final Profile COW = new Profile("cow", COW_MIN, COW_MAX, 0, 0, false, false, false, false, true, true, COW_DASHES);

    enum Phase { FLEE, BACKUP, LINE1, DASH1, TURN, ROAR, LINE2, DASH2, BUNT }

    private static final class State {
        final Profile profile;
        Phase phase;
        int age;
        Vec3 from, to;
        final Player victim;
        final Set<UUID> hit = new HashSet<>();
        boolean blockedFirst;

        /** The player was stunned by the first dash (a block, or a hit taken blind): the second dash keeps them stunned until it is over, so it lands. */
        boolean pinned;

        /** Ticks the charge will wait before turning (a hoglin dazed by a wall), added to a pinned player's stun. */
        int pause;

        /** A retaliation's flight: the way it runs, the ticks of delay before it may turn (a herd's stagger), its dashes so far and its ticks in all. */
        Vec3 fleeDir = Vec3.ZERO;
        int stagger, dashes, total, veerAt;

        State(Profile profile, Player victim, Vec3 from, Vec3 to) {
            this.profile = profile;
            this.phase = profile.backup() > 0 ? Phase.BACKUP : Phase.LINE1;
            this.victim = victim;
            this.from = from;
            this.to = to;
        }
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT = new WeakHashMap<>(), TIRED_UNTIL = new WeakHashMap<>(), SLOWED_UNTIL = new WeakHashMap<>();
    private static final Map<Mob, LivingEntity> LAST_TARGET = new WeakHashMap<>();

    static {
        Holds.onClear(STATES::clear);
    }

    /** True while a charge's own unparryable hit is dealt ({@link Guard}). */
    private static boolean unparryable;

    // ---- pure ----

    /** Whether a hoglin {@code distance} blocks from its target starts the charge now. */
    static boolean ready(double distance, boolean onGround, boolean sees, long now, long next) {
        return ready(HOGLIN, distance, onGround, sees, now, next);
    }

    static boolean ready(Profile p, double distance, boolean onGround, boolean sees, long now, long next) {
        return onGround && sees && distance >= p.min() && distance <= p.max() && now >= next;
    }

    /** The end of a line from {@code from} through {@code at}: {@link #PAST} blocks beyond it, level with {@code from}. */
    static Vec3 lineEnd(Vec3 from, Vec3 at) {
        Vec3 d = new Vec3(at.x - from.x, 0, at.z - from.z);
        if (d.lengthSqr() < 1e-6) return from;
        return new Vec3(at.x, from.y, at.z).add(d.normalize().scale(PAST));
    }

    /** The same with the line running {@code past} blocks beyond (a cow's dash carries on through its attacker). */
    static Vec3 lineEnd(Vec3 from, Vec3 at, double past) {
        Vec3 d = new Vec3(at.x - from.x, 0, at.z - from.z);
        if (d.lengthSqr() < 1e-6) return from;
        return new Vec3(at.x, from.y, at.z).add(d.normalize().scale(past));
    }

    /** The way a hurt cow (and its herd) runs: away from the attacker, level. */
    static Vec3 fleeFrom(Vec3 cow, Vec3 attacker) {
        Vec3 d = new Vec3(cow.x - attacker.x, 0, cow.z - attacker.z);
        return d.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : d.normalize();
    }

    /** Whether a fleeing cow turns to dash now: past its flight and stagger, its attacker 3 to 12 blocks off in sight, on the ground. */
    static boolean turns(int age, int stagger, double distance, boolean sees, boolean onGround) {
        return age >= COW_FLEE + stagger && onGround && sees && distance >= COW_MIN && distance <= COW_MAX;
    }

    /** Whether a fleeing cow bunts now: past its flight and stagger, its attacker closer than {@link #COW_MIN}, on the ground. */
    static boolean bunts(int age, int stagger, double distance, boolean onGround) {
        return age >= COW_FLEE + stagger && onGround && distance < COW_MIN;
    }

    /** A panicked cow's way: straight away from its attacker turned by {@code veer} degrees (left positive). */
    static Vec3 veer(Vec3 away, double veer) {
        double r = Math.toRadians(veer), c = Math.cos(r), s = Math.sin(r);
        return new Vec3(away.x * c - away.z * s, 0, away.x * s + away.z * c);
    }

    /** Whether a retaliation goes on after a dash: dashes left and time left. */
    static boolean another(int dashes, int total) {
        return dashes < COW_DASHES && total < COW_TIME;
    }

    /** How long each of the hoglin's phases lasts for an adult or a baby (the dashes run to the line's end). */
    static int ticks(Phase phase, boolean baby) {
        return switch (phase) {
            case LINE1 -> baby ? BABY_LINE1 : LINE1;
            case TURN -> TURN;
            case ROAR -> baby ? BABY_ROAR : ROAR;
            case LINE2 -> baby ? BABY_LINE2 : LINE2;
            default -> LOST;
        };
    }

    /** The same for any profile: the ravager backs up and paws for its own counts. */
    static int ticks(Profile p, Phase phase, boolean baby) {
        if (p == COW) return phase == Phase.LINE1 ? COW_PAW : phase == Phase.FLEE ? COW_FLEE : LOST;
        if (p == RAVAGER) {
            return switch (phase) {
                case BACKUP -> BACKUP;
                case LINE1 -> PAW;
                default -> LOST;
            };
        }
        return ticks(phase, baby);
    }

    /** The throw of a player a hoglin runs over going {@code dir} (level, unit): up {@link #LAUNCH_UP}, on along the dash {@link #LAUNCH_BACK}. */
    static Vec3 launch(Vec3 dir) {
        return new Vec3(dir.x * LAUNCH_BACK, LAUNCH_UP, dir.z * LAUNCH_BACK);
    }

    /** The ravager's horn toss of whoever it hits charging {@code dir}: steeply up and back over it ({@link #FLING_UP}, {@link #FLING_BACK} against the charge). */
    static Vec3 fling(Vec3 dir) {
        return new Vec3(-dir.x * FLING_BACK, FLING_UP, -dir.z * FLING_BACK);
    }

    /** What hitting terrain does to a ravager charging {@code dir}: kicked back {@link #KICK_BACK} with a little lift. */
    static Vec3 kickBack(Vec3 dir) {
        return new Vec3(-dir.x * KICK_BACK, 0.2, -dir.z * KICK_BACK);
    }

    /** Whether the roar at launch reaches {@code e}: alive, within {@link #ROAR_RADIUS}, not the ravager, its rider or another raider. */
    static boolean roarReaches(boolean self, boolean rider, boolean raider, double distance) {
        return !self && !rider && !raider && distance <= ROAR_RADIUS;
    }

    /** Ticks a dash at its speed needs to reach {@code p} from {@code mob}, plus a few for the hit to land. */
    private static int ticksToReach(Mob mob, Player p, boolean baby) {
        return 6 + (int) Math.ceil(mob.distanceTo(p) / (baby ? BABY_SPEED : SPEED));
    }

    /** What a hit on a blocking player brings: an adult hoglin's first dash stuns, every other dash's block disables the shield. */
    static boolean stunsOnBlock(boolean baby, Phase phase) {
        return stunsOnBlock(HOGLIN, baby, phase);
    }

    static boolean stunsOnBlock(Profile p, boolean baby, Phase phase) {
        return p.twice() && !baby && phase == Phase.DASH1;
    }

    // ---- the game ----

    /** The profile of a mob that charges, or null. */
    static Profile profile(Entity e) {
        if (Domestic.exempt(e)) return null; // bred or tamed: vanilla's
        if (e.getType().is(CHARGERS)) return HOGLIN;
        if (e.getType().is(RAMMERS)) return RAVAGER;
        if (e.getType().is(STAMPEDERS)) return COW;
        return null;
    }

    /** Whether {@code e} is in the charge (it is then immobile to its own AI). */
    public static boolean holding(LivingEntity e) {
        return e instanceof Mob m && !STATES.isEmpty() && STATES.containsKey(m);
    }

    /** True while a charge's own unparryable hit is being dealt: {@link Guard} does not parry it. */
    public static boolean noParry() {
        return unparryable;
    }

    /** How much longer this mob's base attack takes: {@link #SLOW_ATTACKS} while slowed after a charge, else 1. */
    public static double attackScale(Mob mob) {
        Long until = SLOWED_UNTIL.get(mob);
        return until != null && until > mob.level().getGameTime() ? SLOW_ATTACKS : 1;
    }

    private static Player target(Mob mob) {
        LivingEntity t = mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET) ? mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) : mob.getTarget();
        return t instanceof Player p && p.isAlive() && !p.isSpectator() && !p.isCreative() ? p : null;
    }

    private static String id(Mob mob) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    }

    private static SoundEvent angry(Mob mob) {
        return mob instanceof Zoglin ? SoundEvents.ZOGLIN_ANGRY : mob instanceof Hoglin ? SoundEvents.HOGLIN_ANGRY : SoundEvents.RAVAGER_AMBIENT;
    }

    /** Whether a profile's line is drawn as a neutral mob's (yellow): the ones that only retaliate. */
    static boolean neutralLine(Profile p) {
        return p.retaliates();
    }

    /** Sends a line telegraph to every player tracking {@code mob}: each client draws it for {@code ticks} by its own setting ({@link LineClient}). */
    private static void line(Mob mob, Vec3 from, Vec3 to, int ticks, boolean neutral) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntity(mob,
                new LinePayload((float) from.x, (float) from.y, (float) from.z, (float) to.x, (float) to.y, (float) to.z, ticks, neutral ? LinePayload.YELLOW : LinePayload.RED, false));
    }


    private static void face(Mob mob, Vec3 to) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(to.x - mob.getX()), to.z - mob.getZ()));
        mob.setYRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
    }

    private static Vec3 dir(State st) {
        Vec3 d = new Vec3(st.to.x - st.from.x, 0, st.to.z - st.from.z);
        return d.lengthSqr() < 1e-6 ? Vec3.ZERO : d.normalize();
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) return;
        Profile profile = profile(mob);
        if (profile == null) return;
        long now = level.getGameTime();
        Long tired = TIRED_UNTIL.get(mob);
        if (tired != null && tired > now && (tired - now) % 6 == 0) { // panting at the snout
            Vec3 snout = mob.position().add(mob.getLookAngle().scale(mob.getBbWidth() * 0.6)).add(0, mob.getBbHeight() * 0.55, 0);
            level.sendParticles(ParticleTypes.POOF, snout.x, snout.y, snout.z, 2, 0.05, 0.05, 0.05, 0.01);
        }
        State st = STATES.get(mob);
        if (st == null && profile.retaliates()) return; // a cow charges only when hurt (hurt)
        if (st == null) {
            Player p = target(mob);
            LivingEntity last = LAST_TARGET.put(mob, p);
            if (p == null || !CommonConfig.MOB_WIND_UPS.get() || Stuns.isStunned(mob)) return;
            if (last != p) NEXT.put(mob, now); // newly spotted: the charge may open the fight
            if (tired != null && tired > now) return;
            if (!ready(profile, mob.distanceTo(p), mob.onGround(), mob.getSensing().hasLineOfSight(p), now, NEXT.getOrDefault(mob, now))) return;
            State s = new State(profile, p, mob.position(), lineEnd(mob.position(), p.position()));
            STATES.put(mob, s);
            face(mob, p.position());
            if (s.phase == Phase.BACKUP) WindUp.signal(mob, p, "charge_dash", "start", BACKUP); // head down as it backs up
            else WindUp.signal(mob, p, "charge_line", "start", ticks(profile, Phase.LINE1, mob.isBaby()));
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), angry(mob), SoundSource.HOSTILE, 1.2f, mob.isBaby() ? 1.3f : 0.8f);
            Diag.event("charge", "result", "line", "mob", id(mob), "profile", profile.name(), "baby", mob.isBaby(), "dist", Math.round(mob.distanceTo(p) * 100) / 100.0, "tick", now);
            return;
        }
        if (Stuns.isStunned(mob)) return; // staggered (a wall, a parry): the charge waits, dazed
        st.age++;
        st.total++;
        Player p = st.victim;
        boolean baby = mob.isBaby();
        if (!mob.isAlive() || p == null || !p.isAlive() || p.isRemoved() || p.level() != mob.level()) {
            end(mob, p, "lost", false);
            return;
        }
        switch (st.phase) {
            case FLEE -> {
                if (st.age >= st.veerAt) { // panicked: a new way every half second or so, still away from its attacker
                    st.fleeDir = veer(fleeFrom(mob.position(), p.position()), (mob.getRandom().nextDouble() * 2 - 1) * VEER);
                    st.veerAt = st.age + VEER_MIN + mob.getRandom().nextInt(VEER_MAX - VEER_MIN + 1);
                }
                mob.setDeltaMovement(st.fleeDir.x * FLEE_SPEED, mob.getDeltaMovement().y, st.fleeDir.z * FLEE_SPEED);
                mob.hurtMarked = true;
                face(mob, mob.position().add(st.fleeDir));
                if (turns(st.age, st.stagger, mob.distanceTo(p), mob.getSensing().hasLineOfSight(p), mob.onGround())) { // turns on its attacker and paws the dirt
                    st.phase = Phase.LINE1;
                    st.age = 0;
                    st.from = mob.position();
                    st.to = lineEnd(mob.position(), p.position(), COW_PAST);
                    face(mob, st.to);
                    WindUp.signal(mob, p, "charge_line", "start", COW_PAW);
                } else if (bunts(st.age, st.stagger, mob.distanceTo(p), mob.onGround())) { // too close to stampede: a bunt
                    bunt(mob, st, p);
                } else if (st.age > COW_FLEE_MAX || st.total > COW_TIME) {
                    end(mob, p, "calm", false);
                }
            }
            case BACKUP -> {
                Vec3 away = new Vec3(mob.getX() - p.getX(), 0, mob.getZ() - p.getZ());
                away = away.lengthSqr() < 1e-6 ? Vec3.ZERO : away.normalize();
                mob.setDeltaMovement(away.x * BACKUP_SPEED, mob.getDeltaMovement().y, away.z * BACKUP_SPEED);
                mob.hurtMarked = true;
                face(mob, p.position());
                if (st.age >= BACKUP) { // ready: the line from where it now stands
                    st.phase = Phase.LINE1;
                    st.age = 0;
                    st.from = mob.position();
                    st.to = lineEnd(mob.position(), p.position());
                    WindUp.signal(mob, p, "charge_line", "start", ticks(st.profile, Phase.LINE1, baby));
                }
            }
            case LINE1, LINE2 -> {
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
                face(mob, st.to);
                if (st.age == 1) line(mob, st.from, st.to, ticks(st.profile, st.phase, baby), neutralLine(st.profile)); // once: each client draws it, or not, by its setting
                if (st.age % 5 == 0) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), st.profile == COW ? SoundEvents.COW_STEP
                        : mob instanceof Hoglin || mob instanceof Zoglin ? SoundEvents.HOGLIN_STEP : SoundEvents.RAVAGER_STEP, SoundSource.HOSTILE, 1f, 0.7f); // the hoof scraping
                if (st.age >= ticks(st.profile, st.phase, baby)) {
                    st.phase = st.phase == Phase.LINE1 ? Phase.DASH1 : Phase.DASH2;
                    st.age = 0;
                    st.hit.clear();
                    WindUp.signal(mob, p, "charge_dash", "start", LOST);
                    if (st.phase == Phase.DASH2 && st.pinned) Stuns.apply(p, ticksToReach(mob, p, baby) / 20.0); // still stunned until the second dash reaches them
                    if (st.profile.roarAtLaunch()) roar(mob, level);
                    else if (st.profile == COW) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.COW_AMBIENT, SoundSource.NEUTRAL, 1.6f, 0.75f); // a moo with every dash
                    else level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_ATTACK, SoundSource.HOSTILE, 1.5f, baby ? 1.3f : 0.7f);
                }
            }
            case DASH1, DASH2 -> dash(mob, st, level, baby);
            case BUNT -> {
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
                face(mob, p.position());
                if (st.age < BUNT_TELL) break;
                WindUp.signal(mob, p, "charge_dash", "leap", BUNT_TELL);
                boolean landed = mob.distanceTo(p) <= BUNT_REACH && p.hurt(mob.damageSources().mobAttack(mob), (float) BUNT_DAMAGE); // a parry is Guard's: the cow staggered
                if (landed) {
                    Vec3 away = fleeFrom(p.position(), mob.position()).scale(-1);
                    p.knockback(BUNT_SHOVE, -away.x, -away.z);
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.COW_HURT, SoundSource.NEUTRAL, 1.2f, 0.7f);
                }
                Diag.event("charge", "result", landed ? "bunt" : "bunt_missed", "mob", id(mob), "tick", now);
                end(mob, p, "calm", false);
            }
            case TURN -> {
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.5, 1, 0.5));
                face(mob, p.position());
                if (st.age >= TURN) {
                    st.phase = Phase.ROAR;
                    st.age = 0;
                    WindUp.signal(mob, p, "charge_rear", "start", ticks(Phase.ROAR, baby));
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), angry(mob), SoundSource.HOSTILE, 2f, baby ? 1.1f : 0.55f);
                }
            }
            case ROAR -> {
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
                face(mob, p.position());
                if (st.age == ticks(Phase.ROAR, baby) / 2) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), angry(mob), SoundSource.HOSTILE, 2f, baby ? 1.0f : 0.5f);
                if (st.age >= ticks(Phase.ROAR, baby)) {
                    st.phase = Phase.LINE2;
                    st.age = 0;
                    st.from = mob.position();
                    st.to = lineEnd(mob.position(), p.position());
                    face(mob, st.to);
                    WindUp.signal(mob, p, "charge_line", "start", ticks(Phase.LINE2, baby));
                }
            }
        }
    }

    /** The ravager's roar as it launches: everyone near (not its rider or other raiders) slowed and weakened. */
    private static void roar(Mob mob, ServerLevel level) {
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2f, 1f);
        level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(), 30, ROAR_RADIUS * 0.3, 0.3, ROAR_RADIUS * 0.3, 0.05);
        int n = 0;
        for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(ROAR_RADIUS))) {
            if (!t.isAlive() || !roarReaches(t == mob, t.isPassengerOfSameVehicle(mob) || mob.hasPassenger(t), t instanceof Raider, t.distanceTo(mob))) continue;
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ROAR_SLOW, 1), mob);
            t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ROAR_WEAK, 0), mob);
            n++;
        }
        Diag.event("charge", "result", "roar", "mob", id(mob), "reached", n, "tick", level.getGameTime());
    }

    private static void dash(Mob mob, State st, ServerLevel level, boolean baby) {
        Vec3 d = new Vec3(st.to.x - st.from.x, 0, st.to.z - st.from.z);
        double len = d.length(), done = new Vec3(mob.getX() - st.from.x, 0, mob.getZ() - st.from.z).length();
        Vec3 dir = len < 1e-6 ? Vec3.ZERO : d.scale(1 / len);
        for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(0.3),
                t -> t != mob && t.isAlive() && !st.hit.contains(t.getUUID()) && !mob.hasPassenger(t))) {
            st.hit.add(t.getUUID());
            if (hit(mob, st, t, baby, level)) return;
        }
        if (done >= len || st.age > LOST) {
            nextAfterDash(mob, st, level, baby);
            return;
        }
        if (mob.horizontalCollision && st.age > 2) {
            if (st.profile == RAVAGER) { // kicked back by the terrain, it and its rider stunned
                mob.setDeltaMovement(kickBack(dir));
                mob.hurtMarked = true;
                Stuns.apply(mob, WALL_STUN);
                for (Entity rider : mob.getPassengers()) if (rider instanceof LivingEntity l) Stuns.apply(l, WALL_STUN);
                STATES.remove(mob);
                NEXT.put(mob, level.getGameTime() + st.profile.cooldown());
                WindUp.signal(mob, st.victim, "charge_dazed", "start", (int) Math.round(WALL_STUN * 20)); // tipped forward, dazed, while the stun lasts
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_STUNNED, SoundSource.HOSTILE, 1.5f, 1f);
                Diag.event("charge", "result", "wall", "mob", id(mob), "phase", st.phase.name(), "riders", mob.getPassengers().size(), "tick", level.getGameTime());
                return;
            }
            int stagger = st.profile == COW ? COW_WALL : WALL_STAGGER;
            Stuns.apply(mob, stagger / 20.0); // a hoglin (or a cow) staggers itself
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), st.profile == COW ? SoundEvents.COW_HURT : SoundEvents.HOGLIN_HURT, SoundSource.HOSTILE, 1.2f, 0.6f);
            Diag.event("charge", "result", "wall", "mob", id(mob), "phase", st.phase.name(), "tick", level.getGameTime());
            st.pause = stagger;
            nextAfterDash(mob, st, level, baby);
            if (STATES.containsKey(mob)) WindUp.signal(mob, st.victim, "charge_dazed", "start", stagger); // dazed while the stagger lasts; the charge waits for it (tick)
            return;
        }
        double speed = st.profile == RAVAGER ? RAM_SPEED : st.profile == COW ? COW_SPEED : baby ? BABY_SPEED : SPEED;
        mob.setDeltaMovement(dir.x * speed, mob.getDeltaMovement().y, dir.z * speed);
        mob.hurtMarked = true;
        face(mob, st.to);
        if (st.age % 3 == 0) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, mob.getX(), mob.getY() + 0.1, mob.getZ(), 1, 0.2, 0, 0.2, 0.01);
    }

    /** One hit of a dash on {@code t}; true when it ended the charge (a baby parried, which then flees). */
    private static boolean hit(Mob mob, State st, LivingEntity t, boolean baby, ServerLevel level) {
        DamageSource src = mob.damageSources().mobAttack(mob);
        boolean blocking = t instanceof Player pl && pl.isDamageSourceBlocked(src);
        float damage = st.profile == COW ? (float) COW_DAMAGE : (float) (mob.getAttributeValue(Attributes.ATTACK_DAMAGE) * DAMAGE); // a cow has no attack stat
        unparryable = !(st.profile.parryable() || baby && st.profile.babyParry());
        boolean hurt;
        try {
            hurt = t.hurt(src, damage);
        } finally {
            unparryable = false;
        }
        if (t instanceof Player pl && Guard.justParried(pl)) { // only a baby hoglin's can be
            if (mob instanceof Hoglin hoglin) { // it runs off, as when hit
                hoglin.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                hoglin.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                hoglin.getBrain().setMemoryWithExpiry(MemoryModuleType.AVOID_TARGET, pl, 100);
            }
            end(mob, pl, "parried", false);
            return true;
        }
        if (blocking && t instanceof Player && st.profile == COW) { // vanilla's block, nothing more: it is a cow
            Diag.event("charge", "result", "blocked", "mob", id(mob), "phase", st.phase.name(), "stun", false, "tick", level.getGameTime());
            return false;
        }
        if (blocking && t instanceof Player pl) {
            boolean stun = stunsOnBlock(st.profile, baby, st.phase);
            if (stun) {
                Stuns.apply(pl, BLOCK_STUN);
                st.blockedFirst = true;
                st.pinned = true;
            } else {
                pl.disableShield();
            }
            Diag.event("charge", "result", "blocked", "mob", id(mob), "phase", st.phase.name(), "stun", stun, "tick", level.getGameTime());
            return false;
        }
        if (hurt && st.profile == COW) { // butted aside
            Vec3 dir = dir(st);
            t.knockback(COW_SHOVE, -dir.x, -dir.z);
            t.hurtMarked = true;
        } else if (hurt && st.profile == RAVAGER) { // tossed up over its back by the horns, and stunned
            t.setDeltaMovement(fling(dir(st)));
            t.hurtMarked = true;
            Stuns.apply(t, RAM_STUN);
        } else if (hurt && t instanceof Player pl && !baby) { // run over blind: thrown up and back, and hard-stunned
            pl.setDeltaMovement(launch(dir(st)));
            pl.hurtMarked = true;
            Stuns.apply(pl, HIT_STUN);
            if (st.phase == Phase.DASH1) st.pinned = true;
        } else if (hurt) {
            HoglinBase.throwTarget(mob, t);
        }
        Diag.event("charge", "result", "hit", "mob", id(mob), "victim", BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).toString(), "phase", st.phase.name(), "tick", level.getGameTime());
        return false;
    }

    private static void nextAfterDash(Mob mob, State st, ServerLevel level, boolean baby) {
        if (st.profile.retaliates()) { // a cow flees on the way it dashed, and may turn again
            st.dashes++;
            if (another(st.dashes, st.total)) {
                st.phase = Phase.FLEE;
                st.age = COW_FLEE - FLEE_AFTER; // a shorter run before it may turn again
                st.veerAt = st.age;
                st.stagger = 0;
                st.fleeDir = dir(st).lengthSqr() < 1e-6 ? st.fleeDir : dir(st);
                st.hit.clear();
                WindUp.signal(mob, st.victim, "charge_dash", "ended", LOST);
            } else {
                end(mob, st.victim, "calm", false);
            }
            return;
        }
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.2, 1, 0.2));
        if (st.phase == Phase.DASH1 && st.profile.twice()) {
            if (st.pinned && st.victim != null && Stuns.isStunned(st.victim)) { // still down from the first dash: kept down through the turn, the roar and the second line
                Stuns.apply(st.victim, (st.pause + ticks(Phase.TURN, baby) + ticks(Phase.ROAR, baby) + ticks(Phase.LINE2, baby) + ticksToReach(mob, st.victim, baby)) / 20.0);
            }
            st.phase = Phase.TURN;
            st.age = 0;
            WindUp.signal(mob, st.victim, "charge_dash", "ended", LOST);
        } else {
            end(mob, st.victim, "done", st.profile.tires());
        }
    }

    /**
     * A player hurt an adult stampeder (a cow): it and every adult of its herd within {@link #HERD_RADIUS} not already in a charge flee the same way, the others a few ticks behind,
     * each turning to dash on its own when it can.
     */
    @SubscribeEvent
    static void hurt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof Mob cow) || !(cow.level() instanceof ServerLevel level) || cow.isBaby() || !(e.getSource().getEntity() instanceof Player p)) return;
        if (!Combat.landed(e) || !CommonConfig.MOB_WIND_UPS.get() || p.isCreative() || p.isSpectator()) return;
        Profile profile = profile(cow);
        if (profile == null || !profile.retaliates() || STATES.containsKey(cow)) return;
        Vec3 away = fleeFrom(cow.position(), p.position());
        int herd = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, cow.getBoundingBox().inflate(HERD_RADIUS), m -> m.isAlive() && !m.isBaby() && profile(m) == profile && !STATES.containsKey(m))) {
            State s = new State(profile, p, m.position(), m.position());
            s.phase = Phase.FLEE;
            s.fleeDir = away;
            s.stagger = m == cow ? 0 : m.getRandom().nextInt(COW_STAGGER + 1);
            STATES.put(m, s);
            if (m == cow && cow.getRandom().nextDouble() < BITE_CHANCE) bunt(cow, s, p); // it bites back instead of running
            herd++;
        }
        Diag.event("charge", "result", "stampede", "mob", id(cow), "herd", herd, "tick", level.getGameTime());
    }

    /** A cow turns on its attacker for a bunt: head down for {@link #BUNT_TELL} ticks, then the light hit. */
    private static void bunt(Mob cow, State st, Player p) {
        st.phase = Phase.BUNT;
        st.age = 0;
        face(cow, p.position());
        WindUp.signal(cow, p, "charge_dash", "start", BUNT_TELL);
        cow.level().playSound(null, cow.getX(), cow.getY(), cow.getZ(), SoundEvents.COW_AMBIENT, SoundSource.NEUTRAL, 1.3f, 0.6f);
    }

    private static void end(Mob mob, Player p, String result, boolean tire) {
        State st = STATES.remove(mob);
        long now = mob.level().getGameTime();
        NEXT.put(mob, now + (st == null ? COOLDOWN : st.profile.cooldown()));
        if (tire) {
            TIRED_UNTIL.put(mob, now + TIRED);
            SLOWED_UNTIL.put(mob, now + TIRED + SLOWED);
            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, TIRED, 2, false, false));
            WindUp.signal(mob, p, "charge_tired", "start", TIRED);
        } else {
            WindUp.signal(mob, p, "charge_dash", "ended", LOST);
        }
        Diag.event("charge", "result", result, "mob", id(mob), "tick", now);
    }
}
