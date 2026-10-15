package dev.vectorcombat;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * A small pixel arrow beside the crosshair showing which way your last swing went (left, right, forward, rising, falling). It rests dim, flashes
 * on each swing and settles back; a bar on the tail marks a rising or falling swing. Toggle: ClientConfig.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class DirectionArrow {
    private static final String[] UP = {"..#..", ".###.", "#.#.#", "..#..", "..#.."};
    private static final String[] DOT = {".....", "..#..", ".###.", "..#..", "....."};
    private static final boolean[][][] OCTANTS = new boolean[4][][];

    private static final float REST_ALPHA = 0.35f, PEAK_ALPHA = 0.95f;
    private static final long RISE_MS = 80, FALL_MS = 1400;

    private static volatile Direction last;
    private static volatile long swungAt;

    static {
        for (int k = 0; k < 4; k++) {
            boolean[][] g = grid(UP);
            for (int i = 0; i < k; i++) g = rotateClockwise(g);
            OCTANTS[k] = g;
        }
    }

    private static boolean[][] grid(String[] rows) {
        boolean[][] g = new boolean[5][5];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) g[r][c] = rows[r].charAt(c) == '#';
        }
        return g;
    }

    private static boolean[][] rotateClockwise(boolean[][] g) {
        boolean[][] out = new boolean[5][5];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) out[r][c] = g[4 - c][r];
        }
        return out;
    }

    public static void record(Direction d) {
        last = d;
        swungAt = Util.getMillis();
    }

    /** Opacity: quick rise on a swing, ease-out back to a dim resting level. */
    static float alpha(long sinceSwingMs) {
        float rise = Math.min(1f, sinceSwingMs / (float) RISE_MS);
        float fall = 1f - Math.min(1f, sinceSwingMs / (float) FALL_MS);
        return REST_ALPHA + (PEAK_ALPHA - REST_ALPHA) * rise * fall * fall;
    }

    @SubscribeEvent
    static void render(RenderGuiLayerEvent.Pre e) {
        Direction d = last;
        if (d == null || !e.getName().equals(VanillaGuiLayers.CROSSHAIR) || !ClientConfig.SHOW_DIRECTION_ARROW.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ThirdView.crosshairPieces(mc)) return; // first person, or third person where a crosshair shows (the refresh)

        GuiGraphics g = e.getGuiGraphics();
        int color = (Math.round(alpha(Util.getMillis() - swungAt) * 255) << 24) | 0xFFFFFF;
        // Same centre pixel as the vanilla crosshair, then to its lower right, clear of its arms and the attack bar.
        int x = (g.guiWidth() - 15) / 2 + 7 + 9, y = (g.guiHeight() - 15) / 2 + 7 + 4;
        boolean[][] shape = d.none() ? grid(DOT) : OCTANTS[d.octant() / 2];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                if (shape[r][c]) g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
        if (d.vertical() != 0) {
            int barY = d.vertical() > 0 ? y + 6 : y - 2;
            g.fill(x + 1, barY, x + 4, barY + 1, color);
        }
    }
}
