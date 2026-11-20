package us.mcparks.showscript.showscript.framework.schedulers;

import co.aikar.timings.lib.MCTiming;
import co.aikar.timings.lib.TimingManager;
import us.mcparks.showscript.Main;

public interface ShowScheduler extends Runnable {
  public String getName();

  public int getSyntaxVersion();

  public Integer getShowTaskId();

  public void stopShow();

  public int getTimecode();

  public void restart();

  /** True if the show was stopped because one of its actions threw. A normal end or a cancel returns false. */
  default boolean endedWithError() {
    return false;
  }


  default MCTiming getTiming() {
    if (Main.timingManager != null) {
      return TimingManager.of(Main.getPlugin(Main.class)).of(getName());
    }
    return null;
  }


}
