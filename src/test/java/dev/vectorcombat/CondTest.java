package dev.vectorcombat;

/** Run with {@code java -ea}. build.ps1 does this. */
public final class CondTest {
    private static boolean pass(String cond, MoveState m) {
        return Cond.parse(cond).test(m);
    }

    public static void main(String[] args) {
        MoveState sprint = new MoveState(true, true, false, 0.22, 0, 0);
        MoveState fall = new MoveState(false, false, false, 0, 0, -0.4);
        MoveState strafeLeft = new MoveState(true, false, false, 0, -0.13, 0);

        assert pass("sprinting", sprint) && pass("forward_speed:0.15", sprint) && !pass("forward_speed:0.3", sprint);
        assert pass("down_speed", fall) && pass("in_air", fall) && !pass("on_ground", fall) && !pass("up_speed", fall);
        assert pass("left_speed", strafeLeft) && !pass("right_speed", strafeLeft) && pass("backward_speed:0", strafeLeft);
        assert pass("!sprinting", fall) && !pass("!sprinting", sprint);
        try {
            Cond.parse("flying");
            assert false : "unknown condition must throw";
        } catch (IllegalArgumentException expected) {
        }
        System.out.println("CondTest ok");
    }
}
