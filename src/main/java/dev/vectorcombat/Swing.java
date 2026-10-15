package dev.vectorcombat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of a swing. Left click with a Vector weapon never reaches vanilla; instead the attack is chosen from
 * the player's movement, the swing animation plays locally (item and camera, driven by real time so it is smooth at
 * any frame rate), and the server is told to resolve the hit.
 */
@EventBusSubscriber(modid = VectorCombat.ID, value = Dist.CLIENT)
public final class Swing {
    /** Where the held item sits in view space (right hand; the left mirrors x). Rotations pivot here, so the blade turns in place. */
    static final double HAND_X = 0.56, HAND_Y = -0.52, HAND_Z = -0.72;

    private static Anim playing = Anim.NONE;
    private static long startedAt;
    /** Animation seconds per real second: 1 normally, less for slow motion in previews. */
    private static double speed = 1;
    private static String loop;

    /**
     * The clip's clock can be stopped for a while: a landed hit pauses the swing for a few ticks. {@code done} is the milliseconds of
     * holds already over; the current one runs over [from, until). What the clip has played is wall time minus the time held.
     */
    record Hold(long done, long from, long until) {
        static final Hold NONE = new Hold(0, 0, 0);

        /** A hold of {@code ms} asked for at {@code now}: it extends one still running, else starts a new one. */
        Hold add(long now, long ms) {
            return now < until ? new Hold(done, from, until + ms) : new Hold(done + until - from, now, now + ms);
        }

        boolean holding(long now) {
            return now < until;
        }

        /** Clip seconds at {@code now}, at playback {@code speed}. */
        double clock(long now, long startedAt, double speed) {
            return (now - startedAt - done - Math.max(0, Math.min(now, until) - from)) / 1000.0 * speed;
        }
    }

    private static Hold pause = Hold.NONE;

    /** Whether the clip playing is already made for this weapon's model ({@link #modelFitted}), so the first-person fit ({@link Fit}) leaves it alone. */
    private static boolean modelFitted;

    /** What the first-person fit needs of the swing playing now: its clip, the clip's clock (holds included), whether the clip is a model-fitted variant, and when the swing began. */
    record FitView(Anim clip, double time, boolean modelFitted, long startedAt) {
    }

    static FitView fitView() {
        return active() ? new FitView(playing, elapsed(), modelFitted, startedAt) : null;
    }

    /**
     * Whether {@code clip}, which the weapon's set picked for the attack whose own clip is {@code base}, was made for the weapon's model: a variant named for it ({@code joust_spear} for
     * {@code joust}), not another sword-authored clip standing in ({@code swing} for the alternate {@code swing_alt}).
     */
    static boolean modelFitted(String clip, String base) {
        return clip.startsWith(base + "_");
    }

    /** Prefix of a clip's first-person showcase variant ({@code showcase/swing} for {@code swing}): the same length, impact, camera and trail, only the hand differs. */
    static final String SHOWCASE = "showcase/";

    /**
     * The clip your own first person plays for {@code id}: its showcase variant when that style is chosen and one exists (the sword's clips; a weapon's own clips such as
     * {@code joust_spear} have none and play as they are), else the clip itself.
     */
    static Anim firstPerson(String id) {
        if (ClientConfig.FIRST_PERSON_STYLE.get() == ClientConfig.FirstPersonStyle.SHOWCASE && !id.startsWith(SHOWCASE)) {
            Anim variant = Anim.get(SHOWCASE + id);
            if (variant != Anim.NONE) return variant;
        }
        return Anim.get(id);
    }

    /** The latest hold of this player's swing (over by now unless a hit just landed), for the impact effects that follow it. */
    static Hold currentHold() {
        return pause;
    }

    private static int pauseFrames;
    /** Preview only: a pause (ms) to start when the clip reaches its impact, as the server's confirm would. */
    private static long simPauseMs;

