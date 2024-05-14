package us.mcparks.showscript.showscript.framework.actions;

import java.util.Map;

public class BasicShowAction extends ShowAction {

    public BasicShowAction(ShowActionType action, Map<?, ?> propMap) {
        this.action = action;
        this.propMap = propMap;
    }

    @Override
    public double getDoubleProp(String key) {
        return (Double) propMap.get(key);
    }

    @Override
    public boolean getBoolProp(String key) {
        return (Boolean) propMap.get(key);
    }

    @Override
    public String getStringProp(String key) {
        return (String) propMap.get(key);
    }

    @Override
    public int getIntProp(String key) {
        return (Integer) propMap.get(key);
    }

}
