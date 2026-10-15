package dev.vectorcombat;

import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup: the config, with an in-game screen under Mods, and the stun kept out of the potion icons (it is shown at the crosshair, see {@link StunCrosshair}). */
@Mod(value = VectorCombat.ID, dist = Dist.CLIENT)
public final class VectorCombatClient {
    /** No icon for the effect: the HUD's effect row skips it and so does the inventory's effect list (which ignores the instance's own show-icon flag). */
    private static final IClientMobEffectExtensions NO_ICON = new IClientMobEffectExtensions() {
        @Override
        public boolean isVisibleInGui(MobEffectInstance instance) {
            return false;
        }

        @Override
        public boolean isVisibleInInventory(MobEffectInstance instance) {
            return false;
        }
    };

    public VectorCombatClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(RegisterClientExtensionsEvent.class, e -> e.registerMobEffect(NO_ICON, Stuns.STUN));
        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(() -> {
            if (net.neoforged.fml.ModList.get().isLoaded("entity_model_features")) EmfCompat.register(); // never touch EmfCompat without EMF: verifying it loads EMF's classes
        }));
    }
}
