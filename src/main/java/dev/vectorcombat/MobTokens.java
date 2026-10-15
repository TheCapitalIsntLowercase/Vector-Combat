package dev.vectorcombat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Turn-taking in a crowd ({@code [mobs] attackTokens}): only so many melee mobs go for one player at a time (the attackers, kept from one look to the next so turns do not
 * flicker, the nearest filling a free one); the rest wait their turn. Where a wind-up would start ({@code MeleeAttackGoalMixin}, {@link BrainWindUp}) a waiting mob holds its
 * attack. A waiting goal mob ({@code [mobs] circling}) does not crowd in: it holds a spot on a ring {@code [mobs] circleRadius} blocks round the player, the waiting mobs spread
 * evenly over the side away from the attackers so the player is boxed in; the ring prowls (sways round the player together, back and forth) and now and then one feints a few
 * steps in and back. Turns rotate: an attacker that has had its turn for {@link #ROTATE_TICKS} gives way to a waiting one. Every so often the pack surges ({@code [mobs]
 * packSurgeSize}): that many waiting mobs from the ring's flanks rush in together, on top of the attackers, so the player is hit from several sides in a quick volley (still
 * {@code attackerSpacingTicks} apart). Every {@link #ASSIGN_EVERY} ticks the roles and spots are worked out again; special attacks (pounces, charges, jukes) keep their own
 * timing, and ranged mobs and brain mobs are never steered.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobTokens {
    private MobTokens() {
    }

    /** Blocks round the player within which the crowd is gathered. */
    static final double GATHER = 16;
    /** Ticks between two looks at each player's crowd. */
    static final int ASSIGN_EVERY = 5;
    /** Degrees either side of the attackers' side kept free of waiting mobs. */
    static final double GAP = 50;
    /** Ticks a feint lasts, and how far in it comes (blocks off the ring). */
    static final int FEINT_TICKS = 12;
    static final double FEINT_IN = 2.5;
    /** The ring's prowl: degrees it sways either way round the player, and ticks a full sway takes. */
    static final double PROWL = 30;
    static final int PROWL_PERIOD = 100;
    /** Ticks an attacker keeps its turn before a waiting mob takes it (unless it is winding up). */
    static final int ROTATE_TICKS = 100;
    /** Ticks a surge lasts, and the least and most ticks between two surges at one player. */
    static final int SURGE_TICKS = 60;
    static final int SURGE_MIN = 100, SURGE_MAX = 200;

    /** A waiting mob's spot: the player it waits for and its angle round them (degrees). */
    private record Slot(LivingEntity target, double angle) {
    }

    /** A mob rushing in with a surge at {@code target} until game time {@code until}. */
    private record Surge(LivingEntity target, long until) {
    }

    private static final Map<Mob, Surge> SURGE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LivingEntity, Long> NEXT_SURGE = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time each attacker took its turn. */
    private static final Map<Mob, Long> HELD = Collections.synchronizedMap(new WeakHashMap<>());

    /** The ring's sway (degrees) at game time {@code now} for a player whose phase is {@code phase}: the whole ring moves together, so its mobs never cross. */
    static double sway(long now, int phase) {
        return PROWL * Math.sin((now + phase * 13L) * 2 * Math.PI / PROWL_PERIOD);
    }

    /** The order turns are given in: winding up first, then an attacker still within its turn, then a waiting mob, then an attacker whose turn is over. */
    static int rank(boolean windingUp, boolean attacker, long heldSince, long now) {
        if (windingUp) return 0;
        if (!attacker) return 2;
        return now - heldSince < ROTATE_TICKS ? 1 : 3;
    }

    /** Which of {@code n} waiting mobs, in their order round the ring, surge when {@code k} do: the flanks first (first, last, second, second-last...), so they come from apart. */
    static int[] flanks(int n, int k) {
        int[] out = new int[Math.min(n, Math.max(0, k))];
        for (int i = 0; i < out.length; i++) out[i] = i % 2 == 0 ? i / 2 : n - 1 - i / 2;
        return out;
    }

    private static boolean surging(Mob m, LivingEntity target, long now) {
        Surge s = SURGE.get(m);
        return s != null && s.target() == target && now < s.until();
    }

    private static int surgers(LivingEntity target, long now) {
        synchronized (SURGE) {
            return (int) SURGE.values().stream().filter(s -> s.target() == target && now < s.until()).count();
        }
    }

    private static final Map<Mob, Slot> WAITERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LivingEntity, List<Mob>> ATTACKERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Mob, Long> FEINT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Mob, Boolean> MELEE = Collections.synchronizedMap(new WeakHashMap<>());

    /** A target that left the world: removed, or a player who logged out (who can still read as alive). */
    static boolean gone(LivingEntity target) {
        return target.isRemoved() || target instanceof ServerPlayer sp && sp.hasDisconnected();
    }

    /** Whether a mob may take a turn when {@code busy} others already wind up at the same player and the limit is {@code limit} (0: no limit). */
    static boolean free(int busy, int limit) {
        return limit <= 0 || busy < limit;
    }

    /**
     * The angles (degrees) of {@code n} waiting spots round a player whose attackers stand at angle {@code attackers}: spread evenly over the circle less {@link #GAP} either
     * side of the attackers, so the far side, the way out, is covered first.
     */
    static double[] spots(int n, double attackers) {
        double[] a = new double[n];
        double span = 360 - 2 * GAP;
        for (int i = 0; i < n; i++) a[i] = attackers + GAP + span * (i + 0.5) / n;
        return a;
    }

    private static boolean targets(Mob m, LivingEntity target) {
        if (m.getTarget() == target) return true;
        var brain = m.getBrain(); // a memory the brain does not have throws when read, so ask first
        return brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT) && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == target;
    }

    private static boolean melee(Mob m) {
        return MELEE.computeIfAbsent(m, k -> m.goalSelector.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof MeleeAttackGoal));
    }

    /** A goal mob that fights {@code p} in melee and can be steered. */
    private static boolean crowd(Mob m, ServerPlayer p) {
        return m.isAlive() && m.getTarget() == p && m instanceof PathfinderMob && !(m.getMainHandItem().getItem() instanceof ProjectileWeaponItem) && WindUp.applicable(m, p) && melee(m);
    }

    private static double angleOf(Vec3 from, Vec3 center) {
        return Math.toDegrees(Math.atan2(from.z - center.z, from.x - center.x));
    }

    @SubscribeEvent
    static void assign(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % ASSIGN_EVERY != 0) return;
        int limit = CommonConfig.ATTACK_TOKENS.get();
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            List<Mob> crowd = limit <= 0 || !p.isAlive() || p.isSpectator() ? List.of()
                    : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(GATHER), m -> crowd(m, p));
            List<Mob> was = ATTACKERS.getOrDefault(p, List.of());
            long now = p.level().getGameTime();
            synchronized (WAITERS) {
                WAITERS.values().removeIf(s -> s.target() == p || gone(s.target()));
            }
            synchronized (SURGE) {
                SURGE.entrySet().removeIf(en -> now >= en.getValue().until() || !en.getKey().isAlive() || gone(en.getValue().target()));
            }
            // A surging mob is an attacker on top of the limit until its surge ends.
            crowd = crowd.stream().filter(m -> !surging(m, p, now)).toList();
            if (crowd.size() <= limit) {
                held(crowd, now);
                ATTACKERS.put(p, List.copyOf(crowd));
                continue;
            }
            // The attackers: those winding up, then those still within their turn, then the waiting nearest, then those whose turn is over.
            List<Mob> order = new ArrayList<>(crowd);
            order.sort(Comparator.<Mob>comparingInt(m -> rank(MobGuard.windingUp(m), was.contains(m), HELD.getOrDefault(m, now), now)).thenComparingDouble(m -> m.distanceToSqr(p)));
            List<Mob> attackers = List.copyOf(order.subList(0, limit)), waiting = new ArrayList<>(order.subList(limit, order.size()));
            for (Mob m : was) if (!attackers.contains(m) && crowd.contains(m)) Diag.event("token_rotate", "out", m.getType().getDescriptionId(), "tick", now);
            held(attackers, now);
            ATTACKERS.put(p, attackers);
            double ax = 0, az = 0;
            for (Mob m : attackers) {
                Vec3 d = m.position().subtract(p.position()).normalize();
                ax += d.x;
                az += d.z;
            }
            double side = Math.toDegrees(Math.atan2(az, ax));
            // Each waiting mob takes the spot that keeps them in the order they already stand round the player, so none crosses another's path.
            waiting.sort(Comparator.comparingDouble(m -> Math.floorMod(Math.round(angleOf(m.position(), p.position()) - side - GAP), 360)));
            // The pack surges now and then: mobs from the ring's flanks rush in together, on top of the attackers.
            int size = CommonConfig.PACK_SURGE_SIZE.get();
            Long next = NEXT_SURGE.get(p);
            if (next == null) NEXT_SURGE.put(p, now + SURGE_MIN / 2 + p.getRandom().nextInt(SURGE_MIN));
            else if (size > 0 && now >= next) {
                List<Mob> rush = new ArrayList<>();
                for (int i : flanks(waiting.size(), size)) rush.add(waiting.get(i));
                for (Mob m : rush) {
                    SURGE.put(m, new Surge(p, now + SURGE_TICKS));
                    m.playAmbientSound(); // the pack's call: the rush is heard as well as seen
                }
                waiting.removeAll(rush);
                NEXT_SURGE.put(p, now + SURGE_MIN + p.getRandom().nextInt(SURGE_MAX - SURGE_MIN + 1));
                Diag.event("token_surge", "rush", rush.size(), "attackers", attackers.size(), "waiting", waiting.size(), "tick", now);
            }
            double[] at = spots(waiting.size(), side);
            for (int i = 0; i < waiting.size(); i++) WAITERS.put(waiting.get(i), new Slot(p, at[i]));
            if (p.tickCount % 40 < ASSIGN_EVERY) Diag.event("token_assign", "crowd", crowd.size(), "attackers", attackers.size(), "waiting", waiting.size(), "attackSideDeg", Math.round(side),
                    "radius", CommonConfig.CIRCLE_RADIUS.get(), "tick", p.level().getGameTime());
        }
    }

    private static void held(List<Mob> attackers, long now) {
        for (Mob m : attackers) HELD.putIfAbsent(m, now);
        synchronized (HELD) {
            HELD.keySet().removeIf(m -> !attackers.contains(m) && !m.isAlive());
        }
    }

    /**
     * Whether {@code m}'s melee goal should stand aside this tick: it waits on the ring, is not winding up, and (with {@code waitersFight}) the player is not within its reach. Its
     * goal would otherwise re-path it at the player every few ticks, against the ring.
     */
    public static boolean steering(Mob m) {
        if (WAITERS.isEmpty() || !CommonConfig.CIRCLING.get()) return false;
        Slot s = WAITERS.get(m);
        if (s == null || m.getTarget() != s.target() || MobGuard.windingUp(m)) return false;
        return !(CommonConfig.WAITERS_FIGHT.get() && m.isWithinMeleeAttackRange(s.target()));
    }

    /** A waiting goal mob makes for its spot on the ring, which prowls round the player ({@link #sway}), faces the player there, and now and then feints in. */
    @SubscribeEvent
    static void steer(EntityTickEvent.Post e) {
        if (WAITERS.isEmpty() || !(e.getEntity() instanceof Mob m) || m.level().isClientSide()) return;
        Slot s = WAITERS.get(m);
        if (s == null) return;
        LivingEntity target = s.target();
        if (!m.isAlive() || !target.isAlive() || gone(target) || m.getTarget() != target) {
            WAITERS.remove(m);
            return;
        }
        m.getLookControl().setLookAt(target, 30f, 30f);
        if (!CommonConfig.CIRCLING.get() || MobGuard.windingUp(m)) return;
        if (CommonConfig.WAITERS_FIGHT.get() && m.isWithinMeleeAttackRange(target)) return; // the player came to it: it fights, its goal leads
        long now = m.level().getGameTime();
        Long feintUntil = FEINT.get(m);
        boolean feinting = feintUntil != null && now < feintUntil;
        if (!feinting && m.getRandom().nextFloat() < 0.006f) { // about one feint every 8 s per waiting mob
            FEINT.put(m, now + FEINT_TICKS);
            feinting = true;
            Diag.event("token_feint", "mob", m.getType().getDescriptionId(), "tick", now);
        }
        double r = Math.max(2, CommonConfig.CIRCLE_RADIUS.get() - (feinting ? FEINT_IN : 0)), a = Math.toRadians(s.angle() + sway(now, target.getId()));
        Vec3 spot = new Vec3(target.getX() + Math.cos(a) * r, target.getY(), target.getZ() + Math.sin(a) * r);
        var nav = m.getNavigation();
        double far = m.position().distanceToSqr(spot);
        if (far < 0.6 * 0.6) { // on its spot: the spot moves on with the prowl, so this is a step's pause, not a stand
            nav.stop();
            return;
        }
        // Never stand still on the spot: a ring of mobs standing about does not read as circling.
        var going = nav.getTargetPos();
        if (going == null || nav.isDone() || going.distToCenterSqr(spot) > 2.25) nav.moveTo(spot.x, spot.y, spot.z, far > 9 ? CommonConfig.CIRCLE_SPEED.get() : 1.0);
    }

    /** Whether {@code mob} may start a wind-up at {@code target} now: not while it waits its turn, and never past the limit of mobs winding up at once. */
    public static boolean mayStart(Mob mob, LivingEntity target) {
        if (!turn(mob, target)) return false;
        // Spacing: no two wind-ups at one player start within [mobs] attackerSpacingTicks of each other, so two attackers never land in the same tick (twice, a tick apart).
        int spacing = CommonConfig.ATTACKER_SPACING.get();
        if (!(target instanceof net.minecraft.world.entity.player.Player) || spacing <= 0) return true;
        long now = mob.level().getGameTime();
        Long last = LAST_START.get(target);
        return spaced(last == null ? Long.MIN_VALUE / 2 : last, now, spacing);
    }

    /** A wind-up at {@code target} started at game time {@code now} ({@link WindUp#signal}): the spacing counts from it. */
    public static void started(LivingEntity target, long now) {
        if (target instanceof net.minecraft.world.entity.player.Player) LAST_START.put(target, now);
    }

    /** Game time the last wind-up at each player started. */
    private static final Map<LivingEntity, Long> LAST_START = Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether a wind-up may start at {@code now} when the last one at the same player started at {@code last}, {@code spacing} ticks being the least between two. */
    static boolean spaced(long last, long now, int spacing) {
        return spacing <= 0 || now - last >= spacing;
    }

    private static boolean turn(Mob mob, LivingEntity target) {
        int limit = CommonConfig.ATTACK_TOKENS.get();
        if (limit <= 0) return true;
        long now = mob.level().getGameTime();
        if (surging(mob, target, now)) return true; // a surge comes in on top of the turns
        limit += surgers(target, now);
        Slot s = WAITERS.get(mob);
        if (s != null && s.target() == target && !CommonConfig.WAITERS_FIGHT.get()) return false;
        if (s != null && CommonConfig.WAITERS_FIGHT.get()) return true; // waiting limits who comes in, not who hits: one the player walks up to fights
        int busy = mob.level().getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(GATHER),
                m -> m != mob && m.isAlive() && MobGuard.windingUp(m) && targets(m, target)).size();
        boolean ok = free(busy, limit);
        if (!ok && mob.tickCount % 20 == 0) Diag.event("token_wait", "mob", mob.getType().getDescriptionId(), "busy", busy, "limit", limit, "tick", mob.level().getGameTime());
        return ok;
    }

    /** A goal mob held at its attack: a waiting one goes on to its spot ({@link #steer}); one held by the limit alone steps back a little rather than pressing in. */
    public static void circle(Mob mob, LivingEntity target) {
        if (!CommonConfig.CIRCLING.get() || WAITERS.containsKey(mob)) return;
        mob.getNavigation().stop();
        mob.getMoveControl().strafe(mob.distanceTo(target) < 2.0f ? -0.4f : 0f, (mob.getId() & 1) == 0 ? 0.5f : -0.5f);
        mob.getLookControl().setLookAt(target, 30f, 30f);
    }
}
