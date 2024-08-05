package us.mcparks.showscript.showscript.groovy.dsl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowActionType;
import us.mcparks.showscript.showscript.groovy.GroovyShowAction;

import java.util.*;

public class ShowActionDsl {
    List<ShowAction> actions = new ArrayList<>();

    public ShowActionDsl() {
    }

    // this is the method that is called when the user writes "cmd { ... }" -- it should evaluate to a String specifying the Minecraft command to be run
    public void cmd(@DelegatesTo(value = String.class) Closure<?> closure) {
        closure.setResolveStrategy(Closure.DELEGATE_ONLY);
        System.out.println("cmd(" + closure.getDelegate() + ")");
        actions.add(new GroovyShowAction(ShowActionType.CMD, ImmutableMap.of("cmd", closure.call())));
    }

    // this is the method that is called when the user writes "text { ... }" -- it should evaluate to a Groovy map with the keys "world", "x", "y", "z", "text", and "range"
    public void text(@DelegatesTo(value = Map.class) Closure<?> closure) {
        HashMap<?,?> map = new HashMap<>();
        closure.setResolveStrategy(Closure.DELEGATE_ONLY);
        closure.setDelegate(map);
        closure.call();
        ensureMapHasKeys(map, Lists.newArrayList("world", "x", "y", "z", "text", "range"));
        actions.add(new GroovyShowAction(ShowActionType.TEXT, map));
    }

    // this is the method that is called when the user writes "show { ... }" -- it should evaluate to a Groovy map with the key "name" specifying the other show to run
    // any other keys will be passed to the other show as arguments (in the order they are defined in the closure since show arguments are positional)
    public void show(@DelegatesTo(value = LinkedHashMap.class) Closure<?> closure) {
        LinkedHashMap<?,?> map = new LinkedHashMap<>();
        closure.setResolveStrategy(Closure.DELEGATE_FIRST);
        closure.setDelegate(map);
        closure.call();
        ensureMapHasKeys(map, Lists.newArrayList("name"));
        actions.add(new GroovyShowAction(ShowActionType.SHOW, map));
    }

    public void startSelf(Closure<?> closure) {
        actions.add(new GroovyShowAction(ShowActionType.SELF, Collections.emptyMap()));
    }

    // Used by the delegating TimecodeDsl to suck up the actions defined in this show action closure
    public List<ShowAction> getActions() {
        return actions;
    }

    // Use this method to ensure that a map you are delegating a closure to has all the keys you expect
    // If a key is missing, throw an unchecked error to the user
    private void ensureMapHasKeys(Map<?,?> map, List<Object> keys) {
        for (Object key : keys) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("Map must contain key " + key);
            }
        }
    }

}
