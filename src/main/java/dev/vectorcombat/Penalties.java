package dev.vectorcombat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Attacking costs attack speed for a while, every hit in a steady stream costs a little more (fatigue), and repeating the same attack costs more again. The server owns it as a
 * transient ATTACK_SPEED modifier, which vanilla syncs, so the client (crosshair, Better Combat cooldown) just reads it.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Penalties {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "attack_penalty");
    /** A player never loses more than this fraction of their attack speed. */
    static final double CAP = 0.8;
    /** Windows shrink to at most this fraction for fast weapons (their swing is limited by the animation, so shorter would never chain) and stretch to at most this many times for slow ones. */
    static final double MIN_SCALE = 0.75, MAX_SCALE = 3;
    private static final Map<ServerPlayer, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The penalty windows are seconds in the data, tuned on the sword; a weapon whose recharge is longer than the window can never swing again inside it
     * (a hammer at 0.7 recharges in 1.4 s against a 1.0 s window), so its repeats never counted. Scaling the windows by {@code reference / weaponSpeed} makes
     * the same swings in a row cost the same number of cooldowns whatever the weapon. {@code reference} 0 turns it off.
     */
    static double windowScale(double weaponSpeed, double reference) {
        return reference <= 0 ? 1 : Math.max(MIN_SCALE, Math.min(MAX_SCALE, reference / Math.max(0.1, weaponSpeed)));
    }

    static final class State {
        String last;
        int stacks, chain;
        long expires;
        long window;

        double hit(String attack, long now, Props props, double scale) {
            return hit(attack, now, props, scale, 0);
        }

        /**
         * Records a hit with {@code attack} at game tick {@code now} and returns the penalty now in force; the window it keeps open is the data's seconds times {@code scale}. Every hit
         * inside the window adds {@code fatigue} whatever the attack (alternating two attacks reset the repeat stack and never grew past the base), and repeating the same attack
         * adds its consecutive penalty on top; a pause as long as the window clears both.
         */
        double hit(String attack, long now, Props props, double scale, double fatigue) {
            return hit(attack, now, props, scale, fatigue, 0);
        }

        /** As above, with the repeat stack and the chain each capped at {@code maxStacks} (0: no cap). */
        double hit(String attack, long now, Props props, double scale, double fatigue, int maxStacks) {
            boolean live = now < expires, repeat = live && attack.equals(last);
            stacks = repeat ? stacks + 1 : 0;
            chain = live ? chain + 1 : 0;
            if (maxStacks > 0) {
                stacks = Math.min(stacks, maxStacks);
                chain = Math.min(chain, maxStacks);
            }
            last = attack;
            window = (long) ((repeat ? props.consecutive().seconds() : props.penalty().seconds()) * scale * 20);
            expires = now + window;
            return Math.min(CAP, props.penalty().amount() + stacks * props.consecutive().amount() + chain * fatigue);
        }
    }

    public static void hit(ServerPlayer p, String attack, Props props) {
        double speed = Hitstop.weaponSpeed(p, p.getMainHandItem()); // the hit is still inside t.hurt, so the weapon has not worn out yet
        double scale = windowScale(speed, CommonConfig.PENALTY_REFERENCE_SPEED.get());
        State s = STATES.computeIfAbsent(p, k -> new State());
        apply(p, s.hit(attack, p.level().getGameTime(), props, scale, CommonConfig.PENALTY_FATIGUE.get(), CommonConfig.PENALTY_MAX_STACKS.get()));
        Diag.event("penalty", "item", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(), "attack", attack, "penaltyFraction", current(p),
                "attackSpeed", p.getAttributeValue(Attributes.ATTACK_SPEED), "weaponSpeed", speed, "scale", scale, "stacks", s.stacks, "chain", s.chain, "windowS", s.window / 20.0,
                "tick", p.level().getGameTime());
    }

    private static void apply(ServerPlayer p, double amount) {
        AttributeInstance speed = p.getAttribute(Attributes.ATTACK_SPEED);
        speed.removeModifier(ID);
        if (amount > 0) speed.addTransientModifier(new AttributeModifier(ID, -amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        State s = STATES.get(p);
        if (s != null && s.last != null && p.level().getGameTime() >= s.expires) {
            Diag.event("penalty_reset", "after", s.last, "stacks", s.stacks);
            s.last = null;
            s.stacks = 0;
            s.chain = 0;
            apply(p, 0);
        }
    }

    /** With Loris Energy ({@link LorisCompat}): a swing's stamina next to a sword's (1.6 attacks a second): a dagger's less, a hammer's more, within half and one and a half times. */
    static double staminaScale(double weaponSpeed) {
        return weaponSpeed <= 0 ? 1 : Math.max(0.5, Math.min(1.5, 1.6 / weaponSpeed));
    }

    /** Clears {@code p}'s penalty at once (a kill, with {@code [penalty] clearOnKill}). */
    static void clear(ServerPlayer p) {
        State s = STATES.get(p);
        if (s == null || s.last == null) return;
        Diag.event("penalty_reset", "after", s.last, "stacks", s.stacks, "why", "kill");
        s.last = null;
        s.stacks = 0;
        s.chain = 0;
        s.expires = 0;
        apply(p, 0);
    }

    /** Current penalty fraction on any player (the client reads the synced modifier). */
    public static double current(Player p) {
        AttributeInstance speed = p.getAttribute(Attributes.ATTACK_SPEED);
        AttributeModifier m = speed == null ? null : speed.getModifier(ID);
        return m == null ? 0 : -m.amount();
    }
}
