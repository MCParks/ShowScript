package us.mcparks.showscript;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

import cloud.commandframework.annotations.Argument;
import cloud.commandframework.annotations.CommandMethod;
import cloud.commandframework.annotations.CommandPermission;
import cloud.commandframework.annotations.Flag;
import cloud.commandframework.annotations.parsers.Parser;
import cloud.commandframework.annotations.suggestions.Suggestions;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.execution.preprocessor.CommandPreprocessingContext;
import cloud.commandframework.execution.preprocessor.CommandPreprocessor;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;
/* // <INTERNAL>
import us.mcparks.showscript.showscript.toaster.ShowSched;
// </INTERNAL> */
import us.mcparks.showscript.util.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.NonNull;

public class Shows {

  public static Plugin plugin;
  public static Main main;

  public Shows(Plugin pl, Main m) {
    Shows.plugin = pl;
    Shows.main = m;
    Main.cloudCommandManager.registerCommandPreProcessor(new LegacyLogActionsArgumentPreprocessor<>());
  }

  @CommandMethod("show toggledebug")
  @CommandPermission("castmember")
  public void toggleDebug(CommandSender sender) {
    DebugLogger.toggleLogging();
    sender.sendMessage("Console debug has been set to: " + DebugLogger.isEnabled());
  }

  @CommandMethod("show config reload")
  @CommandPermission("castmember")
  public void reloadConfig(CommandSender sender) {
    main.getConfiguration().loadConfig();
    
    // Update settings from newly loaded config
    DebugLogger.setLogging(main.getConfiguration().isDebugLoggingEnabled());
    
    sender.sendMessage(ChatColor.GREEN + "Configuration reloaded successfully!");
  }
  
  @CommandMethod("show config get <key>")
  @CommandPermission("castmember") 
  public void getConfigValue(CommandSender sender, @Argument(value="key", suggestions="configKeys") String key) {
    if (key.equalsIgnoreCase("debug-logging")) {
      sender.sendMessage(ChatColor.GREEN + "debug-logging: " + 
          ChatColor.AQUA + main.getConfiguration().isDebugLoggingEnabled());
    } else if (key.equalsIgnoreCase("enable-timings")) {
      sender.sendMessage(ChatColor.GREEN + "enable-timings: " + 
          ChatColor.AQUA + main.getConfiguration().areTimingsEnabled());
    } else {
      sender.sendMessage(ChatColor.RED + "Unknown configuration key: " + key);
    }
  }
  
  @CommandMethod("show config set <key> <value>")
  @CommandPermission("castmember")
  public void setConfigValue(CommandSender sender, @Argument(value="key", suggestions="configKeys") String key, @Argument("value") String value) {
    boolean boolValue;
    
    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
      sender.sendMessage(ChatColor.RED + "Value must be 'true' or 'false'");
      return;
    }
    
    boolValue = Boolean.parseBoolean(value);
    
