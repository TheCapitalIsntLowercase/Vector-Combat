package dev.vectorcombat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Per-player velocity from position deltas. Players' own deltaMovement is not reliable on the server, and this gives client and server the same signal. The server
 * keeps the last {@link #HISTORY} sampled states of each player so it can check a client's claim about how it was moving ({@link Strike#start}).
 *
 * <p>Everything is kept per side. {@code Entity.equals} and {@code hashCode} compare network ids, and in singleplayer the client's player and the integrated
 * server's share theirs, so one map keyed by player gave both threads the same entry: their samples interleaved (a jump read 0.42 twice) and the render thread
 * printed a history the server thread was changing, a {@code ConcurrentModificationException} from {@link #history} that crashed the game twice. A side's state is
 * only touched by that side's thread.
 */
public final class MoveTracker {
    /** How many ticks of samples are kept for each player. */
    static final int HISTORY = 8;

    /** What one side (the client or the server) has seen of the players it knows, keyed by whatever identifies a player there (the player; any key with its own equality in the test). */
    static final class Side {
        /** One game tick's sample: where the player was, how far it moved since the sample before, and that as a movement state. */
        private record Sample(Vec3 pos, Vec3 v, MoveState state) {
        }

        private final Map<Object, ArrayDeque<Sample>> ring = new WeakHashMap<>();
        private final Map<Object, Long> tick = new WeakHashMap<>();

        /** Takes this game tick's sample for {@code who}, its movement state made from the move since the last sample by {@code toState}; false (nothing taken) when the tick already has one. */
        boolean sample(Object who, long gameTime, Vec3 now, Function<Vec3, MoveState> toState) {
            // The log showed samples alternating between two phases of one tick (vy -0.156, +0.078, ...), so a swing read
            // either a full tick of movement or about none. Only the first sample of each game tick counts: same phase every time.
            Long seen = tick.put(who, gameTime);
            if (seen != null && seen == gameTime) return false;
            ArrayDeque<Sample> d = ring.computeIfAbsent(who, k -> new ArrayDeque<>());
            Vec3 v = d.isEmpty() ? Vec3.ZERO : now.subtract(d.peekLast().pos());
            d.addLast(new Sample(now, v, toState.apply(v)));
            if (d.size() > HISTORY) d.pollFirst();
            return true;
        }

        /** How far {@code who} moved over the last sampled tick. */
        Vec3 velocity(Object who) {
            ArrayDeque<Sample> d = ring.get(who);
            return d == null || d.isEmpty() ? Vec3.ZERO : d.peekLast().v();
        }

        /** The newest {@code n} sampled states of {@code who}, newest first. */
        List<MoveState> recent(Object who, int n) {
            ArrayDeque<Sample> d = ring.get(who);
            List<MoveState> out = new ArrayList<>();
            if (d == null) return out;
            for (Iterator<Sample> it = d.descendingIterator(); it.hasNext() && out.size() < n; ) out.add(it.next().state());
            return out;
        }

        /** For the debug log: Height, vertical speed and ground flag of the kept samples, oldest first. */
        String history(Object who) {
            ArrayDeque<Sample> d = ring.get(who);
            if (d == null) return "[]";
            StringBuilder b = new StringBuilder("[");
            for (Sample s : d) b.append(b.length() > 1 ? ", " : "").append(String.format(Locale.ROOT, "y=%.2f vy=%.3f g=%b", s.pos().y, s.v().y, s.state().onGround()));
            return b.append(']').toString();
        }
    }

    private static final Side CLIENT = new Side();
    private static final Side SERVER = new Side();

    static Side side(boolean client) {
        return client ? CLIENT : SERVER;
    }

    private static Side side(Player p) {
        return side(p.level().isClientSide);
    }

    private static MoveState state(Player p, Vec3 v) {
        return MoveState.of(p.onGround(), p.isSprinting(), p.isCrouching(), v.x, v.y, v.z, p.getYRot());
    }

    public static void tick(Player p) {
        if (!side(p).sample(p, p.level().getGameTime(), p.position(), v -> state(p, v))) Diag.count("dup_tick_sample");
    }

    /** For the debug log: Last 8 ticks of height, vertical speed and ground flag, oldest first. */
    public static String history(Player p) {
        return side(p).history(p);
    }

    /** The newest {@code n} sampled states of {@code p} on this side, newest first. */
    public static List<MoveState> recent(Player p, int n) {
        return side(p).recent(p, n);
    }

    public static MoveState of(Player p) {
        // The client reads its own last-tick displacement directly (xo/yo/zo are the previous tick's position); the
        // event-sampled VEL read zero horizontal movement there. The server only has the sampled value.
        Vec3 v = p.level().isClientSide ? new Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo) : SERVER.velocity(p);
        return state(p, v);
    }
}
