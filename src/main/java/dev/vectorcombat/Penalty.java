package dev.vectorcombat;

/** An attack-speed penalty: fraction of attack speed lost (0..1) for {@code seconds}. */
public record Penalty(double amount, double seconds) {
}