    if (key.equalsIgnoreCase("debug-logging")) {
      main.getConfiguration().setDebugLoggingEnabled(boolValue);
      DebugLogger.setLogging(boolValue);
      sender.sendMessage(ChatColor.GREEN + "debug-logging set to: " + ChatColor.AQUA + boolValue);
    } else if (key.equalsIgnoreCase("enable-timings")) {
      main.getConfiguration().setTimingsEnabled(boolValue);
      sender.sendMessage(ChatColor.GREEN + "enable-timings set to: " + ChatColor.AQUA + boolValue);
      sender.sendMessage(ChatColor.YELLOW + "Note: Changes to timings will take effect after server restart");
    } else {
      sender.sendMessage(ChatColor.RED + "Unknown configuration key: " + key);
    }
  }
  
  @Suggestions("configKeys")
  public List<String> configKeyNames(CommandContext<CommandSender> sender, String input) {
    List<String> keys = new ArrayList<>();
    keys.add("debug-logging");
    keys.add("enable-timings");
    return keys.stream().filter(s -> s.startsWith(input.toLowerCase())).collect(Collectors.toList());
  }

  @CommandMethod("show stopall")
  @CommandPermission("castmember")
  public void stopAllShows(CommandSender sender) {
    if (sender instanceof Player) {
      int count = main.cancelAllShows();
      sender.sendMessage(ChatColor.AQUA + "" + count + ChatColor.GREEN
                + " shows stopped.");
    }
  }

    @CommandMethod("show stop <showName>")
    @CommandPermission("castmember")
    public void stopShow(CommandSender sender, @Argument(value = "showName", suggestions = "runningShows") String showName) {

      int count = main.cancelShowByName(showName);
      sender.sendMessage(ChatColor.AQUA + "" + count + ChatColor.GREEN
              + " instances of show " + ChatColor.AQUA + showName + ChatColor.GREEN
              + " cancelled.");
    }

    @CommandMethod("show list")
    @CommandPermission("castmember")
    public void listShows(CommandSender sender) {
      List<ShowScheduler> shows = main.getActiveShows();
      sender.sendMessage(ChatColor.AQUA + "" + shows.size() + ChatColor.GREEN + " shows: ");
      for (ShowScheduler show : shows) {
        sender.sendMessage(ChatColor.AQUA + show.getName() + ChatColor.GRAY + " - " + ChatColor.GREEN + "Syntax Version: " + show.getSyntaxVersion());
      }
    }

  @CommandMethod("show start <showName>")
  @CommandPermission("castmember")
  public void startShowCommand(CommandSender sender,
                               @Flag(value = "log") boolean logActions,
                               @Flag(value="async") boolean async,
                               @Flag(value="startAt") Integer startAt,
                               @Flag(value="args", parserName = "showArguments") String args,
                               @Argument(value = "showName", suggestions = "showNames") String showName) {
    if (startAt == null) {
      startAt = 0;
    }

    startShow(showName, sender, logActions, async, startAt, new ShowArgs(args));


  }


  @CommandMethod("show startasync <showName> [logActions]")
  @CommandPermission("castmember")
  public void startShowAsyncCommand(CommandSender sender,
                             @Argument(value = "showName", suggestions = "showNames") String showName,
                             @Argument(value = "logActions", defaultValue = "false") boolean logActions) {
    startShow(showName, sender, logActions, true, 0, null);
  }

  @CommandMethod("show startat <showName> <logActions> <startAt>")
  @CommandPermission("castmember")
  public void startShowAtCommand(CommandSender sender,
                                 @Argument(value = "showName", suggestions = "showNames") String showName,
                                 @Argument("logActions") boolean logActions,
                                 @Argument("startAt") int startAt) {
    startShow(showName, sender, logActions, false, startAt, null);
  }

  /**
   * Command auto-completion for all show names
   * @param sender
   * @param input
   * @return
   */
  @Suggestions("showNames")
  public List<String> showNames(CommandContext<CommandSender> sender, String input) {
    if (input.isEmpty()) {
      return new ArrayList<>();
    }

    return main.showFileNames.get().stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
  }

  @Suggestions("showDirectoryNames")
  public List<String> showDirectories(CommandContext<CommandSender> sender, String input) {
    if (input.isEmpty()) {
      return new ArrayList<>();
    }

    return main.showFileNames.get().stream().map(path -> path.lastIndexOf("/") == -1 ? path : path.substring(0, path.lastIndexOf("/"))).distinct().filter(s -> s.startsWith(input)).collect(Collectors.toList());
  }

  public static String getShowNameFromAbsolutePath(String absolutePath) {
    return absolutePath.substring(Main.getPlugin(Main.class).fs.getAbsolutePath().toString().length()+1);
  }

  @Parser(name = "showArguments")
  public String parseShowArguments(CommandContext<CommandSender> sender, Queue<String> input) {
    StringBuilder sb = new StringBuilder();
    while (!input.isEmpty()) {
      sb.append(input.poll());
      sb.append(" ");
    }
    return sb.toString().trim();
  }



  /**
   * For compatibility with legacy command syntax `/show start <showName> <logActions>`, convert
   * `/show start <showName> true` to `/show start <showName> --log`
   * and `/show start <showName> false` to `/show start <showName>`
   * @param <C>
   */
  static class LegacyLogActionsArgumentPreprocessor<C> implements CommandPreprocessor<C> {

    @Override
    public void accept(@NonNull CommandPreprocessingContext<C> context) {
      List<String> input = context.getInputQueue();
      if (input.size() == 4) {
        if (input.get(0).equalsIgnoreCase("show") && input.get(1).equalsIgnoreCase("start")) {
          if (input.get(3).equalsIgnoreCase("true")) {
            input.set(3, "--log");
          } else if (input.get(3).equalsIgnoreCase("false")) {
            input.remove(3);
          }
        }
      }
    }
  }

  /**
   * Command auto-completion for running shows
   * @param sender
   * @param input
   * @return
   */
  @Suggestions("runningShows")
  public List<String> runningShows(CommandContext<CommandSender> sender, String input) {
    return main.getActiveShows().stream().map(ShowScheduler::getName).filter(s -> s.startsWith(input)).collect(Collectors.toList());
  }

  public void startShow(String name, CommandSender p, boolean display, boolean async, int timecode, ShowArgs args) {

    String log = "";
    if (p instanceof Player) {
      log += "Player " + p.getName() + " ";
    } else if (p instanceof BlockCommandSender) {
      BlockCommandSender b = (BlockCommandSender) p;
      log += "CommandBlock at " + b.getBlock().getX() + ", " + b.getBlock().getY() + ", " + b.getBlock().getZ() + " ";
    } else if (p instanceof ConsoleCommandSender) {
      log += "Console ";
    }

    log += "is starting show " + name;
    if (async) {
      log += " asynchronously";
    }
    if (timecode > 0) {
      log += " at timecode " + timecode;
    }

    Bukkit.getLogger().info(log);

    File groovyFile = new File(main.fs, name + ".groovy");
    File yamlFile = new File(main.fs, name + ".yml");
    if (groovyFile.exists() || yamlFile.exists()) {
      main.timecodeExecutor.startShowAt(name, p, display, 0, timecode, async, args);
    } else {
      startToasterShow(name, p, display);
    }



  }

  // In our internal version of ShowScript, this method is used to invoke the parser for a reaaaally old format of show
  // files that MCParks used while we were partnered with mcamusement. This old show plugin was built by mr_toaster111,
  // so the file extension is .toaster!
  private void startToasterShow(String name, CommandSender p, boolean display) {
    File file = new File(main.fs, name + ".toaster");
    FileConfiguration filez;
    if (!file.exists()) {
      p.sendMessage(ChatColor.RED + "ERROR:\n");
      p.sendMessage("File does not exist!");
      return;
    }

    try {
      filez = YamlConfiguration.loadConfiguration(file);
    } catch (Exception x) {
      p.sendMessage(ChatColor.RED + "ERROR:\n");
      p.sendMessage(x.getMessage());
      return;
    }

    Queue<String> showContents;
    showContents = new LinkedList<String>();

    for (String str : filez.getKeys(false)) {
      if (str.equals("counter")) {
        //
      } else {
        showContents.add(str);
      }
    }
    /* // <INTERNAL>
    ShowSched ss = new ShowSched(plugin, main, showContents, name, display, p);
    ss.runTaskTimer(plugin, 1, 1);
    // </INTERNAL> */
  }

  /* // <INTERNAL>
  public void convertShow(String name) {
    FileConfiguration filez;
    FileConfiguration newFilez;
    File file = new File(main.fs, name + ".toaster");
    File newFile = new File(main.fs, name + ".yml");
    if (!newFile.exists()) {
      try {
        newFile.createNewFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    try {
      filez = YamlConfiguration.loadConfiguration(file);
      newFilez = YamlConfiguration.loadConfiguration(newFile);
    } catch (Exception x) {
      x.printStackTrace();
      return;
    }

    int tick = 0;
    for (String str : filez.getKeys(false)) {
      if (!str.equals("counter")) {
        String actionType = str.replaceAll("\\d", "");
        Map<String, Object> action = new HashMap<>();

        if (actionType.equalsIgnoreCase("cmd")) {
          action.put("item", "cmd");
          action.put("cmd", filez.getString(str + ".cmd"));
        } else if (actionType.equalsIgnoreCase("build")) {
          action.put("item", "rebuild");
          action.put("name", filez.getString(str + ".name"));
        } else if (actionType.equalsIgnoreCase("text")) {
          action.put("item", "text");
          action.put("text", filez.getString(str + ".text"));
          action.put("x", Double.parseDouble(filez.getString(str + ".x")));
          action.put("y", Double.parseDouble(filez.getString(str + ".y")));
          action.put("z", Double.parseDouble(filez.getString(str + ".z")));
          action.put("range", Double.parseDouble(filez.getString(str + ".range")));
          action.put("world", filez.getString(str + ".world"));
        }

        if (actionType.equalsIgnoreCase("wait")) {
          tick+=Integer.parseInt(filez.getString(str));
        } else {
          tick++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(action);
        if (action.size() > 0) {
          newFilez.set("time" + tick, list);
        }

      }
    }

    try {
      newFilez.save(newFile);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  // </INTERNAL> */

}