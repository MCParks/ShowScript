package us.mcparks.showscript.showscript.groovy;

import groovy.lang.Closure;
import us.mcparks.showscript.showscript.framework.actions.executors.BasicShowActionExecutor;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.actions.ShowActionType;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import us.mcparks.showscript.showscript.groovy.dsl.ShowActionDsl;
import us.mcparks.showscript.showscript.framework.ShowArgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GroovyShowActionExecutor extends BasicShowActionExecutor {
    public GroovyShowActionExecutor(TimecodeShowScheduler scheduler) {
        super(scheduler);
    }

    @Override
    public void execute(ShowAction action) throws Exception {
        if (action.getType().equals(ShowActionType.CLOSURE) && action instanceof GroovyShowAction) {
            GroovyShowAction groovyAction = (GroovyShowAction) action;
            Closure<?> closure = groovyAction.getClosure();
            ShowActionDsl showActionDsl = new ShowActionDsl();
            closure.setDelegate(showActionDsl);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            groovyAction.getClosure().call();
            for (ShowAction subAction : ((ShowActionDsl)(groovyAction.getClosure().getDelegate())).getActions()) {
                execute(subAction);
            }
        } else if (action.getType().equals(ShowActionType.SHOW) && action instanceof GroovyShowAction) {
            String showName = null;
            List<Object> showArgs = new ArrayList<>();
            for (Map.Entry<?,?> entry : action.getPropertyMap().entrySet()) {
                if (showName == null) {
                    if (entry.getKey().toString().equals("name")) {
                        showName = entry.getValue().toString();
                    } else {
                        throw new IllegalArgumentException("First property of a SHOW action must be `show` and contain the show name");
                    }
                } else {
                    showArgs.add(entry.getValue());
                }
            }

            if (showName == null) {
                throw new IllegalArgumentException("First property of a SHOW action must be `show` and contain the show name");
            }
            startShow(showName, new ShowArgs(showArgs));
        } else {
            super.execute(action);
        }
    }
}
