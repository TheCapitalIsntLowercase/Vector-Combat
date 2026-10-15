package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a slime has engulfed this player for {@code ticks} ({@link Engulf}); 0 is the release. The client blurs the screen under a wash of {@code tint}: {@link #GREEN} for a
 * slime, {@link #ORANGE} for a magma cube ({@link EngulfClient}); {@link #DEEP} is a drowned's drag: the edges only, deep blue, no blur.
 */
public record EngulfPayload(int ticks, int tint) implements CustomPacketPayload {
    public static final int GREEN = 0, ORANGE = 1, DEEP = 2;
    public static final Type<EngulfPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "engulf"));
    public static final StreamCodec<ByteBuf, EngulfPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, EngulfPayload::ticks, ByteBufCodecs.VAR_INT, EngulfPayload::tint, EngulfPayload::new);

    @Override
    public Type<EngulfPayload> type() {
        return TYPE;
    }
}
