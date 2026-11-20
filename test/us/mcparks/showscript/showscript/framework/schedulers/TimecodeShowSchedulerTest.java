package us.mcparks.showscript.showscript.framework.schedulers;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.event.show.ShowStartEvent;
import us.mcparks.showscript.event.show.ShowStopEvent;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.executors.ShowActionExecutor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Show lifecycle when an action throws (tracker ryan/1-12-2-to-1-21-4#54). Runs the scheduler against a mocked
 * Bukkit server, ticking it by hand the way the server's scheduler would until the task is cancelled.
 */
public class TimecodeShowSchedulerTest {
  private static final int TASK_ID = 42;

  private static Server server;
  private static BukkitScheduler scheduler;
  private static PluginManager pluginManager;
  private static final AtomicBoolean primaryThread = new AtomicBoolean(true);
  /** Tasks passed to {@code Bukkit.getScheduler().runTask(...)}, run when the test says the next tick starts. */
  private static final List<Runnable> queuedTasks = new ArrayList<>();
  private static final List<Event> events = new ArrayList<>();
  private static final List<Integer> cancelledTasks = new ArrayList<>();
  /** Order of lifecycle events and executed actions, e.g. "start", "action:a", "stop". */
  private static final List<String> timeline = new ArrayList<>();
  /** Whether each event was fired on the (mocked) main thread. */
  private static final List<Boolean> eventOnMainThread = new ArrayList<>();
  /** An action that restarts its show the first time it runs, like a looping show's startSelf. */
  private static ShowAction restarter;
  private static int restarts;
  /** Runs right after the restarter's restart(), e.g. executor ticks that interleave with the main thread. */
  private static Runnable afterRestart;

  private Main main;

  @BeforeClass
  public static void mockServer() {
    server = mock(Server.class);
    scheduler = mock(BukkitScheduler.class);
    pluginManager = mock(PluginManager.class);
    when(server.getLogger()).thenReturn(Logger.getLogger("test"));
    when(server.getName()).thenReturn("test");
    when(server.getVersion()).thenReturn("test");
    when(server.getBukkitVersion()).thenReturn("1.12.2-R0.1-SNAPSHOT");
    when(server.getScheduler()).thenReturn(scheduler);
    when(server.getPluginManager()).thenReturn(pluginManager);
    when(server.isPrimaryThread()).thenAnswer(inv -> primaryThread.get());
    BukkitTask bukkitTask = mock(BukkitTask.class);
    when(bukkitTask.getTaskId()).thenReturn(TASK_ID);
    when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(bukkitTask);
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(inv -> {
      queuedTasks.add(inv.getArgument(1));
      return mock(BukkitTask.class);
    });
    doAnswer(inv -> cancelledTasks.add(inv.getArgument(0))).when(scheduler).cancelTask(anyInt());
    doAnswer(inv -> {
      Event e = inv.getArgument(0);
      events.add(e);
      eventOnMainThread.add(primaryThread.get());
      timeline.add(e instanceof ShowStartEvent ? "start" : e instanceof ShowStopEvent ? "stop" : e.getEventName());
      return null;
    }).when(pluginManager).callEvent(any(Event.class));
    Bukkit.setServer(server);
  }

  @Before
  public void reset() throws Exception {
    primaryThread.set(true);
    queuedTasks.clear();
    events.clear();
    cancelledTasks.clear();
    timeline.clear();
    eventOnMainThread.clear();
    restarter = null;
    restarts = 0;
    afterRestart = null;
    // A Main without onEnable: real show bookkeeping (activeShows, cancelShow, stopShow), mocked server.
    main = mock(Main.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    main.activeShows = new CopyOnWriteArrayList<>();
    Field serverField = JavaPlugin.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(main, server);
  }

  /** A tick-0 action that throws must stop a synchronous show before any later action runs. */
  @Test
  public void syncShowWhoseFirstTickThrowsStopsAtOnce() {
    ShowAction failing = mock(ShowAction.class);
    ShowAction sentinel = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    TimecodeShowScheduler sched = newSyncShow(executed, failing, sentinel);

    int ticks = tickUntilStopped(sched, 40);

    assertEquals("the show stops on its first tick", 1, ticks);
    assertEquals("the failing action ran once", 1, executed.stream().filter(a -> a == failing).count());
    assertFalse("the later sentinel action never runs", executed.contains(sentinel));
    assertTrue("the show leaves activeShows", main.getActiveShows().isEmpty());
    assertEquals("ShowStartEvent fires once", 1, count(ShowStartEvent.class));
    assertEquals("ShowStopEvent fires once", 1, count(ShowStopEvent.class));
    assertTrue("the stop event says the show failed", stopEvent().endedWithError());
    assertEquals("the Bukkit task is cancelled", TASK_ID, (int) cancelledTasks.get(0));
    assertEquals("the show had its task id when it stopped", TASK_ID, (int) sched.getShowTaskId());

    // A registration queued by an older code path must not re-add the stopped show.
    queuedTasks.forEach(Runnable::run);
    assertTrue(main.getActiveShows().isEmpty());
  }

  /** The same for an action that throws at a later timecode: the show stops there, later actions don't run. */
  @Test
  public void syncShowWhoseLaterTickThrowsStopsThere() {
    ShowAction first = mock(ShowAction.class);
    ShowAction failing = mock(ShowAction.class);
    ShowAction sentinel = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, first);
    map.put(3, failing);
    map.put(8, sentinel);
    TimecodeShowScheduler sched = newSyncShow(map, executed, failing);

    tickUntilStopped(sched, 60);

    assertTrue(executed.contains(first));
    assertFalse(executed.contains(sentinel));
    assertTrue(main.getActiveShows().isEmpty());
    assertEquals(1, count(ShowStopEvent.class));
  }

  /** A show that ends normally still leaves activeShows with one ShowStopEvent. */
  @Test
  public void syncShowThatEndsNormallyIsRemovedOnce() {
    ShowAction a = mock(ShowAction.class);
    ShowAction b = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    map.put(2, b);
    TimecodeShowScheduler sched = newSyncShow(map, executed, null);

    assertEquals(1, tickOnce(sched));
    assertEquals("registered during the first tick", 1, main.getActiveShows().size());
    tickUntilStopped(sched, 60);

    assertEquals(2, executed.size());
    assertTrue(main.getActiveShows().isEmpty());
    assertEquals(1, count(ShowStartEvent.class));
    assertEquals(1, count(ShowStopEvent.class));
    assertFalse("a normal end is not an error", stopEvent().endedWithError());
  }

  /** Async shows register through the main thread; a show stopped before that never shows up in activeShows. */
  @Test
  public void asyncShowStoppedBeforeRegistrationIsNeverAdded() {
    primaryThread.set(false);
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, mock(ShowAction.class));
    AsyncTimecodeShowScheduler sched = new AsyncTimecodeShowScheduler(main, show(map, new ArrayList<>(), null), "test/async", false, null, 0, 0);
    sched.setTask(mock(java.util.concurrent.ScheduledFuture.class));

    sched.run(); // tick 0 on the executor thread: queues registration, then the actions
    assertEquals(2, queuedTasks.size());
    main.cancelShow(sched); // e.g. /show cancel before the main thread got to it
    primaryThread.set(true);
    new ArrayList<>(queuedTasks).forEach(Runnable::run);

    assertTrue(main.getActiveShows().isEmpty());
    assertEquals(0, count(ShowStartEvent.class));
  }

  /** A tick-0 throw skips the rest of that tick too: a stopped show runs no further actions. */
  @Test
  public void syncShowSkipsTheRestOfTheTickAfterAThrow() {
    ShowAction failing = mock(ShowAction.class);
    ShowAction sameTick = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, failing);
    map.put(0, sameTick);
    TimecodeShowScheduler sched = newSyncShow(map, executed, failing);

    tickUntilStopped(sched, 10);

    assertFalse(executed.contains(sameTick));
    assertTrue(main.getActiveShows().isEmpty());
    assertEquals(1, count(ShowStartEvent.class));
    assertEquals(1, count(ShowStopEvent.class));
  }

  /**
   * Review of !25: an async show with only tick-0 actions, where the executor runs two (here three) ticks before
   * the main thread gets to the queued work. The end of the timeline must not overtake the registration and the
   * queued actions: one start, then the actions, then one stop, all on the main thread.
   */
  @Test
  public void asyncShowEndDoesNotOvertakeQueuedActions() {
    ShowAction a = mock(ShowAction.class);
    ShowAction b = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    map.put(0, b);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, null, a, b);

    primaryThread.set(false);
    sched.run(); // tick 0: queues registration and both actions
    sched.run(); // tick 1: timeline over, queues the stop
    sched.run(); // tick 2: the stop is already queued, nothing more
    assertTrue("nothing happened on the main thread yet", timeline.isEmpty());
    assertFalse("the show is not stopped from the executor thread", sched.isStopped());
    assertTrue(cancelledTasks.isEmpty());

    primaryThread.set(true);
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:a", "action:b", "stop"), timeline);
    assertEquals(2, executed.size());
    assertTrue("events fire on the main thread", eventOnMainThread.stream().allMatch(Boolean::booleanValue));
    assertTrue(sched.isStopped());
    assertTrue(main.getActiveShows().isEmpty());
    verify(sched.getFuture(), times(1)).cancel(true);
  }

  /** The same with the actions spread over two executor ticks before the main thread catches up. */
  @Test
  public void asyncShowWithLaggingMainThreadKeepsOrder() {
    ShowAction a = mock(ShowAction.class);
    ShowAction b = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    map.put(1, b);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, null, a, b);

    primaryThread.set(false);
    for (int i = 0; i < 4; i++) {
      sched.run();
    }
    primaryThread.set(true);
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:a", "action:b", "stop"), timeline);
    assertTrue(main.getActiveShows().isEmpty());
  }

  /** An async show whose tick-0 action throws: queued actions after it never run, one stop on the main thread. */
  @Test
  public void asyncShowWhoseFirstActionThrowsRunsNothingQueuedAfterIt() {
    ShowAction failing = mock(ShowAction.class);
    ShowAction sameTick = mock(ShowAction.class);
    ShowAction sentinel = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, failing);
    map.put(0, sameTick);
    map.put(1, sentinel);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, failing, failing, sameTick, sentinel);

    primaryThread.set(false);
    sched.run();
    sched.run(); // tick 1's sentinel is queued before the main thread sees the failure
    primaryThread.set(true);
    drainQueuedTasks();
    primaryThread.set(false);
    sched.run(); // a late executor tick after the stop: its queued end is a no-op
    primaryThread.set(true);
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:failing", "stop"), timeline);
    assertFalse(executed.contains(sameTick));
    assertTrue("an async show's stop event says it failed", stopEvent().endedWithError());
    assertFalse(executed.contains(sentinel));
    assertTrue(main.getActiveShows().isEmpty());
    assertTrue(eventOnMainThread.stream().allMatch(Boolean::booleanValue));
  }

  /** A show cancelled through Main while its normal end is queued fires ShowStopEvent only once. */
  @Test
  public void asyncShowCancelledWhileItsEndIsQueuedStopsOnce() {
    ShowAction a = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, null, a);

    primaryThread.set(false);
    sched.run();
    primaryThread.set(true);
    drainQueuedTasks(); // registered, action ran
    primaryThread.set(false);
    sched.run(); // end queued
    primaryThread.set(true);
    assertEquals(1, main.cancelShowByName("test/async")); // /show cancel gets there first
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:a", "stop"), timeline);
    assertTrue(main.getActiveShows().isEmpty());
  }

  /**
   * A looping async show restarts itself with its last action. The executor may already have seen the end of the
   * timeline before the main thread ran that action; the queued end then sees the restart and lets the show run.
   */
  @Test
  public void asyncShowRestartedByAQueuedActionKeepsRunning() {
    ShowAction a = mock(ShowAction.class);
    restarter = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    map.put(1, restarter);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, null, a, restarter);

    primaryThread.set(false);
    sched.run(); // tick 0: a
    sched.run(); // tick 1: the restart action
    sched.run(); // tick 2: end of timeline, end queued
    primaryThread.set(true);
    drainQueuedTasks(); // a, restart, then the queued end finds the timeline restarted
    assertFalse("the restarted show keeps running", sched.isStopped());
    assertEquals(1, main.getActiveShows().size());

    primaryThread.set(false);
    for (int i = 0; i < 4; i++) {
      sched.run(); // second pass (no restart this time), then the end
    }
    primaryThread.set(true);
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:a", "action:b", "action:a", "action:b", "stop"), timeline);
    assertTrue(sched.isStopped());
    assertTrue(main.getActiveShows().isEmpty());
  }

  /**
   * Review of !25 (round 3): the end queued by the executor for the first pass runs only after the executor has
   * consumed the whole restarted timeline. The stale end must not stop the restarted show or suppress its actions;
   * the restarted pass ends with its own stop.
   */
  @Test
  public void staleAsyncEndDoesNotStopARestartedShow() {
    ShowAction a = mock(ShowAction.class);
    restarter = mock(ShowAction.class);
    List<ShowAction> executed = new ArrayList<>();
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, a);
    map.put(1, restarter);
    AsyncTimecodeShowScheduler sched = newAsyncShow(map, executed, null, a, restarter);
    // The executor runs between the restart and the first pass's queued end, and gets through the whole
    // restarted timeline (tick 0: a, tick 1: the restart action, which doesn't restart again).
    afterRestart = () -> {
      primaryThread.set(false);
      sched.run();
      sched.run();
      primaryThread.set(true);
    };

    primaryThread.set(false);
    sched.run(); // tick 0: a
    sched.run(); // tick 1: the restart action
    sched.run(); // tick 2: end of the first pass, end queued
    primaryThread.set(true);
    drainQueuedTasks(); // a, restart (+ executor ticks), stale end, then the restarted pass's a and b
    assertFalse("the stale end does not stop the restarted show", sched.isStopped());
    assertEquals(java.util.Arrays.asList("start", "action:a", "action:b", "action:a", "action:b"), timeline);
    assertEquals(1, main.getActiveShows().size());

    primaryThread.set(false);
    sched.run(); // end of the restarted pass
    primaryThread.set(true);
    drainQueuedTasks();

    assertEquals(java.util.Arrays.asList("start", "action:a", "action:b", "action:a", "action:b", "stop"), timeline);
    assertTrue(sched.isStopped());
    assertTrue(main.getActiveShows().isEmpty());
    assertEquals(1, count(ShowStopEvent.class));
    verify(sched.getFuture(), times(1)).cancel(true);
  }

  // --- helpers ---

  /** An async show whose executor records "action:<name>" in the timeline; names are a, b, ... by argument order. */
  @SuppressWarnings("unchecked")
  private AsyncTimecodeShowScheduler newAsyncShow(ListMultimap<Integer, ShowAction> map, List<ShowAction> executed,
                                                  ShowAction failing, ShowAction... named) {
    List<ShowAction> names = java.util.Arrays.asList(named);
    List<ShowAction> recorder = new ArrayList<ShowAction>() {
      @Override
      public boolean add(ShowAction action) {
        executed.add(action);
        String n = action == failing ? "failing" : String.valueOf((char) ('a' + names.indexOf(action)));
        timeline.add("action:" + n);
        return super.add(action);
      }
    };
    AsyncTimecodeShowScheduler sched = new AsyncTimecodeShowScheduler(main, show(map, recorder, failing), "test/async", false, null, 0, 0);
    sched.setTask(mock(java.util.concurrent.ScheduledFuture.class));
    return sched;
  }

  /** The main thread catches up: runs every queued task, including tasks queued while doing so. */
  private void drainQueuedTasks() {
    while (!queuedTasks.isEmpty()) {
      List<Runnable> pending = new ArrayList<>(queuedTasks);
      queuedTasks.clear();
      pending.forEach(Runnable::run);
    }
  }

  private TimecodeShowScheduler newSyncShow(List<ShowAction> executed, ShowAction failing, ShowAction sentinel) {
    ListMultimap<Integer, ShowAction> map = ArrayListMultimap.create();
    map.put(0, failing);
    map.put(5, sentinel);
    return newSyncShow(map, executed, failing);
  }

  private TimecodeShowScheduler newSyncShow(ListMultimap<Integer, ShowAction> map, List<ShowAction> executed, ShowAction failing) {
    TimecodeShowScheduler sched = new TimecodeShowScheduler(main, show(map, executed, failing), "test/show", false, null, 0, 0);
    sched.getTask().runTaskTimer(main, 0, 1); // what TimecodeShowExecutor does
    return sched;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private TimecodeShow show(ListMultimap<Integer, ShowAction> map, List<ShowAction> executed, ShowAction failing) {
    return new TimecodeShow("test/show", map, 0, 0, 3) {
      @Override
      public ShowActionExecutor createExecutor(TimecodeShowScheduler s) {
        return new ShowActionExecutor(s) {
          @Override
          public void execute(ShowAction action) throws Exception {
            executed.add(action);
            if (action == restarter && restarts++ == 0) {
              s.restart();
              if (afterRestart != null) {
                afterRestart.run();
              }
            }
            if (action == failing) {
              throw new IllegalStateException("simulated: plugin is disabled");
            }
          }
        };
      }

      @Override
      public TimecodeShow clone() {
        return this;
      }
    };
  }

  /** One server tick: tasks queued with runTask during the previous tick run first, then the show's timer. */
  private int tickOnce(TimecodeShowScheduler sched) {
    List<Runnable> pending = new ArrayList<>(queuedTasks);
    queuedTasks.clear();
    pending.forEach(Runnable::run);
    sched.run();
    return 1;
  }

  /** The first {@link ShowStopEvent} fired so far. */
  private ShowStopEvent stopEvent() {
    return (ShowStopEvent) events.stream().filter(e -> e instanceof ShowStopEvent).findFirst().get();
  }

  /** Ticks like the server would until the show's Bukkit task is cancelled. Returns the number of ticks run. */
  private int tickUntilStopped(TimecodeShowScheduler sched, int maxTicks) {
    int ticks = 0;
    while (cancelledTasks.isEmpty() && ticks < maxTicks) {
      ticks += tickOnce(sched);
    }
    queuedTasks.forEach(Runnable::run);
    queuedTasks.clear();
    assertFalse("the show's task was never cancelled", cancelledTasks.isEmpty());
    return ticks;
  }

  private long count(Class<? extends Event> type) {
    return events.stream().filter(type::isInstance).count();
  }
}
