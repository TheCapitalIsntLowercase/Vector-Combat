package dev.vectorcombat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Contact feedback of a landed hit: layered vanilla sounds and sparks. The weapon decides the sound (a mace booms in every direction); the
 * attack's own category adds one quiet accent (a Falling Slash slams, a Lunge stabs). Played on the server so everyone nearby hears it, at
 * the moment the target is held. The table is code until tuning wants rebuild-free edits.
 * <p>
 * Gains are written as the final loudness (0..1) at config volume 1 and weight 1: Minecraft multiplies a sound's own definition volume
 * ({@link #def}) into the volume we pass and then clamps the result at 1, so {@link #volume} divides it out and what the table says is what
 * is heard. Calibrated against the vanilla sound files so each profile hits about equally hard (within ~1.5 dB over the first 0.25 s).
 */
public final class HitFx {
    /** One sound of a hit: registry id, final gain and pitch (pitch carries weight: low is heavy). */
    record Layer(String sound, float gain, float pitch) {
    }

    private static final String P = "minecraft:entity.player.attack.";
    static final String SMASH = "minecraft:item.mace.smash_ground";
    private static final String GOLEM = "minecraft:entity.iron_golem.damage";
    private static final String SHIELD = "minecraft:item.shield.block";

    /** By the weapon's category: a crack, a body and a ring (bash is its smash, which is already all three). */
    private static final Map<String, List<Layer>> PROFILES = Map.of(
            "slash", List.of(new Layer(P + "strong", 1.0f, 1.15f), new Layer(GOLEM, 0.8f, 1.4f), new Layer(SHIELD, 0.55f, 1.8f)),
            "cleave", List.of(new Layer(P + "strong", 1.0f, 0.75f), new Layer(GOLEM, 0.6f, 0.85f), new Layer(P + "knockback", 0.6f, 0.7f),
                    new Layer("minecraft:block.anvil.land", 0.15f, 1.2f)),
            "pierce", List.of(new Layer("minecraft:item.trident.hit", 1.0f, 1.3f), new Layer(P + "strong", 1.0f, 1.35f), new Layer(P + "weak", 0.8f, 1.2f),
                    new Layer(SHIELD, 0.7f, 2.0f)),
            "bash", List.of(new Layer(SMASH, 0.9f, 1.15f), new Layer(P + "knockback", 0.8f, 0.7f)));

    /** By the attack's own category, when it differs from the weapon's: about 6 dB under the main sound. */
    private static final Map<String, Layer> ACCENTS = Map.of(
            "cleave", new Layer(P + "knockback", 0.6f, 0.55f), // a deep slam under the sound
            "pierce", new Layer(P + "strong", 0.8f, 1.8f), // a sharp stab tick
            "slash", new Layer(P + "crit", 0.55f, 1.3f)); // a bright ting

    private static final Layer CRACK = new Layer(P + "crit", 0.8f, 0.95f);

    /** A parry rings: the shield's thud pitched up under a metal crack, a bright ting and a trident's sharp tick, nothing like any weapon's hit. */
    static final List<Layer> PARRY = List.of(new Layer(SHIELD, 1.0f, 1.55f), new Layer(GOLEM, 0.9f, 1.8f), new Layer(P + "crit", 0.8f, 1.5f), new Layer("minecraft:item.trident.hit", 0.5f, 2.0f));

    /** The vanilla definition volume of the sounds we use (sounds.json); everything else is 1. */
    private static final Map<String, Float> DEF = Map.of(P + "strong", 0.63f, P + "weak", 0.7f, P + "knockback", 0.7f, P + "crit", 0.7f, GOLEM, 0.8f);

    private HitFx() {
    }

    static float def(String sound) {
        return DEF.getOrDefault(sound, 1f);
    }

    /** The volume to pass to {@code playSound} so the final gain is {@code gain x config x weight} (the engine then clamps it at 1). */
    static float volume(Layer l, float config, float weight) {
        return l.gain() * config * weight / def(l.sound());
    }

    /** First category of a list, slash when there is none. */
    static String lead(List<String> categories) {
        return categories == null || categories.isEmpty() ? "slash" : categories.get(0);
    }

    /** The attack's own category when it colours the weapon's sound, else null. */
    static String accentFor(String weapon, String own) {
        return own == null || own.equals(weapon) || !ACCENTS.containsKey(own) ? null : own;
    }

    static Layer accent(String category) {
        return ACCENTS.get(category);
    }

    /** The layers for a hit by a weapon of this category (slash for anything else); a kill adds the crack. */
    static List<Layer> layers(String profile, boolean kill) {
        List<Layer> base = PROFILES.getOrDefault(profile == null ? "" : profile, PROFILES.get("slash"));
        if (!kill) return base;
        List<Layer> all = new ArrayList<>(base);
        all.add(CRACK);
        return all;
    }

    /** Louder, and more sparks, for a longer pause: 0.8 up to 1 tick, 1.0 at 3, 1.3 from 6. */
    static float weight(int ticks) {
        return Math.max(0.8f, Math.min(1.3f, 0.7f + 0.1f * ticks));
    }

    /** Heavier weapons (lower attack speed) sound lower and lighter ones higher, so any weapon has a voice: 1 at a sword's 1.6, within 0.85 to 1.15. */
    static float pitchScale(double weaponSpeed) {
        return (float) Math.max(0.85, Math.min(1.15, Math.pow(Math.max(0.1, weaponSpeed) / 1.6, 0.25)));
    }

    /** What the sound of a hit produced, for the diag log. */
    record Played(int layers, String missing) {
    }

    /** Plays the profile (and accent, and the crack on a kill) at {@code at}; {@code skipSmash} drops our smash when vanilla's own is playing. */
    static Played sound(ServerLevel level, Vec3 at, String profile, String accent, double weaponSpeed, int ticks, boolean kill, boolean skipSmash) {
        List<Layer> all = new ArrayList<>(layers(profile, kill));
        if (accent != null) all.add(ACCENTS.get(accent));
        return play(level, at, all, CommonConfig.HITFX_VOLUME.get().floatValue(), weight(ticks), pitchScale(weaponSpeed), skipSmash);
    }

    /** Every layer of {@code all} at {@code at}, at the config volume and the hit's weight, pitched by {@code pitch} with a little spread. */
    private static Played play(ServerLevel level, Vec3 at, List<Layer> all, float config, float weight, float pitch, boolean skipSmash) {
        int played = 0;
        String missing = null;
        if (config > 0) {
            for (Layer l : all) {
                if (skipSmash && l.sound().equals(SMASH)) continue;
                var sound = BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.parse(l.sound()));
                if (sound.isEmpty()) {
                    missing = l.sound();
                    continue;
                }
                level.playSound(null, at.x, at.y, at.z, sound.get(), SoundSource.PLAYERS, volume(l, config, weight),
                        l.pitch() * pitch * (0.94f + level.random.nextFloat() * 0.12f));
                played++;
            }
        }
        return new Played(played, missing);
    }

    /**
     * A swing landed on {@code hurt}: the sound plays once at the victim nearest the attacker, sparks fly from the first three.
     * {@code ticks} is the hit's pause (0 when hitstop is off), which sets how big the hit sounds.
     */
    static void contact(ServerLevel level, ServerPlayer p, List<LivingEntity> hurt, String profile, String accent, double weaponSpeed, boolean skipSmash, int ticks,
            boolean kill) {
        if (hurt.isEmpty()) return;
        LivingEntity near = hurt.stream().min(Comparator.comparingDouble(t -> t.distanceToSqr(p))).get();
        Played played = sound(level, chest(near), profile, accent, weaponSpeed, ticks, kill, skipSmash);
        int sparks = 0, shown = Math.min(3, hurt.size());
        for (LivingEntity t : hurt.subList(0, shown)) {
            Vec3 at = chest(t);
            sparks += sparks(level, at, at.subtract(p.getX(), p.getY(0.6), p.getZ()).normalize(), profile, ticks, kill, shown);
        }
        Diag.event("hitfx", "profile", profile, "accent", accent, "layers", played.layers(), "volume", CommonConfig.HITFX_VOLUME.get() * weight(ticks), "particles", sparks,
                "victims", hurt.size(), "kill", kill, "skippedSmash", skipSmash, "flag", played.missing() == null ? null : "hitfx_missing_sound", "missing", played.missing());
    }

    /** What a parry produced, for the diag log. */
    record Parried(int layers, int sparks, String missing) {
    }

    /**
     * A parry rings out at {@code at} (the shield): the {@link #PARRY} layers and a spray of sparks flying {@code toward} the attacker. {@code weightTicks} sets how loud it is and how
     * many sparks, as for a hit ({@link #weight}).
     */
    static Parried parry(ServerLevel level, Vec3 at, Vec3 toward, int weightTicks) {
        Played played = play(level, at, PARRY, CommonConfig.HITFX_VOLUME.get().floatValue(), weight(weightTicks), 1f, false);
        float k = weight(weightTicks) * CommonConfig.HITFX_PARTICLE_SCALE.get().floatValue();
        int sparks = !CommonConfig.HITFX_PARTICLES.get() ? 0
                : spray(level, ParticleTypes.ELECTRIC_SPARK, at, toward, n(18, k), 0.8) + spray(level, ParticleTypes.CRIT, at, toward, n(8, k), 0.6) + burst(level, ParticleTypes.ENCHANTED_HIT, at, n(6, k), 0.2, 0.1);
        return new Parried(played.layers(), sparks, played.missing());
    }

    /**
     * A block at {@code at} (a mob's guard): vanilla's shield-block sound, as a player's block sounds, and a small spray of sparks glancing off {@code toward} the attacker; fewer
     * than a parry's and no ring. Returns the particles sent.
     */
    static int block(ServerLevel level, Vec3 at, Vec3 toward, int weightTicks) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, CommonConfig.HITFX_VOLUME.get().floatValue(), 0.8f + level.random.nextFloat() * 0.4f);
        if (!CommonConfig.HITFX_PARTICLES.get()) return 0;
        float k = weight(weightTicks) * CommonConfig.HITFX_PARTICLE_SCALE.get().floatValue();
        return spray(level, ParticleTypes.CRIT, at, toward, n(7, k), 0.35) + spray(level, ParticleTypes.ELECTRIC_SPARK, at, toward, n(4, k), 0.4);
    }

    private static Vec3 chest(LivingEntity t) {
        return new Vec3(t.getX(), t.getY(0.6), t.getZ());
    }

    /** Sparks per victim shrink as more are hit, so a group gets a little over one hit's worth in total, not three times as much: 1 for one victim, 0.71 for two, 0.58 for three. */
    static float share(int shown) {
        return (float) (1 / Math.sqrt(Math.max(1, shown)));
    }

    /**
     * The profile's sparks at {@code at} flying along {@code away}, one of {@code shown} victims; counts scale with weight, x1.5 on a kill, times the config
     * and the group share. Returns how many particles were sent.
     */
    static int sparks(ServerLevel level, Vec3 at, Vec3 away, String profile, int ticks, boolean kill, int shown) {
        if (!CommonConfig.HITFX_PARTICLES.get()) return 0;
        float k = weight(ticks) * (kill ? 1.5f : 1f) * CommonConfig.HITFX_PARTICLE_SCALE.get().floatValue() * share(shown);
        return switch (profile) {
            case "pierce" -> spray(level, ParticleTypes.CRIT, at, away, n(12, k), 0.7) + spray(level, ParticleTypes.ELECTRIC_SPARK, at, away, n(8, k), 0.7)
                    + burst(level, ParticleTypes.ENCHANTED_HIT, at, n(4, k), 0.15, 0.1);
            case "cleave" -> burst(level, ParticleTypes.CRIT, at, n(20, k), 0.3, 0.45) + spray(level, ParticleTypes.ELECTRIC_SPARK, at, away, n(6, k), 0.55)
                    + burst(level, dust(1.0f, 0.55f, 0.2f, 1.2f), at, n(5, k), 0.25, 0) + burst(level, ParticleTypes.ENCHANTED_HIT, at, n(4, k), 0.2, 0.15);
            // No smoke: a POOF puff is up to 1.4 blocks wide and lives up to 4 s (vanilla already puffs on a kill), and a big dust lives as long
            case "bash" -> burst(level, ParticleTypes.CRIT, at, n(10, k), 0.3, 0.35) + burst(level, dust(0.7f, 0.7f, 0.72f, 1.0f), at, n(4, k), 0.3, 0);
            default -> spray(level, ParticleTypes.CRIT, at, away, n(14, k), 0.5) + spray(level, ParticleTypes.ELECTRIC_SPARK, at, away, n(6, k), 0.55)
                    + burst(level, dust(1.0f, 0.9f, 0.5f, 1.2f), at, n(5, k), 0.2, 0) + burst(level, ParticleTypes.ENCHANTED_HIT, at, n(4, k), 0.2, 0.1);
        };
    }

    private static int n(int base, float k) {
        return Math.round(base * k);
    }

    private static DustParticleOptions dust(float r, float g, float b, float scale) {
        return new DustParticleOptions(new Vector3f(r, g, b), scale);
    }

    private static int burst(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread, double speed) {
        if (count <= 0) return 0; // count 0 would send one particle with a velocity instead
        level.sendParticles(type, at.x, at.y, at.z, count, spread, spread, spread, speed);
        return count;
    }

    /** Sparks that fly away from the attacker: count 0 makes each call one particle with the given direction and speed. */
    private static int spray(ServerLevel level, ParticleOptions type, Vec3 at, Vec3 away, int count, double speed) {
        for (int i = 0; i < count; i++) {
            level.sendParticles(type, at.x, at.y, at.z, 0, away.x + (level.random.nextDouble() - 0.5) * 0.5, away.y + (level.random.nextDouble() - 0.3) * 0.5,
                    away.z + (level.random.nextDouble() - 0.5) * 0.5, speed);
        }
        return Math.max(0, count);
    }
}
