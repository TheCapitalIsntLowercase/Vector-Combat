package dev.vectorcombat;

import java.util.List;

/**
 * Which target of a swing is the one the player means, and what the others take. Vanilla's sweep hits every victim but the one clicked for {@code 1 + ratio x damage}, the ratio being the
 * player's {@code sweeping_damage_ratio} attribute (0 unless Sweeping Edge, or anything else that adds to it, raises it). The arc here reaches everything at once, so the target under
 * the crosshair is the primary and takes the whole swing, and the rest are secondary.
 */
final class Sweep {
    private Sweep() {
    }

    /**
     * A target the swing reaches (in the arc and the line of sight): how far along the aim ray its box is first met, squared (negative when the ray misses it), its horizontal
     * offset from the aim in degrees, and its squared distance from the eye.
     */
    record Candidate(double ray, double absOff, double distSq) {
    }

    /** What a secondary target's swing is worth before enchantments and recharge: vanilla's {@code 1 + ratio x damage}. */
    static double secondaryBase(double ratio, double damage) {
        return 1 + ratio * damage;
    }

    /** The index of the primary target: the nearest whose box the aim ray hits, else the one least off the aim, then the nearest; -1 when there are none. A full tie keeps the first. */
    static int primary(List<Candidate> targets) {
        int best = -1;
        for (int i = 0; i < targets.size(); i++) {
            if (best < 0 || before(targets.get(i), targets.get(best))) best = i;
        }
        return best;
    }

    private static boolean before(Candidate a, Candidate b) {
        boolean aimedA = a.ray() >= 0, aimedB = b.ray() >= 0;
        if (aimedA != aimedB) return aimedA;
        if (aimedA) return a.ray() < b.ray();
        if (a.absOff() != b.absOff()) return a.absOff() < b.absOff();
        return a.distSq() < b.distSq();
    }
}
