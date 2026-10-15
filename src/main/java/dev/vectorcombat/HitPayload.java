package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a Vector swing by {@code attacker} landed; the swing pauses for {@code ticks} (0 = no pause) and {@code victims}, the mobs
 * the server held still for that long, are held on the client too. {@code weight} is the hit's pause in ticks before the cap
 * ({@link Hitstop#weightTicks}), which sets how hard the impact effects hit.
 */
public record HitPayload(int attacker, List<Integer> victims, int ticks, int weight, boolean kill) implements CustomPacketPayload {
    public static final Type<HitPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "hit"));
    private static final StreamCodec<ByteBuf, List<Integer>> IDS = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
    public static final StreamCodec<ByteBuf, HitPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, HitPayload::attacker,
            IDS, HitPayload::victims, ByteBufCodecs.VAR_INT, HitPayload::ticks, ByteBufCodecs.VAR_INT, HitPayload::weight,
            ByteBufCodecs.BOOL, HitPayload::kill, HitPayload::new);

    @Override
    public Type<HitPayload> type() {
        return TYPE;
    }
}
