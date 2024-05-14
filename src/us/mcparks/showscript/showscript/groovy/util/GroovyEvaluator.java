package us.mcparks.showscript.showscript.groovy.util;

import groovy.lang.Binding;
import groovy.lang.Script;

import java.util.HashMap;
import java.util.Map;

public class GroovyEvaluator
{
    private static GroovyScriptCachingBuilder groovyScriptCachingBuilder = new GroovyScriptCachingBuilder();

    private GroovyScriptCachingBuilder overrideGroovyScriptCachingBuilder = null;
    private Map<String, Object> variables = new HashMap<>();

    public GroovyEvaluator() {
        this(new HashMap<>());
    }

    public GroovyEvaluator(GroovyScriptCachingBuilder groovyScriptCachingBuilder) {
        this(new HashMap<>());
        this.overrideGroovyScriptCachingBuilder = groovyScriptCachingBuilder;
    }

    public GroovyEvaluator(final Map<String, Object> contextVariables)
    {
        variables.putAll(contextVariables);
    }

    private GroovyScriptCachingBuilder getGroovyScriptCachingBuilder() {
        if (overrideGroovyScriptCachingBuilder != null) {
            return overrideGroovyScriptCachingBuilder;
        }
        return groovyScriptCachingBuilder;
    }

    public void setVariables(final Map<String, Object> answers)
    {
        variables.putAll(answers);
    }

    public void setVariable(final String name, final Object value)
    {
        variables.put(name, value);
    }

    public Object getVariable(final String name)
    {
        return variables.get(name);
    }

    public void removeVariable(final String name)
    {
        variables.remove(name);
    }

    public Object evaluateExpression(String expression)
    {
        final Binding binding = new Binding();
        if (!variables.entrySet().isEmpty()) {
            for (Map.Entry<String, Object> varEntry : variables.entrySet())
            {
                binding.setProperty(varEntry.getKey(), varEntry.getValue());
            }
        }

        Script script = getGroovyScriptCachingBuilder().getScript(expression);
        synchronized (script)
        {
            if (!variables.entrySet().isEmpty()) {
                script.setBinding(binding);
            }
            return script.run();
        }
    }

    public Script getScript(String expression)
    {
        return getGroovyScriptCachingBuilder().getScript(expression);
    }

}