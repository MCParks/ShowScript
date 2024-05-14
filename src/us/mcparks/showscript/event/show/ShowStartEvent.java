package us.mcparks.showscript.event.show;

import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class ShowStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private ShowScheduler show;

    public ShowStartEvent(ShowScheduler show) {
        this.show = show;
    }

    public int getTaskId() {
        return show.getShowTaskId();
    }

    public ShowScheduler getShow() {
        return show;
    }


    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
