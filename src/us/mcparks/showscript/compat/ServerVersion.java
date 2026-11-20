package us.mcparks.showscript.compat;

import org.bukkit.Bukkit;

/**
 * Runtime Minecraft version detection. ShowsRebuild ships one JAR (Java 8 bytecode, compiled against the
 * 1.12 Bukkit API) for Paper 1.12.2 and Paper 1.21.x; anything that differs between them goes through the
 * {@code us.mcparks.showscript.compat} package and branches on this class.
 *
 * <p>When 1.12.2 support is dropped, delete the legacy implementations and this class's callers collapse to
 * the modern path.
 */
public final class ServerVersion {
    /** The running server's version, parsed from {@code Bukkit.getBukkitVersion()}. */
    public static final MinecraftVersion CURRENT;
    /** Major version: 1 for 1.12.2 / 1.21.4, 26 for the year-versioned 26.x releases. */
    public static final int MAJOR;
    /** Minor version: 12 for 1.12.2, 21 for 1.21.4. */
    public static final int MINOR;
    /** Patch version: 2 for 1.12.2, 4 for 1.21.4 (0 if absent). */
    public static final int PATCH;
    /** True before the 1.13 "flattening" (numeric block ids, MaterialData, WorldEdit 6). */
    public static final boolean LEGACY;

    static {
        String raw;
        try {
            raw = Bukkit.getBukkitVersion();
        } catch (RuntimeException e) {
            raw = null; // no server (shouldn't happen in a plugin); parse() treats it as the newest version
        }
        CURRENT = MinecraftVersion.parse(raw);
        MAJOR = CURRENT.major;
        MINOR = CURRENT.minor;
        PATCH = CURRENT.patch;
        LEGACY = CURRENT.isLegacy();
    }

    private ServerVersion() {
    }

    public static boolean isAtLeast(int major, int minor, int patch) {
        return CURRENT.isAtLeast(major, minor, patch);
    }

    public static String describe() {
        return CURRENT + (LEGACY ? " (legacy API)" : " (modern API)");
    }
}
