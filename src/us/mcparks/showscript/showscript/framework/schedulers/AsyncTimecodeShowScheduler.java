package us.mcparks.showscript.showscript.framework.schedulers;

import co.aikar.timings.lib.MCTiming;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.concurrent.ScheduledFuture;

public class AsyncTimecodeShowScheduler extends TimecodeShowScheduler {

    private Runnable runnable;

    private ScheduledFuture<?> task;

    public AsyncTimecodeShowScheduler(Main main, TimecodeShow show, String name, boolean display, CommandSender sender, long recursionDepth, int timecode) {
        super(main, show, name, display, sender, recursionDepth, timecode);
        runnable = new Runnable() {
            @Override
            public void run() {
                AsyncTimecodeShowScheduler.this.run();
            }
        };
    }

    public Runnable getRunnable() {
        return runnable;
    }

    public void setTask(ScheduledFuture<?> task) {
        this.task = task;
    }

    @Override
    protected void executeShowAction(ShowAction action) {
        Bukkit.getScheduler().runTask(main, () -> {
            try (MCTiming timing = getTiming().startTiming()) {
                super.executeShowAction(action);
            }
        });
    }

    @Override
    protected void setTimeToWait() {
        Integer next = show.getNextActionTick();
        if (next != null) {
            if (waitCounter != 0) {
                waitCounter--;
            } else {
                int timeToWait = next - timecode;
                if (timeToWait > 9) {
                    timeToWait = 10;
                }

                waitCounter = timeToWait;
                //System.out.println("Waiting " + finalWait + " ticks, next action is at " + next + " and I have " + timeToWait + " to go. Current time: " + timecode);
            }
        }

    }

    public boolean isAsync() {
        return true;
    }

    @Override
    public void stopShow() {
        task.cancel(true);
    }
}
