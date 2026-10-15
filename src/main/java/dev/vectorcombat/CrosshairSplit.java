package dev.vectorcombat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * While an attack-speed penalty is active the crosshair splits apart, wider the bigger the penalty. The shown penalty
 * eases toward the real one every frame (about 125ms time constant) and arms are placed at sub-pixel offsets, so the
 * split slides open and closed instead of stepping. Vanilla's crosshair returns once it has fully closed.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class CrosshairSplit {
    private static final int COLOR = 0xFFFFFFFF;
    private static final float TIME_CONSTANT_TICKS = 2.5f;
    private static final float SPREAD_PIXELS = 14f;
    private static final float CLOSED = 0.002f;

    private static float shown;

    @SubscribeEvent
    static void render(RenderGuiLayerEvent.Pre e) {
        if (!e.getName().equals(VanillaGuiLayers.CROSSHAIR)) return;
        Minecraft mc = Minecraft.getInstance();
        boolean own = mc.player != null && ThirdView.ownCrosshair(mc); // vanilla's F5 draws none: with the refresh's setting, ours, plain when no penalty splits it
        // Under the refresh the split also replaces a third-person crosshair that is drawn (Shoulder Surfing's, at the centre), as in first person; unsplit, it is left alone.
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson() && !own && !ThirdView.crosshairPieces(mc)) {
            shown = 0;
            return;
        }
        float dt = e.getPartialTick().getRealtimeDeltaTicks();
        shown += ((float) Penalties.current(mc.player) - shown) * (1 - (float) Math.exp(-dt / TIME_CONSTANT_TICKS));
        if (shown < CLOSED) {
            shown = 0;
            if (!own) return;
        }
        e.setCanceled(true);

        GuiGraphics g = e.getGuiGraphics();
        int cx = (g.guiWidth() - 15) / 2 + 7, cy = (g.guiHeight() - 15) / 2 + 7;
        float spread = shown * SPREAD_PIXELS;
        int len = Math.max(3, Math.round(5 - spread * 0.15f));
        g.fill(cx, cy, cx + 1, cy + 1, COLOR);
        arm(g, cx + 2, cy, len, 1, spread, 0);
        arm(g, cx - 2 - len + 1, cy, len, 1, -spread, 0);
        arm(g, cx, cy + 2, 1, len, 0, spread);
        arm(g, cx, cy - 2 - len + 1, 1, len, 0, -spread);
    }

    private static void arm(GuiGraphics g, int x, int y, int w, int h, float dx, float dy) {
        g.pose().pushPose();
        g.pose().translate(dx, dy, 0);
        g.fill(x, y, x + w, y + h, COLOR);
        g.pose().popPose();
    }
}
