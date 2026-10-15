package dev.vectorcombat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;

/**
 * The client's and the server's movement samples stay apart. In singleplayer the two players share a network id and so are equal as map keys; one shared entry crashed the
 * render thread (a ConcurrentModificationException reading a history the server thread was changing) and interleaved their samples. Run with {@code java -ea}.
 */
public final class MoveTrackerTest {
    /** Equal by id alone, as {@code Entity.equals} is. */
    private record Id(int id) {
    }

    /** A player standing or in the air facing north, whatever it moved: only the flag and the vertical speed matter here. */
    private static Function<Vec3, MoveState> state(boolean onGround) {
        return v -> new MoveState(onGround, false, false, 0, 0, v.y);
    }

    public static void main(String[] args) throws Exception {
        MoveTracker.Side client = MoveTracker.side(true), server = MoveTracker.side(false);
        assert client != server : "one state per side";
        assert MoveTracker.side(true) == client && MoveTracker.side(false) == server : "and the same one every time";

        // The same id on both sides: each keeps its own history and velocity.
        Id clientPlayer = new Id(6), serverPlayer = new Id(6);
        assert clientPlayer.equals(serverPlayer) && clientPlayer.hashCode() == serverPlayer.hashCode() : "the premise: they are equal keys";
        assert client.sample(clientPlayer, 100, new Vec3(0, -60, 0), state(true)) && client.sample(clientPlayer, 101, new Vec3(0, -59.58, 0), state(false));
        assert server.sample(serverPlayer, 100, new Vec3(0, -60, 0), state(true));
        assert client.history(clientPlayer).equals("[y=-60.00 vy=0.000 g=true, y=-59.58 vy=0.420 g=false]") : client.history(clientPlayer);
        assert server.history(serverPlayer).equals("[y=-60.00 vy=0.000 g=true]") : "the server did not see the client's jump: " + server.history(serverPlayer);
        assert Math.abs(client.velocity(clientPlayer).y - 0.42) < 1e-9 && server.velocity(serverPlayer).equals(Vec3.ZERO) : "velocity too";

        // One sample per game tick and side: a repeat is refused, the other side's same tick is not.
        assert !client.sample(clientPlayer, 101, new Vec3(0, -50, 0), state(true)) : "a second sample in tick 101 is dropped";
        assert client.history(clientPlayer).endsWith("y=-59.58 vy=0.420 g=false]") : "and changes nothing";
        assert server.sample(serverPlayer, 101, new Vec3(0, -59.58, 0), state(false)) : "the server's tick 101 is its own";

        // The sampled states, newest first, for the server to weigh a claim against; a side never sees the other's.
        List<MoveState> clientStates = client.recent(clientPlayer, 5);
        assert clientStates.size() == 2 && !clientStates.get(0).onGround() && clientStates.get(1).onGround() : "newest first: " + clientStates;
        assert Math.abs(clientStates.get(0).up() - 0.42) < 1e-9 : "the state carries the sample's vertical speed";
        assert client.recent(clientPlayer, 1).size() == 1 && client.recent(clientPlayer, 0).isEmpty() : "at most n of them";
        assert server.recent(serverPlayer, 5).size() == 2 && server.recent(new Id(7), 5).isEmpty() : "the server's own, and none for a player never seen";

        // Eight ticks are kept, oldest first in the history, newest first in the states.
        for (int t = 102; t < 130; t++) client.sample(clientPlayer, t, new Vec3(0, -60 + t * 0.001, 0), state(true));
        String h = client.history(clientPlayer);
        assert h.split(", ").length == 8 && h.startsWith("[y=-59.88 vy=0.001") && h.endsWith("y=-59.87 vy=0.001 g=true]") : h;
        assert client.recent(clientPlayer, 20).size() == MoveTracker.HISTORY && MoveTracker.HISTORY == 8 : "the ring holds eight";
        assert client.history(new Id(7)).equals("[]") && client.velocity(new Id(7)).equals(Vec3.ZERO) : "an unseen player has nothing";

        // The crash: the server thread ticking while the render thread prints. Separate sides, so nothing is shared to race on.
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                for (long t = 1000; !stop.get(); t++) server.sample(serverPlayer, t, new Vec3(0, -60 + (t % 7) * 0.01, 0), state(t % 2 == 0));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "test server");
        serverThread.start();
        try {
            long until = System.nanoTime() + 300_000_000L;
            for (long t = 5000; System.nanoTime() < until; t++) {
                client.sample(clientPlayer, t, new Vec3(0, -60, 0), state(true));
                client.history(clientPlayer);
                client.recent(clientPlayer, 4);
            }
        } finally {
            stop.set(true);
            serverThread.join();
        }
        assert failure.get() == null : failure.get();

        System.out.println("MoveTrackerTest ok");
    }
}
