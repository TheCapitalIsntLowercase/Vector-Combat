package dev.vectorcombat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * What the rest of an arc takes, and which target is the one under the crosshair. Sweeping Edge decides how hard the others are hit, as in vanilla: {@code 1 + ratio x damage}
 * with the ratio the player's {@code sweeping_damage_ratio} attribute. Checked against the game's own attribute and enchantment data, so the numbers are vanilla's. Run with {@code java -ea}.
 */
public final class SweepTest {
    private static void near(double a, double b, String what) {
        assert Math.abs(a - b) < 1e-9 : what + ": " + a + " is not " + b;
    }

    private static double linear(JsonObject o, int level) {
        assert o.get("type").getAsString().equals("minecraft:linear") : "a linear value";
        return o.get("base").getAsDouble() + o.get("per_level_above_first").getAsDouble() * (level - 1);
    }

    public static void main(String[] args) throws Exception {
        // The formula: without Sweeping Edge a secondary target takes 1, whatever the swing; the ratio gives back a share of the swing's damage on top.
        near(Sweep.secondaryBase(0, 6), 1, "no ratio, a sword's sweep");
        near(Sweep.secondaryBase(0, 100), 1, "no ratio, a huge swing");
        near(Sweep.secondaryBase(0.5, 6), 4, "ratio 0.5 on 6");
        near(Sweep.secondaryBase(2.0 / 3, 6), 5, "ratio 2/3 on 6");
        near(Sweep.secondaryBase(0.75, 6), 5.5, "ratio 0.75 on 6");
        near(Sweep.secondaryBase(0.75, 9.6), 8.2, "a falling slash with Sweeping Edge III");

        // The game's own data: the attribute and the enchantment that raises it.
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        RangedAttribute ratio = (RangedAttribute) Attributes.SWEEPING_DAMAGE_RATIO.value();
        assert ratio.getDefaultValue() == 0 && ratio.getMinValue() == 0 && ratio.getMaxValue() == 1 : "the ratio is 0 unless something raises it, within 0 to 1";
        JsonObject enchant;
        try (InputStream in = SweepTest.class.getClassLoader().getResourceAsStream("data/minecraft/enchantment/sweeping_edge.json")) {
            assert in != null : "the game's sweeping_edge.json is on the classpath";
            enchant = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject effect = enchant.getAsJsonObject("effects").getAsJsonArray("minecraft:attributes").get(0).getAsJsonObject();
        assert effect.get("attribute").getAsString().equals("minecraft:player.sweeping_damage_ratio") && effect.get("operation").getAsString().equals("add_value") : "it adds to the ratio";
        JsonObject amount = effect.getAsJsonObject("amount");
        assert amount.get("type").getAsString().equals("minecraft:fraction") : "as a fraction of the level";
        assert enchant.get("max_level").getAsInt() == 3 && enchant.getAsJsonArray("slots").get(0).getAsString().equals("mainhand") : "level 3 at most, while held";
        assert enchant.get("supported_items").getAsString().equals("#minecraft:enchantable/sword") : "swords only, in vanilla";
        double[] expected = {0.5, 2.0 / 3, 0.75};
        double[] sword = {4.0, 5.0, 5.5}, falling = {5.8, 7.4, 8.2};
        for (int level = 1; level <= 3; level++) {
            double r = linear(amount.getAsJsonObject("numerator"), level) / linear(amount.getAsJsonObject("denominator"), level);
            near(r, expected[level - 1], "Sweeping Edge " + level + "'s ratio");
            near(Sweep.secondaryBase(r, 6.0), sword[level - 1], "a sword's sweep at level " + level);
            near(Sweep.secondaryBase(r, 9.6), falling[level - 1], "a falling slash at level " + level);
        }

        // The target under the crosshair: ray distance squared (negative for a miss), horizontal offset in degrees, distance squared.
        assert Sweep.primary(List.of()) == -1 : "no target, no primary";
        assert Sweep.primary(List.of(new Sweep.Candidate(-1, 40, 9))) == 0 : "a lone target is the primary, however far off the aim";
        // The nearest box the aim ray hits wins, even against one whose centre is closer to the aim line.
        assert Sweep.primary(List.of(new Sweep.Candidate(16, 3, 16), new Sweep.Candidate(2.25, 12, 2.25), new Sweep.Candidate(-1, 0.5, 4))) == 1 : "the near zombie the crosshair overlaps";
        assert Sweep.primary(List.of(new Sweep.Candidate(-1, 0, 4), new Sweep.Candidate(25, 20, 25))) == 1 : "any ray hit beats a miss, however well centred";
        // With no ray hit: the smallest offset, then the nearest.
        assert Sweep.primary(List.of(new Sweep.Candidate(-1, 20, 4), new Sweep.Candidate(-1, 5, 9), new Sweep.Candidate(-1, 5, 4))) == 2 : "least off the aim, then nearest";
        assert Sweep.primary(List.of(new Sweep.Candidate(-1, 5, 9), new Sweep.Candidate(-1, 5, 9))) == 0 : "a full tie keeps the first";
        // Three husks side by side as /vcdiag live spawns them (2 blocks ahead, 2 either side): aimed ahead the middle one is hit by the ray and is the primary; aimed at one side, that one is.
        double side = 2 * 2 + 2 * 2;
        assert Sweep.primary(List.of(new Sweep.Candidate(-1, 45, side), new Sweep.Candidate(4, 0, 4), new Sweep.Candidate(-1, 45, side))) == 1 : "aimed at the middle husk";
        assert Sweep.primary(List.of(new Sweep.Candidate(side, 0, side), new Sweep.Candidate(-1, 45, 4), new Sweep.Candidate(-1, 90, side))) == 0 : "aimed at the first husk, the others 45 and 90 degrees off";

        System.out.println("SweepTest ok");
    }
}
