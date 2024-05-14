package us.mcparks.showscript.showscript.groovy.dsl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import groovy.lang.Closure;
import groovy.lang.Script;
import groovy.transform.BaseScript;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.schedulers.ShowScheduler;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.groovy.GroovyShowAction;
import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@BaseScript
public abstract class TimecodeDsl extends Script {
    protected Multimap<Integer, ShowAction> actions = ArrayListMultimap.create();
    protected Closure<?> showClosure = null;

    private static final Map<String, Object> globalVariables = new ConcurrentHashMap<>();

    private final Map<String, Object> exportedVariables = new HashMap<>();

    public TimecodeDsl() {

    }

    // the entire show may be wrapped in a show { } block -- this means that it takes arguments like show { arg, arg -> ... }
    public void show(Closure<?> closure) {
        closure.setDelegate(this);
        closure.setResolveStrategy(Closure.DELEGATE_FIRST);
        showClosure = closure;
    }

    // handle tick(<int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at the specified tick.
    public void ticks(int tick, Closure<?> closure) {
        ticks(new HashMap<>(), tick, closure);
    }

    // handle seconds(<int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at seconds*20
    public void seconds(double seconds, Closure<?> closure) {
        seconds(new HashMap<>(), seconds, closure);
    }


    // handle tick([parseNow: <boolean>], <int>} { ... }, a closure whose body will be parsed as ShowActionDsl. The resulting show actions will be scheduled to run at the specified tick.
    // if parseNow is true, the show action blocks will be executed immediately and their resulting actions will be scheduled. otherwise, the code that will resolve to the show actions will be scheduled to run at the specified tick.
    public void ticks(Map<String,?> optionalParams, int tick, Closure<?> closure) {
        //System.out.println("ticks(" + tick + ")");
        // closure will be several ShowActions
        processTimecode(tick, optionalParams, closure);
    }

    // delegate to ticks with seconds * 20
    public void seconds(Map<String,?> optionalParams, double seconds, Closure<?> closure) {
        ticks(optionalParams, (int) (seconds*20), closure);
    }

    private void processTimecode(int tick, Map<String,?> optionalParams, Closure<?> closure) {
        boolean parseNow = false;
        if (optionalParams.containsKey("parseNow")) {
            parseNow = (Boolean) optionalParams.get("parseNow");
        }


        if (parseNow) {
            //System.out.println("parsing now");
            ShowActionDsl showActionDsl = new ShowActionDsl();
            closure.setDelegate(showActionDsl);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            closure.run();
            actions.putAll(tick, showActionDsl.getActions());
        } else {
            //System.out.println("waiting to parse later");
            actions.put(tick, new GroovyShowAction(closure));
        }

    }

    // this is used internally to wrap around the text of the script, end-users shouldn't be using it
    public Multimap<Integer, ShowAction> evaluate(Object[] args) {
        actions = ArrayListMultimap.create();
        run();

        if (showClosure != null) {
            if (args == null && showClosure.getMaximumNumberOfParameters() > 0) {
                throw new IllegalArgumentException("Expected " + showClosure.getMaximumNumberOfParameters() + " arguments, but got 0");
            }

            if (args.length != showClosure.getMaximumNumberOfParameters()) {
                throw new IllegalArgumentException("Expected " + showClosure.getMaximumNumberOfParameters() + " arguments, but got " + args.length);
            }
            showClosure.call(args);
        }
        return actions;
    }

    public int getMaximumNumberOfParameters() {
        return showClosure == null ? 0 : showClosure.getMaximumNumberOfParameters();
    }


    /*
    Global Variables -- these are variables that are shared across all shows and can be accessed and modified by any show
     */

    public Object getGlobalVariable(String name) {
        return globalVariables.get(name);
    }

    public Object getGlobalVariable(String name, Object defaultValue) {
        return globalVariables.getOrDefault(name, defaultValue);
    }

    public void setGlobalVariable(String name, Object value) {
        globalVariables.put(name, value);
    }

    /*
        `export` and `load` allow a show to export variables to be used by other shows, and to load exported variables
        from other shows into a Map<String, Object> that can be used by the calling show
     */

    public Map<String, Object> load(String showPath, Object... args) throws Exception {
        GroovyShowConfig showConfig = new GroovyShowConfig(showPath, new ShowArgs(args));
        return showConfig.getExportedVariables();
    }

    public void export(String name, Object value) {
        exportedVariables.put(name, value);
    }

    public Map<String, Object> getExports() {
        return exportedVariables;
    }

    /*
    * Convenience methods for end-users to create Bukkit objects and perform common operations
    * */

    public World world(String worldName) {
        return Bukkit.getWorld(worldName);
    }

    public Player player(String playerName) {
        return Bukkit.getPlayerExact(playerName);
    }

    public Location location(String worldName, double x, double y, double z) {
        return new Location(world(worldName), x, y, z);
    }

    public Location location(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    public Location location(double x, double y, double z) {
        return new Location(world("world"), x, y, z);
    }

    public Collection<? extends Player> onlinePlayers() {
        return Bukkit.getOnlinePlayers();
    }

    public double sin(double angle) {
        return Math.sin(Math.toRadians(angle));
    }

    public double cos(double angle) {
        return Math.cos(Math.toRadians(angle));
    }

    public double tan(double angle) {
        return Math.tan(Math.toRadians(angle));
    }

    public Collection<ShowScheduler> runningShows() {
        return Main.getPlugin(Main.class).getActiveShows();
    }
    public boolean isShowRunning(String showName) {
        return runningShows().stream().anyMatch(show -> show.getName().equals(showName));
    }


    // this is used internally to wrap around the text of the script, end-users shouldn't be told about it
    public Multimap<Integer, ShowAction> getActions() {
        return actions;
    }

}
