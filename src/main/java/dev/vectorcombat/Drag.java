package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The drowned's signature: an unarmed adult drowned ({@code #vectorcombat:draggers}) within {@link #RANGE} of a
 * player in water dives under them; a column of bubbles rises under them with a deep-blue ring and a gurgle ({@link #TELL} ticks). If they are still over the spot it latches on and
 * drags them down: {@link #SINK} a tick to {@link #DEPTH} blocks or the floor (never into a block), held there for {@link #HOLD} ticks in all, unable to swim up, losing air
 * {@link #DRAIN} more a tick than vanilla's; it can drown them outright. Any hit on it by anyone breaks the grip; a parry as it latches (a shield raised within the
 * parry window) staggers it and it sinks away. Respiration halves the hold and the extra drain; Water Breathing or Conduit Power halve the hold and stop the extra drain. One drowned
 * holds a player at a time, each at most every {@link #COOLDOWN} ticks. The screen's edges tint deep blue while held (no blur).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Drag {
    private Drag() {
    }

    public static final TagKey<EntityType<?>> DRAGGERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "draggers"));

    static final double RANGE = 4, COLUMN = 2, LATCH = 3, SINK = 0.12, DEPTH = 3, DIVE = 0.15, THROWN_OFF = 0.5;
    static final int TELL = 15, HOLD = 40, DRAIN = 2, COOLDOWN = 200, STAGGER = 30, DAZE = 10;

    enum Phase { TELL, HOLD }

    private static final class State {
        Phase phase = Phase.TELL;
        int age, hold;
        final Player victim;
        final Vec3 spot;
        double startY;

        State(Player victim, Vec3 spot) {
            this.victim = victim;
            this.spot = spot;
        }
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT = new WeakHashMap<>();
    private static final Map<Player, Mob> HELD = new WeakHashMap<>();

    static {
        Holds.onClear(() -> {
            STATES.clear();
            HELD.clear();
        });
    }

    // ---- pure ----

    /** Whether a drowned {@code distance} from a player starts the drag. */
    static boolean ready(boolean unarmed, boolean adult, boolean playerInWater, boolean riding, double distance, boolean playerFree, long now, long next) {
        return unarmed && adult && playerInWater && !riding && distance <= RANGE && playerFree && now >= next;
    }

    /** Whether it latches at the end of the tell: the player still in water, within {@link #COLUMN} of the spot sideways, within {@link #LATCH} of it. */
    static boolean latches(boolean inWater, double fromSpot, double fromDrowned) {
        return inWater && fromSpot <= COLUMN && fromDrowned <= LATCH;
    }

    /** The hold in ticks for a player with {@code respiration} (the oxygen bonus) or {@code waterBreathing}: halved by either. */
    static int hold(boolean respiration, boolean waterBreathing) {
        return respiration || waterBreathing ? HOLD / 2 : HOLD;
    }

    /** The extra air lost a tick: none with Water Breathing, halved by Respiration. */
    static int drain(boolean respiration, boolean waterBreathing) {
        return waterBreathing ? 0 : respiration ? DRAIN / 2 : DRAIN;
    }

    /**
     * The air after the extra drain: vanilla deals drowning damage when the air is exactly -20 after its own tick's decrement, so the extra never takes it past -19 (a jump from -18
     * to -21 would skip the damage and drain forever); at -19 or below it adds nothing.
     */
    static int drained(int air, int extra) {
        return air <= -19 ? air : Math.max(-19, air - extra);
    }

    /** The next height while sinking from {@code y}: down by {@link #SINK}, not below {@code startY - DEPTH}; {@code blocked}: the step below collides, so it stays. */
    static double sink(double y, double startY, boolean blocked) {
        return blocked ? y : Math.max(startY - DEPTH, y - SINK);
    }

    // ---- the game ----

    /** A drowned in the drag is immobile to its own goals ({@code LivingEntityMixin}); we move it. */
    public static boolean holding(LivingEntity e) {
        return e instanceof Mob m && !STATES.isEmpty() && STATES.containsKey(m);
    }

    private static boolean waterBreathing(Player p) {
        return p.hasEffect(MobEffects.WATER_BREATHING) || p.hasEffect(MobEffects.CONDUIT_POWER);
    }

    private static boolean respiration(Player p) {
        return p.getAttributes().hasAttribute(Attributes.OXYGEN_BONUS) && p.getAttributeValue(Attributes.OXYGEN_BONUS) > 0;
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || !mob.getType().is(DRAGGERS)) return;
        long now = level.getGameTime();
        State st = STATES.get(mob);
        if (st == null) {
            if (!CommonConfig.MOB_WIND_UPS.get() || Stuns.isStunned(mob) || !(mob.getTarget() instanceof Player p) || !p.isAlive() || p.isSpectator() || p.isCreative()) return;
            if (!ready(mob.getMainHandItem().isEmpty(), !mob.isBaby(), p.isInWater(), p.isPassenger(), mob.distanceTo(p), !HELD.containsKey(p) && !Holds.heldByOther(p, mob), now, NEXT.getOrDefault(mob, 0L))) return;
            st = new State(p, p.position());
            STATES.put(mob, st);
            HELD.put(p, mob); // claimed: no second drowned starts on this player
            WindUp.signal(mob, p, "drag", "start", TELL);
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.DROWNED_AMBIENT_WATER, SoundSource.HOSTILE, 1.6f, 0.6f);
            SpiderMoves.ring(mob, p.position(), 0.9, TELL, LinePayload.DEEP);
            Diag.event("drag", "result", "tell", "mob", mob.getType().getDescriptionId(), "dist", Math.round(mob.distanceTo(p) * 100) / 100.0, "tick", now);
            return;
        }
        Player p = st.victim;
        if (!mob.isAlive() || p == null || !p.isAlive() || p.isRemoved() || p.level() != level) {
            end(mob, st, "lost");
            return;
        }
        st.age++;
        if (st.phase == Phase.TELL) {
            if (WindUp.hurt(mob)) {
                end(mob, st, "interrupted");
                return;
            }
            Vec3 under = st.spot.add(0, -1.5, 0), to = under.subtract(mob.position());
            mob.setDeltaMovement(to.lengthSqr() < 0.01 ? Vec3.ZERO : to.normalize().scale(DIVE)); // dives under the player
            mob.lookAt(p, 30, 30);
            for (int i = 0; i < 3; i++) level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, st.spot.x + (mob.getRandom().nextDouble() - 0.5) * 0.8, st.spot.y - 1 + mob.getRandom().nextDouble() * 1.5,
                    st.spot.z + (mob.getRandom().nextDouble() - 0.5) * 0.8, 1, 0, 0.1, 0, 0.05);
            if (st.age >= TELL) {
                double fromSpot = Math.hypot(p.getX() - st.spot.x, p.getZ() - st.spot.z);
                if (!latches(p.isInWater(), fromSpot, mob.distanceTo(p))) {
                    end(mob, st, "missed");
                    return;
                }
                if (Guard.shield(p) && p.getTicksUsingItem() < CommonConfig.GUARD_PARRY_TICKS.get()) { // parried as it latched: staggered, it sinks away
                    Stuns.apply(mob, STAGGER / 20.0);
                    mob.setDeltaMovement(0, -0.3, 0);
                    mob.hurtMarked = true;
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1f, 0.8f);
                    end(mob, st, "parried");
                    return;
                }
                if (!Holds.claim(p, mob)) { // held by another grab meanwhile
                    end(mob, st, "taken");
                    return;
                }
                st.phase = Phase.HOLD;
                st.age = 0;
                st.hold = hold(respiration(p), waterBreathing(p));
                st.startY = p.getY();
                if (p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new EngulfPayload(st.hold, EngulfPayload.DEEP));
                WindUp.signal(mob, p, "drag", "leap", TELL);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.DROWNED_HURT_WATER, SoundSource.HOSTILE, 1.4f, 0.5f);
                Diag.event("drag", "result", "grab", "mob", mob.getType().getDescriptionId(), "hold", st.hold, "tick", now);
            }
            return;
        }
        // HOLD: down to the depth or the floor, never into a block; no swimming up; air drains faster
        boolean blocked = !level.noCollision(p, p.getBoundingBox().move(0, -SINK, 0));
        double y = sink(p.getY(), st.startY, blocked);
        p.teleportTo(p.getX(), y, p.getZ());
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        mob.setPos(p.getX(), y - 0.4, p.getZ());
        mob.setDeltaMovement(Vec3.ZERO);
        int extra = drain(respiration(p), waterBreathing(p));
        if (extra > 0 && p.isUnderWater()) p.setAirSupply(drained(p.getAirSupply(), extra));
        if (st.age % 4 == 0) level.sendParticles(ParticleTypes.BUBBLE, p.getX(), p.getY() + 1.4, p.getZ(), 3, 0.2, 0.2, 0.2, 0.05);
        if (st.age >= st.hold || !p.isInWater()) end(mob, st, "released");
    }

    private static void end(Mob mob, State st, String result) {
        STATES.remove(mob);
        if (st.victim != null && HELD.get(st.victim) == mob) HELD.remove(st.victim);
        Holds.release(st.victim, mob);
        NEXT.put(mob, mob.level().getGameTime() + COOLDOWN);
        if (st.phase == Phase.HOLD && st.victim instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new EngulfPayload(0, EngulfPayload.DEEP));
        WindUp.signal(mob, st.victim, "drag", "interrupted".equals(result) ? "interrupted" : "ended", TELL);
        Diag.event("drag", "result", result, "mob", mob.getType().getDescriptionId(), "tick", mob.level().getGameTime());
    }

    /** Any hit on a dragging drowned by anyone breaks the grip: it lets go, pushed away and dazed. */
    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent e) {
        if (STATES.isEmpty() || !(e.getEntity() instanceof Mob mob) || mob.level().isClientSide() || e.getAmount() <= 0) return;
        State st = STATES.get(mob);
        if (st == null || st.phase != Phase.HOLD || e.getSource().getEntity() == mob) return;
        Player p = st.victim;
        if (p != null) {
            Vec3 away = mob.position().subtract(p.position());
            mob.setDeltaMovement(away.lengthSqr() < 1e-6 ? new Vec3(0, -THROWN_OFF, 0) : away.normalize().scale(THROWN_OFF));
            mob.hurtMarked = true;
        }
        Stuns.apply(mob, DAZE / 20.0);
        end(mob, st, "broken");
    }
}
