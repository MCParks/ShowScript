package us.mcparks.showscript.showscript.groovy;

import groovy.lang.Closure;
import groovy.lang.GString;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowActionType;
import org.apache.groovy.util.Maps;

import java.util.Map;

public class GroovyShowAction extends ShowAction {

    public GroovyShowAction(Closure<?> closure) {
        this(ShowActionType.CLOSURE, Maps.of("closure", closure));
    }

    public GroovyShowAction(ShowActionType action, Map<?, ?> propMap) {
        this.action = action;
        this.propMap = propMap;
    }

    public Closure<?> getClosure() {
        if (action.equals(ShowActionType.CLOSURE)) {
            return getClosureProp("closure");
        } else {
            throw new IllegalStateException("GroovyShowAction not of type CLOSURE");
        }
    }

    @Override
    public double getDoubleProp(String key) {
        Object prop = propMap.get(key);
        if (prop instanceof Integer) {
            return ((Integer) prop).doubleValue();
        } else if (prop instanceof GString) {
            return Double.parseDouble(((GString) prop).toString());
        } else {
            return (Double) prop;
        }
    }

    @Override
    public boolean getBoolProp(String key) {
        Object prop = propMap.get(key);
        if (prop instanceof GString) {
            return Boolean.parseBoolean(((GString) prop).toString());
        } else {
            return (Boolean) prop;
        }
    }

    @Override
    public String getStringProp(String key) {
        Object prop = propMap.get(key);
        if (prop instanceof GString) {
            return ((GString) prop).toString();
        }

        return (String) propMap.get(key);
    }

    @Override
    public int getIntProp(String key) {
        Object prop = propMap.get(key);
        if (propMap.get(key) instanceof GString) {
            return Integer.parseInt(((GString) prop).toString());
        } else {
            return (Integer) prop;
        }
    }

    public Closure<?> getClosureProp(String key) {
        return (Closure<?>) propMap.get(key);
    }
}
