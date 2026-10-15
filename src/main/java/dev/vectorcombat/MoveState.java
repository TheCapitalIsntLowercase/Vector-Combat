package dev.vectorcombat;

/** Movement snapshot attack conditions are evaluated against. Speeds are blocks/tick relative to the player's facing. */
public record MoveState(boolean onGround, boolean sprinting, boolean sneaking, double forward, double right, double up) {
    /** The state of a player facing {@code yawDegrees} who moved ({@code dx}, {@code dy}, {@code dz}) blocks over the last tick, with these flags. */
    public static MoveState of(boolean onGround, boolean sprinting, boolean sneaking, double dx, double dy, double dz, double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new MoveState(onGround, sprinting, sneaking, -Math.sin(yaw) * dx + Math.cos(yaw) * dz, -Math.cos(yaw) * dx - Math.sin(yaw) * dz, dy);
    }
}
