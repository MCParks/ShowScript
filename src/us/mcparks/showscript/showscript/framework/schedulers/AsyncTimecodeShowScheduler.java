package us.mcparks.showscript.showscript.framework.schedulers;

import co.aikar.timings.lib.MCTiming;
import us.mcparks.showscript.Main;
import us.mcparks.showscript.showscript.framework.actions.ShowAction;
import us.mcparks.showscript.showscript.framework.TimecodeShow;
import org.bukkit.command.CommandSender;

import java.util.concurrent.ScheduledFuture;

public class AsyncTimecodeShowScheduler extends TimecodeShowScheduler {

    private Runnable runnable;

    private ScheduledFuture<?> task;

    // The pass of the timeline (generation) whose end has been queued, so later ticks don't queue it again.
    // Guarded by timelineLock.
    private int endQueuedGeneration = -1;

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

    ScheduledFuture<?> getFuture() {
        return task;
    }

    public void setTask(ScheduledFuture<?> task) {
        this.task = task;
    }

    @Override
    protected void executeShowAction(ShowAction action) {
        runOnMainThread(() -> {
            MCTiming timing = getTiming();
            if (timing != null) {
                try (MCTiming t = timing.startTiming()) {
                    super.executeActionLogic(action);
                }
            } else {
                super.executeActionLogic(action);
            }
        });
    }

    /**
     * Runs on the executor thread, with timelineLock held. The stop is queued behind this show's registration and
     * the actions it has already queued on the main thread, so they run first and the show stops (one
     * ShowStopEvent) on the main thread. Stopping here directly would end the show before those actions and fire
     * the event off-thread. The stop belongs to this pass of the timeline: a restart() before it runs voids it.
     */
    @Override
    protected void onTimelineEnd() {
        if (endQueuedGeneration == generation) {
            return;
        }
        endQueuedGeneration = generation;
        final int endedGeneration = generation;
        runOnMainThread(() -> endIfNotRestarted(endedGeneration));
    }

    /**
     * Main thread. A queued action (startSelf / restart()) may have restarted the show after the executor saw the
     * end of the timeline, and the executor may even have run through the restarted timeline since then. Either
     * way the restarted pass keeps running and queues its own end later; only an end from the current pass stops
     * the show. Nothing but restart() adds ticks back to a timeline, so a same-pass end is final.
     */
    private void endIfNotRestarted(int endedGeneration) {
        synchronized (timelineLock) {
            if (endedGeneration != generation) {
                return;
            }
        }
        terminate();
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
        stopped = true;
        task.cancel(true);
    }
}
