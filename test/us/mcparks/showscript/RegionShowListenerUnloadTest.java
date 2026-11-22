package us.mcparks.showscript;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/**
 * Unloading region shows (reload and plugin disable). Removing entries from the region map while iterating it threw a
 * ConcurrentModificationException after the first show, on every server stop (ryan/1-12-2-to-1-21-4#267).
 */
public class RegionShowListenerUnloadTest {
  private final List<String> stopped = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private TestListener listener;

  private final class TestInstance extends RegionShowListener.RegionShowInstance {
    final boolean failOnStop;

    TestInstance(String name, String region, boolean failOnStop) throws Exception {
      super(config(name, region));
      this.failOnStop = failOnStop;
    }

    @Override
    public void stop(boolean force) {
      stopped.add(cfg.name + (force ? " force" : ""));
      if (failOnStop) {
        throw new IllegalStateException("stop failed");
      }
    }
  }

  /** Loads two regions: r1 with two shows (one from a multi-region schema), r2 with one, plus an ignored schema. */
  private final class TestListener extends RegionShowListener {
    int loads;
    boolean failFirstStop;
    final Logger logger = Logger.getAnonymousLogger();

    TestListener() {
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
    void loadRegionShows() {
      loads++;
      try {
        put("r1", new TestInstance("load" + loads + "/a", "r1", failFirstStop), "/shows/a_regionshowschema.yml");
        put("r1", new TestInstance("load" + loads + "/b", "r1", false), "/shows/b_regionshowschema.yml");
        put("r2", new TestInstance("load" + loads + "/b", "r2", false), "/shows/b_regionshowschema.yml");
        ignoredSchemas.put("r3", new TestInstance("load" + loads + "/ignored", "r3", false));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    private void put(String region, RegionShowInstance instance, String schema) {
      regionMap.put(region, instance);
      schemaRegionMap.put(schema, region);
    }

    @Override
    Logger logger() {
      return logger;
    }
  }

  private static RegionShowListener.RegionShowConfig config(String name, String region) throws Exception {
    RegionShowListener.RegionShowConfig cfg = mock(RegionShowListener.RegionShowConfig.class);
    setField(cfg, "name", name);
    setField(cfg, "region", region);
    return cfg;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = RegionShowListener.RegionShowConfig.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Before
  public void setUp() {
    listener = new TestListener();
    listener.loadRegionShows();
  }

  private List<String> loadedNames() {
    List<String> names = new ArrayList<>();
    for (RegionShowListener.RegionShowInstance instance : listener.regionMap.values()) {
      names.add(instance.cfg.name);
    }
    names.sort(null);
    return names;
  }

  @Test
  public void unloadStopsEveryRegionShowAndEmptiesTheMaps() {
    listener.unloadRegionShows();

    assertEquals(new HashSet<>(Arrays.asList("load1/a force", "load1/b force")), new HashSet<>(stopped));
    assertEquals("each show is stopped once", 3, stopped.size());
    assertTrue(listener.regionMap.isEmpty());
    assertTrue(listener.schemaRegionMap.isEmpty());
    assertTrue(listener.ignoredSchemas.isEmpty());
    assertTrue(warnings.isEmpty());
  }

  @Test
  public void reloadStopsTheOldShowsAndLoadsAllRegionShowsAgain() {
    listener.reloadRegionShows();

    assertEquals(3, stopped.size());
    assertEquals(2, listener.loads);
    assertEquals(Arrays.asList("load2/a", "load2/b", "load2/b"), loadedNames());
    assertEquals(2, listener.regionMap.get("r1").size());
    assertEquals(1, listener.regionMap.get("r2").size());
    assertEquals("schema b runs in both regions", 3, listener.schemaRegionMap.size());
    assertEquals("ignored schemas aren't listed twice", 1, listener.ignoredSchemas.size());
  }

  @Test
  public void aShowThatFailsToStopDoesNotKeepTheOthersRunning() throws Exception {
    listener = new TestListener();
    listener.failFirstStop = true;
    listener.loadRegionShows();
    stopped.clear();

    listener.reloadRegionShows();

    assertEquals("all three old shows are stopped", 3, stopped.size());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0), warnings.get(0).contains("load1/a") && warnings.get(0).contains("r1"));
    assertEquals(Arrays.asList("load2/a", "load2/b", "load2/b"), loadedNames());
  }

  @Test
  public void shutdownStartsNothingAndLeavesEveryShowIdle() {
    List<RegionShowListener.RegionShowInstance> instances = new ArrayList<>(listener.regionMap.values());
    for (RegionShowListener.RegionShowInstance instance : instances) {
      instance.status = RegionShowListener.RegionShowInstance.Status.LOOP;
    }

    listener.shutdownRegionShows();

    assertTrue("no cleanup show is started on a disabled plugin", stopped.isEmpty());
    for (RegionShowListener.RegionShowInstance instance : instances) {
      assertEquals(RegionShowListener.RegionShowInstance.Status.IDLE, instance.status);
    }
    assertTrue(listener.regionMap.isEmpty());
    assertTrue(listener.schemaRegionMap.isEmpty());
    assertTrue(listener.ignoredSchemas.isEmpty());
  }
}
