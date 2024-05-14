package us.mcparks.showscript;

import cloud.commandframework.annotations.AnnotationParser;
import cloud.commandframework.arguments.parser.StandardParameters;
import cloud.commandframework.bukkit.CloudBukkitCapabilities;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.meta.SimpleCommandMeta;
import cloud.commandframework.minecraft.extras.AudienceProvider;
import cloud.commandframework.paper.PaperCommandManager;

import java.io.File;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import us.mcparks.showscript.event.region.RegionListener;
import us.mcparks.showscript.event.show.ShowStartEvent;
import us.mcparks.showscript.event.show.ShowStopEvent;
import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;
import us.mcparks.showscript.showscript.framework.TimecodeShowExecutor;
import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;
import us.mcparks.showscript.util.DebugLogger;
import us.mcparks.showscript.util.Lag;
import us.mcparks.showscript.util.collection.AsynchronouslyRefreshingSupplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin implements Listener {
  public static PaperCommandManager<CommandSender> cloudCommandManager;
  public static AnnotationParser<CommandSender> cloudAnnotationParser;
  public static BukkitAudiences audiences;
  public static AudienceProvider<CommandSender> commandSenderAudienceProvider;
  public File rbFolder = new File(getRealDataFolder(), "Rebuilds");
  public File fs = new File(getRealDataFolder(), "Shows");
  public TimecodeShowExecutor timecodeExecutor;
  public List<ShowScheduler> activeShows = new CopyOnWriteArrayList<>();
  private RegionShowListener regionShowListener;

  private RegionListener regionListener;

  public static Supplier<List<String>> showFileNames = new AsynchronouslyRefreshingSupplier<>(() -> {
    File fs = new File(Main.getPlugin(Main.class).getRealDataFolder(), "Shows");
    if (!fs.exists()) {
      fs.mkdir();
    }
    List<String> showFiles = new ArrayList<>();
    searchFilesRecursively(fs, fs, showFiles);
    return showFiles;
  }, 30, TimeUnit.SECONDS);

  private static void searchFilesRecursively(File root, File directory, List<String> accumulator) {
    File[] files = directory.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isDirectory()) {
          searchFilesRecursively(root, file, accumulator);
        } else {
          if (file.getName().endsWith(".yml") || file.getName().endsWith(".groovy") || file.getName().endsWith(".toaster")) {
            // add filepath relative to fs to accumulator (without file extension)
            accumulator.add(file.getPath().substring(root.getPath().length() + 1, file.getPath().lastIndexOf('.')));
          }
        }
      }
    }
  }

  @Override
  public void onEnable() {
    DebugLogger.setLogging(true);
    setupCloud();

    // Internally, this plugin also handles a legacy command. Ask Ryan for the story on why.
    /* // <INTERNAL>
    this.rbFolder.mkdir();
    getCommand("rb").setExecutor(new Rebuild());
    // </INTERNAL> */
    Shows commandHandler = new Shows(this, this);
    cloudAnnotationParser.parse(commandHandler);

    getRealDataFolder().mkdir();
    timecodeExecutor = new TimecodeShowExecutor(this);

    // start RegionListener if WorldGuard is present
    Plugin worldGuard = Bukkit.getServer().getPluginManager().getPlugin("WorldGuard");
    if (worldGuard != null) {
        regionListener = new RegionListener((WorldGuardPlugin) worldGuard);
    } else {
        getLogger().warning("WorldGuard not found. Region Shows will not function.");
    }

    regionShowListener = new RegionShowListener(this);
    cloudAnnotationParser.parse(regionShowListener);
    Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Lag(),
        100L, 1L);


    // We evaluate _something_ to instantiate a GroovyShell now so it's cached before shows start running
    GroovyShowConfig.evaluator.evaluateExpression("println 'Hello, World! Warming up the Groovy engine.'");

  }

  @Override
  public void onDisable() {
    regionShowListener.unloadRegionShows();
  }

  private void setupCloud() {

    try {
      cloudCommandManager = new PaperCommandManager<>(
              this,
              CommandExecutionCoordinator.simpleCoordinator(),
              Function.identity(),
              Function.identity()
      );
    } catch (Exception e) {
      getLogger().severe("Could not initialize cloud commands!");
      e.printStackTrace();
    }

    if (cloudCommandManager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
      cloudCommandManager.registerAsynchronousCompletions();
    } else {
      System.out.println("no async completion capability");
    }

    cloudAnnotationParser = new AnnotationParser<>(
            cloudCommandManager,
            CommandSender.class,
            parameters -> SimpleCommandMeta.builder()
                    .with("description", parameters.get(StandardParameters.DESCRIPTION, "")).build()
    );

    audiences = BukkitAudiences.create(this);
    commandSenderAudienceProvider = commandSender -> audiences.sender(commandSender);

  }




  public void addActiveShow(ShowScheduler show) {
    activeShows.add(show);
    getServer().getPluginManager().callEvent(new ShowStartEvent(show));
  }

  /**
   * Cancel shows given a show name.
   * @param name Name of show to cancel (case sensitive).
   * @return The number of shows cancelled.
   */
  public int cancelShowByName(String name) {
    int count = 0;
    List<ShowScheduler> toKill = new ArrayList<>();
    for (ShowScheduler show : activeShows) {
      if (show.getName().equals(name)) {
        stopShow(show);
        toKill.add(show);
        count++;
      }
    }
    for (ShowScheduler show : toKill) {
      activeShows.remove(show);
    }
    return count;
  }

  public int cancelShowById(int id) {
    int count = 0;
    List<ShowScheduler> toKill = new ArrayList<>();
    for (ShowScheduler show : activeShows) {
      if (show.getShowTaskId().intValue() == id) {
        stopShow(show);
        toKill.add(show);
        count++;
      }
    }
    for (ShowScheduler show : toKill) {
      activeShows.remove(show);
    }
    return count;
  }

  public void stopShow(ShowScheduler show) {
    show.stopShow();
    getServer().getPluginManager().callEvent(new ShowStopEvent(show));
  }

  public int cancelAllShows() {
    int count = 0;
    for (ShowScheduler show : activeShows) {
      stopShow(show);
      count++;
    }
    activeShows.clear();
    return count;
  }

  public int generateTaskId() {
    Random random = new Random();
    int val;
    boolean idTaken;
    do {
      int rand = random.nextInt() * -1;
      val = rand;
      idTaken = getActiveShows().stream().anyMatch(s -> s.getShowTaskId() == rand);
    } while (idTaken);

    return val;

  }

  public List<ShowScheduler> getActiveShows() {
    return activeShows;
  }

  // The internal MCParks version of the plugin uses an old location for the data folder.
  public File getRealDataFolder() {
    File realDataFolder;
    realDataFolder = getDataFolder();
      /* // <INTERNAL>
    realDataFolder = new File(getDataFolder(), "../ShowsRebuild");
      // </INTERNAL> */

    return realDataFolder;
  }
}
