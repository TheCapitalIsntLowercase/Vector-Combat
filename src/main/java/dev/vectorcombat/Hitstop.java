package dev.vectorcombat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A landed hit pauses the swing and the mob it hit. How long: heavier weapons (lower attack speed) stop longer, and a kill lingers one tick
 * more. The mob is held through {@code LivingEntityMixin} (no movement, limb animation or death animation); the knockback it was given is
 * kept and handed back when the hold ends.
 */
public final class Hitstop {
    /** One server tick, which is also the unit the pause is counted in. */
    public static final long TICK_MS = 50;

    /** A mob is not held again within this many ticks of its last hold, so several attackers cannot chain it. */
    static final int GATE = 10;

    private Hitstop() {
    }

    /**
     * How heavy a hit is, in ticks, before the cap: the attack's own {@code override} if it has one (0 means none), else 3.2 / weapon attack speed
     * rounded and kept within 1..5 (daggers and hoes 1, sword 2, pickaxe, shovel, trident and diamond axe 3, iron axe 4, mace 5) plus the attack's
     * {@code bonus}; one more on a kill; times {@code scale}. The pause is capped (see {@link #ticks}); this is what still tells a mace from a pickaxe.
     */
    public static int weightTicks(double weaponSpeed, int override, int bonus, boolean kill, double scale) {
        int base = override >= 0 ? override : (int) Math.max(1, Math.min(5, Math.round(3.2 / Math.max(0.1, weaponSpeed)))) + bonus;
        if (base <= 0) return 0;
        return Math.max(0, (int) Math.round((base + (kill ? 1 : 0)) * scale));
    }

    /** Ticks of pause: {@link #weightTicks} at most {@code max}. */
    public static int ticks(double weaponSpeed, int override, int bonus, boolean kill, double scale, int max) {
        return Math.min(max, weightTicks(weaponSpeed, override, bonus, kill, scale));
    }

    /** The weapon's own attack speed: the player's base value plus the item's additive modifiers, so the temporary swing penalty is left out. */
    public static double weaponSpeed(Player p, ItemStack stack) {
        double[] speed = {p.getAttributeBaseValue(Attributes.ATTACK_SPEED)};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.value() == Attributes.ATTACK_SPEED.value() && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) speed[0] += modifier.amount();
        });
        return speed[0];
    }

    /** The holds on one side: who is held until when, and when each key was last held (the gate). Pure, so the keys can be anything. */
    static final class Freezes<K> {
        /** The tick the hold ends on, the momentum handed back then (null: none) and where the mob stood when it began. */
        record State(long until, Vec3 velocity, Vec3 from) {
        }

        private final Map<K, State> held = Collections.synchronizedMap(new WeakHashMap<>());
        private final Map<K, Long> started = Collections.synchronizedMap(new WeakHashMap<>());

        boolean idle() {
            return held.isEmpty();
        }

        boolean ready(K k, long now) {
            Long t = started.get(k);
            return t == null || now - t >= GATE;
        }

        void freeze(K k, long now, int ticks, Vec3 velocity, Vec3 from) {
            held.put(k, new State(now + ticks, velocity, from));
            started.put(k, now);
        }

        boolean holding(K k, long now) {
            State s = held.get(k);
            return s != null && now < s.until();
        }

        /** Ticks left of the hold at {@code now} (a game time that may carry the partial tick), 0 when it is not held. */
        double remaining(K k, double now) {
            State s = held.get(k);
            return s == null ? 0 : Math.max(0, s.until() - now);
        }

        /** Swaps the momentum handed back at the end, while the hold is on. */
        boolean launch(K k, long now, Vec3 velocity) {
            State s = held.get(k);
            if (s == null || now >= s.until()) return false;
            held.put(k, new State(s.until(), velocity, s.from()));
            return true;
        }

        /** The hold that has ended since the last call, once; null otherwise. */
        State ended(K k, long now) {
            State s = held.get(k);
            if (s == null || now < s.until()) return null;
            held.remove(k);
            return s;
        }
    }

    // One registry per side: Entity.equals compares network ids, so on the integrated server a mob and its client copy would be one key.
    private static final Freezes<Entity> SERVER = new Freezes<>(), CLIENT = new Freezes<>();

    private static Freezes<Entity> side(Entity e) {
        return e.level().isClientSide() ? CLIENT : SERVER;
    }

    /** Mobs can be held. Players cannot (their movement belongs to their own client) and armor stands have nothing to hold. */
    public static boolean canFreeze(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand);
    }

    public static boolean ready(Entity e) {
        return side(e).ready(e, e.level().getGameTime());
    }

    /**
     * Holds {@code e} for {@code ticks}. On the server it also takes the momentum it has (the knockback this hit just gave it) and keeps it
     * for when the hold ends; a client only holds, the server's motion packet brings the momentum.
     */
    public static void freeze(Entity e, int ticks) {
        if (ticks <= 0) return;
        boolean server = !e.level().isClientSide();
        Vec3 momentum = server ? e.getDeltaMovement() : null;
        if (server) {
            e.setDeltaMovement(Vec3.ZERO);
            e.hasImpulse = true;
            e.hurtMarked = true;
        }
        side(e).freeze(e, e.level().getGameTime(), ticks, momentum, e.position());
        Diag.event("freeze", "side", server ? "server" : "client", "victim", e.getType().getDescriptionId(), "ticks", ticks, "momentum", momentum == null ? null : momentum.length());
    }

    /** True while {@code e} is held; for the hooks that only look (limbs, death). */
    public static boolean active(Entity e) {
        Freezes<Entity> f = side(e);
        return !f.idle() && f.holding(e, e.level().getGameTime());
    }

    /** Ticks {@code e} stays held counted from the partial tick being drawn, 0 when it is not: what a held mob's shake fades with. */
    public static double remaining(Entity e, float partial) {
        Freezes<Entity> f = side(e);
        return f.idle() ? 0 : f.remaining(e, e.level().getGameTime() + partial);
    }

    /** Swaps the momentum a held mob gets back (a deferred set_motion replaces its knockback); false if it is not held. */
    public static boolean launch(Entity e, Vec3 velocity) {
        return side(e).launch(e, e.level().getGameTime(), velocity);
    }

    /** The aiStep hook: true while {@code e} is held; the first call after the hold ends gives the momentum back and returns false. */
    public static boolean frozen(Entity e) {
        Freezes<Entity> f = side(e);
        if (f.idle()) return false;
        long now = e.level().getGameTime();
        if (f.holding(e, now)) return true;
        Freezes.State s = f.ended(e, now);
        if (s == null) return false;
        boolean server = !e.level().isClientSide();
        if (s.velocity() != null) {
            e.setDeltaMovement(e.getDeltaMovement().add(s.velocity()));
            e.hasImpulse = true;
            if (server) e.hurtMarked = true;
        }
        double drift = e.position().distanceTo(s.from());
        Diag.event("freeze_end", "side", server ? "server" : "client", "victim", e.getType().getDescriptionId(), "driftBlocks", drift,
                "release", s.velocity() == null ? null : s.velocity().length(), "flag", drift > 0.1 ? "freeze_drift" : null);
        return false;
    }
}
