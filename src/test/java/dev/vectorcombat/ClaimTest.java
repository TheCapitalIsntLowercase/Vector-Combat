package dev.vectorcombat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The server's check of the attack a client says it swung: the claim must be backed by the player's own recent movement, which the server samples itself, so a client
 * cannot simply name the strongest attack. Uses the shipped attacks. Run with {@code java -ea}, giving the attacks directory.
 */
public final class ClaimTest {
    private static AttackDef named(String name) {
        return Attacks.all().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static MoveState ground(double forward, double right, boolean sprinting) {
        return new MoveState(true, sprinting, false, forward, right, 0);
    }

    private static MoveState air(double up) {
        return new MoveState(false, false, false, 0, 0, up);
    }

    public static void main(String[] args) throws Exception {
        try (Stream<Path> files = Files.list(Path.of(args[0]))) {
            Attacks.load(files.map(p -> {
                try {
                    return Files.readString(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList());
        }
        AttackDef slash = named("Slash"), falling = named("Falling Slash"), rising = named("Rising Slash"), lunge = named("Lunge"), left = named("Left Sweep"), right = named("Right Sweep");
        List<AttackDef> movement = List.of(falling, rising, lunge, left, right);

        // The states the server weighs a claim against are the state now followed by its newest samples, so that is claimWindow + 1 of them at best.
        MoveState stand = ground(0, 0, false);
        List<MoveState> standing = List.of(stand, stand, stand, stand);
        assert slash.supportedBy(standing) == 0 && slash.supportedBy(List.of()) == 0 : "the default attack has no conditions: always backed, even with no samples";
        for (AttackDef a : movement) assert a.supportedBy(standing) == -1 && a.supportedBy(List.of()) == -1 : a.name() + " is not backed by standing still";

        // A sprint backs the lunge while it lasts and for the window after it, and not the sweeps.
        MoveState sprint = ground(0.28, 0, true);
        assert lunge.supportedBy(List.of(sprint, sprint, stand, stand)) == 0 && left.supportedBy(List.of(sprint, sprint, stand, stand)) == -1 : "sprinting forward";
        assert lunge.supportedBy(List.of(stand, stand, stand, sprint)) == 3 : "a sprint three samples back still backs the lunge";
        assert lunge.supportedBy(List.of(stand, stand, stand, stand)) == -1 : "and one sample further it does not";
        assert lunge.supportedBy(List.of(ground(0.2, 0, false), ground(0.22, 0, false))) == -1 : "walking forward is not sprinting";
        assert lunge.supportedBy(List.of(ground(0.1, 0, true))) == -1 : "a sprint that has barely moved is not a lunge";

        // A strafe backs its own sweep and not the other.
        MoveState strafeLeft = ground(0, -0.2, false), strafeRight = ground(0, 0.2, false);
        assert left.supportedBy(List.of(strafeLeft, stand)) == 0 && right.supportedBy(List.of(strafeLeft, stand)) == -1 : "strafing left";
        assert right.supportedBy(List.of(stand, strafeRight)) == 1 && left.supportedBy(List.of(stand, strafeRight)) == -1 : "strafing right a sample ago";

        // A jump as vanilla moves it (0.42, then (v - 0.08) * 0.98 a tick): the rising slash on the way up, the falling slash from the apex on.
        double[] vy = new double[8];
        vy[0] = 0.42;
        for (int i = 1; i < vy.length; i++) vy[i] = (vy[i - 1] - 0.08) * 0.98;
        assert Math.abs(vy[1] - 0.3332) < 1e-4 && Math.abs(vy[3] - 0.1648) < 1e-3 && Math.abs(vy[5] - 0.0030) < 1e-3 && vy[6] < 0 : "the jump profile used here is vanilla's: " + java.util.Arrays.toString(vy);
        List<MoveState> rise = List.of(air(vy[3]), air(vy[2]), air(vy[1]), air(vy[0]));
        assert rising.supportedBy(rise) == 0 && falling.supportedBy(rise) == -1 : "on the way up";
        List<MoveState> apex = List.of(air(vy[6]), air(vy[5]), air(vy[4]), air(vy[3]));
        assert falling.supportedBy(apex) == 0 : "past the apex";
        assert rising.supportedBy(apex) == 2 : "a rising slash clicked just before the apex is still backed";
        List<MoveState> landing = List.of(stand, air(-0.4), air(-0.35), air(-0.3));
        assert falling.supportedBy(landing) == 1 && rising.supportedBy(landing) == -1 : "a falling slash clicked as the player lands";
        assert rising.supportedBy(standing) == -1 && falling.supportedBy(standing) == -1 : "no aerial attack from the ground";

        // How far back a claim may reach: three samples (the newest lags the position by up to a tick, the client reads the tick before its click, the two loops sit up to a
        // tick out of phase) and one more per 50 ms of ping, within the eight the tracker keeps.
        assert Strike.claimWindow(0) == 3 && Strike.claimWindow(-5) == 3 : "no ping, three";
        assert Strike.claimWindow(1) == 4 && Strike.claimWindow(50) == 4 && Strike.claimWindow(51) == 5 && Strike.claimWindow(120) == 6 : "one more per 50 ms started";
        assert Strike.claimWindow(250) == 8 && Strike.claimWindow(10_000) == 8 : "never more than the tracker keeps: " + Strike.claimWindow(250);
        assert Strike.claimWindow(0) <= MoveTracker.HISTORY && Strike.claimWindow(10_000) == MoveTracker.HISTORY;

        System.out.println("ClaimTest ok");
    }
}
