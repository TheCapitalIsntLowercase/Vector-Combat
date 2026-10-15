package dev.vectorcombat.mixin;

import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The four-legged model's head and front legs (protected in vanilla) for the charge's poses ({@link dev.vectorcombat.WindUpClient}): the cow's, and every animal built on
 * {@code QuadrupedModel} (pigs, sheep, goats, polar bears, pandas, and many modded ones), so any of them can charge from a tag.
 */
@Mixin(QuadrupedModel.class)
public interface QuadrupedModelAccessor {
    @Accessor("head")
    ModelPart vectorcombat$head();

    @Accessor("rightFrontLeg")
    ModelPart vectorcombat$rightFrontLeg();

    @Accessor("leftFrontLeg")
    ModelPart vectorcombat$leftFrontLeg();
}
