package dev.vectorcombat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * While you are stunned a thin ring in the stun's yellow circles the crosshair and unwinds clockwise from 12 o'clock as the stun runs out; empty means free. It stands in for the
 * potion icon the stun no longer has ({@link VectorCombatClient}). Your own stun only, in first person (the crosshair is not drawn otherwise). The ring is a pixel circle sized from
 * the vanilla crosshair, so it has no setting.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class StunCrosshair {
    /** Pixels from the centre pixel: vanilla's crosshair arms stop at 4 and the direction arrow starts beyond 9. */
    static final int RADIUS = 8;
    private static final int RGB = 0xF2C94C; // the stun effect's colour
    private static final float OPACITY = 0.9f;
    private static final long FADE_IN_MS = 100;
    /** The layer was not drawn for this long (hidden GUI, a menu): the next stun is a new one. */
    private static final long STALE_MS = 500;
    private static final int[][] RING = ring(RADIUS);

    /** The longest remaining duration seen in this stun, in ticks; 0 when not stunned. */
    private static float total;
    /** When the ring first drew in this stun, 0 when it is not showing. */
    private static long shownAt;
    private static long lastDraw;

    /** The pixels whose distance from the centre rounds to {@code radius} (a circle one pixel thick), clockwise from 12 o'clock. */
    static int[][] ring(int radius) {
        double lo = (radius - 0.5) * (radius - 0.5), hi = (radius + 0.5) * (radius + 0.5);
        List<int[]> pixels = new ArrayList<>();
        for (int y = -radius - 1; y <= radius + 1; y++) {
            for (int x = -radius - 1; x <= radius + 1; x++) {
                int d2 = x * x + y * y;
                if (d2 >= lo && d2 < hi) pixels.add(new int[] {x, y});
            }
        }
        pixels.sort(Comparator.comparingDouble(p -> clockwise(p[0], p[1])));
        return pixels.toArray(new int[0][]);
    }

    /** Angle from 12 o'clock, clockwise, in [0, 2 pi). */
    private static double clockwise(int dx, int dy) {
        double a = Math.atan2(dx, -dy);
        return a < 0 ? a + 2 * Math.PI : a;
    }

    /** How many of the ring's first pixels the clock wipe has removed. */
    static int dark(int pixels, float fraction) {
        return Math.round((1 - fraction) * pixels);
    }

    /** What is left of the stun, 0 to 1. */
    static float fraction(float total, float remaining) {
        return total <= 0 ? 0 : Math.max(0f, Math.min(1f, remaining / total));
    }

    /** Opacity factor: in over the first 100 ms of a stun, then solid. */
    static float alpha(long sinceMs) {
        return Math.max(0f, Math.min(1f, sinceMs / (float) FADE_IN_MS));
    }

    private static void hide(long now) {
        if (shownAt != 0) Diag.event("stun_ring", "state", "hide", "ms", lastDraw - shownAt);
        shownAt = 0;
        total = 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) // before CrosshairSplit cancels the layer
    static void render(RenderGuiLayerEvent.Pre e) {
        if (!e.getName().equals(VanillaGuiLayers.CROSSHAIR)) return;
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        if (shownAt != 0 && now - lastDraw > STALE_MS) hide(now);
        MobEffectInstance stun = mc.player == null || !ThirdView.crosshairPieces(mc) ? null : mc.player.getEffect(Stuns.STUN);
        if (stun == null || stun.getDuration() == 0) {
            hide(now);
            return;
        }
        float fraction = 1;
        if (!stun.isInfiniteDuration()) {
            total = Math.max(total, stun.getDuration());
            fraction = fraction(total, stun.getDuration() - e.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
        if (shownAt == 0) {
            shownAt = now;
            Diag.event("stun_ring", "state", "show", "ticks", stun.getDuration());
        }
        lastDraw = now;

        GuiGraphics g = e.getGuiGraphics();
        int cx = (g.guiWidth() - 15) / 2 + 7, cy = (g.guiHeight() - 15) / 2 + 7;
        int argb = (Math.round(OPACITY * alpha(now - shownAt) * 255) << 24) | RGB;
        for (int i = dark(RING.length, fraction); i < RING.length; i++) {
            int x = cx + RING[i][0], y = cy + RING[i][1];
            g.fill(x, y, x + 1, y + 1, argb);
        }
    }
}
