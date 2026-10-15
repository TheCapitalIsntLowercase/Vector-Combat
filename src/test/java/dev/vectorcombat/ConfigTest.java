package dev.vectorcombat;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The config screen reads its labels from the lang file: every value and every section of both settings files has a translation key with a short label and a one-line
 * tooltip, and no two labels in a file are alike, so the screen shows words instead of camel-case keys and a paragraph per tooltip. Run with {@code java -ea}, the lang file's path as the argument.
 */
public final class ConfigTest {
    static final int LABEL = 32, TOOLTIP = 100;

    private static int walk(ModConfigSpec spec, UnmodifiableConfig level, List<String> path, JsonObject lang, Set<String> labels, String file, int[] sections) {
        int leaves = 0;
        for (UnmodifiableConfig.Entry e : level.entrySet()) {
            List<String> here = new ArrayList<>(path);
            here.add(e.getKey());
            Object v = e.getValue();
            String where = file + " " + String.join(".", here);
            String key;
            if (v instanceof UnmodifiableConfig inner) {
                key = spec.getLevelTranslationKey(here);
                sections[0]++;
                leaves += walk(spec, inner, here, lang, labels, file, sections);
            } else {
                key = ((ModConfigSpec.ValueSpec) v).getTranslationKey();
                leaves++;
            }
            assert key != null && key.startsWith("vectorcombat.configuration.") : where + " has no translation key of ours: " + key;
            assert lang.has(key) && lang.has(key + ".tooltip") : where + " needs " + key + " and " + key + ".tooltip in en_us.json";
            String label = lang.get(key).getAsString(), tooltip = lang.get(key + ".tooltip").getAsString();
            assert !label.isBlank() && label.length() <= LABEL : where + " label '" + label + "' is " + label.length() + " characters, at most " + LABEL;
            assert !tooltip.isBlank() && tooltip.length() <= TOOLTIP : where + " tooltip is " + tooltip.length() + " characters, at most " + TOOLTIP + ": " + tooltip;
            assert labels.add(label) : where + " label '" + label + "' is used twice in " + file;
        }
        return leaves;
    }

    private static int[] check(ModConfigSpec spec, JsonObject lang, String file) {
        int[] sections = new int[1];
        int leaves = walk(spec, spec.getSpec(), List.of(), lang, new HashSet<>(), file, sections);
        return new int[] {leaves, sections[0]};
    }

    public static void main(String[] args) throws Exception {
        JsonObject lang = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        int[] client = check(ClientConfig.SPEC, lang, "client"), common = check(CommonConfig.SPEC, lang, "common");
        assert client[1] == 2 : "the client file has two sections (display, feel), got " + client[1];
        assert client[0] == 19 : "client values, got " + client[0];
        assert common[1] == 7 && common[0] == 45 : "common sections and values, got " + common[1] + " and " + common[0];
        for (String file : new String[] {"client", "common"}) {
            String key = "vectorcombat.configuration.section.vectorcombat." + file + ".toml";
            assert lang.has(key) && lang.has(key + ".title") : "the " + file + " screen has a title";
        }
        System.out.println("ConfigTest ok (" + client[0] + " client values in " + client[1] + " sections, " + common[0] + " common in " + common[1] + ")");
    }
}
