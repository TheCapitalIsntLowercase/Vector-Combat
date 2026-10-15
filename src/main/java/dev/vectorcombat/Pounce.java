package dev.vectorcombat;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The spider's attack and signature. This goal replaces vanilla's leap on every spider:
 * <ul>
 * <li><b>display</b>: it rears its front half up, front legs raised, and hisses ({@link #DISPLAY}, a cave spider {@link #CAVE_DISPLAY}); a ring marks where it will land;</li>
 * <li><b>pounce</b>: an aimed leap at where the player stood as it took off, a fixed {@link #FLIGHT} ticks on a high arc ({@link Engulf#launch}), a bite as it lands if the player is
 * still in reach; landing on them unblocked pins them ({@link SpiderMoves}); a parried pounce flips it onto its back;</li>
 * <li><b>ceiling drop</b>: climbing above its target (within {@link #DROP_SIDE} sideways, {@link #DROP_MIN} to {@link #DROP_MAX} below) it hangs still, twitching, with a hiss and a
 * ring below ({@link #HANG}), then drops; a miss stuns it {@link #DROP_MISS} ticks;</li>
 * <li><b>web spit</b> (not cave spiders): at {@link #SPIT_MIN} to {@link #SPIT_MAX} blocks, at most every {@link #SPIT_COOLDOWN}, a short display and a lobbed web glob; a hit roots
 * the player and the spider pounces at once, from any range ({@link SpiderMoves});</li>
 * <li><b>circling</b>: after a pounce it skitters sideways round the player ({@link #CIRCLE_MIN} to {@link #CIRCLE_MAX} ticks) before the next attack.</li>
 * </ul>
 * With wind-ups off, or against anything but a player, every method is vanilla's.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Pounce extends LeapAtTargetGoal {
    /** The old crouch, kept for the record: the display took its place. */
    static final int CROUCH_TICKS = 6;

    /** The display (the tell), the flight, and a cave spider's quicker ones; the display before a pounce at a rooted player. */
    static final int DISPLAY = 10, CAVE_DISPLAY = 7, FLIGHT = 12, CAVE_FLIGHT = 10, ROOTED_DISPLAY = 4;

    /** Vanilla's leap: the upward impulse of {@code LeapAtTargetGoal(spider, 0.4F)} and the air physics the game applies each tick (gravity, drag, horizontal friction). */
    static final double UP = 0.4, GRAVITY = 0.08, DRAG = 0.98, AIR_FRICTION = 0.91;

    /** How far from the player's centre the plain leap was aimed to land. */
    static final double STOP_SHORT = 1.2;

    /** The fastest launch. */
    static final double MAX_SPEED = 0.45;

    /** Ticks after a pounce before the spider attacks again: vanilla's attack interval. */
    static final int RECOVERY = 20;

    /** A flight that never ends (a ceiling, a stuck spider) is called a landing after this many ticks. */
    static final int TIMEOUT = 40;

    /** The ceiling drop: how far sideways and below the target may be, the hang, and a miss's stun. */
    static final double DROP_SIDE = 2, DROP_MIN = 3, DROP_MAX = 8;
    static final int HANG = 12, DROP_MISS = 10;

    /** The web spit: its range, cooldown and short display. */
    static final double SPIT_MIN = 6, SPIT_MAX = 12;
    static final int SPIT_COOLDOWN = 160, SPIT_DISPLAY = 8;

    /** The circling after a pounce, and how hard it strafes. */
    static final int CIRCLE_MIN = 20, CIRCLE_MAX = 40;
    static final float CIRCLE_STRAFE = 0.6f;

    /** Ticks airborne, and the horizontal distance covered per unit of launch speed, with {@link #UP}: worked out from the physics above (the plain leap). */
    static final int AIR_TICKS;
    static final double REACH_PER_SPEED;

    static {
        double y = 0, vy = UP;
        int t = 0;
        do {
            y += vy;
            t++;
            vy = (vy - GRAVITY) * DRAG;
        } while (y > 0 && t < 60);
        AIR_TICKS = t;
        double distance = 0;
        for (int k = 0; k < t - 1; k++) distance += Math.pow(AIR_FRICTION, k);
        REACH_PER_SPEED = distance + Math.pow(AIR_FRICTION, t - 1) * 0.5;
    }

    /** The horizontal launch speed that lands {@code landDistance} blocks away after {@link #AIR_TICKS} ticks, within {@link #MAX_SPEED}. */
    static double launchSpeed(double landDistance) {
        return Math.min(MAX_SPEED, Math.max(0, landDistance) / REACH_PER_SPEED);
    }

    // ---- pure rules ----

    /** Whether a spider on a wall {@code side} blocks sideways and {@code below} blocks above its target drops on it. */
    static boolean drops(boolean climbing, double side, double below, boolean sees) {
        return climbing && sees && side <= DROP_SIDE && below >= DROP_MIN && below <= DROP_MAX;
    }

    /** Whether a spider {@code distance} blocks from its target spits (not a cave spider, off cooldown). */
    static boolean spits(boolean cave, double distance, boolean sees, boolean onGround, long now, long next) {
        return !cave && sees && onGround && distance >= SPIT_MIN && distance <= SPIT_MAX && now >= next;
    }

    /** Whether it pounces from {@code distance}: vanilla's two to four blocks, or any range at a player its web has rooted. */
    static boolean pounces(double distance, boolean rooted) {
        return rooted ? distance <= SPIT_MAX + 2 : distance >= 2 && distance <= 4;
    }

    private enum Phase {
        OFF, DISPLAY, AIR, HANG, SPIT, CIRCLE
    }

    private final Mob mob;
    private Phase phase = Phase.OFF;
    private boolean ours; // the last canUse was ours, not vanilla's
    private Phase next = Phase.DISPLAY; // what canUse decided to start
    private long until; // game time the display, hang or spit display ends
    private int air, circle; // ticks since the leap; ticks of circling left
    private boolean left, dropping; // the spider has left the ground; this flight is a drop
    private float side = 1; // which way it circles
    private long spitNext;

    public Pounce(Mob mob, float yd) {
        super(mob, yd);
        this.mob = mob;
    }

    private boolean cave() {
        return mob instanceof CaveSpider;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        ours = WindUp.applicable(mob, target);
        if (!ours) return super.canUse();
        if (WindUp.hurt(mob) || mob.isVehicle() || mob.isInWater() || !WindUp.open(mob) || SpiderMoves.busy(mob)) return false;
        boolean sees = mob.getSensing().hasLineOfSight(target);
        if (mob instanceof Spider s && drops(s.isClimbing(), Math.hypot(target.getX() - mob.getX(), target.getZ() - mob.getZ()), mob.getY() - target.getY(), sees)) {
            next = Phase.HANG;
            return true;
        }
        if (!mob.onGround()) return false;
        double d = mob.distanceTo(target);
        boolean rooted = target instanceof Player p && SpiderMoves.rooted(p);
        if (pounces(d, rooted) && sees) {
            next = Phase.DISPLAY;
            return true;
        }
        if (spits(cave(), d, sees, mob.onGround(), mob.level().getGameTime(), spitNext)) {
            next = Phase.SPIT;
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return phase == Phase.OFF ? super.canContinueToUse() : true;
    }

    @Override
    public boolean isInterruptable() {
        return phase != Phase.AIR && phase != Phase.HANG;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        if (!ours) {
            phase = Phase.OFF;
            super.start();
            return;
        }
        LivingEntity target = mob.getTarget();
        long now = mob.level().getGameTime();
        mob.getNavigation().stop();
        air = 0;
        left = false;
        dropping = false;
        switch (next) {
            case HANG -> { // clings still, twitching, a hiss and a ring on the floor under it
                phase = Phase.HANG;
                until = now + HANG;
                hang(mob, true);
                mob.setDeltaMovement(Vec3.ZERO);
                WindUp.signal(mob, target, "spider_hang", "start", HANG);
                hiss(1.3f);
                SpiderMoves.ring(mob, new Vec3(mob.getX(), target.getY(), mob.getZ()), mob.getBbWidth() * 0.6, HANG + 10);
            }
            case SPIT -> {
                phase = Phase.SPIT;
                until = now + SPIT_DISPLAY;
                spitNext = now + SPIT_COOLDOWN;
                WindUp.signal(mob, target, "pounce_display", "start", SPIT_DISPLAY);
                hiss(1.1f);
            }
            default -> {
                phase = Phase.DISPLAY;
                boolean rooted = target instanceof Player p && SpiderMoves.rooted(p);
                int ticks = rooted ? ROOTED_DISPLAY : cave() ? CAVE_DISPLAY : DISPLAY;
                until = now + ticks;
                WindUp.signal(mob, target, "pounce_display", "start", ticks);
                hiss(cave() ? 1.4f : 0.9f);
            }
        }
    }

    private void hiss(float pitch) {
        mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.6f, pitch);
    }

    @Override
    public void tick() {
        if (phase == Phase.OFF) return;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || !WindUp.applicable(mob, target)) {
            end(target, "interrupted");
            return;
        }
        long now = mob.level().getGameTime();
        switch (phase) {
            case DISPLAY, SPIT -> {
                mob.getLookControl().setLookAt(target, 30f, 30f);
                mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
                if (WindUp.hurt(mob)) end(target, "interrupted");
                else if (now > until + WindUp.STALE) end(target, "stale");
                else if (now >= until) {
                    if (phase == Phase.SPIT) {
                        SpiderMoves.spit(mob, target);
                        WindUp.signal(mob, target, "pounce_display", "ended", SPIT_DISPLAY);
                        WindUp.close(mob, 10);
                        phase = Phase.OFF;
                    } else {
                        leap(target);
                    }
                }
            }
            case HANG -> {
                mob.setDeltaMovement(Vec3.ZERO);
                if (now >= until) { // lets go
                    hang(mob, false);
                    mob.setDeltaMovement(0, -0.6, 0);
                    phase = Phase.AIR;
                    dropping = true;
                    air = 0;
                    left = true;
                    WindUp.signal(mob, target, "pounce", "leap", HANG);
                }
            }
            case AIR -> {
                air++;
                if (!mob.onGround()) left = true;
                if (mob.onGround() && left || air > TIMEOUT) land(target);
            }
            case CIRCLE -> {
                mob.getLookControl().setLookAt(target, 30f, 30f);
                mob.getMoveControl().strafe(0.1f, side * CIRCLE_STRAFE);
                if (--circle <= 0) phase = Phase.OFF;
            }
            default -> {
            }
        }
    }

    @Override
    public void stop() {
        if (phase == Phase.DISPLAY || phase == Phase.SPIT) WindUp.signal(mob, mob.getTarget(), "pounce_display", "goal_stopped", DISPLAY);
        if (phase == Phase.HANG) hang(mob, false);
        phase = Phase.OFF;
    }

    /** The display is over: leap at where the player stands now on a fixed-length arc, so a step aside while it is in the air is a dodge; a ring marks the spot. */
    private void leap(LivingEntity target) {
        int flight = cave() ? CAVE_FLIGHT : FLIGHT;
        Vec3 spot = target.position();
        mob.setDeltaMovement(Engulf.launch(mob.position(), spot, flight));
        mob.hurtMarked = true;
        SpiderMoves.ring(mob, spot, Math.max(0.6, mob.getBbWidth() * 0.5), flight + 2);
        phase = Phase.AIR;
        air = 0;
        left = false;
        WindUp.signal(mob, target, "pounce_leap", "leap", DISPLAY); // out of the raised display, which eases down in the air
    }

    /** Back on the ground: bite if the player is still in reach (parry, block and armour work as for any hit); a landing on them pins, a parry flips it; then it circles. */
    private void land(LivingEntity target) {
        boolean inReach = mob.isWithinMeleeAttackRange(target) && mob.getSensing().hasLineOfSight(target);
        WindUp.signal(mob, target, "pounce", inReach ? "land" : "whiff", DISPLAY);
        WindUp.close(mob, RECOVERY);
        phase = Phase.OFF;
        if (inReach) {
            mob.swing(InteractionHand.MAIN_HAND);
            boolean blocked = target instanceof Player p && p.isDamageSourceBlocked(mob.damageSources().mobAttack(mob));
            boolean hurt = mob.doHurtTarget(target);
            if (target instanceof Player p && Guard.justParried(p)) {
                SpiderMoves.flip(mob);
                return;
            }
            if (hurt && !blocked && target instanceof Player p) {
                if (SpiderMoves.pin(mob, p)) return; // held by another grab: no pin, it circles
            }
        } else if (dropping) {
            Stuns.apply(mob, DROP_MISS / 20.0); // dropped onto nothing
        }
        phase = Phase.CIRCLE; // skitters sideways round the player before the next attack
        circle = CIRCLE_MIN + mob.getRandom().nextInt(CIRCLE_MAX - CIRCLE_MIN + 1);
        side = mob.getRandom().nextBoolean() ? 1 : -1;
    }

    private void end(LivingEntity target, String event) {
        WindUp.signal(mob, target, "pounce_display", event, DISPLAY);
        if (phase == Phase.HANG) hang(mob, false);
        phase = Phase.OFF;
    }

    /** The tag a hanging spider carries, so one saved mid-hang (no gravity) is set right when it loads again. */
    static final String HANGING = "vectorcombat.hanging";

    /** Hangs (no gravity, tagged) or lets go. */
    static void hang(Mob mob, boolean on) {
        mob.setNoGravity(on);
        if (on) mob.addTag(HANGING);
        else mob.removeTag(HANGING);
    }

    /** Every spider's vanilla leap becomes a pounce when it joins a level (cave spiders and other spiders too); done once. */
    @SubscribeEvent
    static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Spider spider)) return;
        if (spider.getTags().contains(HANGING)) hang(spider, false); // saved mid-hang (its chunk unloaded): its gravity back
        GoalSelector goals = spider.goalSelector;
        if (goals.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof Pounce)) return;
        goals.removeAllGoals(g -> g instanceof LeapAtTargetGoal);
        goals.addGoal(3, new Pounce(spider, 0.4f));
    }
}
