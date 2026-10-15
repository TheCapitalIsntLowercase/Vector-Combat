package dev.vectorcombat;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A swing knocks projectiles out of the air ({@code [guard] deflect}). From the moment a swing is accepted until {@link #AFTER} ticks past its impact, every tick, a projectile
 * flying at the swinger whose path this tick passes through the swing's arc (its reach plus {@link #MARGIN}) is met: within {@link #PERFECT} tick of the impact it is
 * <em>reflected</em> (vanilla's {@code REVERSE} deflection: back the way it came, now the swinger's), with the parry's ring, sparks and a knock of the swinger's view; earlier or
 * later it is <em>knocked away</em> (it bounces off and drops), with the block's sound and sparks. Each projectile once per swing. Types in {@code #vectorcombat:undeflectable}
 * are never touched. Checking only the impact tick caught almost nothing.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
final class Deflect {
    private Deflect() {
    }

    static final TagKey<EntityType<?>> UNDEFLECTABLE = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "undeflectable"));

    /** Ticks past the impact the swing still meets projectiles; ticks either side of the impact that count as perfect. */
    static final int AFTER = 2, PERFECT = 1;
    /** Blocks added to the swing's reach for a projectile, and half the size of the box it is tested as. */
    static final double MARGIN = 0.75, SIZE = 0.4;

    private record Armed(double range, double angle, long impact, Set<Integer> met) {
    }

    private static final Map<ServerPlayer, Armed> ARMED = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code p}'s swing reaching {@code range} with arc {@code angle} was accepted and lands at game time {@code impact}: it meets projectiles from now. */
    static void armed(ServerPlayer p, double range, double angle, long impact) {
        if (CommonConfig.DEFLECT.get()) ARMED.put(p, new Armed(range, angle, impact, new HashSet<>()));
    }

    /** What a projectile gets, met at game time {@code now} by a swing landing at {@code impact}: 2 reflect, 1 knock away, 0 nothing; it must be coming at the swinger. */
    static int verdict(boolean met, boolean incoming, long now, long impact) {
        if (!met || !incoming) return 0;
        return Math.abs(now - impact) <= PERFECT ? 2 : 1;
    }

    /** Whether the path from {@code at} along {@code v} this tick passes through the arc: sampled every half block, both ends included. */
    static boolean path(Vec3 eye, float yaw, Vec3 at, Vec3 v, double range, double angle) {
        int steps = Math.max(1, (int) Math.ceil(v.length() / 0.5));
        for (int i = 0; i <= steps; i++) {
            Vec3 pt = at.add(v.scale((double) i / steps));
            if (Arc.hits(eye, yaw, new AABB(pt, pt).inflate(SIZE), range, angle)) return true;
        }
        return false;
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post e) {
        if (ARMED.isEmpty() || !(e.getEntity() instanceof ServerPlayer p)) return;
        Armed a = ARMED.get(p);
        if (a == null) return;
        ServerLevel level = p.serverLevel();
        long now = level.getGameTime();
        if (now > a.impact() + AFTER || !p.isAlive()) {
            ARMED.remove(p);
            return;
        }
        double reach = a.range() + MARGIN;
        Vec3 eye = p.getEyePosition();
        float yaw = p.getYRot();
        List<Projectile> near = level.getEntitiesOfClass(Projectile.class, p.getBoundingBox().inflate(reach + 4),
                pr -> pr.isAlive() && pr.getOwner() != p && !pr.getType().is(UNDEFLECTABLE) && pr.getDeltaMovement().lengthSqr() > 0.01 && !a.met().contains(pr.getId()));
        for (Projectile pr : near) {
            Vec3 at = pr.position(), v = pr.getDeltaMovement();
            int what = verdict(path(eye, yaw, at, v, reach, a.angle()), v.dot(eye.subtract(at)) > 0, now, a.impact());
            if (what == 0) continue;
            a.met().add(pr.getId());
            Vec3 toward = eye.subtract(at).normalize();
            int sparks;
            if (what == 2) {
                pr.deflect(ProjectileDeflection.REVERSE, p, p, true);
                sparks = HitFx.parry(level, at, toward.scale(-1), 4).sparks();
                PacketDistributor.sendToPlayer(p, new GuardPayload(p.getId(), -1, GuardPayload.DEFLECT, 0, 4, false));
            } else {
                pr.setDeltaMovement(v.scale(-0.25).add(0, -0.15, 0));
                pr.hasImpulse = true;
                sparks = HitFx.block(level, at, toward.scale(-1), 3);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 0.8f, 1.4f);
            }
            Diag.event("deflect", "projectile", pr.getType().getDescriptionId(), "result", what == 2 ? "reflected" : "knocked_away", "offImpactTicks", now - a.impact(),
                    "speed", v.length(), "particles", sparks, "tick", now);
        }
    }
}
