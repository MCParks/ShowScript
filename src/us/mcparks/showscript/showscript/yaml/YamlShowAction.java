package us.mcparks.showscript.showscript.yaml;

import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowActionType;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YamlShowAction extends ShowAction {
    static Pattern macroPattern = Pattern.compile("\\^.*\\^");
    Matcher macroMatcher;
    Map<String, String> macros;

    public YamlShowAction(Map<?, ?> map, Map<String, String> macros) throws Exception {
        this.macroMatcher = macroPattern.matcher("");
        this.propMap = map;
        this.macros = macros;
        if (map.get("item") == null || !(map.get("item") instanceof String)) {
            throw new Exception("'item' field is either missing or is not a String");
        }
        this.action = ShowActionType.fromString((String) map.get("item"));
    }

    private String fillMacro(String key) {
        String valStr = String.valueOf(propMap.get(key));

        try {
            if (valStr.startsWith("^") && valStr.endsWith("^")) {
                String potentialMacro = valStr.substring(1, valStr.length() - 1);
                if (macros.containsKey(potentialMacro)) {
                    return macros.get(potentialMacro);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String fillMacroRegex(String key) {
        String valStr = (String) propMap.get(key);
        try {
            macroMatcher.reset(valStr);
            if (macroMatcher.find()) {
                String macroKey = valStr.substring(macroMatcher.start() + 1, macroMatcher.end() - 1);
                String macro = macros.get(macroKey);
                if (macro.startsWith("'") && macro.endsWith("'")) {
                    macro = macro.substring(1, macro.length() - 1);
                }
                return valStr.substring(0, macroMatcher.start()) + macro + valStr.substring(macroMatcher.end());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @Override
    public String getStringProp(String key) {
        String macro = fillMacroRegex(key);
        return macro == null ? (String) propMap.get(key) : macro;
    }

    @Override
    public int getIntProp(String key) {
        String macro = fillMacro(key);
        return macro == null ? (Integer) propMap.get(key) : Integer.parseInt(macro);
    }

    @Override
    public double getDoubleProp(String key) {
        String macro = fillMacro(key);
        if (macro == null) {
            try {
                return (Double) propMap.get(key);
            } catch (ClassCastException ex) {
                return (Integer) propMap.get(key) + 0.0D;
            }
        } else {
            return Double.parseDouble(macro);
        }
    }

    @Override
    public boolean getBoolProp(String key) {
        String macro = fillMacro(key);
        return macro == null ? (Boolean) propMap.get(key) : Boolean.parseBoolean(macro);
    }
}