    /** Starts the clip's clock from now. */
    private static void restart() {
        startedAt = Util.getMillis();
        pause = Hold.NONE;
        LocalPlayer p = Minecraft.getInstance().player;
        // The look the clip starts with is the frame its trail and pose are laid out in, so they stay in the air where the swing was; ClientSwings holds it for you as for anyone.
        if (p != null) ClientSwings.start(p.getId(), playing, speed, p.getYRot(), p.getXRot(), startedAt);
    }

    // ---- /vcanim: preview one clip on a loop, or play the whole showcase hands-free for recording ----

    private record Step(double at, String clip, double speed, String caption, int holdTicks) {
        Step(double at, String clip, double speed, String caption) {
            this(at, clip, speed, caption, 0);
        }
    }

    private static List<Step> show = List.of();
    private static long showStart;
    private static int showIndex;
    private static String caption = "";

    private static void stopAll() {
        loop = null;
        show = List.of();
        caption = "";
        playing = Anim.NONE;
        modelFitted = false;
        speed = 1;
        pause = Hold.NONE;
        simPauseMs = 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) ClientSwings.stop(p.getId());
    }

    private static void preview(String id, double s) {
        Diag.event("vcanim", "clip", id, "speed", s);
        stopAll();
        loop = id;
        speed = s;
        playing = firstPerson(id);
        restart();
    }

    /** Each clip: a title card, 3 loops at normal speed, a title card, 2 loops in slow motion. Ends with a card. */
    private static void showcase() {
        stopAll();
        List<Step> s = new ArrayList<>();
        double t = 0;
        for (String id : List.of("swing", "slash_to_left", "slash_to_right", "joust", "overhead", "uppercut")) {
            for (double sp : new double[] {1, 0.25}) {
                String label = id + (sp == 1 ? " - normal speed" : " - slow motion 0.25x");
                s.add(new Step(t, null, sp, label));
                t += 1.0;
                int loops = sp == 1 ? 3 : 2;
                for (int i = 0; i < loops; i++) {
                    s.add(new Step(t, id, sp, label + " (" + (i + 1) + "/" + loops + ")"));
                    t += Anim.get(id).length() / sp + 0.5;
                }
            }
        }
        s.add(new Step(t, null, 1, "showcase done"));
        s.add(new Step(t + 1.5, null, 1, null));
        show = s;
        showIndex = -1;
        showStart = Util.getMillis();
    }

    /** Each clip with a simulated confirm at its impact: 3 loops pausing 2 ticks, then 3 pausing 4, so the pause can be judged without a target. */
    private static void hits() {
        stopAll();
        List<Step> s = new ArrayList<>();
        double t = 0;
        for (String id : List.of("swing", "slash_to_left", "slash_to_right", "joust", "overhead", "uppercut")) {
            for (int ticks : new int[] {2, 4}) {
                String label = id + " - pause " + ticks + " ticks (" + ticks * Hitstop.TICK_MS + " ms)";
                s.add(new Step(t, null, 1, label));
                t += 1.0;
                for (int i = 0; i < 3; i++) {
                    s.add(new Step(t, id, 1, label + " (" + (i + 1) + "/3)", ticks));
                    t += Anim.get(id).length() + ticks * Hitstop.TICK_MS / 1000.0 + 0.5;
                }
            }
        }
        s.add(new Step(t, null, 1, "hits done"));
        s.add(new Step(t + 1.5, null, 1, null));
        show = s;
        showIndex = -1;
        showStart = Util.getMillis();
    }

    /** Each pose parameter alone, from negative to positive: shows what every number does on screen (see animations: axis_*). */
    private static void axes() {
        stopAll();
        List<Step> s = new ArrayList<>();
        double t = 0;
        for (String id : List.of("axis_pos_x", "axis_pos_y", "axis_pos_z", "axis_rot_x", "axis_rot_y", "axis_rot_z")) {
            s.add(new Step(t, null, 1, id + " (negative to positive)"));
            t += 1.0;
            s.add(new Step(t, id, 1, id));
            t += Anim.get(id).length() + 0.5;
        }
        s.add(new Step(t, null, 1, "axes done"));
        s.add(new Step(t + 1.5, null, 1, null));
        show = s;
        showIndex = -1;
        showStart = Util.getMillis();
    }

    private static void showTick() {
        if (show.isEmpty()) return;
        double now = (Util.getMillis() - showStart) / 1000.0;
        int i = showIndex;
        while (i + 1 < show.size() && show.get(i + 1).at() <= now) i++;
        if (i == showIndex) return;
        showIndex = i;
        Step st = show.get(i);
        if (st.caption() == null) {
            stopAll();
            return;
        }
        caption = st.caption();
        speed = st.speed();
        playing = st.clip() == null ? Anim.NONE : firstPerson(st.clip());
        restart();
        simPauseMs = st.holdTicks() * Hitstop.TICK_MS;
        Diag.event("showcase", "caption", caption, "clip", st.clip(), "speed", st.speed());
    }

    @SubscribeEvent
    static void commands(RegisterClientCommandsEvent e) {
        if (!Dev.on()) return;
        e.getDispatcher().register(Commands.literal("vcanim").then(Commands.argument("args", StringArgumentType.greedyString()).executes(c -> {
            // greedy so tab-completion's trailing space is fine: "<id> [speed]", "showcase", "hits", "axes", "stop"
            String[] a = StringArgumentType.getString(c, "args").trim().split("\\s+");
            if (a[0].equals("stop") || (a[0].equals("showcase") && a.length > 1 && a[1].equals("stop"))) stopAll();
            else if (a[0].equals("showcase")) showcase();
            else if (a[0].equals("hits")) hits();
            else if (a[0].equals("axes")) axes();
            else preview(a[0], a.length > 1 ? Double.parseDouble(a[1]) : 1);
            return 1;
        })));
        e.getDispatcher().register(Commands.literal("vcfit").executes(c -> {
            // What the first-person fit makes of the item in your main hand: "/vcfit"
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal(Fit.describe(p)), false);
            return 1;
        }));
    }

    @SubscribeEvent
    static void overlay(RenderGuiEvent.Post e) {
        showTick();
        if (show.isEmpty() || caption.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        e.getGuiGraphics().drawCenteredString(mc.font, caption + (pause.holding(Util.getMillis()) ? "   [HOLD]" : "") + "   t=" + Diag.now() + "ms", e.getGuiGraphics().guiWidth() / 2, 12, 0xFFFFFF);
        if (active()) {
            double[] p = Anim.sample(playing.hand(), elapsed());
            e.getGuiGraphics().drawCenteredString(mc.font, String.format("pos=(%.2f, %.2f, %.2f) rot=(%.0f, %.0f, %.0f)", p[0], p[1], p[2], p[3], p[4], p[5]),
                    e.getGuiGraphics().guiWidth() / 2, 24, 0xAAAAAA);
        }
    }

    private static double elapsed() {
        return pause.clock(Util.getMillis(), startedAt, speed);
    }

    private static boolean active() {
        return playing != Anim.NONE && elapsed() <= playing.length();
    }

    /** Stops the clip's clock (hand and camera together) for {@code ms}, but only while a swing is playing. */
    static boolean hold(long ms) {
        if (ms <= 0 || !active()) return false;
        long now = Util.getMillis();
        pause = pause.add(now, ms);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) ClientSwings.hold(p.getId(), ms, now);
        return true;
    }

    /** The server confirmed a hit from this swing: pause for its ticks, and log how that lined up with the clip (negative lateMs = before its impact). */
    static void hold(HitPayload p) {
        double clip = elapsed();
        boolean swinging = active();
        boolean held = hold(p.ticks() * Hitstop.TICK_MS);
        Diag.event("hit_confirm", "ticks", p.ticks(), "weight", p.weight(), "victims", p.victims().size(), "kill", p.kill(), "swinging", swinging, "held", held,
                "clipS", clip, "impactS", playing.impact(), "lateMs", (clip - playing.impact()) * 1000 / speed, "sinceClickMs", Util.getMillis() - startedAt);
    }

    /** Pickaxes, axes, shovels and hoes (and modded tools that declare those NeoForge abilities): they mine any block. */
    static boolean miningTool(ItemStack s) {
        return s.canPerformAction(ItemAbilities.PICKAXE_DIG) || s.canPerformAction(ItemAbilities.AXE_DIG)
                || s.canPerformAction(ItemAbilities.SHOVEL_DIG) || s.canPerformAction(ItemAbilities.HOE_DIG);
    }

    /** A sword's speed on the blocks it is merely efficient on (leaves, plants): faster than this is a tool at work, so the click mines. */
    static boolean minesFast(float destroySpeed) {
        return destroySpeed > 1.5f;
    }

    /**
     * Why vanilla keeps this click, or null when it becomes a swing. Kept: any block for a mining tool, so mining is never overlaid by a
     * swing; a block this item is the right tool for (sword on cobweb) or mines faster than a sword's 1.5 (a modded tool that declares no NeoForge ability but carries
     * vanilla's tool component, see {@link #minesFast}); an entity that is not alive (item frames, boats, minecarts, end
     * crystals, dragon parts), which the server's arc never finds. Swords on grass or leaves still swing: their speed bonus there is not
     * "correct for drops". Mobs and air always swing. NeoForge asks every tick the attack key is held on a block, not only at the press, and
     * a cancelled ask never reaches vanilla's mining, so the answer must stay "keep" for as long as the block should be mined.
     */
    static String keepsClick(HitResult.Type type, boolean living, boolean tool, boolean correct) {
        return switch (type) {
            case ENTITY -> living ? null : "entity";
            case BLOCK -> tool ? "tool" : correct ? "correct" : null;
            case MISS -> null;
        };
    }

    private static String vanillaClick(LocalPlayer p, HitResult hit) {
        if (hit == null) return null;
        ItemStack held = p.getMainHandItem();
        boolean tool = false, correct = false;
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            if (ClientConfig.SWING_THROUGH_GRASS.get() && throughPlant(p, block)) return null; // a mob behind grass: swing at it, the plant stays
            tool = miningTool(held);
            BlockState state = p.level().getBlockState(block.getBlockPos());
            correct = !tool && (held.isCorrectToolForDrops(state) || minesFast(held.getDestroySpeed(state)));
        }
        return keepsClick(hit.getType(), hit instanceof EntityHitResult entity && entity.getEntity() instanceof LivingEntity, tool, correct);
    }

    /**
     * Whether a click on {@code block} goes through it to a mob behind: the block has nothing to collide with (grass, flowers, ferns, saplings, crops, a cobweb...) and a living
     * mob is on the look line within reach beyond it. The server's own line of sight already ignores such blocks; the crosshair stops at them.
     */
    private static boolean throughPlant(LocalPlayer p, BlockHitResult block) {
        net.minecraft.world.level.block.state.BlockState state = p.level().getBlockState(block.getBlockPos());
        if (!state.getCollisionShape(p.level(), block.getBlockPos()).isEmpty()) return false;
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
        double reach = p.entityInteractionRange();
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(reach));
        net.minecraft.world.phys.AABB box = p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1);
        EntityHitResult behind = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, box,
                en -> en instanceof LivingEntity && en.isAlive() && !en.isSpectator() && en.isPickable(), reach * reach);
        if (behind == null) return false;
        Diag.event("click_through", "block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), "target", behind.getEntity().getType().getDescriptionId());
        return true;
    }

    private static long lastVanillaLog, lastRefusedLog;

    /** Whether the attack key was down at the end of the previous tick: a click event with it already down is a held key repeating, not a press. */
    private static boolean attackWasDown;

    /**
     * Vanilla kept a click of this press (mining): the rest of the press stays vanilla. Without it the block breaking leaves the crosshair on air, where
     * a held key would swing the pickaxe at nothing (and the server, whose strength every mining swing packet resets, would refuse that swing).
     */
    private static boolean miningHold;

    @SubscribeEvent
    static void attackKey(InputEvent.InteractionKeyMappingTriggered e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!e.isAttack() || p == null) return;
        MoveState move = MoveTracker.of(p);
        AttackDef def = Attacks.select(p.getMainHandItem(), move);
        if (def == null) return; // not a Vector weapon: vanilla handles it
        var hit = Minecraft.getInstance().hitResult;
        String why = vanillaClick(p, hit);
        if (why != null) {
            miningHold = true;
            long now = Util.getMillis();
            if (now - lastVanillaLog > 400) { // asked every tick while mining: log a burst once
                lastVanillaLog = now;
                Diag.event("click_vanilla", "item", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(), "looking", hit.getType(), "why", why);
            }
            return;
        }
        e.setCanceled(true);
        e.setSwingHand(false);
        startSwing(p, move, def, hit, false);
    }


    /** The last accepted swing, for the alternating slash: its attack, whether it played the alternate clip, its item and when it began. */
    private static String comboAttack = "", comboItem = "";
    private static boolean comboSecond;
    private static long comboAt;

    /**
     * Whether this swing plays the attack's alternate clip: the setting is on, the attack has one, and the previous accepted swing was this same attack with this
     * same item, played the first clip, and began within {@code windowMs} (the weapon's recharge and a second more). Anything else (a pause, a different attack or
     * weapon, a swing that already was the alternate) starts the pair again from the first clip.
     */
    static boolean alternates(boolean enabled, String alternate, String lastAttack, boolean lastSecond, String lastItem, long lastAt, String attack, String item, long now, long windowMs) {
        return enabled && !alternate.isEmpty() && attack.equals(lastAttack) && item.equals(lastItem) && !lastSecond && now - lastAt <= windowMs;
    }

    /**
     * Swings now unless the weapon is not ready (the click is logged either way): the clip starts and the server is told to strike. {@code auto} is a held
     * key repeating rather than a click. NeoForge posts a click every tick the key is held on a block, so a click with the key already down is held to the
     * same recharge as the repeat on mobs and air ({@code holdToAttack.minStrength}).
     */
    private static void startSwing(LocalPlayer p, MoveState move, AttackDef def, HitResult hit, boolean auto) {
        float strength = p.getAttackStrengthScale(0.5f);
        boolean heldClick = !auto && attackWasDown && ClientConfig.HOLD_TO_ATTACK.get();
        boolean refused = Stuns.isStunned(p) || active() || strength < Strike.MIN_STRENGTH || heldClick && strength < ClientConfig.HOLD_MIN_STRENGTH.get();
        long now = Util.getMillis();
        ItemStack held = p.getMainHandItem();
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        boolean second = alternates(ClientConfig.ALTERNATE_SLASH.get(), def.alternate(), comboAttack, comboSecond, comboItem, comboAt, def.name(), itemId, now,
                (long) (p.getCurrentItemAttackStrengthDelay() * Hitstop.TICK_MS) + 1000);
        String clip = Attacks.animationFor(held, second ? def.alternate() : def.animation()); // the weapon's own clip for this attack (or its alternate), if its set maps one
        if (!refused || now - lastRefusedLog > 300) { // a held key on a block is refused every tick: log a burst once
            if (refused) lastRefusedLog = now;
            Diag.event("click", "item", itemId, "pick", def.name(), "clip", clip, "alt", second, "trail", ClientConfig.TRAIL_STYLE.get().name(), "auto", auto, "refused", refused,
                    "stunned", Stuns.isStunned(p), "animating", active(), "strength", strength,
                    "looking", hit == null ? null : hit.getType(), "fps", Minecraft.getInstance().getFps(), "tick", p.level().getGameTime(),
                    "move", move, "history", MoveTracker.history(p), "flag", refused && strength >= 1 && !active() ? "refused_at_full_strength" : null);
        }
        if (refused) return;
        comboAttack = def.name();
        comboSecond = second;
        comboItem = itemId;
        comboAt = now;

        switching = 0; // the swing takes the hand over from a weapon switch that is still rising
        p.resetAttackStrengthTicker();
        stopAll(); // a real swing ends any preview
        playing = firstPerson(clip);
        modelFitted = modelFitted(clip, second ? def.alternate() : def.animation());
        restart();
        Diag.clickMs = System.currentTimeMillis();
        DirectionArrow.record(Direction.of(def));
        ThirdView.aimSwing(p, p.entityInteractionRange() * def.range()); // third person from behind: face what the crosshair is on (or the assist's foe), and tell the server first
        PacketDistributor.sendToServer(new AttackPayload(def.name(), clip));
        // First person plays our own swing, third person the pose of ThirdPerson; with that off, give vanilla's arm swing so something shows it.
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson() && !ClientConfig.THIRD_PERSON.get()) p.swing(InteractionHand.MAIN_HAND);
    }

    /** What the main hand's pose was moved by this frame, so the off hand (shield) can be moved back. */
    private static double[] applied;
    private static double appliedLift;
    private static double pivotX;
    private static final double[] REST = new double[6];

    // ---- vanilla's cooldown dip ----
    // ItemInHandRenderer lowers the held item by 0.6 * (1 - height) where height chases (attack strength)^3, so every swing (we reset the
    // strength on click) drops the weapon off screen and lets it climb back over the cooldown; a durability change re-equips it the same way.
    // Vector weapons cancel it so poses land where the simulator put them. A real switch (slot or item) keeps vanilla's equip animation, and since
    // vanilla resets the attack strength when the item changes, the new weapon rises over its whole cooldown (a mace takes 1.7 s): the cancel stays off
    // until it is up (keepSwitching), and a swing started on the way takes the hand over.

    private static int lastSlot = -1;
    private static Item lastItem;
    private static int switching;

    /** How far to raise the item to undo vanilla's dip: its equip progress times 0.6, except while a real switch is in progress or the dip is wanted. */
    static double lift(float equipProgress, int switchTicks, boolean keepDip) {
        return keepDip || switchTicks > 0 ? 0 : 0.6 * equipProgress;
    }

    /** A switch in progress stays on while the weapon is still lowered (never starts one): at least 2 ticks, which the next tick's countdown takes down again. */
    static int keepSwitching(int switching, float equipProgress) {
        return switching > 0 && equipProgress > 0.05f ? Math.max(switching, 2) : switching;
    }

    /**
     * True when a held attack key should swing again: the setting is on, the key is down with the game in front (no screen, mouse grabbed), the hand is
     * free (not using an item, no clip playing), the player is not stunned, this press is not mining ({@link #miningHold}) and the weapon has recharged to {@code min}.
     */
    static boolean repeats(boolean enabled, boolean keyDown, boolean inGame, boolean usingItem, boolean animating, boolean stunned, boolean mining, float strength, double min) {
        return enabled && keyDown && inGame && !usingItem && !animating && !stunned && !mining && strength >= min;
    }

    /** Holding the attack key swings again as soon as the weapon is ready, at whatever the crosshair is on unless vanilla keeps that click (mining). */
    private static void holdAttack(Minecraft mc, LocalPlayer p) {
        boolean inGame = mc.screen == null && mc.mouseHandler.isMouseGrabbed() && mc.isWindowActive();
        if (!repeats(ClientConfig.HOLD_TO_ATTACK.get(), mc.options.keyAttack.isDown(), inGame, p.isUsingItem(), active(), Stuns.isStunned(p), miningHold,
                p.getAttackStrengthScale(0.5f), ClientConfig.HOLD_MIN_STRENGTH.get())) {
            return;
        }
        MoveState move = MoveTracker.of(p);
        AttackDef def = Attacks.select(p.getMainHandItem(), move);
        if (def == null || vanillaClick(p, mc.hitResult) != null) return; // not a Vector weapon, or vanilla keeps this click
        startSwing(p, move, def, mc.hitResult, true);
    }

    @SubscribeEvent
    static void clientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Item item = p == null ? null : p.getMainHandItem().getItem();
        int slot = p == null ? -1 : p.getInventory().selected;
        if (slot != lastSlot || item != lastItem) {
            lastSlot = slot;
            lastItem = item;
            switching = 10; // also true on joining a world, so the first equip animation plays
        } else if (switching > 0) {
            switching--;
        }
        boolean keyDown = mc.options.keyAttack.isDown();
        if (!keyDown) miningHold = false; // the press is over
        if (p != null) holdAttack(mc, p);
        attackWasDown = keyDown;
    }

    private static long dipStart;
    private static float dipMax, dipStrength;
    private static boolean dipCompensated, dipSwitched;

    /** [diag] One event per dip of the held weapon: how deep, how long, and whether the cooldown explains it. {@code held} is what the hand shows at the end of it (a switch's new weapon). */
    private static void trackDip(ItemStack held, float equipProgress, boolean compensated) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (equipProgress > 0.05f) {
            if (dipStart == 0) {
                dipStart = Util.getMillis();
                dipMax = 0;
                dipStrength = p == null ? 1 : p.getAttackStrengthScale(0);
                dipCompensated = compensated;
                dipSwitched = switching > 0; // a real switch's dip outlives the 10 ticks it is flagged for, so ask when it starts
            }
            dipMax = Math.max(dipMax, equipProgress);
        } else if (dipStart != 0 && equipProgress < 0.02f) {
            Diag.event("equip_dip", "item", BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), "maxEquipProgress", dipMax, "ms", Util.getMillis() - dipStart, "strengthAtStart", dipStrength, "compensated", dipCompensated, "switched", dipSwitched,
                    "flag", dipStrength >= 0.99f && !dipSwitched && Util.getMillis() - lastVanillaLog > 1500 ? "dip_without_cooldown" : null); // mining wears the tool: that dip is expected
            dipStart = 0;
        }
    }

    @SubscribeEvent
    static void hand(RenderHandEvent e) {
        showTick();
        var pose = e.getPoseStack();
        if (loop != null && elapsed() > playing.length() + 0.5 * speed) restart();
        // Both hands render on one pose stack, so the main hand's swing would carry onto the shield: undo it first.
        if (e.getHand() == InteractionHand.OFF_HAND) {
            if (applied != null) {
                pose.translate(0, -appliedLift, 0);
                pose.translate(pivotX, HAND_Y, HAND_Z);
                pose.mulPose(Axis.ZP.rotationDegrees((float) -applied[5]));
                pose.mulPose(Axis.YP.rotationDegrees((float) -applied[4]));
                pose.mulPose(Axis.XP.rotationDegrees((float) -applied[3]));
                pose.translate(-pivotX, -HAND_Y, -HAND_Z);
                pose.translate(-applied[0], -applied[1], -applied[2]);
                applied = null;
            }
            double[] rest = Hands.raise(e.getItemStack(), Minecraft.getInstance().options.mainHand().get() != HumanoidArm.LEFT); // the off hand is the arm the main hand is not
            if (rest[1] != 0) pose.translate(rest[0], rest[1], 0);
            double[] guard = GuardClient.pose(e); // a shield in the off hand: raising, lowering, parrying
            if (guard != null) applyPose(pose, guard, Minecraft.getInstance().options.mainHand().get() == HumanoidArm.LEFT ? HAND_X : -HAND_X);
            return;
        }
        // Main hand only, once per frame: a preview's simulated confirm when the clip reaches its impact, and the frames drawn during a hold.
        if (simPauseMs > 0 && active() && elapsed() >= playing.impact()) {
            Diag.event("hit_confirm", "simulated", true, "ticks", simPauseMs / Hitstop.TICK_MS, "clipS", elapsed(), "impactS", playing.impact());
            Impact.hit((int) (simPauseMs / Hitstop.TICK_MS)); // the effects hit as hard as the labelled pause
            hold(simPauseMs);
            simPauseMs = 0;
        }
        if (pause.holding(Util.getMillis())) {
            pauseFrames++;
        } else if (pauseFrames > 0) {
            double[] peaks = Impact.takePeaks();
            Diag.event("hit_hold_end", "ms", pause.until() - pause.from(), "frames", pauseFrames, "rattleDeg", peaks[0], "pinchPct", peaks[1] * 100);
            pauseFrames = 0;
        }
        boolean vector = !Attacks.attacksFor(e.getItemStack()).isEmpty();
        switching = keepSwitching(switching, e.getEquipProgress());
        double lift = vector ? lift(e.getEquipProgress(), switching, ClientConfig.KEEP_COOLDOWN_DIP.get()) : 0;
        if (vector) trackDip(e.getItemStack(), e.getEquipProgress(), lift > 0 || e.getEquipProgress() <= 0.05f);
        // While the hands are drawn the item rests higher than vanilla's place (Hands.raise); a clip fades that out through its strike, so the arcs are as authored.
        double[] up = Hands.raise(e.getItemStack(), Minecraft.getInstance().options.mainHand().get() == HumanoidArm.LEFT);
        double keep = up[1] == 0 ? 0 : active() ? Hands.raiseKeep(elapsed(), playing.length()) : 1;
        double[] guard = GuardClient.pose(e); // a shield in the main hand: raising, lowering, parrying
        if (!active() && lift == 0 && keep == 0 && guard == null) {
            applied = null;
            return;
        }
        double[] p = guard != null ? guard : active() ? Anim.sample(playing.hand(), elapsed()) : REST;
        if (p != REST && guard == null) { // a fresh array: the weapon shudders while a landed hit holds the swing
            double[] shudder = Impact.hand(pause);
            for (int i = 0; i < 3; i++) p[i] += shudder[i];
        }
        if (keep > 0 && guard == null) {
            if (p == REST) p = new double[6]; // not the shared rest pose
            p[0] += up[0] * keep;
            p[1] += up[1] * keep;
        }
        applied = p;
        appliedLift = lift;
        pivotX = Minecraft.getInstance().options.mainHand().get() == HumanoidArm.LEFT ? -HAND_X : HAND_X;
        applyPose(pose, p, pivotX);
        // Last, so it composes with vanilla's own -0.6 * equipProgress translation to exactly nothing whatever the rotation above was.
        pose.translate(0, lift, 0);
    }

    /** Moves the pose stack by a clip pose {x, y, z, rx, ry, rz}: rotate about the hand (at {@code pivotX}), not the camera (the origin here), so a big swing tilts the item in place instead of throwing it off screen. */
    private static void applyPose(PoseStack pose, double[] p, double pivotX) {
        pose.translate(p[0], p[1], p[2]);
        pose.translate(pivotX, HAND_Y, HAND_Z);
        pose.mulPose(Axis.XP.rotationDegrees((float) p[3]));
        pose.mulPose(Axis.YP.rotationDegrees((float) p[4]));
        pose.mulPose(Axis.ZP.rotationDegrees((float) p[5]));
        pose.translate(-pivotX, -HAND_Y, -HAND_Z);
    }

    @SubscribeEvent
    static void camera(ViewportEvent.ComputeCameraAngles e) {
        if (!active()) return;
        double[] c = Anim.sample(playing.camera(), elapsed());
        double[] rattle = Impact.camera(pause); // and the camera rattles while a landed hit holds the swing
        e.setPitch(e.getPitch() + (float) (c[3] + rattle[0]));
        e.setYaw(e.getYaw() + (float) (c[4] + rattle[1]));
        e.setRoll(e.getRoll() + (float) (c[5] + rattle[2]));
    }
}
