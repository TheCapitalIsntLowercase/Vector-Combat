package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: the loaded attack and animation JSON, so clients pick and play what the server will resolve, and the weapon data lines ({@link WeaponData#lines}). */
public record SyncPayload(List<String> attacks, List<String> animations, List<String> weapons) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "sync"));
    private static final StreamCodec<ByteBuf, List<String>> STRINGS = ByteBufCodecs.stringUtf8(1 << 20).apply(ByteBufCodecs.list());
    public static final StreamCodec<ByteBuf, SyncPayload> CODEC =
            StreamCodec.composite(STRINGS, SyncPayload::attacks, STRINGS, SyncPayload::animations, STRINGS, SyncPayload::weapons, SyncPayload::new);

    @Override
    public Type<SyncPayload> type() {
        return TYPE;
    }
}
