package us.mcparks.showscript.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Minecraft/Bukkit version (major.minor.patch). Has no Bukkit dependency, so it can be unit-tested.
 *
 * <p>Handles both the classic {@code 1.x.y} scheme and the year-based scheme Mojang adopted after 1.21
 * ({@code 26.1}, {@code 26.1.2}, ...). Anything with a major version above 1 is newer than every 1.x release.
 */
public final class MinecraftVersion implements Comparable<MinecraftVersion> {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public final int major;
    public final int minor;
    public final int patch;

    public MinecraftVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * Parses a Bukkit version string such as {@code 1.21.4-R0.1-SNAPSHOT} or {@code 26.1-R0.1-SNAPSHOT}.
     * An unrecognised string is treated as a version newer than anything known, because new formats only come
     * from newer servers.
     */
    public static MinecraftVersion parse(String bukkitVersion) {
        Matcher m = VERSION.matcher(bukkitVersion == null ? "" : bukkitVersion.trim());
        if (!m.find()) {
            return new MinecraftVersion(Integer.MAX_VALUE, 0, 0);
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            return new MinecraftVersion(major, minor, patch);
        } catch (NumberFormatException e) { // absurdly long digit runs
            return new MinecraftVersion(Integer.MAX_VALUE, 0, 0);
        }
    }

    /** True before the 1.13 "flattening" (numeric block ids, MaterialData, WorldEdit 6). */
    public boolean isLegacy() {
        return major == 1 && minor < 13;
    }

    public boolean isAtLeast(int major, int minor, int patch) {
        return compareTo(new MinecraftVersion(major, minor, patch)) >= 0;
    }

    @Override
    public int compareTo(MinecraftVersion o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        return Integer.compare(patch, o.patch);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MinecraftVersion && compareTo((MinecraftVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return (major * 31 + minor) * 31 + patch;
    }

    @Override
    public String toString() {
        return major == Integer.MAX_VALUE ? "unknown (assumed newest)" : major + "." + minor + "." + patch;
    }
}
