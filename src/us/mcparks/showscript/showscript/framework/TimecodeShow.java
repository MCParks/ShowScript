package us.mcparks.showscript.showscript.framework;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.executors.ShowActionExecutor;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;

import java.util.*;
import java.util.stream.Collectors;

public abstract class TimecodeShow<T extends TimecodeShowConfig> implements Cloneable {

  protected ListMultimap<Integer, ShowAction> map;
  protected PriorityQueue<Integer> actionTicks = new PriorityQueue<Integer>();
  protected long maxRecursionDepth;
  int syntaxVersion;
  protected String name;

  public TimecodeShow(T cfg, long startTick) {
    this(cfg.getShowName(), ArrayListMultimap.create(cfg.getMap()), cfg.getMaxRecursionDepth(), startTick, cfg.getSyntaxVersion());
  }

  public TimecodeShow(String name, ListMultimap<Integer, ShowAction> map, long maxRecursionDepth, long startTick, int syntaxVersion) {
    this.map = map;
    this.maxRecursionDepth = maxRecursionDepth;
    this.syntaxVersion = syntaxVersion;
    this.name = name;
    if (startTick > 0) {
      this.actionTicks = map.keySet().stream().filter(tick -> tick >= startTick).collect(Collectors.toCollection(PriorityQueue::new));
    } else {
      this.actionTicks = new PriorityQueue<>(map.keySet());
    }

  }

  public int getSyntaxVersion() {
    return syntaxVersion;
  }

  public String getShowName() {
    return name;
  }

  public abstract ShowActionExecutor createExecutor(TimecodeShowScheduler scheduler);

  public Integer getNextActionTick() {
    return actionTicks.peek();
  }

  public List<ShowAction> getNextActions() {
    return map.get(actionTicks.poll());
  }

  public long getMaxRecursionDepth() {
    return maxRecursionDepth;
  }

  public boolean isEmpty() {
    return map.isEmpty();
  }

  public abstract TimecodeShow clone();

  @Override
  public String toString() {
    String res = "";
    for (Integer tick : map.keySet()) {
      String strTick = tick + ":";
      for (ShowAction action : map.get(tick)) {
        strTick += "\n" + action.toString();
      }
      strTick += "\n\n";
      res += strTick;
    }
    return res;
  }

}

