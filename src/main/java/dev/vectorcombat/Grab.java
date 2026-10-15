package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The zombie's grab and bite (plain zombies only): an adult grabber, armed or not (the unarmed rule left almost none, Jaam's arming most),
 * ({@code #vectorcombat:grabbers}) within {@link #RANGE} of its player, not mid wind-up, rolls every {@link #REROLL} ticks ({@link #CHANCE} to grab) and reaches for them instead of its hit: it plants and leans in with its arms out for
 * {@link #TELL} ticks, then lunges. Still within {@link #LATCH} at the end, it grabs (a {@link Holds} claim, so never on top of another grab): the player is slowed hard and cannot
 * sprint for {@link #HOLD} ticks, then it bites (its own attack) and adds Weakness for {@link #WEAKNESS} ticks (not Hunger, so the hunger bar is left alone). A shield up as it lunges stops the grab; raised within the parry window it
 * staggers the zombie too. Any hit on it by anyone breaks the grab (it is dazed). At most every {@link #COOLDOWN} ticks.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Grab {
    private Grab() {
    }

    public static final TagKey<EntityType<?>> GRABBERS = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "grabbers"));

    static final double RANGE = 3.5, MIN = 1.2, LATCH = 2.6, LUNGE = 0.45, SLIP = 3.5, CHANCE = 0.35;
    static final int TELL = 12, HOLD = 20, WEAKNESS = 80, REROLL = 40, COOLDOWN = 160, STAGGER = 30, DAZE = 10;

    enum Phase { TELL, HOLD }

    private static final class State {
        Phase phase = Phase.TELL;
        int age;
        final Player victim;

        State(Player victim) {
            this.victim = victim;
        }
    }

    private static final Map<Mob, State> STATES = new WeakHashMap<>();
    private static final Map<Mob, Long> NEXT = new WeakHashMap<>();

    static {
        Holds.onClear(STATES::clear);
    }

    // ---- pure ----

    /** Whether a grabber {@code distance} from its player may reach for them: adult, between {@link #MIN} and {@link #RANGE}, the player free, out of its cooldown. */
    static boolean ready(boolean adult, double distance, boolean playerFree, long now, long next) {
        return adult && distance >= MIN && distance <= RANGE && playerFree && now >= next;
    }

    /** What a shield does to the grab as it lands: 0 nothing (it grabs), 1 blocked (no grab), 2 parried (no grab, the zombie staggered). */
    static int shielded(boolean shieldUp, int ticksUp, int parryTicks) {
        if (!shieldUp) return 0;
        return ticksUp < parryTicks ? 2 : 1;
    }

    // ---- the game ----

    /** A grabbing zombie is immobile to its own goals ({@code LivingEntityMixin}) and holds its plain attack ({@code MeleeAttackGoalMixin}). */
    public static boolean holding(LivingEntity e) {
        return e instanceof Mob m && !STATES.isEmpty() && STATES.containsKey(m);
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || !mob.getType().is(GRABBERS)) return;
        long now = level.getGameTime();
        State st = STATES.get(mob);
        if (st == null) {
            if (!CommonConfig.MOB_WIND_UPS.get() || Stuns.isStunned(mob) || !(mob.getTarget() instanceof Player p) || !p.isAlive() || p.isSpectator() || p.isCreative()) return;
            if (MobGuard.windingUp(mob) || !mob.getSensing().hasLineOfSight(p)) return;
            if (!ready(!mob.isBaby(), mob.distanceTo(p), !Holds.heldByOther(p, mob), now, NEXT.getOrDefault(mob, 0L))) return;
            if (mob.getRandom().nextDouble() >= CHANCE) { // not this time: its plain hit, and another roll in 2 s
                NEXT.put(mob, now + REROLL);
                return;
            }
            if (!MobTokens.mayStart(mob, p)) return; // a crowd takes turns
            st = new State(p);
            STATES.put(mob, st);
            mob.getNavigation().stop();
            WindUp.signal(mob, p, "grab", "start", TELL);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.7f);
            Diag.event("grab", "result", "tell", "mob", mob.getType().getDescriptionId(), "dist", Math.round(mob.distanceTo(p) * 100) / 100.0, "tick", now);
            return;
        }
        Player p = st.victim;
        if (!mob.isAlive() || p == null || !p.isAlive() || p.isRemoved() || p.level() != level) {
            end(mob, st, "lost");
            return;
        }
        st.age++;
        mob.getLookControl().setLookAt(p, 30, 30);
        if (st.phase == Phase.TELL) {
            if (WindUp.hurt(mob) || Stuns.isStunned(mob)) {
                end(mob, st, "interrupted");
                return;
            }
            if (st.age < TELL) return;
            Vec3 to = p.position().subtract(mob.position()).multiply(1, 0, 1);
            if (to.lengthSqr() > 1e-6) {
                mob.setDeltaMovement(to.normalize().scale(LUNGE).add(0, mob.getDeltaMovement().y, 0)); // the lunge
                mob.hurtMarked = true;
            }
            if (mob.distanceTo(p) > LATCH) {
                end(mob, st, "missed");
                return;
            }
            int shield = shielded(Guard.shield(p), p.getTicksUsingItem(), CommonConfig.GUARD_PARRY_TICKS.get());
            if (shield > 0) {
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1f, shield == 2 ? 0.8f : 1f);
                if (shield == 2) Stuns.apply(mob, STAGGER / 20.0);
                mob.setDeltaMovement(to.lengthSqr() > 1e-6 ? to.normalize().scale(-0.4) : Vec3.ZERO);
                mob.hurtMarked = true;
                end(mob, st, shield == 2 ? "parried" : "blocked");
                return;
            }
            if (!Holds.claim(p, mob)) {
                end(mob, st, "taken");
                return;
            }
            st.phase = Phase.HOLD;
            st.age = 0;
            WindUp.signal(mob, p, "grab", "leap", TELL);
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 1.2f, 0.8f);
            Diag.event("grab", "result", "grab", "mob", mob.getType().getDescriptionId(), "tick", now);
            return;
        }
        // HOLD: slowed hard, no sprint; then the bite
        if (mob.distanceTo(p) > SLIP) {
            end(mob, st, "slipped");
            return;
        }
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 3, 4, false, false), mob);
        p.setSprinting(false);
        mob.setDeltaMovement(Vec3.ZERO);
        if (st.age < HOLD) return;
        boolean bit = mob.doHurtTarget(p);
        mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (bit) p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS, 0), mob);
        end(mob, st, bit ? "bit" : "bite_blocked");
    }

    private static void end(Mob mob, State st, String result) {
        STATES.remove(mob);
        Holds.release(st.victim, mob);
        NEXT.put(mob, mob.level().getGameTime() + COOLDOWN);
        if (st.phase == Phase.TELL) WindUp.signal(mob, st.victim, "grab", "interrupted".equals(result) ? "interrupted" : "ended", TELL);
        Diag.event("grab", "result", result, "mob", mob.getType().getDescriptionId(), "tick", mob.level().getGameTime());
    }

    /** Any hit on a grabbing zombie by anyone breaks the grab: it lets go, dazed. */
    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent e) {
        if (STATES.isEmpty() || !(e.getEntity() instanceof Mob mob) || mob.level().isClientSide() || e.getAmount() <= 0) return;
        State st = STATES.get(mob);
        if (st == null || st.phase != Phase.HOLD || e.getSource().getEntity() == mob) return;
        Stuns.apply(mob, DAZE / 20.0);
        end(mob, st, "broken");
    }
}
