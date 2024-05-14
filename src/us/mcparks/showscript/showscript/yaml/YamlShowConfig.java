package us.mcparks.showscript.showscript.yaml;

import com.google.common.collect.MultimapBuilder;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import us.mcparks.showscript.showscript.framework.TimecodeShowConfig;
import us.mcparks.showscript.util.DebugLogger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class YamlShowConfig extends TimecodeShowConfig {
    private File file;
    Map<String, String> macros;

    public YamlShowConfig(File f, String showName) throws Exception {
        this.file = f;
        this.showName = showName;
        FileConfiguration filez = YamlConfiguration.loadConfiguration(f);

        Set<String> keys = filez.getKeys(false);

        map = MultimapBuilder.hashKeys(keys.size()).arrayListValues().build();
        macros = new HashMap<>();
        maxRecursionDepth = 10;

        for (String key : keys) {
            if (key.equalsIgnoreCase("maxRecursionDepth")) {
                maxRecursionDepth = filez.getInt(key);
            } else if (key.equalsIgnoreCase("macros")) {
                for (String macro : filez.getConfigurationSection("macros").getKeys(false)) {
                    DebugLogger.log(showName, "setting " + macro + " to " + filez.getString("macros." + macro));

                    macros.put(macro, filez.getString("macros." + macro));
                }
            } else {
                int tick = Integer.parseInt(key.substring(key.indexOf("time")+4));
                actionTicks.add(tick);

                for (Map<?, ?> action : filez.getMapList(key)) {
                    //System.out.println("adding \n" + new ShowAction(action) + "\n to " + tick);
                    map.put(tick, new YamlShowAction(action, macros));
                }

            }

        }
    }

    public Map<String, String> getMacros() {
        return macros;
    }

    @Override
    public TimecodeShowConfig clone() {
        try {
            return new YamlShowConfig(file, showName);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public int getSyntaxVersion() {
        return 2;
    }

    @Override
    public TimecodeShow<? extends TimecodeShowConfig> createShow(long startTick, ShowArgs args) {
        return new YamlShow(this, startTick);
    }
}
