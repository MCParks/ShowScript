package us.mcparks.showscript.showscript.framework.actions.executors;

import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

public class BasicShowActionExecutor extends ShowActionExecutor {

    public BasicShowActionExecutor(TimecodeShowScheduler scheduler) {
        super(scheduler);
    }

    @Override
    public void execute(ShowAction action) throws Exception {
        // execute the proper action
        switch (action.getType()) {
            case BUILD:
                build(action.getStringProp("name"));
                break;
            case CMD:
                //System.out.println(action.getStringProp("cmd"));
                cmd(action.getStringProp("cmd"));
                break;
            case FOUNTAIN:
                Location fountainLoc = new Location(
                        Bukkit.getWorld(action.getStringProp("world")),
                        action.getDoubleProp("x"),
                        action.getDoubleProp("y"),
                        action.getDoubleProp("z"));
                double xvel = action.getDoubleProp("xvel");
                double yvel = action.getDoubleProp("yvel");
                double zvel = action.getDoubleProp("zvel");
                Vector velocity = new Vector(xvel, yvel, zvel);
                int time = action.getIntProp("time");
                int id = action.getIntProp("id");
                int damage = action.getIntProp("damage");
                fountain(fountainLoc, id, damage, velocity, time);
                break;
            case TEXT:
                Location loc = new Location(
                        Bukkit.getWorld(action.getStringProp("world")),
                        action.getDoubleProp("x"),
                        action.getDoubleProp("y"),
                        action.getDoubleProp("z"));
                text(action.getStringProp("text"), loc, action.getDoubleProp("range"));
                //System.out.println(action.getStringProp("text"));
                break;
            case RANDOM:
                List<String> cmds = action.getAllPropsAsStrings();
                cmds.remove("random");
                random(cmds);
                break;
            case SELF:
                startSelf();
                break;
            default:
                break;
        }
    }

}
