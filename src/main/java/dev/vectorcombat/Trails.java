package dev.vectorcombat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * A swing's trail, yours or another player's ({@link ClientSwings}): a crescent that follows the blade through the strike, drawn in the air where the swing was (the look it
 * started with, around the swinger's current eye), so it stays put when you turn or run through it. The path is the clip's own {@link Anim.Trail}, the numbers the clip was built from, so the
 * leading edge sits on the blade and the body is where it was a moment ago. It is solid pixel-art cells like Minecraft's sweep particle: four bands thick in the
 * middle and pointed at the ends, brightest on the outer edge and the leading edge, advancing in steps. It freezes with a hit's hold and shrinks away after.
 * A thrust leaves a streak along the aim line instead. {@code [trails] style = VANILLA} swaps all of it for Minecraft's own crescent (client config).
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class Trails {
    private static final int COLS = 14, ROWS = 4, THRUST_CELLS = 8, FRAMES = 8;
    private static final float[] BAND = {1f, 0.7f, 0.45f, 0.25f};
    /** The body is the blade's path over this fraction of the strike's time, behind its leading edge. */
    private static final double TAIL = 0.6;
    /** Clip seconds it takes to shrink away after the strike ends. */
    private static final double FADE = 0.2;
    /** Blocks from the shoulder to the outer edge: just past a sword's tip (0.65 to its grip, 0.84 more). */
    private static final double RADIUS = 1.6;
    /** One band's width in blocks; four make 0.3 at the thickest, about Minecraft's crescent. */
    private static final double CELL = 0.075;
    /** Where the swing models put the shoulder in view space (x right, y up, z toward the viewer): the generator's constant. */
    private static final double[] SHOULDER = {0.3, -0.4, -0.1};
    private static final double GRIP_TO_TIP = 0.84, STREAK_PAST = 0.7;

    /** The swings (entity id to start time) whose Minecraft crescent is already out, in VANILLA style. */
    private static final Map<Integer, Long> spawned = new HashMap<>();
    private static boolean broken;

    /** Nearer than this many blocks (squared) a swing leaves a trail. */
    private static final double RANGE_SQ = 64 * 64;

    @SubscribeEvent
    static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Map<Integer, ClientSwings.View> swings = ClientSwings.all(Util.getMillis());
        spawned.keySet().retainAll(swings.keySet());
        if (swings.isEmpty()) return;
        boolean vanilla = ClientConfig.TRAIL_STYLE.get() == ClientConfig.TrailStyle.VANILLA;
        float opacity = ClientConfig.TRAIL_OPACITY.get().floatValue();
        if (!vanilla && (opacity <= 0.01f || broken)) return;
        BufferBuilder buf = null;
        try {
            Vec3 cam = e.getCamera().getPosition();
            float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
            for (Map.Entry<Integer, ClientSwings.View> en : swings.entrySet()) {
                if (!(mc.level.getEntity(en.getKey()) instanceof LivingEntity who) || who.distanceToSqr(cam) > RANGE_SQ) continue;
                if (vanilla) {
                    vanilla(mc, who, en.getValue());
                } else {
                    if (buf == null) buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                    add(buf, e.getPoseStack().last().pose(), cam, partial, who, en.getValue(), opacity);
                }
            }
            if (buf != null) {
                MeshData mesh = buf.build();
                buf = null;
                if (mesh != null) flush(mesh);
            }
        } catch (RuntimeException ex) { // a drawing fault must never take the game down: say so once and stop drawing trails
            broken = true;
            VectorCombat.LOG.error("Swing trail drawing failed, trails are off until restart", ex);
            if (buf != null) {
                try { // leave the shared tesselator as it was found
                    MeshData open = buf.build();
                    if (open != null) open.close();
                } catch (RuntimeException ignored) {
                    // nothing more to do
                }
            }
        }
    }

    /** Minecraft's own crescent for every swing but a thrust, where and when the server used to send it: 1.5 blocks ahead at body height as the blow lands (the wall clock, so a hold does not delay it). */
    private static void vanilla(Minecraft mc, LivingEntity who, ClientSwings.View st) {
        if (st.clip().trail().thrust() || st.startedAt() == spawned.getOrDefault(who.getId(), -1L) || Util.getMillis() - st.startedAt() < st.clip().impact() / st.speed() * 1000) return;
        spawned.put(who.getId(), st.startedAt());
        Vec3 look = who.getLookAngle();
        mc.level.addParticle(ParticleTypes.SWEEP_ATTACK, who.getX() + look.x * 1.5, who.getY(0.5), who.getZ() + look.z * 1.5, 0, 0, 0);
    }

    private static void flush(MeshData mesh) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** One swing's crescent (or thrust streak) added to the mesh; nothing when it has not begun, has faded, or is too small to show. */
    private static void add(BufferBuilder buf, Matrix4f mat, Vec3 cam, float partial, LivingEntity who, ClientSwings.View st, float opacity) {
        Anim.Trail tr = st.clip().trail();
        double t = st.time();
        if (t < tr.start()) return;
        double window = Math.max(0.03, tr.end() - tr.start());
        double fade = 1 - Mth.clamp((t - tr.end()) / FADE, 0, 1);
        if (fade <= 0) return;
        // Where the blade is now (the leading edge) and where it was a TAIL of the strike ago, in steps like the vanilla sprite's frames.
        double lead = Math.ceil(tr.progress(t) * FRAMES) / FRAMES, tail = Math.floor(tr.progress(t - TAIL * window) * FRAMES) / FRAMES;
        if (lead - tail < 0.02) return;

        // The swing's frame: the look it started with. View space maps onto it as x right, y up, -z forward.
        double yaw = Math.toRadians(st.yaw()), pitch = Math.toRadians(st.pitch());
        Vec3 f = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3 up = right.cross(f);
        Vec3 origin = who.getEyePosition(partial).add(world(SHOULDER, right, up, f));
        float light = 0.35f + 0.65f * who.level().getMaxLocalRawBrightness(BlockPos.containing(who.getEyePosition(partial))) / 15f; // no glowing trail in a cave
        float base = opacity * light * (float) fade;
        if (tr.thrust()) {
            streak(buf, mat, cam, tr, origin, right, up, f, lead, tail, base);
        } else {
            crescent(buf, mat, cam, tr, origin, right, up, f, lead, tail, base);
        }
    }

    /** A view-space vector in the swing's frame. */
    private static Vec3 world(double[] v, Vec3 right, Vec3 up, Vec3 f) {
        return right.scale(v[0]).add(up.scale(v[1])).add(f.scale(-v[2]));
    }

    /** The arc between the trailing and leading edge, as rows of cells that always face the camera: outer edge on the blade's reach, bands inward. */
    private static void crescent(BufferBuilder buf, Matrix4f mat, Vec3 cam, Anim.Trail tr, Vec3 origin, Vec3 right, Vec3 up, Vec3 f, double lead, double tail, float base) {
        Vec3[] c = new Vec3[COLS + 1], w = new Vec3[COLS + 1];
        for (int i = 0; i <= COLS; i++) {
            c[i] = origin.add(world(tr.direction(Mth.lerp(i / (double) COLS, tail, lead)), right, up, f).scale(RADIUS));
        }
        for (int i = 0; i <= COLS; i++) {
            Vec3 tangent = c[Math.min(i + 1, COLS)].subtract(c[Math.max(i - 1, 0)]);
            Vec3 across = tangent.cross(c[i].subtract(cam)); // square to the line of sight, so the ribbon is never seen edge-on
            across = across.lengthSqr() < 1e-8 ? up : across.normalize();
            w[i] = across.dot(c[i].subtract(origin)) < 0 ? across.reverse() : across; // outward from the wielder
        }
        for (int k = 0; k < COLS; k++) {
            double um = (k + 0.5) / COLS;
            int rows = Math.max(1, (int) Math.round(Math.pow(Math.sin(Math.PI * um), 0.7) * ROWS)); // thick in the middle, pointed at the ends
            for (int r = 0; r < rows; r++) {
                cell(buf, mat, cam, c[k].subtract(w[k].scale(CELL * (r + 1))), c[k].subtract(w[k].scale(CELL * r)),
                        c[k + 1].subtract(w[k + 1].scale(CELL * r)), c[k + 1].subtract(w[k + 1].scale(CELL * (r + 1))), base * BAND[r] * step(um));
            }
        }
    }

    /**
     * A thrust: a streak along the aim line from the hand to a little past where the tip ends, a cross of two quads that widens outward so it reads from any
     * angle. The hand is low on the right, so on screen it is a cone shooting out toward the aim point.
     */
    private static void streak(BufferBuilder buf, Matrix4f mat, Vec3 cam, Anim.Trail tr, Vec3 origin, Vec3 right, Vec3 up, Vec3 f, double lead, double tail, float base) {
        Vec3 aim = world(tr.direction(0), right, up, f);
        double d0 = tr.reachAt(0), d1 = tr.reachAt(1) + GRIP_TO_TIP + STREAK_PAST;
        for (int k = 0; k < THRUST_CELLS; k++) {
            double u0 = k / (double) THRUST_CELLS, u1 = (k + 1) / (double) THRUST_CELLS;
            Vec3 p0 = origin.add(aim.scale(Mth.lerp(Mth.lerp(u0, tail, lead), d0, d1))), p1 = origin.add(aim.scale(Mth.lerp(Mth.lerp(u1, tail, lead), d0, d1)));
            double half = 0.05 + 0.04 * Math.round(u0 * 3);
            for (Vec3 side : new Vec3[] {right, up}) {
                cell(buf, mat, cam, p0.add(side.scale(-half)), p0.add(side.scale(half)), p1.add(side.scale(half)), p1.add(side.scale(-half)), base * step(u0));
            }
        }
    }

    /** Alpha in four flat steps, rising toward the leading edge (pixel art has no gradients). */
    static float step(double u) {
        return 0.4f + 0.2f * Math.min(3, (int) (u * 4));
    }

    private static void cell(BufferBuilder buf, Matrix4f mat, Vec3 cam, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float alpha) {
        for (Vec3 v : new Vec3[] {a, b, c, d}) {
            buf.addVertex(mat, (float) (v.x - cam.x), (float) (v.y - cam.y), (float) (v.z - cam.z)).setColor(1f, 1f, 1f, Mth.clamp(alpha, 0f, 1f));
        }
    }
}
