package com.pulseforge.session;

import java.util.function.LongSupplier;

/**
 * Simulated live heart rate (clearly labeled as simulated in the UI). After a set, HR jumps to
 * 70-80% of max depending on RPE and decays exponentially toward resting HR (tau = 50 s).
 * Used to demonstrate the HR-gated rest timer without wearable hardware.
 */
public class SimulatedHeartRateSource implements HeartRateSource {

    private static final double TAU_SECONDS = 50;

    private final int restingHr;
    private final int maxHr;
    private final LongSupplier clockMillis;
    private double peakHr;
    private long peakAtMillis;

    public SimulatedHeartRateSource(int restingHr, int maxHr) {
        this(restingHr, maxHr, System::currentTimeMillis);
    }

    public SimulatedHeartRateSource(int restingHr, int maxHr, LongSupplier clockMillis) {
        this.restingHr = restingHr;
        this.maxHr = maxHr;
        this.clockMillis = clockMillis;
        this.peakHr = restingHr;
        this.peakAtMillis = clockMillis.getAsLong();
    }

    @Override
    public synchronized void onSetCompleted(double rpe) {
        peakHr = maxHr * Math.min(0.85, 0.70 + 0.025 * Math.max(0, rpe - 6));
        peakAtMillis = clockMillis.getAsLong();
    }

    @Override
    public synchronized int currentBpm() {
        double t = (clockMillis.getAsLong() - peakAtMillis) / 1000.0;
        return (int) Math.round(restingHr + (peakHr - restingHr) * Math.exp(-t / TAU_SECONDS));
    }

    @Override
    public String describe() {
        return "Simulated HR stream";
    }
}
