package dev.vectorcombat;

import net.neoforged.fml.ModList;

/**
 * Whether the developer tools are on: the test commands ({@code /vcdiag}, {@code /vcarena}, {@code /vcanim}, {@code /vcfit}) and the arena's data. Only in development builds
 * (a version with {@code -dev} or {@code -diag} in it) or with {@code -Dvectorcombat.dev=true}; a release build registers none of them.
 */
public final class Dev {
    private Dev() {
    }

    private static Boolean on;

    public static boolean on() {
        if (on == null) on = Boolean.getBoolean("vectorcombat.dev") || ModList.get().getModContainerById(VectorCombat.ID)
                .map(c -> devVersion(c.getModInfo().getVersion().toString())).orElse(false);
        return on;
    }

    /** Whether {@code version} names a development build. */
    static boolean devVersion(String version) {
        return version.contains("-dev") || version.contains("-diag");
    }
}
