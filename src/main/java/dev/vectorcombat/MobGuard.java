package dev.vectorcombat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Mobs guard by the player's rules ({@link Guard}): a mob using a shield or a weapon in the block pose parries a hit that lands in the first {@code [guard] parryTicks} of a
 * raise that was re-armed, from the front, and otherwise blocks it: a shield stops it all, a weapon {@code [mobGuard] weaponBlock} of it (weapon mods block only for players).
 * A block reads as one, not as a hit: the shield's block sound, sparks off the guard, the mob pushed back a little, no hit pause or hit effects ({@link #justBlocked}, read by
 * {@link Strike}). An axe knocks a mob's guard down for {@code [mobGuard] disableTicks}, as vanilla does to a player's shield. A parry cancels the hit, shoves the attacker back
 * and, for a player, locks their attacks for {@code [mobGuard] parriedLockout} seconds with a knock of the view.
 * <p>
 * When a guard goes up ({@code [mobGuard] react}): a mob that is after the player, sees them, faces them and stands within reach of their swing reads it the moment the server
 * accepts it and, by the odds of {@link MobGuardData} (mob, blocker, weapon type, difficulty), raises its guard to land the hit inside the window (a parry), early (a block) or not
 * at all, then waits its cooldown before choosing again. Mobs never get equipment from here: they guard with what they hold. {@code /vcdiag mobguard} raises guards on cue.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class MobGuard {
    private MobGuard() {
    }

    /** How hard a block pushes the blocking mob back: a step, not a knockback. */
    static final double BLOCK_PUSH = 0.3;

    /** Ticks before a swing's hit lands that a mob raises for a parry: the hit lands about 2 ticks into the raise, inside the window. */
    static final int PARRY_LEAD = 2;

    /** Ticks before the hit that a mob raises for a block: past the window when the hit lands. */
    static final int BLOCK_LEAD = 7;

    /** Ticks a raise is held after the hit was due to land: long enough for a late hit, short enough to re-arm before the next swing. */
    static final int HOLD_AFTER = 8;

    /** Blocks beyond the swing's own reach within which a mob still reads it. */
    static final double READ_MARGIN = 1.5;

    /** The hand a mob guards with: an off-hand shield first, else a main-hand item used in the block pose (or a shield); null when it holds nothing that guards. */
    static InteractionHand guardHand(LivingEntity m) {
        if (guardItem(m.getOffhandItem()) && m.getOffhandItem().canPerformAction(ItemAbilities.SHIELD_BLOCK)) return InteractionHand.OFF_HAND;
        if (guardItem(m.getMainHandItem())) return InteractionHand.MAIN_HAND;
        return guardItem(m.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
    }

    static boolean guardItem(ItemStack s) {
        return !s.isEmpty() && Guard.guards(s.canPerformAction(ItemAbilities.SHIELD_BLOCK), s.getUseAnimation());
    }

    /** The fraction of {@code damage} that gets past a weapon blocking {@code stops} of it. */
    static float through(float damage, double stops) {
        return (float) (damage * (1 - Math.max(0, Math.min(1, stops))));
    }

    /** Durability a block of {@code damage} costs, vanilla's shield rule: nothing under 3, else 1 + floor(damage). */
    static int wear(float damage) {
        return damage < 3 ? 0 : 1 + (int) Math.floor(damage);
    }

    /** The tick a mob raises at for a hit {@code delay} ticks away: {@code lead} ticks before it, or now when that has passed. */
    static int raiseIn(long delay, int lead) {
        return (int) Math.max(0, delay - lead);
    }

    // ---- per mob and player ----

    /** Game time until which a mob's guard is knocked down by an axe. */
    private static final Map<LivingEntity, Long> DISABLED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time a mob last blocked a hit, for {@link #justBlocked}. */
    private static final Map<LivingEntity, Long> BLOCKED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time until which a mob's raise is a chosen block: a hit inside the window is blocked, not parried. */
    private static final Map<LivingEntity, Long> BLOCK_ONLY = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time before which a mob does not choose to guard again. */
    private static final Map<LivingEntity, Long> NEXT = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time a goal mob's wind-up ends at (set by {@code MeleeAttackGoalMixin}); a mob winding up is committed and does not guard. */
    private static final Map<LivingEntity, Long> WINDING = Collections.synchronizedMap(new WeakHashMap<>());
    /** Game time from which a mob that parried may riposte (its next attack starts at once), for a short while. */
    private static final Map<LivingEntity, Long> RIPOSTE = Collections.synchronizedMap(new WeakHashMap<>());
    /** Ticks a riposte stays due after it opens: a mob out of reach by then attacks on its own time. */
    static final int RIPOSTE_DUE = 20;
    /** Game time until which a parried player cannot start a swing. */
    private static final Map<ServerPlayer, Long> LOCKED = Collections.synchronizedMap(new WeakHashMap<>());

    /** Mobs under {@code /vcdiag mobguard}: what they do, and for whom. */
    private record Drill(String mode, ServerPlayer player) {
    }

    private static final Map<Mob, Drill> DRILLS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Called by {@code MeleeAttackGoalMixin} with the game time the mob's wind-up ends at (0: none). */
    public static void windingUp(Mob m, long until) {
        if (until == 0) WINDING.remove(m);
        else WINDING.put(m, until);
    }

    /** Whether {@code m} is winding up an attack (a goal mob's or a brain mob's): then it is committed and does not raise its guard. */
    static boolean windingUp(Mob m) {
        Long until = WINDING.get(m);
        return until != null && until > m.level().getGameTime() || BrainWindUp.windingUp(m);
    }

    /** Whether a riposte is due for {@code m} now: once, from {@code [mobGuard] riposteTicks} after its parry until {@link #RIPOSTE_DUE} later. Called by {@code MeleeAttackGoalMixin}. */
    public static boolean takeRiposte(Mob m) {
        if (RIPOSTE.isEmpty()) return false;
        Long at = RIPOSTE.get(m);
        long now = m.level().getGameTime();
        if (at == null || now < at) return false;
        RIPOSTE.remove(m);
        return due(at, now);
    }

    /** Whether a riposte that opened at {@code at} is still due at {@code now}. */
    static boolean due(long at, long now) {
        return now >= at && now - at <= RIPOSTE_DUE;
    }

    /** After a parry: the mob lowers its guard {@code [mobGuard] riposteTicks} later and its next attack starts at once (a goal mob's cooldown, a brain mob's cooling memory, a closed gate). */
    private static void riposte(Mob m) {
        if (!CommonConfig.MOB_GUARD_RIPOSTE.get()) return;
        int in = CommonConfig.MOB_GUARD_RIPOSTE_TICKS.get();
        RIPOSTE.put(m, m.level().getGameTime() + in);
        Combat.later(in, () -> {
            if (!m.isAlive()) return;
            Drill d = DRILLS.get(m);
            if ((d == null || !d.mode().equals("hold")) && Guard.shield(m)) m.stopUsingItem();
            WindUp.close(m, 0);
            m.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_COOLING_DOWN);
            Diag.event("mob_guard_riposte", "mob", m.getType().getDescriptionId(), "afterTicks", in, "tick", m.level().getGameTime());
        });
    }

    private static boolean disabled(LivingEntity m) {
        Long until = DISABLED.get(m);
        return until != null && m.level().getGameTime() < until;
    }

    /** True when {@code e} blocked a hit in this tick or the last: the hit is not drawn as one (no pause, no hit sparks, no knockback). */
    static boolean justBlocked(LivingEntity e) {
        Long at = BLOCKED.get(e);
        return at != null && e.level().getGameTime() - at <= 1;
    }

    /** True while a mob's parry of {@code p}'s hit keeps them from swinging. */
    static boolean lockedOut(ServerPlayer p) {
        Long until = LOCKED.get(p);
        return until != null && p.level().getGameTime() < until;
    }

    @SubscribeEvent
    static void tick(EntityTickEvent.Post e) {
        if (!(e.getEntity() instanceof Mob m) || m.level().isClientSide()) return;
        boolean up = Guard.shield(m);
        if (up && disabled(m)) {
            m.stopUsingItem();
            up = false;
        }
        Guard.Raise r = up ? Guard.raise(m) : Guard.raiseIfAny(m);
        long now = m.level().getGameTime();
        if (r != null && r.look(now, up, CommonConfig.GUARD_REARM_TICKS.get()))
            Diag.event("mob_guard_raise", "mob", m.getType().getDescriptionId(), "armed", r.armed, "downTicks", r.down, "hand", m.getUsedItemHand().name(), "blocker", Guard.blocker(m), "tick", now);
        if (!up && !DRILLS.isEmpty()) {
            Drill d = DRILLS.get(m);
            if (d != null && d.mode().equals("hold")) raise(m);
        }
    }

    private static boolean raise(Mob m) {
        InteractionHand hand = guardHand(m);
        if (hand == null || disabled(m) || !m.isAlive() || Stuns.isStunned(m) || Guard.shield(m)) return false;
        m.startUsingItem(hand);
        return true;
    }

    /** Raises {@code m}'s guard in {@code in} ticks and lowers it {@code hold} ticks later, unless it is held up by the drill's hold mode; a block only blocks. */
    private static void timedRaise(Mob m, int in, int hold, boolean blockOnly) {
        Combat.later(in, () -> {
            if (!raise(m)) return;
            if (blockOnly) BLOCK_ONLY.put(m, m.level().getGameTime() + hold);
            Combat.later(hold, () -> {
                Drill d = DRILLS.get(m);
                if ((d == null || !d.mode().equals("hold")) && Guard.shield(m)) m.stopUsingItem();
            });
        });
    }

    // ---- a mob reads a swing ----

    /** A swing of {@code p} reaching {@code range} blocks was accepted and lands in {@code delay} ticks: drilled mobs raise on cue, others in reach may choose to guard. */
    static void swing(ServerPlayer p, long delay, double range) {
        if (!CommonConfig.MOB_GUARD.get()) return;
        long now = p.level().getGameTime();
        double reach = range + READ_MARGIN;
        List<Mob> near = p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(reach), m -> m.isAlive() && guardHand(m) != null);
        if (near.isEmpty()) return;
        Vec3 look = p.calculateViewVector(0, p.getYHeadRot());
        for (Mob m : near) {
            Drill d = DRILLS.get(m);
            if (d != null) {
                if (d.player() == p && d.mode().equals("parry")) timedRaise(m, raiseIn(delay, PARRY_LEAD), (int) Math.min(delay, PARRY_LEAD) + HOLD_AFTER, false);
                continue;
            }
            if (!CommonConfig.MOB_GUARD_REACT.get() || m.getTarget() != p || disabled(m) || Stuns.isStunned(m) || Guard.shield(m) || now < NEXT.getOrDefault(m, 0L)) continue;
            if (windingUp(m)) { // committed to its own attack: the player's swing beats it or trades
                Diag.event("mob_guard_choice", "mob", m.getType().getDescriptionId(), "choice", "winding_up", "delay", delay, "tick", now);
                continue;
            }
            if (m.distanceTo(p) > reach || !Guard.inFront(look, m.position(), p.position()) || !Guard.inFront(m.calculateViewVector(0, m.getYHeadRot()), p.position(), m.position())
                    || !m.hasLineOfSight(p)) continue; // the mob must be ahead of the swing, face the player and see them
            decide(m, p, delay, now);
        }
    }

    /** Rolls {@code m}'s odds against a hit by {@code attacker} due in {@code delay} ticks and raises its guard for a parry or a block, then its cooldown; or nothing. */
    private static void decide(Mob m, LivingEntity attacker, long delay, long now) {
        InteractionHand hand = guardHand(m);
        ItemStack item = m.getItemInHand(hand);
        String blocker = item.canPerformAction(ItemAbilities.SHIELD_BLOCK) ? "shield" : "weapon";
        String type = blocker.equals("weapon") ? WeaponData.type(item.getItem()) : null;
        String diff = m.level().getDifficulty().getKey();
        MobGuardData.Odds odds = MobGuardData.roll(MobGuardData.base(m.getType()), MobGuardData.blocker(blocker), MobGuardData.weaponType(type), MobGuardData.difficulty(diff));
        String choice = MobGuardData.choose(odds, m.getRandom().nextDouble());
        Diag.event("mob_guard_choice", "mob", m.getType().getDescriptionId(), "choice", choice, "blocker", blocker, "weaponType", type, "parryOdds", odds.parry(), "blockOdds", odds.block(),
                "difficulty", diff, "delay", delay, "dist", m.distanceTo(attacker), "against", attacker.getType().getDescriptionId(), "tick", now);
        if (choice.equals("none")) return;
        NEXT.put(m, now + delay + Math.round(odds.cooldown() * 20));
        if (choice.equals("parry")) timedRaise(m, raiseIn(delay, PARRY_LEAD), (int) Math.min(delay, PARRY_LEAD) + HOLD_AFTER, false);
        else timedRaise(m, raiseIn(delay, BLOCK_LEAD), (int) Math.min(delay, BLOCK_LEAD) + HOLD_AFTER, true);
    }

    /**
     * Another mob's wind-up at {@code m} started and lands in {@code ticks} ({@link WindUp#signal}): {@code m} reads the tell as it reads a player's swing, by the same odds,
     * if it holds a guard, faces and sees the attacker, and is free to (not stunned, guarding, winding up, knocked down or cooling down).
     */
    public static void readTell(Mob m, LivingEntity attacker, int ticks) {
        if (!CommonConfig.MOB_GUARD.get() || !CommonConfig.MOB_GUARD_REACT.get() || !m.isAlive() || guardHand(m) == null || DRILLS.containsKey(m)) return;
        long now = m.level().getGameTime();
        if (disabled(m) || Stuns.isStunned(m) || Guard.shield(m) || windingUp(m) || now < NEXT.getOrDefault(m, 0L)) return;
        if (!Guard.inFront(m.calculateViewVector(0, m.getYHeadRot()), attacker.position(), m.position()) || !m.hasLineOfSight(attacker)) return;
        decide(m, attacker, ticks, now);
    }

    // ---- a hit on a mob's guard ----

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void incoming(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof Mob m) || e.getAmount() <= 0 || !Guard.shield(m) || !CommonConfig.MOB_GUARD.get()) return;
        DamageSource src = e.getSource();
        if (!Guard.parryable(src)) return; // what bypasses a shield gets past a guard
        long now = m.level().getGameTime();
        Guard.Raise r = Guard.raise(m);
        r.look(now, true, CommonConfig.GUARD_REARM_TICKS.get());
        int used = m.getTicksUsingItem();
        Vec3 from = src.getSourcePosition();
        boolean front = from != null && Guard.inFront(m.calculateViewVector(0, m.getYHeadRot()), from, m.position());
        boolean window = used < CommonConfig.GUARD_PARRY_TICKS.get();
        boolean chosenBlock = now <= BLOCK_ONLY.getOrDefault(m, -1L);
        String why = !front ? "arc" : !window || chosenBlock ? null : !r.armed ? "rearm" : src.getDirectEntity() instanceof LivingEntity l && Stuns.isStunned(l) ? "stunned" : null;
        if (front && window && !chosenBlock && why == null) {
            parry(m, e, src, used);
            return;
        }
        if (why != null) Diag.event("mob_guard_denied", "mob", m.getType().getDescriptionId(), "why", why, "blocker", Guard.blocker(m), "usedTicks", used, "damage", e.getAmount(), "downTicks", r.down, "tick", now);
        boolean shield = m.getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK);
        // A shield's dead ticks (vanilla blocks from the 5th) let a late, unchosen raise take the hit, as a player's shield does; a weapon blocks from the first.
        if (front && (!shield || used >= 5 || chosenBlock)) block(m, e, src, used, shield);
    }

    private static void parry(Mob m, LivingIncomingDamageEvent e, DamageSource src, int used) {
        ServerLevel level = (ServerLevel) m.level();
        float damage = e.getAmount();
        e.setCanceled(true);
        Guard.markParried(m);
        LivingEntity attacker = src.getDirectEntity() instanceof LivingEntity l ? l : null;
        int weight = Guard.weight(damage);
        double lockout = 0;
        if (attacker != null) {
            attacker.knockback(Guard.SHOVE, m.getX() - attacker.getX(), m.getZ() - attacker.getZ());
            attacker.hurtMarked = true;
            if (attacker instanceof ServerPlayer p) {
                lockout = CommonConfig.MOB_GUARD_PARRIED_LOCKOUT.get();
                LOCKED.put(p, level.getGameTime() + Math.round(lockout * 20));
                p.resetAttackStrengthTicker(); // the cooldown bar empties: the parry cost the swing
                PacketDistributor.sendToPlayer(p, new GuardPayload(m.getId(), p.getId(), GuardPayload.REBUFF, 0, weight, false));
            }
        }
        Vec3 toward = toward(m, attacker, src);
        HitFx.Parried fx = HitFx.parry(level, guardPoint(m, toward), toward, weight);
        if (attacker != null) riposte(m);
        Diag.event("mob_guard_parry", "mob", m.getType().getDescriptionId(), "blocker", Guard.blocker(m), "attacker", attacker == null ? null : attacker.getType().getDescriptionId(),
                "source", src.getMsgId(), "damage", damage, "usedTicks", used, "weight", weight, "lockoutS", lockout, "layers", fx.layers(), "tick", level.getGameTime());
    }

    /** A block: a shield stops the hit, a weapon {@code weaponBlock} of it (and wears as a shield does); either way it sounds and looks like a block and the mob gives a step. */
    private static void block(Mob m, LivingIncomingDamageEvent e, DamageSource src, int used, boolean shield) {
        ServerLevel level = (ServerLevel) m.level();
        float damage = e.getAmount(), left = shield ? 0 : through(damage, CommonConfig.MOB_GUARD_WEAPON_BLOCK.get());
        if (left <= 0) e.setCanceled(true);
        else e.setAmount(left);
        BLOCKED.put(m, level.getGameTime());
        int wear = shield ? 0 : wear(damage - left); // a mob's shield is never worn (vanilla wears only players' shields)
        if (wear > 0) m.getUseItem().hurtAndBreak(wear, m, LivingEntity.getSlotForHand(m.getUsedItemHand()));
        LivingEntity attacker = src.getDirectEntity() instanceof LivingEntity l ? l : null;
        if (attacker != null) {
            m.knockback(BLOCK_PUSH, attacker.getX() - m.getX(), attacker.getZ() - m.getZ());
            m.hurtMarked = true;
        }
        Vec3 toward = toward(m, attacker, src);
        int sparks = HitFx.block(level, guardPoint(m, toward), toward, Guard.weight(damage));
        boolean axe = disableBy(m, src);
        boolean broke = !axe && GuardBreak.breaks(src);
        if (broke) GuardBreak.breakGuard(m, src);
        Diag.event("mob_guard_block", "broken", broke ? true : null, "mob", m.getType().getDescriptionId(), "blocker", shield ? "shield" : "weapon", "damage", damage, "through", left, "wear", wear, "usedTicks", used,
                "chosen", now(m) <= BLOCK_ONLY.getOrDefault(m, -1L), "disabled", axe, "particles", sparks, "tick", level.getGameTime());
    }

    private static long now(LivingEntity m) {
        return m.level().getGameTime();
    }

    private static Vec3 toward(Mob m, LivingEntity attacker, DamageSource src) {
        Vec3 source = attacker != null ? attacker.position() : src.getSourcePosition() != null ? src.getSourcePosition() : m.position().add(m.getLookAngle());
        Vec3 t = new Vec3(source.x - m.getX(), 0, source.z - m.getZ());
        return t.lengthSqr() < 1e-6 ? m.getLookAngle() : t.normalize();
    }

    /** Where a mob's guard meets a hit: chest height, a little out toward the attacker. */
    private static Vec3 guardPoint(Mob m, Vec3 toward) {
        return new Vec3(m.getX(), m.getY(0.6), m.getZ()).add(toward.scale(m.getBbWidth() * 0.5 + 0.2));
    }

    /** A block that reached vanilla's own shield code (one this class did not take): an axe knocks the guard down there too (vanilla does that only to players). */
    @SubscribeEvent
    static void blocked(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof Mob m) || !e.getBlocked() || e.isCanceled() || !CommonConfig.MOB_GUARD.get()) return;
        boolean axe = disableBy(m, e.getDamageSource());
        Diag.event("mob_guard_block", "mob", m.getType().getDescriptionId(), "blocker", "shield", "vanilla", true, "damage", e.getBlockedDamage(), "usedTicks", m.getTicksUsingItem(), "disabled", axe,
                "tick", m.level().getGameTime());
    }

    /** Holds {@code m}'s guard down for {@code ticks} (a guard break). */
    static void knockDown(Mob m, int ticks) {
        DISABLED.put(m, m.level().getGameTime() + ticks);
    }

    private static boolean disableBy(Mob m, DamageSource src) {
        int ticks = CommonConfig.MOB_GUARD_DISABLE_TICKS.get();
        if (ticks <= 0 || !(src.getDirectEntity() instanceof LivingEntity attacker) || !attacker.getMainHandItem().canDisableShield(m.getUseItem(), m, attacker)) return false;
        DISABLED.put(m, m.level().getGameTime() + ticks);
        m.stopUsingItem();
        m.level().playSound(null, m.getX(), m.getY(), m.getZ(), net.minecraft.sounds.SoundEvents.SHIELD_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 0.8f,
                0.8f + m.getRandom().nextFloat() * 0.4f);
        return true;
    }

    // ---- /vcdiag mobguard: mobs near you raise their guard on cue ----

    static int drill(CommandSourceStack src, String mode) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        List<Mob> touched = new ArrayList<>();
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8))) {
            if (mode.equals("off")) {
                if (DRILLS.remove(m) != null) {
                    m.stopUsingItem();
                    touched.add(m);
                }
                continue;
            }
            if (guardHand(m) == null) continue;
            DRILLS.put(m, new Drill(mode, p));
            touched.add(m);
        }
        int count = touched.size();
        String what = switch (mode) {
            case "parry" -> "raise their guard just before each of your hits lands (a parry, unless you hit from the side or behind)";
            case "hold" -> "keep their guard up (a block after the window)";
            default -> "stop drilling and choose for themselves again";
        };
        src.sendSuccess(() -> Component.literal("mob guard: " + count + " mob" + (count == 1 ? "" : "s") + " within 8 blocks " + what
                + (count == 0 && !mode.equals("off") ? ". None holds a shield or a weapon that blocks: give one with /item replace entity @e[type=zombie,limit=1,sort=nearest] weapon.offhand with minecraft:shield" : "")), false);
        Diag.event("mob_guard_drill", "mode", mode, "mobs", count, "enabled", CommonConfig.MOB_GUARD.get());
        return count;
    }
}
