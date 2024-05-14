package us.mcparks.showscript.showscript.groovy;

import com.google.common.collect.Multimap;
import groovy.lang.*;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.groovy.dsl.TimecodeDsl;
import us.mcparks.showscript.showscript.groovy.util.GroovyEvaluator;
import us.mcparks.showscript.showscript.groovy.util.GroovyScriptCachingBuilder;
import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.TimecodeShowConfig;
import us.mcparks.showscript.showscript.framework.TimecodeShowExecutor;

import java.io.File;
import java.util.Map;

/**
 * A show that is defined by a Groovy script.
 * Example syntax:
 *
 *
 * env {
 *     world = "world"
 * }
 *
 * ticks(0) {
 *     text {
 *         text: "Hello World!"
 *         x: 100
 *         y: 60
 *         z: 100
 *         range: 100
 *         world: env.world
 *     }
 *
 *     cmd {
 *         broadcast Hello World!
 *     }
 *
 *     cmd "broadcast you can also define a command like this"
 *
 *     code {
 *         if (server.onlinePlayers.size() > 20) {
 *             cmd "broadcast There are more than 20 players online"
 *         }
 *     }
 * }
 *
 * seconds(5) {
 *     code {
 *         if (world(env.world).getTime() > 12000) {
 *             cmd "broadcast It's night time!"
 *         }
 *     }
 * }
 *
 * def summonFirework = { x, y, z, motion, colors, fadeColors ->
 *     cmd "summon fireworks_rocket $x $y $z {Motion:$motion,FireworksItem:{id:fireworks,Count:1,tag:{Fireworks:{Explosions:[{Type:4,Trail:1,Colors:$colors,FadeColors:$fadeColors}]}}}}"
 * }
 *
 * time(0:34.2) {
 *     summonFirework(100, 60, 100, "{X:0.0,Y:0.0,Z:0.0}", "[I;16711680]", "[I;255]")
 * }
 *  */
public class GroovyShowConfig extends TimecodeShowConfig {

    public static GroovyEvaluator evaluator;

    private String fileContents="";

    TimecodeDsl script;

    ShowArgs args;

    static {
        CompilerConfiguration config = new CompilerConfiguration();
        config.setScriptBaseClass(TimecodeDsl.class.getName());
        GroovyScriptCachingBuilder scriptCachingBuilder = new GroovyScriptCachingBuilder(new GroovyShell(config));
        evaluator = new GroovyEvaluator(scriptCachingBuilder);
    }

    private static String getFileContents(File file, String showName) throws Exception {
        if (!file.exists()) {
            throw new IllegalArgumentException("Show file " + showName + ".groovy does not exist");
        }
        return FileUtils.readFileToString(file, "UTF-8");
    }

    public GroovyShowConfig(File file, String showName, ShowArgs args) throws Exception {
        this(getFileContents(file, showName), showName, args);
    }

    public GroovyShowConfig(String showName, ShowArgs args) throws Exception {
        this(new File(TimecodeShowExecutor.showDirectory, showName + ".groovy"), showName, args);
    }


    public GroovyShowConfig(String fileContents, String showName, ShowArgs args) throws Exception {
        this.showName = showName;
        this.fileContents = fileContents;
        this.args = args;
        script = (TimecodeDsl) evaluator.getScript(fileContents);
        Object[] showArgs = args == null ? new Object[0] : args.getArgs(this);

        Multimap<Integer, ShowAction> actions = script.evaluate(showArgs);

        System.out.println(actions.values().size() + " actions parsed in " + showName);
        addShowActions(actions);
    }

    @Override
    public TimecodeShowConfig clone() {
        try {
            return new GroovyShowConfig(this.fileContents, this.showName, this.args);
        } catch (Exception ex) {
            // if we got here, somehow we're cloning a show that couldn't have been created
            return null;
        }
    }

    public TimecodeShowConfig cloneWithArgs(ShowArgs args) {
        try {
            GroovyShowConfig clone = new GroovyShowConfig(this.fileContents, this.showName, args);
            return clone;
        } catch (Exception ex) {
            // if we got here, somehow we're cloning a show that couldn't have been created
            return null;
        }
    }

    @Override
    public int getSyntaxVersion() {
        return 3;
    }

    @Override
    public TimecodeShow<? extends TimecodeShowConfig> createShow(long startTick, ShowArgs args) {
        if (args.equals(this.args)) {
            return new GroovyShow(this, startTick);
        } else {
            return new GroovyShow((GroovyShowConfig) this.cloneWithArgs(args), startTick);
        }
    }

    private TimecodeDsl getScript() {
        return (TimecodeDsl) evaluator.getScript(fileContents);
    }

    public int getRequiredArgumentCount() {
        return getScript().getMaximumNumberOfParameters();
    }

    public Map<String, Object> getExportedVariables() {
        return getScript().getExports();
    }


}
