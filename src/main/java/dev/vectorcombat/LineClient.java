package dev.vectorcombat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Draws the telegraphs ({@link LinePayload}) as dust on the ground every other tick for as long as each lasts: chargers' lines (red hostile, yellow neutral) unless the player turned
 * them off ({@code [display] lineTelegraphs}), and landing rings (the spider's, the slime's) unless {@code ringTelegraphs} is off; the attacks are the same either way.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class LineClient {
    private LineClient() {
    }

    private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(0.85f, 0.12f, 0.08f), 1.3f),
            YELLOW = new DustParticleOptions(new Vector3f(0.95f, 0.85f, 0.15f), 1.3f), SLIME = new DustParticleOptions(new Vector3f(0.4f, 0.85f, 0.35f), 1.3f),
            MAGMA = new DustParticleOptions(new Vector3f(1f, 0.5f, 0.1f), 1.3f), DEEP = new DustParticleOptions(new Vector3f(0.15f, 0.3f, 0.85f), 1.3f),
            SAND = new DustParticleOptions(new Vector3f(0.9f, 0.78f, 0.45f), 1.3f), SCULK = new DustParticleOptions(new Vector3f(0.1f, 0.6f, 0.65f), 1.3f);

    private static DustParticleOptions dust(int color) {
        return switch (color) {
            case LinePayload.YELLOW -> YELLOW;
            case LinePayload.SLIME -> SLIME;
            case LinePayload.MAGMA -> MAGMA;
            case LinePayload.DEEP -> DEEP;
            case LinePayload.SAND -> SAND;
            case LinePayload.SCULK -> SCULK;
            default -> RED;
        };
    }

    /** Whether a segment is drawn: lines by {@code lineTelegraphs}, landing rings by {@code ringTelegraphs}. */
    static boolean shown(boolean ring, boolean lines, boolean rings) {
        return ring ? rings : lines;
    }

    /** A line being drawn and the game time it ends at. */
    private record Line(LinePayload p, long until) {
    }

    private static final List<Line> LINES = new ArrayList<>();

    static void receive(LinePayload p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        LINES.add(new Line(p, level.getGameTime() + p.ticks()));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            LINES.clear();
            return;
        }
        long now = level.getGameTime();
        LINES.removeIf(l -> now > l.until());
        if (LINES.isEmpty() || now % 2 != 0) return;
        boolean lines = ClientConfig.LINE_TELEGRAPHS.get(), rings = ClientConfig.RING_TELEGRAPHS.get();
        for (Line l : LINES) {
            LinePayload p = l.p();
            if (!shown(p.ring(), lines, rings)) continue;
            double dx = p.tx() - p.fx(), dy = p.ty() - p.fy(), dz = p.tz() - p.fz(), len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            for (double s = 0; s <= len; s += 0.5) {
                double k = s / Math.max(len, 1e-6);
                level.addParticle(dust(p.color()), p.fx() + dx * k + (level.random.nextDouble() - 0.5) * 0.1, p.fy() + dy * k + 0.15,
                        p.fz() + dz * k + (level.random.nextDouble() - 0.5) * 0.1, 0, 0, 0);
            }
        }
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        LINES.clear();
    }
}
