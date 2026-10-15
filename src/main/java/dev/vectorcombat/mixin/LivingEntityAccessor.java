package dev.vectorcombat.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla keeps the attack-strength ticker {@code protected} and only lets it be reset to zero; a parry sets it to full, on both sides. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("attackStrengthTicker")
    void vectorcombat$setAttackStrengthTicker(int ticks);
}
