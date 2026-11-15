package com.pulseforge.session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * F07 countdown clock. Ticks once per second on a daemon thread and notifies
 * {@link TimerListener}s (GUI countdown, CLI messages, session manager). When a heart-rate
 * source is connected and the countdown reaches zero, it holds until HR drops below the
 * recovery threshold. Supports +30 s, skip, and abort (next set started early).
 */
public class RestTimer {

    private final List<TimerListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    private int total;
    private int remaining;
    private int elapsed;
    private boolean running;
    private boolean holding;
    private HeartRateSource hrSource;
    private int hrThreshold;

    /** Real-time timer. */
    public RestTimer() {
        this(true);
    }

    /** @param autoTick false for tests, which drive {@link #tick()} manually */
    public RestTimer(boolean autoTick) {
        this.scheduler = autoTick ? Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rest-timer");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    public void addListener(TimerListener l) {
        listeners.add(l);
    }

    public void removeListener(TimerListener l) {
        listeners.remove(l);
    }

    public synchronized void start(int seconds, HeartRateSource hr, int hrThreshold) {
        if (running) {
            finish("next set started early");
        }
        this.total = seconds;
        this.remaining = seconds;
        this.elapsed = 0;
        this.holding = false;
        this.hrSource = hr;
        this.hrThreshold = hrThreshold;
        this.running = true;
        if (scheduler != null) {
            future = scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        }
        fireTick();
    }

    /** Advances the clock by one second. */
    public synchronized void tick() {
        if (!running) {
            return;
        }
        elapsed++;
        if (remaining > 0) {
            remaining--;
        }
        if (remaining == 0) {
            if (hrSource != null && hrSource.currentBpm() > hrThreshold) {
                holding = true;      // countdown done but not yet recovered
            } else {
                fireTick();
                finish("complete");
                return;
            }
        }
        fireTick();
    }

    public synchronized void addTime(int seconds) {
        if (!running) {
            return;
        }
        remaining += seconds;
        total += seconds;
        holding = false;
        fireTick();
    }

    public synchronized void skip() {
        if (running) {
            finish("skipped");
        }
    }

    /** The lifter started the next set before rest ended. */
    public synchronized void abort() {
        if (running) {
            finish("next set started early");
        }
    }

    private void finish(String reason) {
        running = false;
        holding = false;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        int actual = elapsed;
        for (TimerListener l : listeners) {
            l.onFinished(actual, reason);
        }
    }

    private void fireTick() {
        int hr = hrSource == null ? -1 : hrSource.currentBpm();
        for (TimerListener l : listeners) {
            l.onTick(remaining, total, holding, hr);
        }
    }

    public synchronized boolean isRunning() { return running; }
    public synchronized int getRemaining() { return remaining; }
    public synchronized int getTotal() { return total; }
    public synchronized int getElapsed() { return elapsed; }
    public synchronized boolean isHolding() { return holding; }
    public int getHrThreshold() { return hrThreshold; }
}
