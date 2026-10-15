package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to the players tracking a swinger (not the swinger, who knows): entity {@code entityId} started the swing animation {@code clip}, so their clients
 * can pose its arm along the blade, draw its trail and hold it for a landed hit ({@link ClientSwings}).
 */
public record SwingPayload(int entityId, String clip) implements CustomPacketPayload {
    public static final Type<SwingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "swing"));
    public static final StreamCodec<ByteBuf, SwingPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, SwingPayload::entityId,
            ByteBufCodecs.STRING_UTF8, SwingPayload::clip, SwingPayload::new);

    @Override
    public Type<SwingPayload> type() {
        return TYPE;
    }
}
