package dev.vectorcombat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

/**
 * Domesticated animals keep vanilla's behaviour: an animal born from breeding is
 * tagged {@link #BRED} for life; it, a tamed pet and a tamed horse never charge, stampede, bunt or wind up ({@link Charge#profile}, {@link WindUp#applicable}). Animals bred before
 * the tag existed cannot be told from wild ones.
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Domestic {
    private Domestic() {
    }

    static final String BRED = "vectorcombat.bred";

    /** Whether {@code e} is domesticated: bred, or a tamed pet or horse. */
    public static boolean exempt(Entity e) {
        return exempt(e.getTags().contains(BRED), e instanceof TamableAnimal t && t.isTame() || e instanceof AbstractHorse h && h.isTamed());
    }

    /** The pure rule: bred or tamed. */
    static boolean exempt(boolean bred, boolean tamed) {
        return bred || tamed;
    }

    @SubscribeEvent
    static void born(BabyEntitySpawnEvent e) {
        if (e.getChild() != null && !e.getChild().level().isClientSide()) e.getChild().addTag(BRED);
    }
}
