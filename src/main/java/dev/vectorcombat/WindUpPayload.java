package dev.vectorcombat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: mob {@code entity} began a wind-up of {@code ticks} ({@link #START}), struck, which is a hit or a miss ({@link #STRIKE}), or ended without striking
 * ({@link #CANCEL}); the clients draw the mob rearing back and snapping forward ({@link WindUpClient}, {@link WindUp#lean}). {@code style} is how: {@link #LEAN} the body alone,
 * {@link #OVERHEAD} the arms raised over the head as well, then slammed down (a signature tell, {@link WindUp#overheadArm}), {@link #SHIVER} a shake in place with no lean (the
 * enderman's cue before a juke, {@link WindUp#shiver}), {@link #FLAIL} both arms raised and spread, then swept down and crossed, {@link #KICK} the right leg cocked, then a
 * front kick (the enderman's limbs, {@link WindUp#flailArmX}, {@link WindUp#kickLegX}), {@link #WEAPON} the weapon arm raised over the shoulder and chopped down
 * ({@link WindUp#weaponArmX}), {@link #PLAIN} the lean with no arms, {@link #SQUASH} and {@link #WOBBLE} the slime's shape instead of a lean ({@link WindUp#shape}), and the hoglin's charge: {@link #LINE} head down
 * and a hoof scraping, {@link #CHARGE} head down, {@link #REAR} rearing up on the hind legs with the front ones pawing ({@link WindUp#rear}), {@link #TIRED} head hanging; {@link #BITE} a feral mob's bite, the head reared up and back, then snapped forward and down
 * ({@link WindUp#biteHead}); {@link #FLIPPED} a spider on its back, legs flailing, {@link #HANG} a spider clinging still before it drops, legs twitching
 * ({@link WindUp#legFlail}); {@link #DISPLAY} a spider's threat display, up and held until it leaps, then easing down in the air ({@link WindUp#display}); {@link #DAZED} a charger that hit terrain, tilted down onto its front, head low, front legs forward ({@link WindUp#daze}). A {@link #LEAN} on a model with arms adds a claw ({@link WindUp#clawArmX}).
 */
public record WindUpPayload(int entity, int phase, int ticks, int style) implements CustomPacketPayload {
    public static final int START = 0, STRIKE = 1, CANCEL = 2;
    public static final int LEAN = 0, OVERHEAD = 1, SHIVER = 2, FLAIL = 3, KICK = 4, WEAPON = 5, PLAIN = 6, SQUASH = 7, WOBBLE = 8, LINE = 9, CHARGE = 10, REAR = 11, TIRED = 12, BITE = 13, DAZED = 14, FLIPPED = 15, HANG = 16, DISPLAY = 17;
    public static final Type<WindUpPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "windup"));
    public static final StreamCodec<ByteBuf, WindUpPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, WindUpPayload::entity, ByteBufCodecs.VAR_INT, WindUpPayload::phase,
            ByteBufCodecs.VAR_INT, WindUpPayload::ticks, ByteBufCodecs.VAR_INT, WindUpPayload::style, WindUpPayload::new);

    @Override
    public Type<WindUpPayload> type() {
        return TYPE;
    }
}
