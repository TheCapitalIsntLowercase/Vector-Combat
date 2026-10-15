package dev.vectorcombat.mixin;

import net.minecraft.client.model.HoglinModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hoglin model's head and front legs, private in vanilla, for the charge's poses ({@link dev.vectorcombat.WindUpClient}). The zoglin draws with the same model. */
@Mixin(HoglinModel.class)
public interface HoglinModelAccessor {
    @Accessor("head")
    ModelPart vectorcombat$head();

    @Accessor("rightFrontLeg")
    ModelPart vectorcombat$rightFrontLeg();

    @Accessor("leftFrontLeg")
    ModelPart vectorcombat$leftFrontLeg();
}
