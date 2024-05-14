package us.mcparks.showscript.event.region;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

public class PlayerEnterRegionEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    String regionName;

    public PlayerEnterRegionEvent(Player who) {
        super(who);
    }

    public PlayerEnterRegionEvent(Player who, String regionName) {
        super(who);
        this.regionName = regionName;
    }

    public String getRegionName() {
        return regionName;
    }

    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}