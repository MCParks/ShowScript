package us.mcparks.showscript;

import org.bukkit.event.EventPriority;
import org.junit.Before;
import org.junit.Test;
import us.mcparks.showscript.event.show.ShowStopEvent;
import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Region show lifecycle (setup, loop, cleanup) driven by ShowStopEvents, with the show starts and the one-time
 * listeners recorded instead of going to a server. Owner decision A9 on ryan/1-12-2-to-1-21-4#221: a setup show
 * that ends with an error doesn't start the loop; the cleanup show runs and the region goes back to idle.
 */
public class RegionShowInstanceTest {
  private static final String SETUP = "setup:region:r1";
  private static final String CLEANUP = "cleanup:region:r1";

  private RecordingInstance instance;
  private final List<String> warnings = new ArrayList<>();

  /** A one-time listener for the next stop of a show, like OneTimeUseListener. */
  private static final class Pending {
    final String showName;
    final Consumer<ShowStopEvent> action;
    final EventPriority priority;

    Pending(String showName, Consumer<ShowStopEvent> action, EventPriority priority) {
      this.showName = showName;
      this.action = action;
      this.priority = priority;
    }
  }

  private final class RecordingInstance extends RegionShowListener.RegionShowInstance {
    final List<String> calls = new ArrayList<>();
    final List<Pending> pending = new ArrayList<>();
    final Logger logger = Logger.getAnonymousLogger();

    RecordingInstance(RegionShowListener.RegionShowConfig cfg) {
      super(cfg);
      logger.setUseParentHandlers(false);
      logger.addHandler(new Handler() {
        @Override
        public void publish(LogRecord record) {
          warnings.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      });
    }

    @Override
    void onShowStop(String showName, Consumer<ShowStopEvent> action, EventPriority priority) {
      pending.add(new Pending(showName, action, priority));
    }

    @Override
    String setupShowName() {
      return SETUP;
    }

    @Override
    String cleanupShowName() {
      return CLEANUP;
    }

    @Override
    Logger logger() {
      return logger;
    }

    @Override
    void stopLoopShows() {
      calls.add("stopLoops");
    }

    @Override
    void startSetupShow() {
      calls.add("setup");
    }

    @Override
    void startCleanupShow() {
      calls.add("cleanup");
    }

    @Override
    void startLoopShows() {
      calls.add("loop");
    }

    /**
     * Fires a ShowStopEvent for {@code showName}: the listeners registered for it before the event, lowest priority
     * first, each once. Listeners registered while it is handled wait for the next stop, as with Bukkit.
     */
    void showStops(String showName, boolean error) {
      ShowScheduler show = mock(ShowScheduler.class);
      when(show.getName()).thenReturn(showName);
      when(show.endedWithError()).thenReturn(error);
      ShowStopEvent event = new ShowStopEvent(show);
      List<Pending> matching = pending.stream()
              .filter(p -> p.showName.equals(showName))
              .sorted(Comparator.comparingInt(p -> p.priority.getSlot()))
              .collect(Collectors.toList());
      pending.removeAll(matching);
      for (Pending p : matching) {
        p.action.accept(event);
      }
    }
  }

  @Before
  public void setUp() throws Exception {
    RegionShowListener.RegionShowConfig cfg = mock(RegionShowListener.RegionShowConfig.class);
    setField(cfg, "name", "test-region-show");
    setField(cfg, "region", "r1");
    instance = new RecordingInstance(cfg);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = RegionShowListener.RegionShowConfig.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test
  public void setupThatEndsNormallyStartsTheLoop() {
    instance.onPlayerEnterRegion();
    instance.showStops(SETUP, false);

    assertEquals(RegionShowListener.RegionShowInstance.Status.LOOP, instance.status);
    assertEquals(Arrays.asList("stopLoops", "setup", "loop"), instance.calls);
    assertTrue(warnings.isEmpty());
  }

  @Test
  public void setupThatEndsWithAnErrorRunsCleanupInsteadOfTheLoop() {
    instance.onPlayerEnterRegion();
    instance.showStops(SETUP, true);

    assertEquals(RegionShowListener.RegionShowInstance.Status.CLEANUP, instance.status);
    assertEquals("the loop never starts", Arrays.asList("stopLoops", "setup", "cleanup"), instance.calls);
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0), warnings.get(0).contains(SETUP) && warnings.get(0).contains("ended with an error"));

    instance.showStops(CLEANUP, false);
    assertEquals(RegionShowListener.RegionShowInstance.Status.IDLE, instance.status);
    assertEquals("nothing else runs while players stay in the region",
            Arrays.asList("stopLoops", "setup", "cleanup"), instance.calls);
  }

  @Test
  public void failedSetupAfterTheLastPlayerLeftRunsCleanupOnce() {
    instance.onPlayerEnterRegion();
    instance.onPlayerLeaveRegion(); // during setup: waits for setup to end
    instance.showStops(SETUP, true);

    assertEquals(Arrays.asList("stopLoops", "setup", "cleanup"), instance.calls);
    instance.showStops(CLEANUP, false);
    assertEquals(RegionShowListener.RegionShowInstance.Status.IDLE, instance.status);
    assertEquals(Arrays.asList("stopLoops", "setup", "cleanup"), instance.calls);
  }

  @Test
  public void regionStartsAgainWhenAPlayerEntersAfterAFailedSetup() {
    instance.onPlayerEnterRegion();
    instance.showStops(SETUP, true);
    instance.onPlayerLeaveRegion();
    instance.showStops(CLEANUP, false);

    instance.onPlayerEnterRegion();
    instance.showStops(SETUP, false);

    assertEquals(RegionShowListener.RegionShowInstance.Status.LOOP, instance.status);
    assertEquals(Arrays.asList("stopLoops", "setup", "cleanup", "stopLoops", "setup", "loop"), instance.calls);
  }

  @Test
  public void playerWhoEntersDuringTheErrorCleanupRestartsTheRegionAfterIt() {
    instance.onPlayerEnterRegion();
    instance.onPlayerLeaveRegion();
    instance.showStops(SETUP, true); // cleanup now running, nobody in the region
    instance.onPlayerEnterRegion(); // first player again: waits for the cleanup
    instance.showStops(CLEANUP, false);

    assertEquals(RegionShowListener.RegionShowInstance.Status.SETUP, instance.status);
    assertEquals(Arrays.asList("stopLoops", "setup", "cleanup", "stopLoops", "setup"), instance.calls);
  }

  @Test
  public void loopAndCleanupUnchangedWhenTheLastPlayerLeaves() {
    instance.onPlayerEnterRegion();
    instance.showStops(SETUP, false);
    instance.onPlayerLeaveRegion();
    instance.showStops(CLEANUP, false);

    assertEquals(RegionShowListener.RegionShowInstance.Status.IDLE, instance.status);
    assertEquals(Arrays.asList("stopLoops", "setup", "loop", "stopLoops", "cleanup"), instance.calls);
  }
}
