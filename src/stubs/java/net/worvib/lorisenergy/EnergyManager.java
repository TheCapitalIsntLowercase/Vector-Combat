package net.worvib.lorisenergy;

import net.minecraft.world.entity.player.Player;

/** Stand-in for Loris Energy's API (compile only; the real class comes from the mod at run time). */
public class EnergyManager {
    public static float getEnergy(Player p) {
        throw new UnsupportedOperationException();
    }

    public static void setEnergy(Player p, float energy) {
        throw new UnsupportedOperationException();
    }

    public static void syncToClient(Player p) {
        throw new UnsupportedOperationException();
    }
}
