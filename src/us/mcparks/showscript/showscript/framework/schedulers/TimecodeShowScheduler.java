package us.mcparks.showscript.showscript.framework.schedulers;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import co.aikar.timings.lib.MCTiming;
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
  // Set once the show is stopped (error, normal end, or a cancel through Main). A show that is stopped
  // before its deferred registration runs is never added to Main.activeShows.
  volatile boolean stopped;
  // Set when an action threw and stopped the show; read by ShowStopEvent listeners (region shows).
  volatile boolean failed;

  TimecodeShow show;
  int timecode;

  BukkitRunnable task;
  ShowActionExecutor executor;

  // Counters used for tasks that execute over time
  int waitCounter;
  int actionCounter;

  /**
   * Guards the timeline (timecode, wait counter, the show's remaining ticks) and {@link #generation}. An async
   * show advances its timeline on the executor thread while a queued action may restart it on the main thread.
   */
  final Object timelineLock = new Object();
  /** Incremented by every {@link #restart()}; an async end queued for an older pass of the timeline is ignored. */
  int generation;

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
    // Check if timings are enabled in the configuration
    MCTiming timing = getTiming();
    if (timing != null) {
      try (MCTiming t = timing.startTiming()) {
        runShowLogic();
      }
    } else {
      runShowLogic();
    }
  }

  private void runShowLogic() {
    synchronized (timelineLock) {
      advanceTimeline();
    }
  }

  private void advanceTimeline() {
    // Increment the timecode
    timecode++;
    //System.out.println("It's timecode " + timecode + " for show " + name + " at time " + System.currentTimeMillis() + " with recursion depth " + recursionDepth);
    if (!initialized) {
      DebugLogger.log(getName(), "starting");
      init();
      initialized = true;
    }

    Integer next = show.getNextActionTick();

    if (next == null) {
      onTimelineEnd();
    } else if (next <= timecode && waitCounter == 0) {
      // Reset wait counter
      waitCounter = 0;

      // Get actions for this tick, execute each
      List<ShowAction> actions = show.getNextActions();
      for (ShowAction action : actions) {
          //System.out.println("executing " + actions.size() + " actions for timecode " + next + " at time " + timecode + " for show " + name + " with recursion depth " + recursionDepth);
          executeShowAction(action);
      }
    } else {
      setTimeToWait();
    }
  }

  @Override
  public void restart() {
    synchronized (timelineLock) {
      generation++;
      timecode = -1;
      waitCounter = 0;
      actionCounter = 0;
      show.reset();
    }
  }



  protected void executeShowAction(ShowAction action) {
    // Per-action timings disabled - creating unique timing names like
    // "ticks: X action.toString()" causes Aikar's timing library to cache
    // unlimited entries that never get GC'd, causing memory leaks.
    executeActionLogic(action);
  }
  
  protected void executeActionLogic(ShowAction action) {
    // A stopped show runs no further actions: neither the rest of the tick in which an action threw, nor
    // actions an async show had already queued on the main thread before it was stopped.
    if (stopped) {
      return;
    }
    // display if the flag is set
    if (display && sender != null) {
      sender.sendMessage(action.toString());
    }
    try {
      executor.execute(action);
    } catch (Exception e) {
      e.printStackTrace();
      if (sender != null) {
        sender.sendMessage(ChatColor.RED + "ERROR IN SHOW " + name + " at timecode " + timecode
                + "\n" +
                "Could not parse Show Action: " + action.toString());
        sender.sendMessage(String.valueOf(e.getMessage()));
      }
      // Cancel through Main (not just stopShow()) so the show also leaves activeShows and fires ShowStopEvent.
      // Otherwise it stays in /show list and isShowRunning() reports it as running forever.
      // Cancel by instance, not by id: works even before the show has its id, and only once per show.
      failed = true;
      terminate();
    }
  }

  /**
   * The timeline has no actions left (called with {@link #timelineLock} held). Synchronous shows are on the main
   * thread and stop at once; async shows override this to stop on the main thread after the actions they have
   * already queued there.
   */
  protected void onTimelineEnd() {
    terminate();
  }

  /**
   * Ends this show through {@link Main#cancelShow(ShowScheduler)}: stops its task, fires one ShowStopEvent and
   * removes it from the active shows. Idempotent: a show that is already stopped (by an earlier error, a normal
   * end or a cancel through Main) is left alone. Must run on the main thread.
   */
  protected void terminate() {
    if (stopped) {
      return;
    }
    main.cancelShow(this);
  }

  private void init() {
    if (Bukkit.isPrimaryThread()) {
      // Synchronous shows: register before the first tick's actions run, so an action that throws on
      // tick 0 can cancel the show through Main (it used to be registered one tick later).
      register();
    } else {
      // Async shows: queued on the main thread before this tick's actions, which run there too.
      runOnMainThread(this::register);
    }
  }

  private void register() {
    if (stopped) {
      return;
    }
    try {
      this.showTaskId.set(task.getTaskId());
    } catch (IllegalStateException ignore) {
      this.showTaskId.set(main.generateTaskId());
    }
    main.addActiveShow(this);
  }

  /**
   * Main-thread work of this show (async shows: registration, actions, the final stop), run strictly in the order
   * it was queued. Each call schedules a drain of the whole queue; drains that find it empty do nothing. The order
   * does not depend on how the server's scheduler orders tasks queued for the same tick.
   */
  private final ConcurrentLinkedQueue<Runnable> mainThreadWork = new ConcurrentLinkedQueue<>();

  protected void runOnMainThread(Runnable work) {
    mainThreadWork.add(work);
    Bukkit.getScheduler().runTask(main, this::drainMainThreadWork);
  }

  private void drainMainThreadWork() {
    Runnable work;
    while ((work = mainThreadWork.poll()) != null) {
      work.run();
    }
  }

  public boolean isStopped() {
    return stopped;
  }

  @Override
  public boolean endedWithError() {
    return failed;
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
    stopped = true;
    task.cancel();
  }

}
