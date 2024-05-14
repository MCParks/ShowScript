package us.mcparks.showscript.showscript.framework.schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import us.mcparks.showscript.util.DebugLogger;
import us.mcparks.showscript.util.Lag;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.executors.ShowActionExecutor;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

public class TimecodeShowScheduler implements ShowScheduler, Runnable {

  // Data about the instance of this show
  Main main;
  String name;
  boolean display;
  CommandSender sender;
  AtomicInteger showTaskId;
  long recursionDepth;
  boolean initialized;

  TimecodeShow show;
  int timecode;

  BukkitRunnable task;
  ShowActionExecutor executor;

  // Counters used for tasks that execute over time
  int waitCounter;
  int actionCounter;


  public TimecodeShowScheduler(Main main, TimecodeShow show, String name, boolean display,
                               CommandSender sender, long recursionDepth, int startAt) {
    this.main = main;
    this.name = name;
    this.display = display;
    this.sender = sender;
    this.show = show;
    if (startAt > 0) {
      timecode = startAt-1;
    } else {
      timecode = -1;
    }
    waitCounter = 0;
    actionCounter = 0;
    this.recursionDepth = recursionDepth;
    showTaskId = new AtomicInteger();
    executor = show.createExecutor(this);

    task = new BukkitRunnable() {
      @Override
      public void run() {
        TimecodeShowScheduler.this.run();
      }
    };
  }

  public BukkitRunnable getTask() {
    return task;
  }

  @Override
  public void run() {
    // this.showTaskId = this.getTaskId();
    // Increment the timecode
    timecode++;

    if (!initialized) {
      DebugLogger.log(getName(), "starting");
      init();
      initialized = true;
    }

    Integer next = show.getNextActionTick();

    if (next == null) {
      main.cancelShowById(getShowTaskId());
    } else if (next <= timecode && waitCounter == 0) {
      // Reset wait counter
      waitCounter = 0;

      // Get actions for this tick, execute each
      List<ShowAction> actions = show.getNextActions();
      for (ShowAction action : actions) {
        //System.out.println("executing " + actions.size() + " actions for timecode " + next + " at time " + timecode);
        executeShowAction(action);
      }
    } else {
      setTimeToWait();
    }

  }



  protected void executeShowAction(ShowAction action) {
    // System.out.println("EXEC: " + action.toString());
    // display if the flag is set
    if (display && sender != null) {
      sender.sendMessage(action.toString());
    }
    try {
      executor.execute(action);
    } catch (Exception e) {
      e.printStackTrace();
      sender.sendMessage(ChatColor.RED + "ERROR IN SHOW " + name + " at timecode " + timecode
          + "\n" +
              "Could not parse Show Action: " + action.toString());
      sender.sendMessage(e.getMessage());
      //sender.sendMessage();
      stopShow();
    }

  }

  private void init() {
    Bukkit.getScheduler().runTask(main, () -> {
      main.addActiveShow(this);
      try {
        this.showTaskId.set(task.getTaskId());
      } catch (IllegalStateException ignore) {
        this.showTaskId.set(main.generateTaskId());
      }
    });

  }

  protected void setTimeToWait() {
    Integer next = show.getNextActionTick();
    if (next != null) {
      if (waitCounter != 0) {
        waitCounter--;
      } else {
        int timeToWait = next - timecode;
        if (timeToWait > 9) {
          timeToWait = 10;
        }
        double lagMultiplier = Lag.getTPS() / 20;

        int finalWait = Math.round((float) (timeToWait * lagMultiplier));

        waitCounter = finalWait;
        //System.out.println("Waiting " + finalWait + " ticks, next action is at " + next + " and I have " + timeToWait + " to go. Current time: " + timecode);
      }
    }

  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public int getTimecode() {
    return timecode;
  }

  @Override
  public int getSyntaxVersion() {
    return show.getSyntaxVersion();
  }

  @Override
  public Integer getShowTaskId() {
    return showTaskId.get();
  }

  public TimecodeShow getShow() {
    return show;
  }

  public long getCurrentRecursionDepth() {
    return recursionDepth;
  }

  public Main getPlugin() {
    return main;
  }

  public CommandSender getSender() {
    return sender;
  }

  public boolean shouldDisplay() {
    return display;
  }

  public boolean isAsync() {
    return false;
  }

  @Override
  public void stopShow() {
    DebugLogger.log(getName(), "stopping");
    task.cancel();
  }

}
