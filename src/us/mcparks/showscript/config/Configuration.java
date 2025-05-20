package us.mcparks.showscript.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Configuration {
    
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;
    
    // Configuration keys
    private static final String DEBUG_LOGGING_KEY = "debug-logging";
    private static final String ENABLE_TIMINGS_KEY = "enable-timings";
    
    public Configuration(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }
    
    /**
     * Loads/reloads the configuration from disk
     */
    public void loadConfig() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), "config.yml");
        }
        
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // Set defaults if they don't exist
        setDefaults();
    }
    
    /**
     * Sets default values for configuration options if they don't exist
     */
    private void setDefaults() {
        boolean saveNeeded = false;
        
        // Debug Logging - default: true
        if (!config.contains(DEBUG_LOGGING_KEY)) {
            config.set(DEBUG_LOGGING_KEY, true);
            saveNeeded = true;
        }
        
        // Enable Timings - default: true
        if (!config.contains(ENABLE_TIMINGS_KEY)) {
            config.set(ENABLE_TIMINGS_KEY, true);
            saveNeeded = true;
        }
        
        if (saveNeeded) {
            saveConfig();
        }
    }
    
    /**
     * Saves the configuration to disk
     */
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save config to " + configFile);
            ex.printStackTrace();
        }
    }
    
    /**
     * Gets whether debug logging is enabled
     * @return true if debug logging is enabled
     */
    public boolean isDebugLoggingEnabled() {
        return config.getBoolean(DEBUG_LOGGING_KEY, true);
    }
    
    /**
     * Sets whether debug logging is enabled
     * @param enabled true to enable debug logging
     */
    public void setDebugLoggingEnabled(boolean enabled) {
        config.set(DEBUG_LOGGING_KEY, enabled);
        saveConfig();
    }
    
    /**
     * Gets whether timings are enabled
     * @return true if timings are enabled
     */
    public boolean areTimingsEnabled() {
        return config.getBoolean(ENABLE_TIMINGS_KEY, true);
    }
    
    /**
     * Sets whether timings are enabled
     * @param enabled true to enable timings
     */
    public void setTimingsEnabled(boolean enabled) {
        config.set(ENABLE_TIMINGS_KEY, enabled);
        saveConfig();
    }
}