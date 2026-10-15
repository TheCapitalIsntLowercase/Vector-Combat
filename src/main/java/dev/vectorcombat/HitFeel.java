package dev.vectorcombat;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Client side of a landed hit. Payload handlers enter here, so a dedicated server never loads client classes. Every client holds the mobs
 * the server held; the attacker's also pauses its own swing. The camera, FOV, hand and sound layers join later.
 */
final class HitFeel {
    private HitFeel() {
    }

    static void confirm(HitPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        Impact.hit(p.weight());
        if (me != null && p.attacker() == me.getId()) Swing.hold(p);
        else ClientSwings.hold(p.attacker(), p.ticks() * Hitstop.TICK_MS, Util.getMillis());
        if (mc.level == null) return;
        for (int id : p.victims()) {
            Entity e = mc.level.getEntity(id);
            if (e != null) Hitstop.freeze(e, p.ticks());
        }
    }
}
