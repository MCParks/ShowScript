package us.mcparks.showscript.showscript.yaml;


import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import us.mcparks.showscript.showscript.framework.actions.executors.ShowActionExecutor;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import us.mcparks.showscript.showscript.framework.actions.executors.BasicShowActionExecutor;

import java.util.Map;


public class YamlShow extends TimecodeShow<YamlShowConfig> {
    Map<String, String> macros;
    YamlShowConfig cfg;

    public YamlShow(YamlShowConfig cfg, long startTick) {
        super(cfg, startTick);
        this.cfg = cfg;
        this.macros = cfg.getMacros();
    }

    @Override
    public ShowActionExecutor createExecutor(TimecodeShowScheduler scheduler) {
        return new BasicShowActionExecutor(scheduler);
    }

    @Override
    public TimecodeShow clone() {
        return new YamlShow(cfg, 0);
    }
}
