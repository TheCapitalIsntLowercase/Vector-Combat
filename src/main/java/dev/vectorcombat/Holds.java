package dev.vectorcombat;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Who holds a player: a slime's engulf ({@link Engulf}), a spider's pin ({@link SpiderMoves}) and a drowned's drag ({@link Drag}) each move the held player every tick, so
 * two at once fought over their position. A grab first claims the player here; a second one finds them taken and does not grab. A holder that is gone (dead, removed) no longer
 * counts. Cleared on logout and on server stop, with the moves' own state ({@link #onClear}).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class Holds {
    private Holds() {
    }

    private static final Map<Player, Entity> HOLDERS = new WeakHashMap<>();

    /** Whether {@code holder} may hold {@code p} now: nobody else holds them (or their holder is gone); it then holds them. */
    public static boolean claim(Player p, Entity holder) {
        Entity was = HOLDERS.get(p);
        if (was != null && was != holder && live(was)) return false;
        HOLDERS.put(p, holder);
        return true;
    }

    /** Lets {@code p} go if {@code holder} holds them. */
    public static void release(Player p, Entity holder) {
        if (p != null && HOLDERS.get(p) == holder) HOLDERS.remove(p);
    }

    /** Whether someone other than {@code holder} holds {@code p}. */
    public static boolean heldByOther(Player p, Entity holder) {
        Entity was = HOLDERS.get(p);
        return was != null && was != holder && live(was);
    }

    /** Whether a holder still counts: alive and in the world. */
    static boolean live(Entity e) {
        return e.isAlive() && !e.isRemoved();
    }

    /** The pure rule, for the tests: a claim succeeds when the player is free, held by the claimer, or held by a holder that is gone. */
    static boolean claims(boolean held, boolean sameHolder, boolean holderLive) {
        return !held || sameHolder || !holderLive;
    }

    private static final java.util.List<Runnable> CLEARS = new java.util.ArrayList<>();

    /** A move's state to drop on server stop. */
    static void onClear(Runnable r) {
        CLEARS.add(r);
    }

    @SubscribeEvent
    static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        HOLDERS.remove(e.getEntity());
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        HOLDERS.clear();
        for (Runnable r : CLEARS) r.run();
    }
}
