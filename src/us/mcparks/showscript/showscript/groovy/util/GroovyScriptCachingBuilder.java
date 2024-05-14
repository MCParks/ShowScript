package us.mcparks.showscript.showscript.groovy.util;

import groovy.lang.GroovyShell;
import groovy.lang.Script;

import java.util.HashMap;
import java.util.Map;

public class GroovyScriptCachingBuilder {
    private GroovyShell shell;
    private Map<String, Script> scripts = new HashMap<>();

    public GroovyScriptCachingBuilder() {
        this.shell = new GroovyShell(this.getClass().getClassLoader());
    }

    public GroovyScriptCachingBuilder(GroovyShell shell) {
        this.shell = shell;
    }

    public Script getScript(final String expression) {
        Script script;
        if (scripts.containsKey(expression))
        {
            script = scripts.get(expression);
        }
        else
        {

            script = shell.parse(expression);
            scripts.put(expression, script);
        }
        return script;
    }
}