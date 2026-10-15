package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the shield of player {@code defender} took a hit, a {@link #PARRY} (the hit was cancelled), a {@link #BLOCK} (vanilla's block) or {@link #ALSO} (one more
 * attacker of a raise's parry: clients only hold that mob, the shield already showed the parry), or {@link #REBUFF} (a mob {@code defender} parried player {@code attacker}'s hit:
 * that player's view is knocked and their cooldown emptied; {@link MobGuard}), or {@link #DEFLECT} (player {@code defender}'s swing sent a projectile back: their view takes the
 * knock; {@link Deflect}). {@code attacker} is the
 * entity id of the melee attacker that was staggered, -1 for none; {@code ticks} is how long it is held still for (0 = not held); {@code weight} sets how hard the impact
 * effects hit ({@link HitFx#weight}); {@code refund} says the defender's weapon cooldown was refunded, which their client mirrors.
 */
public record GuardPayload(int defender, int attacker, int kind, int ticks, int weight, boolean refund) implements CustomPacketPayload {
    public static final int PARRY = 0, BLOCK = 1, ALSO = 2, REBUFF = 3, DEFLECT = 4;
    public static final Type<GuardPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "guard"));
    public static final StreamCodec<ByteBuf, GuardPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, GuardPayload::defender, ByteBufCodecs.VAR_INT, GuardPayload::attacker,
            ByteBufCodecs.VAR_INT, GuardPayload::kind, ByteBufCodecs.VAR_INT, GuardPayload::ticks, ByteBufCodecs.VAR_INT, GuardPayload::weight, ByteBufCodecs.BOOL, GuardPayload::refund,
            GuardPayload::new);

    @Override
    public Type<GuardPayload> type() {
        return TYPE;
    }
}
