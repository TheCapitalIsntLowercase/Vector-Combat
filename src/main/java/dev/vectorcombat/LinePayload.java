package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a telegraph segment, from ({@code fx}, {@code fy}, {@code fz}) to ({@code tx}, {@code ty}, {@code tz}), shown for {@code ticks}
 * in {@code color} ({@link #RED} a hostile charger, {@link #YELLOW} a neutral one, {@link #SLIME}, {@link #MAGMA}, {@link #DEEP} the drowned's, {@link #SAND} a thrown mob's, {@link #SCULK} the warden's slam); {@code ring} marks a segment of a landing ring, drawn by its own
 * setting. Each client draws it as dust, or not, by its settings ({@link LineClient}, {@code [display] lineTelegraphs}, {@code ringTelegraphs}).
 */
public record LinePayload(float fx, float fy, float fz, float tx, float ty, float tz, int ticks, int color, boolean ring) implements CustomPacketPayload {
    public static final int RED = 0, YELLOW = 1, SLIME = 2, MAGMA = 3, DEEP = 4, SAND = 5, SCULK = 6;
    public static final Type<LinePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "line"));
    public static final StreamCodec<ByteBuf, LinePayload> CODEC = new StreamCodec<>() {
        @Override
        public LinePayload decode(ByteBuf buf) {
            return new LinePayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), buf.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, LinePayload p) {
            buf.writeFloat(p.fx()).writeFloat(p.fy()).writeFloat(p.fz()).writeFloat(p.tx()).writeFloat(p.ty()).writeFloat(p.tz());
            ByteBufCodecs.VAR_INT.encode(buf, p.ticks());
            ByteBufCodecs.VAR_INT.encode(buf, p.color());
            buf.writeBoolean(p.ring());
        }
    };

    @Override
    public Type<LinePayload> type() {
        return TYPE;
    }
}
