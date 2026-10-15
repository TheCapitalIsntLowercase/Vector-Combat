package dev.vectorcombat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No client-only code is reachable from code a dedicated server loads. A server has none of Minecraft's client classes, so a class it loads that names one fails there with
 * NoClassDefFoundError, while singleplayer, which has them all, never shows it. Reads the constant pool of every compiled class (exact names, so {@code Swing} is not
 * {@code SwingPayload}) and checks four rules. R1: every class naming a client-only package is on {@link #CLIENT}, the list of classes only a client may load, so a new one is a
 * conscious decision. R2: a listed class that registers event handlers or is a mod entry point is gated to {@code Dist.CLIENT}. R3: no class off the list names one on it, except
 * a class's own nest and the four client payload handlers {@code VectorCombat} names in lambdas only a client runs. R4: the mixin config lists a client-touching mixin under
 * {@code client}, never under {@code mixins}. Run with {@code java -ea}, giving the compiled classes directory and {@code vectorcombat.mixins.json}.
 */
public final class DistTest {
    private static final String PKG = "dev/vectorcombat/";
    private static final List<String> CLIENT_PACKAGES = List.of("net/minecraft/client/", "com/mojang/blaze3d/", "net/neoforged/neoforge/client/", "org/lwjgl/");

    /** Classes (and their nested classes) that only a client may load, named from {@code dev/vectorcombat/}. {@code ClientConfig} names no client class, but it is a client-only config. */
    private static final Set<String> CLIENT = Set.of("LineClient", "mixin/QuadrupedModelAccessor", "mixin/HoglinModelAccessor", "EngulfClient", "Blade", "AttackTooltip", "CrosshairSplit", "DirectionArrow", "StunCrosshair", "Fit", "DiagHud", "Impact", "GuardClient", "HitFeel", "ClientSwings", "Hands",
            "Swing", "Trails", "ThirdPerson", "WindUpClient", "VectorCombatClient", "EmfCompat", "MobGuardClient", "ThirdView", "PoseCopy", "mixin/RenderLayerMixin", "ClientConfig", "TwoHanded$Client", "mixin/HumanoidModelMixin", "mixin/ItemInHandLayerMixin",
            "mixin/ItemInHandRendererMixin", "mixin/LivingEntityRendererMixin");

    /** What {@code VectorCombat.payloads} hands the client-bound payloads to: named in lambdas that only run on a client. */
    private static final Set<String> PAYLOAD_HANDLERS = Set.of(PKG + "HitFeel", PKG + "ClientSwings", PKG + "GuardClient", PKG + "WindUpClient", PKG + "EngulfClient", PKG + "LineClient");

    private static final Pattern DESCRIPTOR = Pattern.compile("L([A-Za-z0-9_/$]+)[;<]");
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_/$]+");

    /** A class's name (from {@code dev/vectorcombat/}), its raw constant-pool strings and the class names found in them. */
    private record Cls(String name, Set<String> tokens, Set<String> names) {
    }

    private static String outer(String name) {
        return name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
    }

    private static boolean client(String name) {
        return CLIENT.contains(name) || CLIENT.contains(outer(name));
    }

    private static Cls read(Path file, String name) throws IOException {
        Set<String> tokens = new TreeSet<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            in.readInt(); // magic
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int n = in.readUnsignedShort();
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> tokens.add(in.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++; // a long or a double takes two slots
                    }
                    case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("unknown constant pool tag " + tag + " in " + name);
                }
            }
        }
        Set<String> names = new TreeSet<>();
        for (String t : tokens) {
            if (BARE.matcher(t).matches()) names.add(t);
            Matcher m = DESCRIPTOR.matcher(t);
            while (m.find()) names.add(m.group(1));
        }
        return new Cls(name, tokens, names);
    }

    private static boolean touchesClient(Cls c) {
        return c.names().stream().anyMatch(n -> CLIENT_PACKAGES.stream().anyMatch(n::startsWith));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("dev/vectorcombat");
        Map<String, Cls> all = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - ".class".length());
                all.put(name, read(p, name));
            }
        }
        assert all.size() > 60 : "found only " + all.size() + " classes under " + root;
        for (String n : CLIENT) assert all.containsKey(n) : n + " is on the client list but there is no such class";

        // R1: client-touching classes are on the list.
        int touching = 0;
        for (Cls c : all.values()) {
            if (!touchesClient(c)) continue;
            touching++;
            assert client(c.name()) : c.name() + " names a client-only class but is not on DistTest.CLIENT: if only a client can ever load it, list it (and gate its handlers); if a server can, it will crash there";
        }

        // R2: a listed class that handles events or is a mod entry point only does so on a client.
        for (Cls c : all.values()) {
            if (!CLIENT.contains(c.name())) continue;
            Set<String> t = c.tokens();
            boolean registers = t.contains("Lnet/neoforged/bus/api/SubscribeEvent;") || t.contains("Lnet/neoforged/fml/common/EventBusSubscriber;") || t.contains("Lnet/neoforged/fml/common/Mod;");
            if (!registers) continue;
            assert t.contains("Lnet/neoforged/api/distmarker/Dist;") && t.contains("CLIENT") && !t.contains("DEDICATED_SERVER") : c.name() + " handles events or is a mod entry point, so it must be gated to Dist.CLIENT";
        }

        // R3: nothing off the list names a class on it, bar its own nest and the client payload handlers.
        for (Cls c : all.values()) {
            if (client(c.name())) continue;
            for (String n : c.names()) {
                if (!n.startsWith(PKG)) continue;
                String other = n.substring(PKG.length());
                if (!client(other) || outer(other).equals(outer(c.name()))) continue;
                assert c.name().equals("VectorCombat") && PAYLOAD_HANDLERS.contains(n) : c.name() + " names the client-only " + other + ", which a dedicated server would try to load";
            }
        }
        assert all.get("VectorCombat").names().containsAll(PAYLOAD_HANDLERS) : "the exception for VectorCombat's payload handlers is stale";

        // R4: the mixin config separates them the same way.
        JsonObject mixins = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        Set<String> common = new TreeSet<>(), client = new TreeSet<>();
        mixins.getAsJsonArray("mixins").forEach(e -> common.add("mixin/" + e.getAsString()));
        mixins.getAsJsonArray("client").forEach(e -> client.add("mixin/" + e.getAsString()));
        for (String m : common) {
            assert all.containsKey(m) && !client(m) && !touchesClient(all.get(m)) : m + " is in \"mixins\" (applied on a server too) but is client-only";
        }
        for (String m : client) assert all.containsKey(m) && client(m) : m + " is in \"client\" but not on DistTest.CLIENT";
        for (String n : CLIENT) assert !n.startsWith("mixin/") || client.contains(n) : n + " is a client mixin missing from the config's \"client\" list";
        for (String n : all.keySet()) assert !n.startsWith("mixin/") || common.contains(n) || client.contains(n) : n + " is in neither list of the mixin config";

        System.out.println("DistTest ok (" + all.size() + " classes, " + touching + " name client classes, all of them on the client list)");
    }
}
