package dev.vectorcombat;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * A vex going for a player, instead of vanilla's charge, which flew straight at the eyes through blocks (a vex has no collision) and so dipped into the ground:
 * <ol>
 * <li><b>hover</b>: it circles the player {@link #RADIUS} blocks out, {@link #HEIGHT} above their feet and never less than {@link #CLEARANCE} above the floor under it, never inside a
 * block, watching;</li>
 * <li><b>opening</b>: after {@link #MIN_HOVER} ticks it takes the first opening (the player just swung, their shield is down, or they look more than {@link #LOOK_AWAY} degrees away),
 * and after {@link #MAX_HOVER} it goes anyway;</li>
 * <li><b>tell</b>: it stops, raises its arms and glows (vanilla's charging state) with the charge sound for {@link #TELL} ticks, and locks on the player's chest where it is then;</li>
 * <li><b>swoop</b>: a U through that point ({@link #swoop}): down from where it hovers, lowest at the chest, and up the far side to the same height, at {@link #SPEED} blocks a tick, no
 * homing, so a sidestep dodges it and a parry meets it. A hit or a block carries on up the curve (a straight dash kept its momentum into the ground); a block in the way ends it
 * early, climbing;</li>
 * <li><b>parried</b>: it drops to the floor and lies there through the stagger, open to a strike ({@link #fall}), then flies up and {@link #RETREAT retreats} to {@link #RETREAT_RADIUS};</li>
 * <li><b>recovers</b>: climbs back to its hover for {@link #RECOVER} ticks before watching again.</li>
 * </ol>
 * Against anything that is not a player vanilla's charge still runs (this goal has the higher priority and only takes players). The geometry and the opening are pure.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class VexDash extends Goal {
    static final double RADIUS = 3.5, HEIGHT = 2.5, CLEARANCE = 1.5, SPEED = 0.7, RETREAT_RADIUS = 6;
    static final int MIN_HOVER = 20, MAX_HOVER = 60, TELL = 10, RECOVER = 10, RETREAT = 30;
    static final double LOOK_AWAY = 60;

    /** Degrees a tick the hover point travels round the player (a lap in about 4.5 s). */
    static final double ORBIT = 4;

    /** How far below the hover point the floor is looked for, in blocks. */
    static final int FLOOR_SCAN = 8;

    /** A parried vex's fall: gravity a tick and the fastest it falls (blocks), vanilla's for a falling mob. */
    static final double GRAVITY = 0.08, TERMINAL = 1.0;

    enum Phase { HOVER, TELL, SWOOP, RECOVER, RETREAT }

    private final Vex vex;
    private Phase phase = Phase.HOVER;
    private int age;
    private double angle;
    private int spin = 1;
    private Vec3 at, p0, p1, p2;
    private int steps;
    private boolean touched;

    /** Server side: the parried vexes that are falling or lying on the floor, and how fast each falls now. */
    private static final Map<Vex, double[]> DROPPED = new WeakHashMap<>();

    public VexDash(Vex vex) {
        this.vex = vex;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Player target() {
        return vex.getTarget() instanceof Player p && p.isAlive() && !p.isSpectator() && !p.isCreative() ? p : null;
    }

    @Override
    public boolean canUse() {
        return CommonConfig.MOB_WIND_UPS.get() && target() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        Player p = target();
        angle = Math.toDegrees(Math.atan2(vex.getZ() - p.getZ(), vex.getX() - p.getX()));
        spin = vex.getRandom().nextBoolean() ? 1 : -1;
        if (phase != Phase.RETREAT) enter(Phase.HOVER);
    }

    @Override
    public void stop() {
        vex.setIsCharging(false);
        if (phase != Phase.RETREAT) phase = Phase.HOVER;
    }

    private void enter(Phase next) {
        phase = next;
        age = 0;
    }

    // ---- pure ----

    /** The point on the circle round ({@code px}, {@code pz}) at {@code degrees}, {@code radius} out (x and z only). */
    static Vec3 orbit(double px, double pz, double degrees, double radius) {
        double a = Math.toRadians(degrees);
        return new Vec3(px + Math.cos(a) * radius, 0, pz + Math.sin(a) * radius);
    }

    static Vec3 orbit(double px, double pz, double degrees) {
        return orbit(px, pz, degrees, RADIUS);
    }

    /** The hover height: {@link #HEIGHT} above the player's feet at {@code feet}, and never less than {@link #CLEARANCE} above the floor under the point ({@code floor}, null for none found). */
    static double hoverY(double feet, Double floor) {
        double y = feet + HEIGHT;
        return floor == null ? y : Math.max(y, floor + CLEARANCE);
    }

    /** Whether the player gives an opening: they just swung (attack not recharged), their shield is down, or they look more than {@link #LOOK_AWAY} degrees from the vex. */
    static boolean opening(float attackCharge, boolean blocking, double lookAwayDegrees) {
        return attackCharge < 0.9f || !blocking || lookAwayDegrees > LOOK_AWAY;
    }

    /** Whether it commits after {@code hovered} ticks with or without an opening. */
    static boolean commits(int hovered, boolean opening) {
        return hovered >= MAX_HOVER || hovered >= MIN_HOVER && opening;
    }

    /**
     * The swoop from {@code from} through {@code at} (the player's chest), as a quadratic Bézier's three points: it starts where the vex is, ends as far past the chest on the other side,
     * level with the start, and its middle control point puts the curve's middle on the chest. With the start above the chest the curve's lowest point is the chest
     * ({@code y(t) = h - 4t(1 - t)(h - chest)}), so over flat ground it never goes lower than the player's own chest.
     */
    static Vec3[] swoop(Vec3 from, Vec3 at) {
        double y = Math.max(from.y, at.y);
        Vec3 start = new Vec3(from.x, y, from.z);
        Vec3 end = new Vec3(2 * at.x - from.x, y, 2 * at.z - from.z);
        Vec3 control = at.scale(2).subtract(start.add(end).scale(0.5));
        return new Vec3[] {start, control, end};
    }

    /** The point {@code t} (0 to 1) along a quadratic Bézier. */
    static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, double t) {
        double u = 1 - t;
        return a.scale(u * u).add(b.scale(2 * u * t)).add(c.scale(t * t));
    }

    /** How many ticks a swoop takes at {@link #SPEED} (its length from sixteen chords). */
    static int steps(Vec3 a, Vec3 b, Vec3 c) {
        double len = 0;
        Vec3 last = a;
        for (int i = 1; i <= 16; i++) {
            Vec3 next = bezier(a, b, c, i / 16.0);
            len += next.distanceTo(last);
            last = next;
        }
        return Math.max(4, (int) Math.ceil(len / SPEED));
    }

    /** A falling vex's next height and speed: down by its speed, which grows by {@link #GRAVITY} to {@link #TERMINAL}, never below {@code floor} (null: no floor in reach, it stays). */
    static double[] fall(double y, double speed, Double floor) {
        if (floor == null) return new double[] {y, 0};
        double v = Math.min(TERMINAL, speed + GRAVITY);
        return new double[] {Math.max(floor, y - v), v};
    }

    // ---- the game ----

    /** The top of the highest solid block under ({@code x}, {@code y}, {@code z}) within {@link #FLOOR_SCAN} blocks, null for none. */
    private static Double floor(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
        for (int i = 0; i <= FLOOR_SCAN; i++, pos.move(0, -1, 0)) {
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()) return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return null;
    }

    /** Whether the vex's box at {@code feet} is clear of blocks. */
    private boolean clear(Vec3 feet) {
        AABB box = vex.getDimensions(vex.getPose()).makeBoundingBox(feet);
        return vex.level().noBlockCollision(vex, box);
    }

    /** The hover point now: round the orbit from where it is, the first clear one of a few further on, at the hover height. Null when there is none. */
    private Vec3 hoverPoint(Player p, double radius) {
        for (int k = 0; k < 8; k++) {
            double a = angle + spin * k * 45;
            Vec3 o = orbit(p.getX(), p.getZ(), a, radius);
            double y = hoverY(p.getY(), floor(vex.level(), o.x, p.getY() + HEIGHT + 1, o.z));
            Vec3 point = new Vec3(o.x, y, o.z);
            if (clear(point)) {
                if (k > 0) angle = a;
                return point;
            }
        }
        return null;
    }

    private static double lookAway(Player p, Vec3 at) {
        Vec3 look = p.getViewVector(1), to = at.subtract(p.getEyePosition());
        double len = to.length();
        return len < 1e-6 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dot(to) / len))));
    }

    private Vec3 centre() {
        return vex.position().add(0, vex.getBbHeight() * 0.5, 0);
    }

    @Override
    public void tick() {
        Player p = target();
        if (p == null) return;
        age++;
        vex.getLookControl().setLookAt(p, 30, 30);
        switch (phase) {
            case HOVER, RECOVER, RETREAT -> {
                angle += spin * ORBIT;
                Vec3 point = hoverPoint(p, phase == Phase.RETREAT ? RETREAT_RADIUS : RADIUS);
                if (point != null) vex.getMoveControl().setWantedPosition(point.x, point.y, point.z, phase == Phase.HOVER ? 0.6 : 1.0);
                if (phase == Phase.RECOVER && age >= RECOVER || phase == Phase.RETREAT && age >= RETREAT) {
                    enter(Phase.HOVER);
                } else if (phase == Phase.HOVER && commits(age, opening(p.getAttackStrengthScale(0), p.isBlocking(), lookAway(p, vex.position())))) {
                    enter(Phase.TELL);
                    vex.setIsCharging(true);
                    vex.playSound(SoundEvents.VEX_CHARGE, 1f, 1f);
                    at = p.position().add(0, p.getBbHeight() * 0.5, 0);
                    Diag.event("vex_dash", "result", "tell", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(vex.getType()).toString(), "dist", Math.round(vex.distanceTo(p) * 100) / 100.0,
                            "tick", vex.level().getGameTime());
                }
            }
            case TELL -> {
                vex.setDeltaMovement(vex.getDeltaMovement().scale(0.5)); // stops in the air
                vex.getMoveControl().setWantedPosition(vex.getX(), vex.getY(), vex.getZ(), 0.1);
                if (age >= TELL) {
                    Vec3[] curve = swoop(centre(), at);
                    p0 = curve[0];
                    p1 = curve[1];
                    p2 = curve[2];
                    steps = steps(p0, p1, p2);
                    touched = false;
                    enter(Phase.SWOOP);
                }
            }
            case SWOOP -> swoop(p);
        }
    }

    private void swoop(Player p) {
        if (!touched && vex.getBoundingBox().inflate(0.2).intersects(p.getBoundingBox())) {
            touched = true;
            vex.doHurtTarget(p);
            if (Stuns.isStunned(vex)) { // parried: it drops, and once the stagger is over it makes distance
                vex.setIsCharging(false);
                vex.setDeltaMovement(Vec3.ZERO);
                DROPPED.put(vex, new double[] {0});
                Diag.event("vex_dash", "result", "parried", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(vex.getType()).toString(), "tick", vex.level().getGameTime());
                enter(Phase.RETREAT);
                return;
            }
            Diag.event("vex_dash", "result", "contact", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(vex.getType()).toString(), "tick", vex.level().getGameTime());
        }
        if (age >= steps) {
            end(touched ? "through" : "missed");
            return;
        }
        Vec3 next = bezier(p0, p1, p2, (age + 1) / (double) steps), step = next.subtract(centre());
        if (!clear(vex.position().add(step))) { // a block ahead: it pulls up instead of entering it
            vex.setDeltaMovement(new Vec3(0, SPEED * 0.5, 0));
            end("blocked");
            return;
        }
        vex.setDeltaMovement(step);
        vex.getMoveControl().setWantedPosition(next.x, next.y - vex.getBbHeight() * 0.5, next.z, 1.0);
    }

    private void end(String result) {
        vex.setIsCharging(false);
        Diag.event("vex_dash", "result", result, "mob", BuiltInRegistries.ENTITY_TYPE.getKey(vex.getType()).toString(), "tick", vex.level().getGameTime());
        enter(Phase.RECOVER);
    }

    /**
     * A parried vex falls to the floor and lies there while it is staggered (its AI is off, so this runs from the entity tick, before vanilla's: the vex has no collision, so gravity
     * alone would sink it through the floor; it is set down on the floor's top instead). When the stagger ends it is let go, and its goal is already retreating.
     */
    @SubscribeEvent
    static void fall(EntityTickEvent.Pre e) {
        if (!(e.getEntity() instanceof Vex vex) || vex.level().isClientSide()) return;
        double[] state = DROPPED.get(vex);
        if (state == null) return;
        if (!Stuns.isStunned(vex) || !vex.isAlive()) {
            DROPPED.remove(vex);
            return;
        }
        double[] next = fall(vex.getY(), state[0], floor(vex.level(), vex.getX(), vex.getY() + 0.5, vex.getZ()));
        state[0] = next[1];
        vex.setDeltaMovement(Vec3.ZERO);
        vex.setPos(vex.getX(), next[0], vex.getZ());
    }

    /** Every vex gets the swoop ahead of vanilla's charge when it joins a level; done once. */
    @SubscribeEvent
    static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Vex vex)) return;
        GoalSelector goals = vex.goalSelector;
        if (goals.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof VexDash)) return;
        goals.addGoal(3, new VexDash(vex));
    }
}
