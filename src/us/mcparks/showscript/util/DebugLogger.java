package us.mcparks.showscript.util;

import org.bukkit.Bukkit;

public class DebugLogger {
    static boolean enabled;


    public static void log(String showName, String message) {
        if (enabled) {
            Bukkit.getLogger().info("[show " + showName + "] " + message);
        }
    }

    public static void log(String message) {

    }

    public static void toggleLogging() {
        enabled = !enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setLogging(boolean enabled) {
        DebugLogger.enabled = enabled;
    }
}
