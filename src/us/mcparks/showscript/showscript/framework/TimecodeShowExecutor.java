package us.mcparks.showscript.showscript.framework;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.showscript.framework.schedulers.AsyncTimecodeShowScheduler;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;
import us.mcparks.showscript.showscript.yaml.YamlShowConfig;

import org.apache.commons.io.FileUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.md_5.bungee.api.ChatColor;

public class TimecodeShowExecutor implements CommandExecutor {
  Main main;
  public static File showDirectory;

  Cache<Long, TimecodeShowConfig> showCache = CacheBuilder.newBuilder()
          .maximumSize(200)
          .expireAfterAccess(1, TimeUnit.HOURS)
          .build();

  ScheduledExecutorService executor = Executors.newScheduledThreadPool(100, new ThreadFactoryBuilder().setNameFormat("showscript-executor-%d").setDaemon(true).build());

  public TimecodeShowExecutor(Main plugin) {
    this.main = plugin;
    showDirectory = plugin.fs;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String lbl,
      String[] args) {

    return false;
  }

  public int startShowAt(String name, CommandSender sender, boolean display, long recursionDepth, int timecode, boolean async, ShowArgs args) {

    try {
      TimecodeShowConfig cfg = getShowConfig(name, args);
      if (cfg == null) {
        sender.sendMessage(ChatColor.RED + "That show doesn't exist!");
        return -1;
      } else {
        return startShowAt(cfg, sender, display, recursionDepth, timecode, async, args);
      }

    } catch(Exception x) {
      sender.sendMessage(ChatColor.RED + "Error loading show configuration: \n" + x.getMessage());
      x.printStackTrace();
      return -1;
    }


  }

  public TimecodeShowConfig getShowConfig(String name, ShowArgs args) throws Exception {

    File file = new File(showDirectory, name + ".groovy");
    TimecodeShowConfig cfg = null;
    if (!file.exists()) {
      file = new File(showDirectory, name + ".yml");

      if (!file.exists()) {
        return null;
      }

      long checksum = FileUtils.checksumCRC32(file);
      cfg = showCache.getIfPresent(checksum);
      if (cfg == null) {
        cfg = new YamlShowConfig(file, name);
        showCache.put(checksum, cfg);
      }
    } else {
      cfg = new GroovyShowConfig(file, name, args);
    }

    return cfg;
  }

  public int startShow(TimecodeShowConfig cfg, CommandSender sender, boolean display, long recursionDepth, boolean async, ShowArgs args) throws Exception {
    return startShowAt(cfg, sender, display, recursionDepth, 0, async, args);
  }

  public int startShow(TimecodeShow show, CommandSender sender, boolean display, long recursionDepth, boolean async) throws Exception {
    return startShowAt(show, sender, display, recursionDepth, 0, async);
  }

  public int startShowAt(TimecodeShowConfig cfg, CommandSender sender, boolean display, long recursionDepth, int timecode, boolean async, ShowArgs args) throws Exception {
    TimecodeShow show = cfg.createShow(timecode, args);
    return startShowAt(show, sender, display, recursionDepth, timecode, async);
  }

  public int startShowAt(TimecodeShow show, CommandSender sender, boolean display, long recursionDepth, int timecode, boolean async) throws Exception {
    show = show.clone();
    if (async) {
      AsyncTimecodeShowScheduler sched = new AsyncTimecodeShowScheduler(main, show, show.getShowName(), display, sender, recursionDepth, timecode);
      ScheduledFuture<?> task = executor.scheduleAtFixedRate(sched, 0, 50, TimeUnit.MILLISECONDS);
      sched.setTask(task);
      System.out.println("Starting show in exectutor");
      return sched.getShowTaskId();
    } else {
      TimecodeShowScheduler sched = new TimecodeShowScheduler(main, show, show.getShowName(), display, sender, recursionDepth, timecode);
      sched.getTask().runTaskTimer(main, 0, 1);
      return sched.getShowTaskId();
    }
  }


}
