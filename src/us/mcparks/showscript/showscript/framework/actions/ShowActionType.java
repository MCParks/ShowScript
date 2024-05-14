package us.mcparks.showscript.showscript.framework.actions;

public enum ShowActionType {
    TEXT, CMD, BUILD, FOUNTAIN, RANDOM, CLOSURE, SHOW, SELF;

    public static ShowActionType fromString(String string) {
        switch (string.toLowerCase()) {
            case "text":
                return ShowActionType.TEXT;
            case "cmd":
                return ShowActionType.CMD;
            case "rebuild":
                return ShowActionType.BUILD;
            case "build":
                return ShowActionType.BUILD;
            case "fountain":
                return ShowActionType.FOUNTAIN;
            case "random":
                return ShowActionType.RANDOM;
            case "closure":
                return ShowActionType.CLOSURE;
            case "show":
                return ShowActionType.SHOW;
            case "self":
                return ShowActionType.SELF;
            default:
                throw new IllegalArgumentException(string + " is not a valid Show action");
        }
    }
}
