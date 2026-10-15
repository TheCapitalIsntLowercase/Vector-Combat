package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * Swarmers wear a player down: every {@link #PER_LEVEL} hits that land on a player from mobs tagged {@code #vectorcombat:swarming} (silverfish and endermite), counted
 * across all of them, add a level of Slowness, up to {@link #MAX_LEVEL}, for {@link #DURATION} ticks, each new level refreshing it. The count starts over once the slow from it has
 * worn off (or, before the first level, once no swarmer has hit for that long), so a lone silverfish now and then never builds one. The count is pure so it is checked without a game.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Swarm {
    private Swarm() {
    }

    public static final TagKey<EntityType<?>> SWARMING = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "swarming"));

    static final int PER_LEVEL = 3, MAX_LEVEL = 3, DURATION = 80;

    /** A player's count of swarm hits and the game time it lapses at. */
    record Count(int hits, long lapses) {
    }

    private static final Map<Player, Count> COUNTS = new WeakHashMap<>();

    /**
     * The count after a hit at {@code now}, from {@code was} (null for none): it starts over when the last one has lapsed. It lapses when the slow it gave ends, or, before the first
     * level, {@link #DURATION} after the last hit.
     */
    static Count hit(Count was, long now) {
        int hits = was == null || now >= was.lapses() ? 1 : was.hits() + 1;
        long lapses = raises(hits) || level(hits) == 0 ? now + DURATION : was.lapses();
        return new Count(hits, lapses);
    }

    /** The Slowness level a count of {@code hits} gives (0 for none yet). */
    static int level(int hits) {
        return Math.min(MAX_LEVEL, hits / PER_LEVEL);
    }

    /** Whether this hit applies the slow (a new level, or at the top every third hit refreshing it, so a swarm that keeps biting keeps it). */
    static boolean raises(int hits) {
        return hits % PER_LEVEL == 0;
    }

    @SubscribeEvent
    static void hit(LivingDamageEvent.Post e) {
        if (!Combat.landed(e) || !(e.getEntity() instanceof Player p) || p.level().isClientSide()) return;
        if (!(e.getSource().getEntity() instanceof LivingEntity attacker) || e.getSource().getDirectEntity() != attacker || !attacker.getType().is(SWARMING)) return;
        long now = p.level().getGameTime();
        Count c = hit(COUNTS.get(p), now);
        COUNTS.put(p, c);
        Diag.event("swarm_hit", "attacker", attacker.getType().getDescriptionId(), "hits", c.hits(), "level", level(c.hits()), "tick", now);
        if (raises(c.hits())) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DURATION, level(c.hits()) - 1), attacker);
    }
}
