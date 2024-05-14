package us.mcparks.showscript.showscript.framework.schedulers;

public interface ShowScheduler extends Runnable {
  public String getName();

  public int getSyntaxVersion();

  public Integer getShowTaskId();

  public void stopShow();

  public int getTimecode();


}
