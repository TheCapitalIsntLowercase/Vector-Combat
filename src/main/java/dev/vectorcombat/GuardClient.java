package dev.vectorcombat;

import dev.vectorcombat.mixin.LivingEntityAccessor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * The shield in first person, client side. Vanilla switches between the idle and the raised shield model in one frame; here the shield lifts into place when you raise it
 * and drops when you lower it ({@code guard_raise}, {@code guard_lower}: the offset between the two models, so the clip starts where the other model sat and ends on the
 * rest pose), and the server's word on a hit plays {@code guard_parry} (it snaps forward, and the camera and the world hold for {@link Guard#FREEZE_TICKS} ticks, with the
 * mob it staggered) or {@code guard_hit} (a recoil). The clips are authored for the left arm (a shield's usual hand); the right arm takes {@code <clip>_right} when there
 * is one (the raise and lower offsets, whose two models are not mirror images) and the mirrored left clip otherwise. Everything is a pose offset added to the hand's, by
 * {@link Swing#hand}, so the arm of {@link Hands} follows it.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
final class GuardClient {
    private GuardClient() {
    }

    private static Anim playing = Anim.NONE;
    private static long startedAt;
    /** The clip is the left arm's, shown on the right: x, yaw and roll negate. */
    private static boolean mirror;
    private static InteractionHand clipHand = InteractionHand.OFF_HAND;

    /** The pause a parry holds the clip for, from the moment it reaches its impact; the hold itself is what {@link Impact} shakes the camera and pinches the view with. */
    private static Swing.Hold hold = Swing.Hold.NONE;
    private static int holdTicks;
    private static boolean holdStarted;

    /** Per hand (main, off): whether its shield was up in the last frame it was drawn. */
    private static final boolean[] up = new boolean[2];

    static Swing.Hold currentHold() {
        return hold;
    }

    private static double elapsed(long now) {
        return hold.clock(now, startedAt, 1.0);
    }

    private static boolean left(LocalPlayer p, InteractionHand hand) {
        return (hand == InteractionHand.MAIN_HAND ? p.getMainArm() : p.getMainArm().getOpposite()) == HumanoidArm.LEFT;
    }

    /**
     * Whether clip {@code next} may take over from {@code current}, which is {@code playing} (ids without the {@code _right} difference mattering). Raising and lowering always do,
     * they are what the hand is really doing. Anything else waits for a clip that is still playing, except that a parry takes over from a raise, lower or hit: so a shield
     * that blocks every tick, or a crowd of hits, never restarts a recoil or a parry halfway and jitters.
     */
    static boolean replaces(String current, boolean playing, String next) {
        if (next.startsWith("guard_raise") || next.startsWith("guard_lower") || !playing) return true;
        return next.startsWith("guard_parry") && !current.startsWith("guard_parry");
    }

    /** Starts clip {@code id} for the shield in {@code hand}, pausing it {@code pauseTicks} when it reaches its impact. */
    private static void play(String id, InteractionHand hand, boolean leftArm, int pauseTicks) {
        if (!replaces(playing == Anim.NONE ? "" : playing.id(), playing != Anim.NONE && elapsed(Util.getMillis()) <= playing.length(), id)) return;
        Anim own = leftArm ? Anim.NONE : Anim.get(id + "_right");
        Anim clip = own != Anim.NONE ? own : Anim.get(id);
        if (clip == Anim.NONE) return;
        playing = clip;
        mirror = !leftArm && own == Anim.NONE;
        clipHand = hand;
        startedAt = Util.getMillis();
        hold = Swing.Hold.NONE;
        holdTicks = pauseTicks;
        holdStarted = false;
        Diag.event("guard_clip", "clip", clip.id(), "hand", hand.name(), "mirrored", mirror, "pauseTicks", pauseTicks);
    }

    /**
     * Called for every hand drawn. Sees the shield in that hand go up or down (starting the raise or lower clip in the same frame the model switches) and returns the clip's
     * pose offset for it, {x, y, z, rx, ry, rz}, or null when no clip is playing for that hand.
     */
    static double[] pose(RenderHandEvent e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !ClientConfig.GUARD_ANIMATIONS.get()) return null;
        InteractionHand hand = e.getHand();
        boolean shield = e.getItemStack().canPerformAction(ItemAbilities.SHIELD_BLOCK);
        boolean raised = shield && p.isUsingItem() && p.getUsedItemHand() == hand;
        int i = hand == InteractionHand.MAIN_HAND ? 0 : 1;
        if (raised != up[i]) {
            up[i] = raised;
            if (shield) play(raised ? "guard_raise" : "guard_lower", hand, left(p, hand), 0);
        }
        if (!shield || playing == Anim.NONE || hand != clipHand) return null;
        long now = Util.getMillis();
        startHold(now);
        double t = elapsed(now);
        if (t > playing.length()) {
            playing = Anim.NONE;
            return null;
        }
        double[] s = Anim.sample(playing.hand(), t);
        double[] shudder = Impact.hand(hold);
        double side = mirror ? -1 : 1;
        s[0] = side * s[0] + side * shudder[0];
        s[1] += shudder[1];
        s[2] += shudder[2];
        s[4] *= side;
        s[5] *= side;
        return s;
    }

    /** Starts the parry's pause once the clip reaches its impact; from the hand or, for a weapon that guards (no hand clip, its mod owns the pose), from the camera. */
    private static void startHold(long now) {
        if (holdTicks > 0 && !holdStarted && elapsed(now) >= playing.impact()) {
            holdStarted = true;
            hold = hold.add(now, holdTicks * Hitstop.TICK_MS);
        }
    }

    /** The server said a hit landed on this player's shield (or on another's: then only the mob it staggered is held here). */
    static void receive(GuardPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) return;
        if (p.attacker() >= 0 && p.ticks() > 0) {
            Entity mob = mc.level.getEntity(p.attacker());
            if (mob != null) Hitstop.freeze(mob, p.ticks()); // the server holds it; so does every client that saw it
        }
        if (p.kind() == GuardPayload.DEFLECT) { // this player's swing sent a projectile back: the knock of a parry, no shield clip
            if (p.defender() == me.getId()) Impact.hit(p.weight());
            Diag.event("guard_recv", "kind", "deflect", "weight", p.weight());
            return;
        }
        if (p.kind() == GuardPayload.REBUFF) { // a mob parried this player's hit: the view takes the knock, the cooldown bar empties as the server's did
            if (p.attacker() != me.getId()) return;
            Impact.hit(p.weight());
            me.resetAttackStrengthTicker();
            Diag.event("guard_recv", "kind", "rebuffed", "weight", p.weight(), "mob", p.defender());
            return;
        }
        if (p.defender() != me.getId() || p.kind() == GuardPayload.ALSO) return; // one more attacker of a parry the shield already showed
        boolean parry = p.kind() == GuardPayload.PARRY;
        InteractionHand hand = Guard.shield(me) ? me.getUsedItemHand()
                : me.getOffhandItem().canPerformAction(ItemAbilities.SHIELD_BLOCK) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (parry) {
            Impact.hit(p.weight());
            if (p.refund()) ((LivingEntityAccessor) me).vectorcombat$setAttackStrengthTicker((int) Math.ceil(me.getCurrentItemAttackStrengthDelay())); // the server refunded the cooldown; so does this side's ticker
        }
        Diag.event("guard_recv", "kind", parry ? "parry" : "block", "weight", p.weight(), "heldTicks", p.ticks(), "refund", p.refund(), "hand", hand.name(), "animations", ClientConfig.GUARD_ANIMATIONS.get());
        if (ClientConfig.GUARD_ANIMATIONS.get()) play(parry ? "guard_parry" : "guard_hit", hand, left(me, hand), parry ? Guard.FREEZE_TICKS : 0);
    }

    @SubscribeEvent
    static void camera(ViewportEvent.ComputeCameraAngles e) {
        if (playing == Anim.NONE || !Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        long now = Util.getMillis();
        startHold(now);
        double t = elapsed(now);
        if (t > playing.length()) return;
        double[] c = Anim.sample(playing.camera(), t), rattle = Impact.camera(hold);
        double side = mirror ? -1 : 1;
        e.setPitch(e.getPitch() + (float) (c[3] + rattle[0]));
        e.setYaw(e.getYaw() + (float) (side * c[4] + rattle[1]));
        e.setRoll(e.getRoll() + (float) (side * c[5] + rattle[2]));
    }
}
