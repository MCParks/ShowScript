package us.mcparks.showscript.showscript.framework.actions.executors;

import us.mcparks.showscript.util.DebugLogger;
import us.mcparks.showscript.util.Lag;
import us.mcparks.showscript.Rebuild;
import us.mcparks.showscript.showscript.framework.ShowArgs;
import us.mcparks.showscript.showscript.framework.TimecodeShowConfig;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.schedulers.TimecodeShowScheduler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Random;

public abstract class ShowActionExecutor {
    protected TimecodeShowScheduler scheduler;

    public ShowActionExecutor(TimecodeShowScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public abstract void execute(ShowAction action) throws Exception;

    protected void text(String text, Location loc, double range) {
        String color = ChatColor.translateAlternateColorCodes('&', text);

        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (p.getLocation().distanceSquared(loc) <= (range * range)) {
                    p.sendMessage(color);
                }
            } catch (IllegalArgumentException x) {
                x.printStackTrace();
            }
        }
    }

    protected void build(String name) {
        DebugLogger.log(scheduler.getName(), "build: " + name);

        Rebuild rb = new Rebuild();
        rb.buildRegion(name);
    }

    protected void cmd(String cmd) throws Exception {
        DebugLogger.log(scheduler.getName(), "cmd: " + cmd);

        if (cmd.startsWith("show")) {
            String showName = cmd.split(" ")[2];
            if (showName.equals(scheduler.getName())) {
                if (scheduler.getCurrentRecursionDepth() < scheduler.getShow().getMaxRecursionDepth()) {
                    DebugLogger.log(scheduler.getName(), "starting itself -- recursion depth is " + scheduler.getCurrentRecursionDepth() + " (max " + scheduler.getShow().getMaxRecursionDepth() + ")");
                    // assume no arguments because this is only called from ShowScript 2
                    scheduler.getPlugin().timecodeExecutor.startShow(scheduler.getShow().clone(), scheduler.getSender(), scheduler.shouldDisplay(), scheduler.getCurrentRecursionDepth()+ 1, true);
                }
                return;
            }
        }


        ServerCommandEvent event = new ServerCommandEvent(Bukkit.getConsoleSender(), cmd);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            Bukkit.getServer().dispatchCommand(event.getSender(), event.getCommand());
        }
        // Running a test to _always_ pass commands through the event
//        if (cmd.startsWith("train")) {
//            // this is better style, not sure if calling the event will create any overhead so i'm scared to do it in the general case
//            ServerCommandEvent event = new ServerCommandEvent(Bukkit.getConsoleSender(), cmd);
//            Bukkit.getPluginManager().callEvent(event);
//            if (!event.isCancelled()) {
//                Bukkit.getServer().dispatchCommand(event.getSender(), event.getCommand());
//            }
//        } else {
//            Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), cmd);
//        }
    }

    protected void startShow(String showName, ShowArgs showArgs) throws Exception {
        TimecodeShowConfig showToStart = scheduler.getPlugin().timecodeExecutor.getShowConfig(showName, showArgs);
        long recursionDepth = 0;
        if (showName.equals(scheduler.getName())) {
                if (scheduler.getCurrentRecursionDepth() < scheduler.getShow().getMaxRecursionDepth()) {
                    DebugLogger.log(scheduler.getName(), "starting itself -- recursion depth is " + scheduler.getCurrentRecursionDepth() + " (max " + scheduler.getShow().getMaxRecursionDepth() + ")");
                    recursionDepth = scheduler.getCurrentRecursionDepth() + 1;
                } else {
                    return;
                }
        } else {
            if (!showArgs.isEmpty() && showToStart.getSyntaxVersion() != 3) {
                throw new IllegalArgumentException("No ShowScript 3 show found with name " + showName);
            }

            DebugLogger.log(scheduler.getName(), "starting show: " + showName + (showArgs.isEmpty() ? "" : " with args: " + showArgs));
        }

        if (showToStart == null) {
            scheduler.getPlugin().timecodeExecutor.startShowAt(showName, scheduler.getSender(), scheduler.shouldDisplay(), recursionDepth, 0, scheduler.isAsync(), showArgs);
        } else {
            scheduler.getPlugin().timecodeExecutor.startShowAt(showToStart, scheduler.getSender(), scheduler.shouldDisplay(), recursionDepth, 0, scheduler.isAsync(), showArgs);
        }
    }

    protected void startSelf() throws Exception {
        if (scheduler.getCurrentRecursionDepth() < scheduler.getShow().getMaxRecursionDepth()) {
            DebugLogger.log(scheduler.getName(), "starting itself -- recursion depth is " + scheduler.getCurrentRecursionDepth() + " (max " + scheduler.getShow().getMaxRecursionDepth() + ")");
            scheduler.getPlugin().timecodeExecutor.startShow(scheduler.getShow().clone(), scheduler.getSender(), scheduler.shouldDisplay(), scheduler.getCurrentRecursionDepth() + 1, true);
        }
    }

    protected void random(List<String> commands) throws Exception {
        Random random = new Random();
        cmd(commands.get(random.nextInt(commands.size())));
    }

    @SuppressWarnings("deprecation")
    protected void fountain(Location loc, int id, int damage, Vector velocity, int time) {
        int finalWait = (int) (Lag.getTPS() / 10) * (time);
        int taskId = Bukkit.getScheduler().runTaskTimer(scheduler.getPlugin(), () -> {
            FallingBlock fb = loc.getWorld().spawnFallingBlock(loc, id, (byte) damage);
            fb.setDropItem(false);
            fb.setVelocity(velocity);
        }, 1L, 1L).getTaskId();

        Bukkit.getScheduler().runTaskLater(scheduler.getPlugin(), () -> {
            Bukkit.getScheduler().cancelTask(taskId);
        }, finalWait);
    }
}
