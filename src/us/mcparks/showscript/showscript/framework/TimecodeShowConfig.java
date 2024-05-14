package us.mcparks.showscript.showscript.framework;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;

import java.util.*;


public abstract class TimecodeShowConfig implements Cloneable {
    protected ListMultimap<Integer, ShowAction> map = MultimapBuilder.treeKeys().arrayListValues().build();
    protected PriorityQueue<Integer> actionTicks = new PriorityQueue<Integer>();
    protected long maxRecursionDepth;

    protected String showName;

    public ListMultimap<Integer, ShowAction> getMap() {
        return map;
    }

    public PriorityQueue<Integer> getActionTicks() {
        return actionTicks;
    }

    public long getMaxRecursionDepth() {
        return maxRecursionDepth;
    }

    public String getShowName() {
        return showName;
    }

    public void setShowName(String showName) {
        this.showName = showName;
    }
    public void addShowAction(Integer timecode, ShowAction action) {
        map.put(timecode, action);
        if (!actionTicks.contains(timecode)) {
            actionTicks.add(timecode);
        }
    }

    public void addShowActions(Integer timecode, List<ShowAction> actions) {
        for (ShowAction action : actions) {
            addShowAction(timecode, action);
        }
    }

    public void addShowActions(Multimap<Integer, ShowAction> actions) {
        for (Integer timecode : actions.keySet()) {
            addShowActions(timecode, (List<ShowAction>) actions.get(timecode));
        }
    }

    public int getDuration() {
        return map.keySet().isEmpty() ? 0 : Collections.max(map.keySet())+1;
    }

    public void setMaxRecursionDepth(long maxRecursionDepth) {
        this.maxRecursionDepth = maxRecursionDepth;
    }

    public abstract TimecodeShowConfig clone();

    public abstract int getSyntaxVersion();

    public abstract TimecodeShow<? extends TimecodeShowConfig> createShow(long startTick, ShowArgs args);
}
