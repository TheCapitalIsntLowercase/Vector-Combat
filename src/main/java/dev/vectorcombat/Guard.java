package dev.vectorcombat;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Rotations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shield timing: the first moments after a raise are a parry, holding the shield is vanilla's block. A shield that has just gone up (within {@code [guard] parryTicks}, 5 by
 * default, which is vanilla's dead time before a raised shield blocks at all) parries a melee hit or an arrow that lands from the front: the hit is cancelled, so it costs no
 * damage, no durability and cannot disable the shield, a melee attacker is shoved back, held for a moment and staggered (stunned, longer for harder hits), and the defender's
 * weapon cooldown is refunded. A raise opens the window only if the shield had been down {@code [guard] rearmTicks} (10) first, so tapping it over and over parries nothing; a
 * tap that is late takes the hit and one that is early is a plain block. Server only; the client's shield motion is {@link GuardClient}.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Guard {
    /** The mod whose own parry this one steps aside for under {@code mode AUTO}. */
    static final String OTHER = "shieldexp";

    /** How long a parried mob is held still, in ticks (the same pause the defender's view takes). */
    static final int FREEZE_TICKS = 4;

    /** How hard a parry shoves the attacker back: vanilla's strength for a block. */
    static final double SHOVE = 0.5;

    /** A parry's stagger is never shorter or longer than this, in seconds. */
    static final double STAGGER_MIN = 0.6, STAGGER_MAX = 1.6;

    private Guard() {
    }

    // ---- the setting ----

    static boolean on(CommonConfig.GuardMode mode, boolean otherLoaded) {
        return switch (mode) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> !otherLoaded;
        };
    }

    private static boolean checked, otherLoaded;
    private static Boolean lastOn;

    private static boolean enabled() {
        if (!checked) {
            otherLoaded = ModList.get().isLoaded(OTHER);
            checked = true;
        }
        CommonConfig.GuardMode mode = CommonConfig.GUARD_MODE.get();
        boolean itemOnly = otherLoaded && itemOnly();
        boolean on = on(mode, otherLoaded && !itemOnly);
        if (!Boolean.valueOf(on).equals(lastOn)) { // once, and again when the setting (or Shield Expansion's mode) changes
            lastOn = on;
            VectorCombat.LOG.info("[diag] guard: {} (mode {}{})", on ? "on" : "off", mode, otherLoaded ? ", " + OTHER + " is installed" + (itemOnly ? " in item only mode" : " with its own parry") : "");
            Diag.event("guard", "on", on, "mode", mode.name(), "other", otherLoaded ? OTHER : null, "itemOnly", otherLoaded ? itemOnly : null);
        }
        return on;
    }

    private static java.lang.reflect.Field itemOnlyField;
    private static boolean itemOnlyBroken;

    /**
     * Shield Expansion's {@code itemOnlyMode}: on, it adds its shields as plain items and runs none of its own blocking, parry or stamina (each of its handlers returns at once),
     * so these rules run under AUTO. Read from its live config field (synced from the server), by name; if that moves, it counts as off (its own parry), as before.
     */
    private static boolean itemOnly() {
        if (itemOnlyBroken) return false;
        try {
            if (itemOnlyField == null) itemOnlyField = Class.forName("org.infernalstudios.shieldexp.config.ShieldExpansionConfig").getField("ITEM_ONLY_MODE");
            return itemOnlyField.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            itemOnlyBroken = true;
            VectorCombat.LOG.warn("[diag] guard: cannot read Shield Expansion's itemOnlyMode ({}), stepping aside for it", e.toString());
            return false;
        }
    }

    // ---- the rules, pure ----

    /** One player's shield: when it was last seen up, and whether the raise in progress opens a parry window. */
    static final class Raise {
        long lastUp = -1_000_000;
        boolean up, armed, parried;
        long down;

        /** The first parry of this raise gets the whole show (true); any later one in the same raise is one more attacker dealt with (false). */
        boolean claim() {
            if (parried) return false;
            parried = true;
            return true;
        }

        /**
         * Looks at the shield at game time {@code now}, raised or not. A raise first seen now is armed if the shield had been down {@code rearm} ticks (the ticks since it was
         * last seen up). Returns true for that first look, from whichever caller makes it (the tick, or a hit that lands in the same tick the shield went up).
         */
        boolean look(long now, boolean raised, int rearm) {
            boolean began = raised && !up;
            if (began) {
                down = now - lastUp;
                armed = down >= rearm;
                parried = false;
            }
            if (raised) lastUp = now;
            up = raised;
            return began;
        }
    }

    /** Ticks between two recoils sent for a blocked hit: longer than the recoil clip, so contact that blocks every tick (a slime pressed on the shield) never restarts it. */
    static final int BLOCK_GAP = 6;

    /** Whether a blocked hit at game time {@code now} gets its recoil sent, the last one having been sent at {@code last}. */
    static boolean blockDue(long last, long now) {
        return now - last >= BLOCK_GAP;
    }

    /** Whether a hit {@code used} ticks into a raise is inside the parry window: the first {@code parryTicks} ticks of a raise that was armed. */
    static boolean inWindow(boolean armed, int used, int parryTicks) {
        return armed && used < parryTicks;
    }

    /**
     * Vanilla's shield arc, {@code LivingEntity.isDamageSourceBlocked}: the source is in front when the horizontal vector from the source to the defender points against the
     * defender's head-yaw view vector {@code view}.
     */
    static boolean inFront(Vec3 view, Vec3 source, Vec3 defender) {
        return new Vec3(defender.x - source.x, 0, defender.z - source.z).normalize().dot(view) < 0;
    }

    /** Seconds a parried attacker is staggered: {@code base} plus {@code perDamage} for each point of the hit's damage, within {@link #STAGGER_MIN} and {@link #STAGGER_MAX}. */
    static double stagger(double damage, double base, double perDamage) {
        return Math.max(STAGGER_MIN, Math.min(STAGGER_MAX, base + perDamage * damage));
    }

    /** How hard a parry of {@code damage} hits, as a pause in ticks for {@link HitFx#weight}: 2 for a light hit, 6 for a heavy one. */
    static int weight(double damage) {
        return (int) Math.max(2, Math.min(6, 2 + Math.round(damage / 3)));
    }

    /** What can be parried: a melee hit by a living attacker, or a projectile that is not a piercing arrow; never what vanilla's shield lets through (explosions, magic, thorns...). */
    static boolean parryable(DamageSource src) {
        Entity direct = src.getDirectEntity();
        return parryable(src.is(DamageTypeTags.BYPASSES_SHIELD), direct instanceof AbstractArrow, direct instanceof AbstractArrow arrow ? arrow.getPierceLevel() : 0,
                src.is(DamageTypeTags.IS_PROJECTILE), direct instanceof LivingEntity,
                src.is(DamageTypes.PLAYER_ATTACK) || src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.MOB_ATTACK_NO_AGGRO));
    }

    /** The rule behind {@link #parryable(DamageSource)}, on the facts it reads: what bypasses shields is out, an arrow needs no piercing, any other projectile is in, melee needs a living attacker. */
    static boolean parryable(boolean bypassesShield, boolean arrow, int pierce, boolean projectile, boolean livingDirect, boolean meleeType) {
        if (bypassesShield) return false;
        if (arrow) return pierce == 0;
        return projectile || livingDirect && meleeType;
    }

    // ---- per defender (a player, or a mob: MobGuard) ----

    private static final Map<LivingEntity, Raise> RAISES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LivingEntity, Long> PARRIED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerPlayer, Long> LAST_BLOCK = Collections.synchronizedMap(new WeakHashMap<>());

    static Raise raise(LivingEntity e) {
        return RAISES.computeIfAbsent(e, k -> new Raise());
    }

    /** {@code e}'s raise memory, or null when it has never guarded. */
    static Raise raiseIfAny(LivingEntity e) {
        return RAISES.get(e);
    }

    /** Marks a hit on {@code e} as parried in this tick, for {@link #justParried}. */
    static void markParried(LivingEntity e) {
        PARRIED.put(e, e.level().getGameTime());
    }

    /**
     * Whether {@code e} has its guard up: a shield, or any item whose use is vanilla's block pose ({@code UseAnim.BLOCK}), which is how weapon mods make a weapon block (Jaam's
     * guard stance, its gauntlet, Shield Expansion's own check), so a weapon that blocks parries as a shield does, whichever mod gave it the block.
     */
    static boolean shield(LivingEntity e) {
        return e.isUsingItem() && guards(e.getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK), e.getUseItem().getUseAnimation());
    }

    /** The rule behind {@link #shield}: a shield, or an item used in the block pose. */
    static boolean guards(boolean shieldAbility, UseAnim anim) {
        return shieldAbility || anim == UseAnim.BLOCK;
    }

    /** What {@code e} guards with, for the log: "shield" or "weapon" (a block-pose item that is not a shield). */
    static String blocker(LivingEntity e) {
        return e.getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK) ? "shield" : "weapon";
    }

    /** True when {@code e} parried a hit in this tick or the last: a swing at it that was refused was a parry, not an anomaly. */
    static boolean justParried(LivingEntity e) {
        Long at = PARRIED.get(e);
        return at != null && e.level().getGameTime() - at <= 1;
    }

    private static void raised(ServerPlayer p, Raise r, long now) {
        Diag.event("guard_raise", "armed", r.armed, "downTicks", r.down, "hand", p.getUsedItemHand().name(), "blocker", blocker(p), "tick", now);
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !enabled()) return;
        Raise r = raise(p);
        long now = p.level().getGameTime();
        if (r.look(now, shield(p), CommonConfig.GUARD_REARM_TICKS.get())) raised(p, r, now);
    }

    // ---- a hit on a raised shield ----

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void incoming(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getAmount() <= 0 || !shield(p) || !enabled()) return;
        DamageSource src = e.getSource();
        long now = p.level().getGameTime();
        Raise r = raise(p);
        if (r.look(now, true, CommonConfig.GUARD_REARM_TICKS.get())) raised(p, r, now); // the raise may be new this tick, before the tick handler has seen it
        int used = p.getTicksUsingItem();
        if (used >= CommonConfig.GUARD_PARRY_TICKS.get() && parryable(src) && GuardBreak.breaks(src) && src.getSourcePosition() != null
                && inFront(p.calculateViewVector(0, p.getYHeadRot()), src.getSourcePosition(), p.position())) GuardBreak.breakGuard(p, src); // a heavy hit on a held guard: blocked, then broken
        if (used >= CommonConfig.GUARD_PARRY_TICKS.get() || !parryable(src) || Charge.noParry()) return; // later in the raise it is vanilla's block; an adult hoglin's charge is never parried
        Vec3 from = src.getSourcePosition();
        String why = !r.armed ? "rearm" : src.getDirectEntity() instanceof LivingEntity l && Stuns.isStunned(l) ? "stunned" : from == null || !inFront(p.calculateViewVector(0, p.getYHeadRot()), from, p.position()) ? "arc" : null;
        if (why != null) {
            Diag.event("guard_denied", "why", why, "blocker", blocker(p), "usedTicks", used, "damage", e.getAmount(), "source", src.getMsgId(), "downTicks", r.down, "tick", now);
            return;
        }
        parry(p, e, src, used, r.claim());
    }

    /**
     * Cancels the hit and deals with its attacker. {@code first} is the raise's first parry, which also refunds the cooldown, rings and tells the defender's client; a later
     * parry of the same raise (a crowd, a slime that hits every tick) only shoves, holds and staggers its own attacker, and every client holds that mob.
     */
    private static void parry(ServerPlayer p, LivingIncomingDamageEvent e, DamageSource src, int used, boolean first) {
        ServerLevel level = p.serverLevel();
        float damage = e.getAmount();
        e.setCanceled(true);
        markParried(p);
        LivingEntity attacker = src.getDirectEntity() instanceof LivingEntity l ? l : null; // a melee hit; a projectile's owner is far away and is left alone
        double stagger = 0;
        int held = 0;
        if (attacker != null) {
            attacker.knockback(SHOVE, p.getX() - attacker.getX(), p.getZ() - attacker.getZ());
            attacker.hurtMarked = true;
            stagger = stagger(damage, CommonConfig.GUARD_STAGGER_BASE.get(), CommonConfig.GUARD_STAGGER_PER_DAMAGE.get());
            if (Hitstop.canFreeze(attacker) && Hitstop.ready(attacker)) { // the shove is handed back when the pause ends, as for a hit
                Hitstop.freeze(attacker, FREEZE_TICKS);
                held = FREEZE_TICKS;
            }
            Stuns.apply(attacker, stagger);
        }
        int weight = weight(damage);
        if (!first) { // one more attacker of the same parry: no second sound, sparks, clip or shake
            if (attacker != null && held > 0) PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new GuardPayload(p.getId(), attacker.getId(), GuardPayload.ALSO, held, weight, false));
            Diag.event("guard_parry", "also", true, "blocker", blocker(p), "attacker", attacker == null ? null : attacker.getType().getDescriptionId(), "source", src.getMsgId(), "damage", damage, "usedTicks", used,
                    "staggerS", stagger, "held", held, "tick", level.getGameTime());
            return;
        }
        boolean refund = CommonConfig.GUARD_REFUND.get();
        if (refund) Strike.refund(p);
        Vec3 source = attacker != null ? attacker.position() : src.getSourcePosition() != null ? src.getSourcePosition() : p.position().add(p.getLookAngle());
        Vec3 toward = new Vec3(source.x - p.getX(), 0, source.z - p.getZ());
        toward = toward.lengthSqr() < 1e-6 ? p.getLookAngle() : toward.normalize();
        HitFx.Parried fx = HitFx.parry(level, new Vec3(p.getX(), p.getEyeY() - 0.35, p.getZ()).add(toward.scale(0.75)), toward, weight);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new GuardPayload(p.getId(), attacker == null ? -1 : attacker.getId(), GuardPayload.PARRY, held, weight, refund));
        Diag.event("guard_parry", "blocker", blocker(p), "attacker", attacker == null ? null : attacker.getType().getDescriptionId(), "source", src.getMsgId(), "damage", damage, "usedTicks", used, "staggerS", stagger,
                "held", held, "refund", refund, "weight", weight, "layers", fx.layers(), "particles", fx.sparks(), "tick", level.getGameTime(),
                "flag", fx.missing() == null ? null : "hitfx_missing_sound");
    }

    /** Vanilla's block, past the parry window: the defender's client recoils the shield (not a weapon: its mod owns that pose). Not a rule, so it does not depend on the setting. */
    @SubscribeEvent
    static void blocked(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !e.getBlocked() || e.isCanceled()) return;
        int weight = weight(e.getBlockedDamage());
        long now = p.level().getGameTime();
        boolean due = p.getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK) && blockDue(LAST_BLOCK.getOrDefault(p, -1_000_000L), now);
        if (due) {
            LAST_BLOCK.put(p, now);
            PacketDistributor.sendToPlayer(p, new GuardPayload(p.getId(), -1, GuardPayload.BLOCK, 0, weight, false));
        }
        Diag.event("guard_block", "damage", e.getBlockedDamage(), "usedTicks", p.getTicksUsingItem(), "source", e.getDamageSource().getMsgId(), "weight", weight, "tick", now, "sent", due);
    }

    // ---- /vcdiag drill: an armour stand that swings at you on a timer, to practise and measure the timing without anything hostile ----

    /** Ticks between the stand's raised arm and the hit that lands with it: about what a hostile mob's wind-up gives, so a parry is a reaction, not a guess. */
    private static final int WIND_UP = 8;

    /** Ticks between one round's cue and the next. */
    private static final int ROUND = 70;

    /** Damage of a drill hit: three hearts, enough that being hit is noticed. */
    private static final float DRILL_DAMAGE = 6;

    private static final Rotations ARM_UP = new Rotations(-130, 0, 10), ARM_DOWN = new Rotations(-15, 0, 10); // the stand's right arm raised, and at rest (vanilla's default pose)

    static int drill(CommandSourceStack src, int rounds) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) { // Player.hurt zeroes a mob's damage there, so nothing would land
            src.sendFailure(Component.literal("guard drill needs difficulty Easy or higher: on Peaceful mobs deal no damage"));
            return 0;
        }
        ArmorStand stand = Diag.spawn(level, EntityType.ARMOR_STAND, p, 2.2, 0, "drill", false);
        stand.setShowArms(true);
        Vec3 home = stand.position();
        for (int i = 0; i < rounds; i++) {
            int round = i + 1, cue = 40 + i * ROUND;
            Combat.later(cue, () -> { // the wind-up: the arm goes up, with a swish
                if (!stand.isAlive()) return;
                stand.teleportTo(home.x, home.y, home.z); // a parry shoves it, and the next round starts from where it stood
                stand.setRightArmPose(ARM_UP);
                level.playSound(null, home.x, home.y + 1, home.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1f, 0.9f);
            });
            Combat.later(cue + WIND_UP, () -> {
                stand.setRightArmPose(ARM_DOWN);
                drillStrike(p, stand, round, rounds);
            });
        }
        Combat.later(40 + rounds * ROUND, stand::discard);
        src.sendSuccess(() -> Component.literal("guard drill: " + rounds + " rounds. The stand raises its arm and 0.4 s later hits you for 3 hearts: raise your shield or a weapon that blocks (hold right click) as the arm comes down, in front of it."), false);
        return 1;
    }

    private static void drillStrike(ServerPlayer p, LivingEntity stand, int round, int rounds) {
        if (!p.isAlive() || !stand.isAlive()) return;
        float before = p.getHealth();
        boolean up = shield(p);
        int used = up ? p.getTicksUsingItem() : -1;
        boolean armed = up && raise(p).armed;
        long wasParried = PARRIED.getOrDefault(p, -10L);
        p.hurt(p.damageSources().mobAttack(stand), DRILL_DAMAGE);
        boolean parried = PARRIED.getOrDefault(p, -10L) != wasParried;
        String result = parried ? "PARRY" : p.getHealth() < before ? "hit" : "block";
        String how = !up ? "guard down" : parried ? "raised " + used + " ticks ago" : used < CommonConfig.GUARD_PARRY_TICKS.get() && !armed ? "raised " + used + " ticks ago but not re-armed" : "raised " + used + " ticks ago";
        p.sendSystemMessage(Component.literal("round " + round + "/" + rounds + ": " + result + " (" + how + ")"));
        Diag.event("drill", "round", round, "result", result, "shieldUp", up, "usedTicks", used, "armed", armed, "damageTaken", before - p.getHealth());
    }
}
