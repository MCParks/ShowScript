package us.mcparks.showscript.util.collection;

import com.google.common.base.Supplier;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AsynchronouslyRefreshingSupplier<T> implements Supplier<T> {
    private static final String KEY = "KEY";
    private volatile T value;
    private final Callable<T> delegate;
    private final long period;
    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    public AsynchronouslyRefreshingSupplier(Callable<T> delegate, long duration, TimeUnit unit) {
        this.delegate = delegate;
        this.period = unit.toMillis(duration);
        schedule();
    }

    @Override
    public T get() {
        return value;
    }

    public void refresh() {
        executor.shutdown();
        schedule();
    }

    private void schedule() {
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
        executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    value = delegate.call();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 0, period, TimeUnit.MILLISECONDS);
    }
}
