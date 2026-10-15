package dev.vectorcombat;

import dev.vectorcombat.mixin.LivingEntityAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side swing: validates the request, picks the attack from the server's own view of the player's movement,
 * waits out the wind-up, then finds everything in the attack's arc and damages it. Replaces vanilla's single-target
 * attack for Vector weapons.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Strike {
    /** The swing must be at least this recharged to start (vanilla's attack strength scale, 0..1; 0.9, up from 0.8). A constant, so the client's refusal and the server's agree. */
    static final float MIN_STRENGTH = 0.9f;

    /** Items a pack tags {@code vectorcombat:no_hitstop} never pause a swing or the mob they hit. */
    private static final TagKey<Item> NO_HITSTOP = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "no_hitstop"));

    /** {@code fall} is the player's fall distance when the swing started (see {@link #tick}). */
    private record Pending(AttackDef def, long due, float strength, float fall) {
    }

    /** A target of a swing: whether the arc and the line of sight reach it, and its horizontal offset from the aim in degrees. */
    private record Reach(LivingEntity t, boolean arc, boolean los, double off) {
    }

    private static final Map<ServerPlayer, Pending> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** When a player's weapon last became fresh (a swing accepted, or the held item type changed) and which item that was. */
    private record Fresh(Item item, long since) {
    }

    /**
     * The attack-strength clock the swing check trusts next to vanilla's ticker. Vanilla's also resets on every swing packet ({@code ServerPlayer.swing}), and
     * mining sends one each tick, so a tool that has just mined read as almost uncharged here while the client, whose ticker mining leaves alone, said it was
     * ready: the client played the swing and the server refused it. This one resets only where the client's does.
     */
    private static final Map<ServerPlayer, Fresh> FRESH = Collections.synchronizedMap(new WeakHashMap<>());

    /** Vanilla's {@code getAttackStrengthScale(0.5f)} for a ticker reset at game time {@code since}: the ticks since, plus half, over the recharge time in ticks. */
    static float clockStrength(long now, long since, float delay) {
        return Mth.clamp((now - since + 0.5f) / delay, 0f, 1f);
    }

    /** A parry refunds {@code p}'s weapon cooldown: vanilla's ticker and this class's clock both read fully recharged from now on. */
    static void refund(ServerPlayer p) {
        float delay = p.getCurrentItemAttackStrengthDelay();
        ((LivingEntityAccessor) p).vectorcombat$setAttackStrengthTicker((int) Math.ceil(delay));
        FRESH.put(p, new Fresh(p.getMainHandItem().getItem(), p.level().getGameTime() - (long) Math.ceil(delay)));
    }

    /** Damage scale for a partly recharged swing: vanilla's floor and ceiling, but on the fourth power, so an early swing at 0.9 does 72% instead of vanilla's 85%. */
    static double strengthFactor(float s) {
        return 0.2 + s * s * s * s * 0.8;
    }

    private static int swings, mismatches;

    /** Ticks back within which the server's own samples backing the client's claim count as agreeing with it. */
    static final int APEX_TICKS = 2;

    /** Entity types a sweep never takes (only an aimed hit), for packs: {@code #vectorcombat:sweep_spared}. */
    static final net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> SWEEP_SPARED = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "sweep_spared"));

    /**
     * Whether a sweep (a target other than the aimed one) leaves {@code t} alone ({@code [mobs] spareBystanders}): the swinger's own tamed animals, anything in
     * {@link #SWEEP_SPARED}, and a bystander, a mob that is neither an enemy nor going for the swinger (a horse, a villager, a calm wolf); players and anything after the swinger still take it.
     */
    static boolean spared(boolean ownedBySwinger, boolean tagged, boolean enemy, boolean player, boolean targetsSwinger) {
        return ownedBySwinger || tagged || !enemy && !player && !targetsSwinger;
    }

    /**
     * How many of the server's sampled states a claim may reach back into, for a player whose ping is {@code latencyMs}: three, and one more for each 50 ms of ping, at most
     * what {@link MoveTracker} keeps. The newest sample lags the player's position by up to a tick, the client reads the tick before its click, and the two 20 Hz loops sit up
     * to a tick out of phase; ping adds jitter. A stall longer than this can make an honest claim lose to the server's own pick, which changes the attack and loses no swing.
     */
    static int claimWindow(int latencyMs) {
        return Math.min(MoveTracker.HISTORY, 3 + (Math.max(0, latencyMs) + 49) / 50);
    }

    /** The clip a swing shows everyone else: the one the client played if it is this weapon's clip for the attack ({@code first}) or for its alternate ({@code second}, null for none), else the attack's own. */
    static String chooseClip(String requested, String first, String second) {
        return requested != null && (requested.equals(first) || requested.equals(second)) ? requested : first;
    }

    private static Boolean loris, jaams, basicWeapons;

    /** Whether Loris Energy is installed; checked here so {@link LorisCompat}, which names its classes, is only loaded with it. */
    private static boolean loris() {
        if (loris == null) loris = ModList.get().isLoaded(LorisCompat.ID);
        return loris;
    }

    /** Whether Jaam's Weaponry is installed; {@link JaamsCompat}, which names its classes, is only loaded with it. */
    private static boolean jaams() {
        if (jaams == null) jaams = ModList.get().isLoaded(JaamsCompat.ID);
        return jaams;
    }

    private static boolean basicWeapons() {
        if (basicWeapons == null) basicWeapons = ModList.get().isLoaded(BasicWeaponsCompat.ID);
        return basicWeapons;
    }

    public static void start(ServerPlayer p, String clientPick, String clientClip) {
        String why = p.isSpectator() ? "spectator" : !p.isAlive() ? "dead" : Stuns.isStunned(p) ? "stunned" : MobGuard.lockedOut(p) ? "parried" : PENDING.containsKey(p) ? "previous swing still pending" : null;
        long sinceClick = Diag.sinceClick();
        if (why != null) {
            Diag.event("swing", "result", "rejected", "why", why, "client", clientPick, "sinceClickMs", sinceClick, "tick", p.level().getGameTime());
            return;
        }
        ItemStack stack = p.getMainHandItem();
        MoveState move = MoveTracker.of(p);
        AttackDef serverPick = Attacks.select(stack, move);
        // The server's view of movement lags the client's by a tick or two (it only learns positions from packets), so it cannot re-pick airborne or strafing
        // attacks exactly. It does not take the client's word either: the claim is accepted when the attack's conditions held in the state now or in a recent sample
        // of the server's own tracking (see claimWindow), and otherwise the swing runs as the attack the server's own state picks. It is never refused for that.
        AttackDef claimed = Attacks.attacksFor(stack).stream().filter(a -> a.name().equals(clientPick)).findFirst().orElse(null);
        int window = claimWindow(p.connection.latency());
        List<MoveState> seen = new ArrayList<>(window + 1);
        seen.add(move);
        seen.addAll(MoveTracker.recent(p, window));
        int support = claimed == null ? -1 : claimed.supportedBy(seen);
        boolean rejected = claimed != null && support < 0;
        AttackDef def = support >= 0 ? claimed : serverPick;
        long now = p.level().getGameTime();
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        float vanilla = p.getAttackStrengthScale(0.5f);
        Fresh fresh = FRESH.get(p);
        float strength = fresh != null && fresh.item() == stack.getItem() ? Math.max(vanilla, clockStrength(now, fresh.since(), p.getCurrentItemAttackStrengthDelay())) : vanilla;
        // A claim the server's own recent samples back within APEX_TICKS is agreement (8 of 105 airborne spear swings, Rising against Falling at a jump's top, where the
        // server's vertical speed lags the client's by a tick); it is used either way, this only keeps the flag for real disagreements.
        boolean mismatch = serverPick != null && !serverPick.name().equals(clientPick) && !(support >= 0 && support <= APEX_TICKS);
        if (serverPick != null) {
            swings++;
            if (mismatch) mismatches++;
        }
        if (def == null || strength < MIN_STRENGTH) {
            Diag.event("swing", "result", "rejected", "why", def == null ? "no attack for item" : "strength", "item", item, "client", clientPick,
                    "server", def == null ? null : def.name(), "strength", strength, "vanilla", vanilla, "sinceClickMs", sinceClick, "tick", now,
                    "flag", def != null && strength < MIN_STRENGTH ? "server_refused_low_strength" : null);
            return;
        }
        if (loris() && !LorisCompat.swing(p)) { // Loris Energy: a swing is an attack and costs its stamina, hit or miss
            Diag.event("swing", "result", "rejected", "why", "stamina", "item", item, "client", clientPick, "server", def.name(), "sinceClickMs", sinceClick, "tick", now);
            return;
        }

        p.resetAttackStrengthTicker();
        FRESH.put(p, new Fresh(stack.getItem(), now));
        p.swing(InteractionHand.MAIN_HAND, false); // viewers without the mod see a generic arm swing; with it, the swing below replaces it (ThirdPerson zeroes vanilla's)
        String clip = chooseClip(clientClip, Attacks.animationFor(stack, def), def.alternate().isEmpty() ? null : Attacks.animationFor(stack, def.alternate()));
        PacketDistributor.sendToPlayersTrackingEntity(p, new SwingPayload(p.getId(), clip)); // their pose, trail and crescent; the swinger knows its own
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), sound(def.sound()), SoundSource.PLAYERS, 1f, def.pitch());
        long delay = Math.max(1, Math.round(def.hitAt() * 20));
        PENDING.put(p, new Pending(def, p.level().getGameTime() + delay, strength, p.fallDistance));
        MobGuard.swing(p, delay, p.entityInteractionRange() * def.range());
        Deflect.armed(p, p.entityInteractionRange() * def.range(), def.angle(), p.level().getGameTime() + delay); // the swing meets projectiles from now to just after its impact
        if (rejected) Diag.count("claim_rejected");
        Diag.event("swing", "result", "accepted", "item", item, "client", clientPick, "clip", clip, "server", serverPick == null ? null : serverPick.name(), "used", def.name(), "mismatch", mismatch, "strength", strength,
                "supported", claimed == null ? null : support >= 0, "supportAge", support >= 0 ? support : null, "window", window,
                "vanilla", vanilla != strength ? vanilla : null, "hitInTicks", delay,
                "sinceClickMs", sinceClick, "ping", p.connection.latency(), "tick", now, "move", move, "history", MoveTracker.history(p),
                "mismatches", mismatches + "/" + swings, "flag", rejected ? "claim_rejected" : mismatch ? "pick_mismatch" : null);
        if (sinceClick >= 0) Diag.add("click_to_accept_ms", sinceClick);
    }

    private static SoundEvent sound(String id) {
        return BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.parse(id))
                .orElse(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP);
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Item held = p.getMainHandItem().getItem();
        Fresh fresh = FRESH.get(p);
        if (fresh == null || fresh.item() != held) FRESH.put(p, new Fresh(held, p.level().getGameTime())); // vanilla resets its ticker when the held item type changes
        Pending pending = PENDING.get(p);
        if (pending == null || p.level().getGameTime() < pending.due()) return;
        PENDING.remove(p);
        if (!p.isAlive() || Stuns.isStunned(p)) return;
        // The strike lands 3-4 ticks after the click and a long fall may have ended by then, but the mace's smash bonus needs the fall it was swung in.
        float fall = p.fallDistance;
        p.fallDistance = Math.max(fall, pending.fall());
        try {
            strike(p, pending);
        } finally {
            p.fallDistance = fall;
            GuardBreak.done(); // never left set past the swing, even if a hit threw
        }
    }

    private static void strike(ServerPlayer p, Pending pending) {
        AttackDef def = pending.def();
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        double range = p.entityInteractionRange() * def.range();
        float attack = (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double damage = attack * def.damage() * strengthFactor(pending.strength());
        // Enchantments follow vanilla's Player.attack: Sharpness/Smite/Bane add flat damage scaled by recharge only (not by
        // the attack's damage multiplier), then Knockback and the post-attack effects (Fire Aspect, Bane slowness, Thorns)
        // run on every victim. The target under the crosshair takes the whole swing; every other target in the arc is a sweep victim as in vanilla (see Sweep):
        // 1 + the sweeping ratio x the swing's damage, so Sweeping Edge, or anything else that raises the ratio, is what keeps a crowd hurting.
        // Sprint knockback and the jump crit are deliberately not applied.
        double ratio = p.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO);
        ItemStack weapon = p.getWeaponItem();
        double weaponSpeed = Hitstop.weaponSpeed(p, weapon); // read now: a weapon that breaks on this swing reads as empty afterwards
        boolean noHitstop = weapon.is(NO_HITSTOP); // likewise
        DamageSource source = p.damageSources().playerAttack(p);

        Props props = Attacks.propsFor(p.getMainHandItem(), def);
        Combat.record(p, def.name(), props, pause(noHitstop, weaponSpeed, def, false).ticks());
        // The weapon's own category picks the contact sound (a mace booms in every direction); the attack's own category only adds an accent. Read now, the weapon can break.
        Props weaponProps = Attacks.defaultsFor(p.getMainHandItem());
        String profile = HitFx.lead(weaponProps == null ? null : weaponProps.categories());
        String accent = HitFx.accentFor(profile, def.ownCategories() ? HitFx.lead(props.categories()) : null);
        boolean maceSmash = weapon.getItem() instanceof MaceItem && MaceItem.canSmashAttack(p); // vanilla plays its own smash on this swing
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range + 1.5),
                t -> t != p && t.isAlive() && !(t instanceof ArmorStand a && a.isMarker()) && !p.isAlliedTo(t) && p.canAttack(t)
                        && !(CommonConfig.SPARE_MOUNT.get() && (t == p.getVehicle() || t.getVehicle() == p))); // never the horse you ride (or what rides you)
        boolean hit = false, killed = false;
        List<LivingEntity> hurt = new ArrayList<>();
        int inArc = 0, close = 0;
        boolean worn = false;
        long t0 = System.nanoTime();
        // Each target once: whether the arc and the line of sight reach it, and how the aim ray meets it. Of those reached, the one under the crosshair is the primary target.
        Vec3 aimEnd = eye.add(p.getLookAngle().scale(range));
        List<Reach> reaches = new ArrayList<>(targets.size());
        List<Sweep.Candidate> candidates = new ArrayList<>();
        List<Integer> candidateAt = new ArrayList<>();
        for (LivingEntity t : targets) {
            AABB box = t.getBoundingBox();
            boolean arc = Arc.hits(eye, p.getYRot(), box, range, def.angle());
            boolean los = arc && clear(level, p, eye, t);
            Vec3 c = box.getCenter();
            double off = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-(c.x - eye.x), c.z - eye.z)) - p.getYRot());
            if (arc && los) {
                candidates.add(new Sweep.Candidate(box.clip(eye, aimEnd).map(h -> h.distanceToSqr(eye)).orElse(-1.0), Math.abs(off), box.distanceToSqr(eye)));
                candidateAt.add(reaches.size());
            }
            reaches.add(new Reach(t, arc, los, off));
        }
        int pick = Sweep.primary(candidates);
        LivingEntity primary = pick < 0 ? null : reaches.get(candidateAt.get(pick)).t();
        // Vanilla's crit and sweep events, which Player.attack fires and a Vector swing replaces, once per swing for the primary target: a mod may make the hit a crit (its
        // multiplier on the primary hit) or call off the sweep (no secondary targets). A Vector swing has no jump crit of its own, so the crit starts as not one.
        float critMult = 1f;
        boolean sweeps = true;
        if (primary != null) {
            net.neoforged.neoforge.event.entity.player.CriticalHitEvent crit = CommonHooks.fireCriticalHit(p, primary, false, 1.5f);
            if (crit.isCriticalHit()) critMult = crit.getDamageMultiplier();
            net.neoforged.neoforge.event.entity.player.SweepAttackEvent sweep = CommonHooks.fireSweepAttack(p, primary, true);
            sweeps = !(crit.isCriticalHit() && crit.disableSweep()) && !sweep.isCanceled() && sweep.isSweeping(); // the flag defaults to true and counts only for a crit (read always, nothing ever swept)
        }
        GuardBreak.swinging(p, def.name(), pending.strength()); // a heavy swing breaks the guard it lands on
        for (Reach r : reaches) {
            LivingEntity t = r.t();
            boolean arc = r.arc(), los = r.los();
            double off = r.off();
            if (t.getBoundingBox().distanceToSqr(eye) <= range * range) close++;
            if (!arc || !los) {
                Diag.event("target", "attack", def.name(), "victim", name(t), "dist", Math.sqrt(t.getBoundingBox().distanceToSqr(eye)), "offAxisDeg", off,
                        "inArc", arc, "lineOfSight", arc ? los : null, "range", range, "angle", def.angle());
                continue;
            }
            inArc++;
            if (t != primary && CommonConfig.SPARE_BYSTANDERS.get() && spared(t instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwner() == p, t.getType().is(SWEEP_SPARED),
                    t instanceof net.minecraft.world.entity.monster.Enemy, t instanceof net.minecraft.world.entity.player.Player, t instanceof net.minecraft.world.entity.Mob m && m.getTarget() == p)) {
                Diag.event("target", "attack", def.name(), "victim", name(t), "inArc", true, "lineOfSight", true, "flag", "sweep_spared");
                continue;
            }
            if (t != primary && !sweeps) {
                Diag.event("target", "attack", def.name(), "victim", name(t), "inArc", true, "lineOfSight", true, "flag", "sweep_called_off");
                continue;
            }
            // Player.attack's first step: AttackEntityEvent and Item.onLeftClickEntity, so other mods' weapons and protections see the swing.
            if (!(loris() ? LorisCompat.attack(p, t) : CommonHooks.onPlayerAttackTarget(p, t))) {
                Diag.event("target", "attack", def.name(), "victim", name(t), "inArc", true, "lineOfSight", true, "flag", "attack_cancelled_by_hook");
                continue;
            }
            float hpBefore = t.getHealth();
            int iframes = t.invulnerableTime;
            float strength = pending.strength();
            boolean main = t == primary;
            float ench, bonus, amount;
            if (main) {
                ench = (EnchantmentHelper.modifyDamage(level, weapon, t, source, attack) - attack) * strength;
                // Item.getAttackDamageBonus (mace smash, modded weapons), added after the recharge scaling as Player.attack does.
                bonus = weapon.getItem().getAttackDamageBonus(t, (float) damage, source);
                amount = ((float) damage + ench + bonus) * critMult;
            } else {
                // A sweep victim, as in Player.attack: the enchantments' damage on 1 + ratio x damage, all of it scaled by the recharge; no item bonus (the primary target's alone).
                float base = (float) Sweep.secondaryBase(ratio, damage);
                ench = (EnchantmentHelper.modifyDamage(level, weapon, t, source, base) - base) * strength;
                bonus = 0;
                amount = base * strength + ench;
            }
            boolean through = main && jaams() && JaamsCompat.throughStrike(p, t); // Jaam's Through Strike, which it hooks into vanilla's attack, just before the hit as there
            boolean ok = t.hurt(source, amount);
            boolean guarded = MobGuard.justBlocked(t); // a mob's guard took it: not drawn as a hit (no pause, sparks or knockback), even when some got through
            if (ok && main && critMult > 1f) {
                p.crit(t);
                level.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
            }
            hit |= ok;
            if (ok && !guarded) {
                hurt.add(t);
                killed |= !t.isAlive();
            }
            float kb = 0;
            // Vanilla order on the first hit of a swing: ItemStack.hurtEnemy (hook), post-attack enchant effects, then
            // postHurtEnemy, which is what actually wears the weapon in 1.21.1.
            boolean wear = ok && !worn;
            ItemStack held = p.getMainHandItem();
            int wearBefore = held.getDamageValue();
            if (wear) {
                worn = true;
                if (basicWeapons()) BasicWeaponsCompat.strength(p, BasicWeaponsCompat.strength(held, def, strength)); // Basic Weapons reads the swing's strength from vanilla's attack, which never ran
                held.hurtEnemy(t, p);
            }
            if (ok) {
                kb = guarded ? 0 : EnchantmentHelper.modifyKnockback(level, weapon, t, source, (float) p.getAttributeValue(Attributes.ATTACK_KNOCKBACK));
                if (kb > 0) t.knockback(kb * 0.5, Mth.sin(p.getYRot() * (float) (Math.PI / 180)), -Mth.cos(p.getYRot() * (float) (Math.PI / 180)));
                EnchantmentHelper.doPostAttackEffects(level, t, source);
                if (ench > 0 && main) p.magicCrit(t); // the sparkle is the primary target's, as in vanilla
            }
            if (wear) {
                held.postHurtEnemy(t, p);
                if (held.isEmpty()) p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            long since = Diag.sinceClick();
            if (since >= 0) Diag.add("click_to_hit_ms", since);
            Diag.event("target", "attack", def.name(), "victim", name(t), "dist", Math.sqrt(t.getBoundingBox().distanceToSqr(eye)), "offAxisDeg", off,
                    "inArc", true, "lineOfSight", true, "hurt", ok, "hpBefore", hpBefore, "hpAfter", t.getHealth(), "invulnTicksBefore", iframes,
                    "sinceClickMs", since, "stunned", Stuns.isStunned(t), "role", main ? "primary" : "secondary", "amount", amount, "base", damage, "enchBonus", ench, "itemBonus", bonus, "kb", kb, "fireTicks", t.getRemainingFireTicks(),
                    "enchants", Diag.enchants(weapon), "wornThisSwing", wear, "damageBefore", wear ? wearBefore : null, "damageAfter", wear ? held.getDamageValue() : null,
                    "parried", Guard.justParried(t) ? true : null, "blocked", guarded ? true : null, "throughStrike", through ? true : null, "flag", !ok && !Guard.justParried(t) && !guarded ? "hurt_refused" : since > 250 ? "slow_click_to_hit" : null);
            Diag.watch(t, "after_" + def.name());
        }
        GuardBreak.done();
        if (killed && CommonConfig.PENALTY_CLEAR_ON_KILL.get()) Penalties.clear(p); // a kill wipes the attack-speed penalty: a crowd is not a duel
        if (critMult > 1f || !sweeps) Diag.event("strike_extra", "attack", def.name(), "critMult", critMult, "sweeps", sweeps, "tick", level.getGameTime());
        Diag.add("strike_cost_us", (System.nanoTime() - t0) / 1000);
        Diag.event("strike", "attack", def.name(), "range", range, "angle", def.angle(), "damage", damage, "sweepRatio", ratio, "primary", primary == null ? null : name(primary), "nearby", targets.size(), "inArc", inArc, "hit", hit,
                "tick", level.getGameTime(), "close", close, "flag", close > 0 && inArc == 0 ? "nearby_but_none_in_arc" : null);
        if (!hit) {
            if (CommonConfig.PENALTY_MISSES.get()) Penalties.hit(p, Attacks.penaltyKey(def.name()), props); // a miss costs what a hit does
            return;
        }
        p.causeFoodExhaustion(0.1f);
        // Everything hit stops with the blade for the pause (ticks 0 = none): the mob is held and its knockback is given back when the pause ends.
        // Clients hear of it too (the attacker's holds its hand and camera); the list names the mobs actually held, so no client holds one the server did not.
        Pause pause = pause(noHitstop, weaponSpeed, def, killed);
        int ticks = pause.ticks();
        List<Integer> held = new ArrayList<>();
        for (LivingEntity t : hurt) {
            if (ticks > 0 && Hitstop.canFreeze(t) && Hitstop.ready(t)) {
                Hitstop.freeze(t, ticks);
                held.add(t.getId());
            }
        }
        HitFx.contact(level, p, hurt, profile, accent, weaponSpeed, maceSmash, ticks, killed);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new HitPayload(p.getId(), held, ticks, pause.weight(), killed));
        Diag.event("hitstop", "attack", def.name(), "ticks", ticks, "weight", pause.weight(), "victims", hurt.size(), "held", held.size(), "kill", killed,
                "weaponSpeed", weaponSpeed, "override", def.hitstop(), "bonus", def.hitstopBonus());
    }

    /** What a hit pauses for: the ticks the swing and mobs are held (capped) and the weight the effects hit with (not capped). */
    private record Pause(int ticks, int weight) {
    }

    /** The pause of a hit with {@code def}: none when hitstop is off or the weapon is tagged {@code no_hitstop}. */
    private static Pause pause(boolean noHitstop, double weaponSpeed, AttackDef def, boolean kill) {
        if (noHitstop || !CommonConfig.HITSTOP_ENABLED.get()) return new Pause(0, 0);
        double scale = CommonConfig.HITSTOP_SCALE.get();
        return new Pause(Hitstop.ticks(weaponSpeed, def.hitstop(), def.hitstopBonus(), kill, scale, CommonConfig.HITSTOP_MAX_TICKS.get()),
                Hitstop.weightTicks(weaponSpeed, def.hitstop(), def.hitstopBonus(), kill, scale));
    }

    private static String name(LivingEntity t) {
        return t.hasCustomName() ? t.getCustomName().getString() : t.getType().getDescriptionId();
    }

    /** No solid block between the attacker's eye and the target's centre. */
    private static boolean clear(ServerLevel level, ServerPlayer p, Vec3 eye, LivingEntity t) {
        return level.clip(new ClipContext(eye, t.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
                .getType() == HitResult.Type.MISS;
    }
}
