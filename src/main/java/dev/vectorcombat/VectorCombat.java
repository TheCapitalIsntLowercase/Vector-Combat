package dev.vectorcombat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(VectorCombat.ID)
@EventBusSubscriber(modid = VectorCombat.ID)
public final class VectorCombat {
    public static final String ID = "vectorcombat";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public VectorCombat(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);
        Stuns.EFFECTS.register(modBus);
        Stuns.ATTRIBUTES.register(modBus);
        for (String id : new String[] {"combatnouveau", "cutthrough", "shouldersurfing", "crosshairtweaks", "shieldexp", "basicweapons", "damagenumbers", "bettercombat", "visceral_combat"}) {
            if (net.neoforged.fml.ModList.get().isLoaded(id)) LOG.info("[diag] interfering mod present: {}", id);
        }
    }

    @SubscribeEvent
    static void payloads(RegisterPayloadHandlersEvent e) {
        var registrar = e.registrar("1").optional();
        registrar.playToClient(SyncPayload.TYPE, SyncPayload.CODEC, (payload, ctx) -> {
            Attacks.load(payload.attacks());
            Anim.load(payload.animations());
            WeaponData.loadLines(payload.weapons());
        });
        registrar.playToServer(AttackPayload.TYPE, AttackPayload.CODEC, (payload, ctx) -> Strike.start((ServerPlayer) ctx.player(), payload.pick(), payload.clip()));
        registrar.playToClient(HitPayload.TYPE, HitPayload.CODEC, (payload, ctx) -> HitFeel.confirm(payload));
        registrar.playToClient(SwingPayload.TYPE, SwingPayload.CODEC, (payload, ctx) -> ClientSwings.receive(payload));
        registrar.playToClient(GuardPayload.TYPE, GuardPayload.CODEC, (payload, ctx) -> GuardClient.receive(payload));
        registrar.playToClient(WindUpPayload.TYPE, WindUpPayload.CODEC, (payload, ctx) -> WindUpClient.receive(payload));
        registrar.playToClient(EngulfPayload.TYPE, EngulfPayload.CODEC, (payload, ctx) -> EngulfClient.receive(payload));
        registrar.playToClient(LinePayload.TYPE, LinePayload.CODEC, (payload, ctx) -> LineClient.receive(payload));
    }

    private static void listen(AddReloadListenerEvent e, String dir, Consumer<List<JsonElement>> sink) {
        e.addListener(new SimpleJsonResourceReloadListener(new Gson(), "vectorcombat/" + dir) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
                sink.accept(List.copyOf(files.values()));
                LOG.info("Loaded {} {} file(s)", files.size(), dir);
            }
        });
    }

    @SubscribeEvent
    static void reloadListeners(AddReloadListenerEvent e) {
        // Attacks and animations are also synced to clients, which pick and play them.
        listen(e, "attacks", files -> Attacks.load(files.stream().map(JsonElement::toString).toList()));
        listen(e, "animations", files -> Anim.load(files.stream().map(JsonElement::toString).toList()));
        listen(e, "mobs", Profiles::loadProfiles);
        listen(e, "mob_attacks", Profiles::loadMobAttacks);
        listen(e, "mob_throws", MobThrows::load);
        listen(e, "mob_arming", MobArming::load);
        listen(e, "armor", ArmorModifiers::load);
        listen(e, "mob_guard", MobGuardData::load);
        listen(e, "mob_passives", MobPassives::load);
        listen(e, "mob_shields", MobShields::load);
        listen(e, "guard_break", GuardBreak::load);
        if (Dev.on()) listen(e, "arena", Arena::load);
        // Weapon mods' own data (Better Combat's weapon_attributes format), read from its usual directory, not ours.
        e.addListener(new SimpleJsonResourceReloadListener(new Gson(), "weapon_attributes") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
                WeaponData.load(files);
                LOG.info("Loaded {} weapon_attributes file(s), {} with something to say", files.size(), WeaponData.count());
            }
        });
    }

    @SubscribeEvent
    static void sync(OnDatapackSyncEvent e) {
        SyncPayload payload = new SyncPayload(Attacks.raw(), Anim.raw(), WeaponData.lines());
        e.getRelevantPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, payload));
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post e) {
        long t0 = System.nanoTime();
        MoveTracker.tick(e.getEntity());
        Diag.add("move_tick_cost_us", (System.nanoTime() - t0) / 1000);
    }
}
