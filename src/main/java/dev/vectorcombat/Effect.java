package dev.vectorcombat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/** What an attack does to its victim (or to its user) besides damage. Parsed from an attack's {@code effects} list. */
public sealed interface Effect {
    /** Stun the victim for {@code seconds}, scaled down by their stun resistance. */
    record Stun(double seconds) implements Effect {
    }

    /** Set velocity as (right, up, forward) relative to the attacker's facing; on the attacker if {@code user}. */
    record Motion(boolean user, Vec3 motion) implements Effect {
    }

    /** Extra hunger on the attacker, once per swing. */
    record Exhaustion(float amount) implements Effect {
    }

    static Effect parse(JsonObject o) {
        return switch (o.get("type").getAsString()) {
            case "stun" -> new Stun(o.get("duration").getAsDouble());
            case "set_motion" -> {
                JsonArray m = o.getAsJsonArray("motion");
                yield new Motion("user".equals(o.has("target") ? o.get("target").getAsString() : "victim"),
                        new Vec3(m.get(0).getAsDouble(), m.get(1).getAsDouble(), m.get(2).getAsDouble()));
            }
            case "food_exhaustion" -> new Exhaustion(o.get("amount").getAsFloat());
            default -> throw new IllegalArgumentException("unknown effect type: " + o.get("type"));
        };
    }
}
