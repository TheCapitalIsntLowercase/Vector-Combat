package dev.vectorcombat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Every swing in view, by entity id: the clip, when it started by the wall clock, the pause a landed hit holds it for, and the look it started with.
 * Your own swings register here too ({@link Swing} starts and holds them), so the pose, the trail and the crescent are drawn by one path for you and for
 * everyone else; another player's swing is just another {@link #start} when the server's {@link SwingPayload} arrives.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class ClientSwings {
    private ClientSwings() {
    }

    private static final class State {
        final Anim clip;
        final long startedAt;
        final double speed;
        final float yaw, pitch;
        Swing.Hold pause = Swing.Hold.NONE;

        State(Anim clip, long startedAt, double speed, float yaw, float pitch) {
            this.clip = clip;
            this.startedAt = startedAt;
            this.speed = speed;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        double time(long now) {
            return pause.clock(now, startedAt, speed);
        }
    }

    /** What the pose and the trail need of a swing in progress: how far its clip has played (stopped while a hit holds it) and the look it began with. */
    record View(Anim clip, double time, float yaw, float pitch, long startedAt, double speed) {
    }

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    /** A swing of {@code clip} by entity {@code id} began at {@code now} (wall clock ms) with the look {@code yaw}, {@code pitch}, at playback {@code speed}. */
    static void start(int id, Anim clip, double speed, float yaw, float pitch, long now) {
        if (clip == Anim.NONE) STATES.remove(id);
        else STATES.put(id, new State(clip, now, speed, yaw, pitch));
    }

    /** A landed hit holds this entity's swing for {@code ms}, but only while it is playing. */
    static void hold(int id, long ms, long now) {
        State s = STATES.get(id);
        if (s != null && ms > 0 && s.time(now) <= s.clip.length()) s.pause = s.pause.add(now, ms);
    }

    static void stop(int id) {
        STATES.remove(id);
    }

    /** The swing in progress, or null when there is none, it is over, or its clip has no trail (no blade path to pose an arm along). */
    static View view(int id, long now) {
        State s = STATES.get(id);
        if (s == null || s.clip.trail() == null) return null;
        double t = s.time(now);
        return t > s.clip.length() ? null : new View(s.clip, t, s.yaw, s.pitch, s.startedAt, s.speed);
    }

    static View view(Entity e) {
        return view(e.getId(), Util.getMillis());
    }

    /** As {@link #view(Entity)}, for the third-person pose, kept past the clip's end until {@code until} says (the refresh's longer recovery, {@link ThirdPerson#poseEnd}). */
    static View viewUntil(Entity e, java.util.function.ToDoubleFunction<Anim> until) {
        State s = STATES.get(e.getId());
        if (s == null || s.clip.trail() == null) return null;
        double t = s.time(Util.getMillis());
        return t > Math.max(s.clip.length(), until.applyAsDouble(s.clip)) ? null : new View(s.clip, t, s.yaw, s.pitch, s.startedAt, s.speed);
    }

    /** Every swing in progress: entity id to view. */
    static Map<Integer, View> all(long now) {
        Map<Integer, View> out = new HashMap<>();
        for (int id : STATES.keySet()) {
            View v = view(id, now);
            if (v != null) out.put(id, v);
        }
        return out;
    }

    /** Forgets swings that are over. */
    static void cleanup(long now) {
        STATES.entrySet().removeIf(en -> en.getValue().time(now) > en.getValue().clip.length());
    }

    static void clear() {
        STATES.clear();
    }

    /** Another entity's swing, as the server saw it accepted. Your own are registered by {@link Swing}, which knows the exact moment of the click. */
    static void receive(SwingPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || p.entityId() == mc.player.getId()) return;
        Entity e = mc.level.getEntity(p.entityId());
        Anim clip = Anim.get(p.clip());
        Diag.event("tp_swing", "entity", p.entityId(), "found", e != null, "clip", p.clip(), "known", clip != Anim.NONE);
        if (e != null && clip != Anim.NONE) start(e.getId(), clip, 1.0, e.getYRot(), e.getXRot(), Util.getMillis());
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        cleanup(Util.getMillis());
    }

    @SubscribeEvent
    static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        clear();
    }
}
