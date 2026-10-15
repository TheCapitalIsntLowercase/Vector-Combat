package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: "I swung". {@code pick} is the attack the client says it swung, a claim: the server runs it only if its own tracking of the player's movement backs it
 * ({@link Strike#claimWindow}), else it runs the attack its own state picks. {@code clip} is the animation the client played (the attack's own or its alternate), which the
 * server checks ({@link Strike#chooseClip}) before showing it to everyone else. Both only select among names the server already knows.
 */
public record AttackPayload(String pick, String clip) implements CustomPacketPayload {
    public static final Type<AttackPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "attack"));
    public static final StreamCodec<ByteBuf, AttackPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, AttackPayload::pick, ByteBufCodecs.STRING_UTF8, AttackPayload::clip, AttackPayload::new);

    @Override
    public Type<AttackPayload> type() {
        return TYPE;
    }
}
