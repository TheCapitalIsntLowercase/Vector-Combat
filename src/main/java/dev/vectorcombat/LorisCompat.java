package dev.vectorcombat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.CommonHooks;
import net.worvib.lorisenergy.EnergyManager;
import net.worvib.lorisenergy.attachment.EnergyData;
import net.worvib.lorisenergy.attachment.ModAttachments;
import net.worvib.lorisenergy.config.ModConfig;

/**
 * Loris Energy charges stamina for each {@code AttackEntityEvent}, so with its rules alone a swing that hits nothing is free and a cleave pays once per mob. Here a swing
 * is the attack: every accepted swing pays Loris's {@code attackExhaustion} once, hit or miss (with Loris's own regen pause), a swing with too little left is refused as
 * Loris refuses an exhausted hit, and the hits' own events run with Loris's charge undone. Only loaded when Loris Energy is installed (the check is in {@link Strike},
 * outside this class); the classes it names come from the mod at run time.
 */
final class LorisCompat {
    static final String ID = "lorisenergy";

    private LorisCompat() {
    }

    /** Charges one attack for an accepted swing. False (energy emptied, as Loris does) when there was not enough: the swing is refused. Creative and spectators pay nothing, as with Loris. */
    static boolean swing(Player p) {
        float cost = ModConfig.getAttackExhaustion() * (CommonConfig.LORIS_WEAPON_SCALE.get() ? (float) Penalties.staminaScale(Hitstop.weaponSpeed(p, p.getMainHandItem())) : 1f);
        if (p.isCreative() || p.isSpectator() || cost <= 0) return true;
        float before = EnergyManager.getEnergy(p);
        boolean enough = before >= cost;
        EnergyManager.setEnergy(p, enough ? before - cost : 0);
        EnergyData d = p.getData(ModAttachments.ENERGY_DATA);
        d.exhaustionTimer = ModConfig.getRegenDelay();
        d.accelerationTimer = 0;
        EnergyManager.syncToClient(p);
        Diag.event("stamina", "cost", cost, "before", before, "after", EnergyManager.getEnergy(p), "enough", enough, "tick", p.level().getGameTime());
        return enough;
    }

    /** One hit's {@code AttackEntityEvent} (see {@link CommonHooks#onPlayerAttackTarget}) with the energy topped up around it and put back after, so Loris neither charges nor refuses the hit: the swing paid. */
    static boolean attack(Player p, Entity t) {
        float before = EnergyManager.getEnergy(p);
        EnergyManager.setEnergy(p, Float.MAX_VALUE); // clamped to the maximum
        try {
            return CommonHooks.onPlayerAttackTarget(p, t);
        } finally {
            EnergyManager.setEnergy(p, before);
            EnergyManager.syncToClient(p);
        }
    }
}
