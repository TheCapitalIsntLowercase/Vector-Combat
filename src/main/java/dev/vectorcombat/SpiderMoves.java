package dev.vectorcombat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What follows a spider's pounce, and its web: the <b>pin</b> (landed on a player unblocked, it stays on them {@link #PIN} ticks, slowing
 * them hard and biting at {@link #PIN_BITES}; any hit on it by anyone throws it off), the <b>flip</b> (a parried pounce: on its back {@link #FLIP} ticks, legs flailing, taking
 * x{@link #FLIP_DAMAGE}), the <b>web glob</b> (a lobbed projectile drawn in web particles, no entity and no cobweb blocks: a hit roots the player {@link #ROOT} ticks, slowed to a
 * crawl and unable to jump, and the spider pounces at once; a shield stops it), and the landing <b>ring</b> (drawn by the clients that show line telegraphs).
 */
@EventBusSubscriber(modid = VectorCombat.ID)
public final class SpiderMoves {
    private SpiderMoves() {
    }

    static final int PIN = 30, FLIP = 40, ROOT = 30; // the flip lasts 2 s (1.5)
    static final int[] PIN_BITES = {15, 30};
    static final double FLIP_DAMAGE = 1.5, GLOB_SPEED = 0.8, GLOB_GRAVITY = 0.03, THROWN_OFF = 0.6;
    static final int GLOB_LOST = 60, THROWN_STUN = 10;

    private static final ResourceLocation ROOT_ID = ResourceLocation.fromNamespaceAndPath(VectorCombat.ID, "web_root");
    private static final ItemParticleOption WEB = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.COBWEB));

    private record Pin(Player victim, int[] age) {
    }

    private static final class Glob {
        final Mob spider;
        Vec3 pos, vel;
        int age;

        Glob(Mob spider, Vec3 pos, Vec3 vel) {
            this.spider = spider;
            this.pos = pos;
            this.vel = vel;
        }
    }

    private static final Map<Mob, Pin> PINS = new WeakHashMap<>();
    private static final Map<Mob, Long> FLIPS = new WeakHashMap<>();
    private static final Map<Player, Long> ROOTS = new WeakHashMap<>();
    private static final List<Glob> GLOBS = new ArrayList<>();

    static {
        Holds.onClear(() -> {
            PINS.clear();
            FLIPS.clear();
            ROOTS.clear();
            GLOBS.clear();
        });
    }

    // ---- pure ----

    /** The launch of a glob from {@code from} to {@code to} in {@code ticks} ticks (it moves by its speed, then loses {@link #GLOB_GRAVITY}): solved exactly. */
    static Vec3 globLaunch(Vec3 from, Vec3 to, int ticks) {
        int t = Math.max(1, ticks);
        double vy = (to.y - from.y + GLOB_GRAVITY * t * (t - 1) / 2.0) / t;
        return new Vec3((to.x - from.x) / t, vy, (to.z - from.z) / t);
    }

    /** The ticks a glob takes to cross {@code distance}. */
    static int globTicks(double distance) {
        return Math.max(4, (int) Math.ceil(distance / GLOB_SPEED));
    }

    /** Whether the pin bites at {@code age}. */
    static boolean bites(int age) {
        for (int b : PIN_BITES) if (b == age) return true;
        return false;
    }

    // ---- the game ----

    /** A spider pinning a player or flipped on its back: no pounce starts. */
    public static boolean busy(Mob spider) {
        return PINS.containsKey(spider) || flipped(spider);
    }

    /** A spider on a player is immobile to its own goals ({@code LivingEntityMixin}); we hold it there. */
    public static boolean holding(LivingEntity e) {
        return e instanceof Mob m && !PINS.isEmpty() && PINS.containsKey(m);
    }

    static boolean flipped(Mob spider) {
        Long until = FLIPS.get(spider);
        return until != null && until > spider.level().getGameTime();
    }

    public static boolean rooted(Player p) {
        Long until = ROOTS.get(p);
        return until != null && until > p.level().getGameTime();
    }

    /** A landing ring of {@code radius} round {@code center} in {@code color}, sent as eight ring segments to the players tracking {@code mob}, for {@code ticks}. */
    static void ring(Mob mob, Vec3 center, double radius, int ticks, int color) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, b = (i + 1) * Math.PI / 4;
            PacketDistributor.sendToPlayersTrackingEntity(mob, new LinePayload((float) (center.x + Math.cos(a) * radius), (float) center.y, (float) (center.z + Math.sin(a) * radius),
                    (float) (center.x + Math.cos(b) * radius), (float) center.y, (float) (center.z + Math.sin(b) * radius), ticks, color, true));
        }
    }

    static void ring(Mob mob, Vec3 center, double radius, int ticks) {
        ring(mob, center, radius, ticks, LinePayload.RED);
    }

    /** Pins {@code p}, unless another grab holds them ({@link Holds}); false then, and the spider does not pin. */
    static boolean pin(Mob spider, Player p) {
        if (!Holds.claim(p, spider)) return false;
        PINS.put(spider, new Pin(p, new int[] {0}));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, PIN, 3), spider);
        spider.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPIDER_HURT, SoundSource.HOSTILE, 1.2f, 0.6f);
        Diag.event("spider", "result", "pin", "mob", spider.getType().getDescriptionId(), "tick", spider.level().getGameTime());
        return true;
    }

    static void flip(Mob spider) {
        FLIPS.put(spider, spider.level().getGameTime() + FLIP);
        Stuns.apply(spider, FLIP / 20.0);
        WindUp.signal(spider, spider.getTarget(), "spider_flipped", "start", FLIP);
        spider.level().playSound(null, spider.getX(), spider.getY(), spider.getZ(), SoundEvents.SPIDER_HURT, SoundSource.HOSTILE, 1.4f, 1.3f);
        Diag.event("spider", "result", "flipped", "mob", spider.getType().getDescriptionId(), "tick", spider.level().getGameTime());
    }

    private static void release(Mob spider, Pin pin, boolean thrown) {
        PINS.remove(spider);
        Player p = pin.victim();
        Holds.release(p, spider);
        if (p != null && p.getEffect(MobEffects.MOVEMENT_SLOWDOWN) != null && p.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() == 3) p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN); // the pin's own
        if (thrown && p != null) { // knocked off: away from the player, a moment's daze
            Vec3 away = spider.position().subtract(p.position());
            Vec3 flat = new Vec3(away.x, 0, away.z);
            flat = flat.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : flat.normalize();
            spider.setDeltaMovement(flat.scale(THROWN_OFF).add(0, 0.3, 0));
            spider.hurtMarked = true;
            Stuns.apply(spider, THROWN_STUN / 20.0);
        }
        Diag.event("spider", "result", thrown ? "thrown_off" : "let_go", "mob", spider.getType().getDescriptionId(), "tick", spider.level().getGameTime());
    }

    static void spit(Mob spider, LivingEntity target) {
        Vec3 from = spider.getEyePosition(), to = target.position().add(0, target.getBbHeight() * 0.5, 0);
        GLOBS.add(new Glob(spider, from, globLaunch(from, to, globTicks(from.distanceTo(to)))));
        spider.level().playSound(null, spider.getX(), spider.getY(), spider.getZ(), SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 1.2f, 1.6f);
        Diag.event("spider", "result", "spit", "mob", spider.getType().getDescriptionId(), "dist", Math.round(from.distanceTo(to) * 100) / 100.0, "tick", spider.level().getGameTime());
    }

    private static void root(Mob spider, Player p, ServerLevel level) {
        ROOTS.put(p, level.getGameTime() + ROOT);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ROOT, 4), spider);
        AttributeInstance jump = p.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null && !jump.hasModifier(ROOT_ID)) jump.addTransientModifier(new AttributeModifier(ROOT_ID, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE, 1.2f, 0.7f);
        Diag.event("spider", "result", "rooted", "mob", spider.getType().getDescriptionId(), "tick", level.getGameTime());
    }

    // ---- ticking ----

    @SubscribeEvent
    static void spiderTick(EntityTickEvent.Post e) {
        if (PINS.isEmpty() || !(e.getEntity() instanceof Mob spider) || spider.level().isClientSide()) return;
        Pin pin = PINS.get(spider);
        if (pin == null) return;
        Player p = pin.victim();
        if (!spider.isAlive() || p == null || !p.isAlive() || p.isRemoved() || p.level() != spider.level() || spider.distanceTo(p) > 4) {
            release(spider, pin, false);
            return;
        }
        int age = ++pin.age()[0];
        Vec3 front = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(0.45)).add(0, 0.5, 0); // on them, at their chest
        spider.setPos(front.x, front.y, front.z);
        spider.setDeltaMovement(Vec3.ZERO);
        spider.lookAt(p, 360, 360);
        if (bites(age)) {
            spider.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            spider.doHurtTarget(p);
            if (Guard.justParried(p)) {
                release(spider, pin, false);
                flip(spider);
                return;
            }
        }
        if (age >= PIN) release(spider, pin, false);
    }

    @SubscribeEvent
    static void levelTick(LevelTickEvent.Post e) {
        if (GLOBS.isEmpty() || !(e.getLevel() instanceof ServerLevel level)) return;
        for (Iterator<Glob> it = GLOBS.iterator(); it.hasNext(); ) {
            Glob g = it.next();
            if (g.spider.level() != level) continue;
            Vec3 prev = g.pos;
            g.pos = g.pos.add(g.vel);
            g.vel = g.vel.subtract(0, GLOB_GRAVITY, 0);
            g.age++;
            level.sendParticles(WEB, g.pos.x, g.pos.y, g.pos.z, 3, 0.05, 0.05, 0.05, 0.01);
            Player hit = null;
            for (Player p : level.players()) {
                AABB box = p.getBoundingBox().inflate(0.3);
                if (!p.isSpectator() && (box.contains(g.pos) || box.clip(prev, g.pos).isPresent())) {
                    hit = p;
                    break;
                }
            }
            if (hit != null) {
                DamageSource src = g.spider.damageSources().mobAttack(g.spider);
                if (hit.isDamageSourceBlocked(src)) { // caught on the shield
                    level.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1f, 1.4f);
                    Diag.event("spider", "result", "spit_blocked", "tick", level.getGameTime());
                } else {
                    root(g.spider, hit, level);
                }
                level.sendParticles(WEB, g.pos.x, g.pos.y, g.pos.z, 15, 0.3, 0.3, 0.3, 0.05);
                it.remove();
                continue;
            }
            if (!level.getBlockState(BlockPos.containing(g.pos)).getCollisionShape(level, BlockPos.containing(g.pos)).isEmpty() || g.age > GLOB_LOST) {
                level.sendParticles(WEB, g.pos.x, g.pos.y, g.pos.z, 10, 0.2, 0.2, 0.2, 0.03);
                it.remove();
            }
        }
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post e) {
        if (ROOTS.isEmpty() || !(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel level)) return;
        Long until = ROOTS.get(p);
        if (until == null) return;
        if (until <= level.getGameTime()) {
            ROOTS.remove(p);
            AttributeInstance jump = p.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) jump.removeModifier(ROOT_ID);
            return;
        }
        if (level.getGameTime() % 2 == 0) level.sendParticles(WEB, p.getX(), p.getY() + 0.2, p.getZ(), 4, 0.35, 0.1, 0.35, 0.01); // strands round the feet
    }

    /** A hit on a pinning spider throws it off (anyone's hit); a flipped spider takes more. */
    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof Mob spider) || spider.level().isClientSide() || e.getAmount() <= 0) return;
        if (flipped(spider)) e.setAmount((float) (e.getAmount() * FLIP_DAMAGE));
        Pin pin = PINS.get(spider);
        if (pin != null && e.getSource().getEntity() != spider) release(spider, pin, true);
    }
}
