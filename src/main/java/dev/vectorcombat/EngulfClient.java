package dev.vectorcombat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Inside a slime ({@link Engulf}): the world blurred (the game's own menu blur, so its strength is the player's blur setting), under a green wash (orange inside a magma cube) with a darker edge, fading in over a
 * few ticks and out on the release, before the HUD is drawn so the hearts stay readable.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class EngulfClient {
    private EngulfClient() {
    }

    /** Game time the hold ends at (0 for none) and when the last one began, for the fades. */
    private static long until, since;

    /** The wash of the hold in progress ({@link EngulfPayload#GREEN} or {@link EngulfPayload#ORANGE}). */
    private static int tint;

    /** Set by the first fault: the blur is skipped for the session, the wash still draws. */
    private static boolean blurFailed;

    static void receive(EngulfPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (p.ticks() > 0) {
            tint = p.tint();
            since = now;
            until = now + p.ticks();
        } else {
            until = Math.min(until, now);
        }
        Diag.event("engulf_screen", "ticks", p.ticks());
    }

    /** How strong the wash is {@code t} ticks into a hold of {@code length} (0 to 1): in over 3 ticks, full, out over 4 after it ends. */
    static float strength(float t, float length) {
        if (t < 0) return 0;
        if (t < 3) return t / 3;
        if (t <= length) return 1;
        return Math.max(0, 1 - (t - length) / 4);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void render(RenderGuiEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (since == 0 || mc.level == null) return;
        float t = mc.level.getGameTime() - since + e.getPartialTick().getGameTimeDeltaPartialTick(false);
        float s = strength(t, until - since);
        if (s <= 0) {
            if (t > 0) since = 0;
            return;
        }
        if (!blurFailed && s > 0.5f && blurs(tint)) {
            try {
                mc.gameRenderer.processBlurEffect(e.getPartialTick().getGameTimeDeltaPartialTick(false));
                mc.getMainRenderTarget().bindWrite(false);
            } catch (RuntimeException ex) {
                blurFailed = true;
                VectorCombat.LOG.error("Engulf blur failed, the green wash stays", ex);
            }
        }
        GuiGraphics g = e.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        int[] wash = wash(tint);
        g.fill(0, 0, w, h, argb(0.45f * s * fill(tint), wash[0], wash[1], wash[2]));
        int edge = Math.max(8, Math.min(w, h) / 6);
        int dark = argb(0.55f * s, wash[3], wash[4], wash[5]), clear = argb(0, wash[3], wash[4], wash[5]);
        g.fillGradient(0, 0, w, edge, dark, clear);
        g.fillGradient(0, h - edge, w, h, clear, dark);
    }

    /** The wash's colour and its darker edge (r, g, b each): a slime's green, a magma cube's orange, a drowned's deep blue. */
    static int[] wash(int tint) {
        if (tint == EngulfPayload.DEEP) return new int[] {0x10, 0x30, 0x70, 0x02, 0x08, 0x28};
        return tint == EngulfPayload.ORANGE ? new int[] {0xFF, 0x7A, 0x1E, 0x60, 0x18, 0x00} : new int[] {0x3F, 0xA8, 0x3A, 0x10, 0x40, 0x10};
    }

    /** Whether a hold blurs the world: inside a slime or a magma cube, not in a drowned's grip (the edges only). */
    static boolean blurs(int tint) {
        return tint != EngulfPayload.DEEP;
    }

    /** How strong the full-screen wash is, as a share of the edges': none for a drowned's grip, which only darkens the edges. */
    static float fill(int tint) {
        return tint == EngulfPayload.DEEP ? 0 : 1;
    }

    private static int argb(float alpha, int r, int g, int b) {
        return (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        since = 0;
        until = 0;
    }
}
