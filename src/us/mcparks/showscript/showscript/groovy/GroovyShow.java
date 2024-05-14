package us.mcparks.showscript.showscript.groovy;

import com.google.common.collect.ListMultimap;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import us.mcparks.showscript.showscript.framework.actions.executors.ShowActionExecutor;
import us.mcparks.showscript.showscript.framework.TimecodeShow;


public class GroovyShow extends TimecodeShow<GroovyShowConfig> {

    public GroovyShow(GroovyShowConfig cfg, long startTick) {
        super(cfg, startTick);
    }

    public GroovyShow(String name, ListMultimap<Integer, ShowAction> map, long maxRecursionDepth, long startTick) {
        super(name, map, maxRecursionDepth, startTick, 3);
    }



    @Override
    public ShowActionExecutor createExecutor(TimecodeShowScheduler scheduler) {
        return new GroovyShowActionExecutor(scheduler);
    }

    @Override
    public TimecodeShow clone() {
        return new GroovyShow(name, map, maxRecursionDepth, 0);
    }


}