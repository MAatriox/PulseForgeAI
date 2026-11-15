package com.pulseforge.session;

/** Observer of the rest timer: receives a tick every second and a final notification. */
public interface TimerListener {

    /**
     * @param remaining       seconds left on the countdown (0 while holding for heart rate)
     * @param total           current total duration (grows with "+30s")
     * @param holdingForHr    true when the countdown is done but HR is still above the recovery threshold
     * @param heartRate       latest heart rate, or -1 when no HR source is connected
     */
    void onTick(int remaining, int total, boolean holdingForHr, int heartRate);

    /** @param reason "complete", "skipped" or "next set started early" */
    void onFinished(int actualRestSeconds, String reason);
}
