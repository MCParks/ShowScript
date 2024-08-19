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


  default MCTiming getTiming() {
    return TimingManager.of(Main.getPlugin(Main.class)).of(getName());
  }


}
