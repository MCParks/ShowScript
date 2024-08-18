package us.mcparks.showscript.showscript.framework.actions;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class ShowAction {
    protected ShowActionType action;
    protected Map<?, ?> propMap;

    public ShowActionType getType() {
        return action;
    }

    public List<String> getAllPropsAsStrings() {
        return propMap.values().stream().map((prop) -> (String) prop).collect(Collectors.toList());
    }

    public Map<?,?> getPropertyMap() {
        return propMap;
    }

    public abstract double getDoubleProp(String key);

    public abstract boolean getBoolProp(String key);

    public abstract String getStringProp(String key);

    public abstract int getIntProp(String key);


    @Override
    public String toString() {
        return action.toString() + ": " + propMap.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

}
