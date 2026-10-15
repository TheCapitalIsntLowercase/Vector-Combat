package dev.vectorcombat;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Modded mobs with their own attack animation (Hominid's Famished, Juggernaut, Vampire...) usually start it from an entity event their {@code doHurtTarget} broadcasts, so under a
 * wind-up it played only at the hit, after our tell, and never on a miss. Here the event is learned: whatever event a non-vanilla mob broadcasts about itself while its wind-up's
 * hit runs ({@code MeleeAttackGoalMixin} marks the landing, {@code ServerLevelMixin} sees the broadcast) is its attack animation; from then on every wind-up of that mob type
 * sends it at the tell ({@link WindUp#signal} on {@code start}), and the copy its hit sends is dropped so the swing is not restarted. The first attack of each type plays as
 * before. Vanilla mobs keep our own poses. {@code [mobs] modAttackAnimations}.
 */
public final class MobAttackAnims {
    private MobAttackAnims() {
    }

    private static final Map<EntityType<?>, Byte> LEARNED = new ConcurrentHashMap<>();
    /** Mobs whose attack animation was sent at the tell of the wind-up in progress. */
    private static final Map<Mob, Boolean> TOLD = Collections.synchronizedMap(new WeakHashMap<>());
    /** The mob whose wind-up's hit is running now (server thread), or null. */
    private static Mob landing;

    /** Whether a mob type is left to our own poses: vanilla's mobs. */
    static boolean vanilla(String namespace) {
        return "minecraft".equals(namespace);
    }

    private static boolean modded(Mob m) {
        return !vanilla(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getNamespace());
    }

    /** Called by {@code MeleeAttackGoalMixin} as a wind-up's hit is about to run ({@code mob}), and after it ran (null). */
    public static void landing(Mob mob) {
        landing = mob;
    }

    public static void landed(Mob mob) {
        if (landing == mob) landing = null;
        if (mob != null) TOLD.remove(mob);
    }

    /** A wind-up started ({@link WindUp#signal}): a learned attack animation plays now, as the tell. */
    static void tell(Mob mob) {
        if (!CommonConfig.MOD_ATTACK_ANIMATIONS.get() || !modded(mob)) return;
        Byte event = LEARNED.get(mob.getType());
        if (event == null) return;
        TOLD.put(mob, true);
        mob.level().broadcastEntityEvent(mob, event);
    }

    /**
     * {@code ServerLevelMixin}: entity {@code e} broadcasts {@code event}. Learns the attack animation of the mob whose hit is running; returns true to drop the broadcast, when that
     * animation already played at the tell.
     */
    public static boolean broadcast(Entity e, byte event) {
        Mob m = landing;
        if (m == null || e != m || !modded(m) || !CommonConfig.MOD_ATTACK_ANIMATIONS.get()) return false;
        Byte was = LEARNED.put(m.getType(), event);
        if (was == null || was != event) Diag.event("attack_anim_learned", "mob", BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString(), "event", event);
        return TOLD.containsKey(m) && was != null && was == event;
    }
}
