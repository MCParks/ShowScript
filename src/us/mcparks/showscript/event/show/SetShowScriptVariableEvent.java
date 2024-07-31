package us.mcparks.showscript.event.show;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class SetShowScriptVariableEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private String variable;
    private Object value;

    public SetShowScriptVariableEvent(String variable, Object value) {
        this.variable = variable;
        this.value = value;
    }

    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public String getVariable() {
        return variable;
    }

    public Object getValue() {
        return value;
    }
}
