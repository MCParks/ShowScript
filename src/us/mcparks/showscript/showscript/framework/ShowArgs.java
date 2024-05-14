package us.mcparks.showscript.showscript.framework;

import us.mcparks.showscript.showscript.groovy.GroovyShowConfig;

import java.util.List;

public class ShowArgs {
    private String argsString;
    private Object[] args;

    public ShowArgs(String args) {
        this.argsString = args;
    }

    public ShowArgs(Object[] args) {
        this.args = args;
    }

    public ShowArgs(List<Object> args) {
        this.args = args.toArray();
    }

    private Object[] evaluateArgs(String argString, TimecodeShowConfig config) throws Exception {

        if (config instanceof GroovyShowConfig) {
            if (argString == null || argString.isEmpty()) {
                return new Object[0];
            }
            try {
                Object result = GroovyShowConfig.evaluator.evaluateExpression("return [" + argString + "]");
                return result instanceof List ? ((List)result).toArray() : new Object[]{result};
            } catch (Exception ex) {
                throw new IllegalArgumentException("Error evaluating args: " + ex.getMessage(), ex);
            }
        } else {
            return new Object[0];
        }
    }

    public Object[] getArgs(TimecodeShowConfig config) throws Exception {
        if (args == null && !isEmpty()) {
            args = evaluateArgs(argsString, config);
        }
        return args;
    }


    public int length(TimecodeShowConfig config) throws Exception {
        return getArgs(config).length;
    }

    public boolean isEmpty() {
        return args == null && (argsString == null || argsString.isEmpty());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ShowArgs) {
            ShowArgs other = (ShowArgs) obj;
            if (argsString != null && other.argsString != null) {
                return argsString.equals(other.argsString);
            } else if (args != null && other.args != null) {
                // check if each element of the array is equal
                if (args.length != other.args.length) {
                    return false;
                }
                for (int i = 0; i < args.length; i++) {
                    if (!args[i].equals(other.args[i])) {
                        return false;
                    }
                }
                return true;
            } else {
                return isEmpty() && other.isEmpty();
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (argsString != null ? argsString.hashCode() : 0);
        if (args != null) {
            for (Object arg : args) {
                result = 31 * result + (arg != null ? arg.hashCode() : 0);
            }
        }
        return result;
    }
}
