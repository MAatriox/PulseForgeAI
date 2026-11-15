package com.pulseforge.session;

import com.pulseforge.domain.MovementType;

/**
 * F07 rest-length rules: compound lifts need longer recovery than isolation lifts, and harder
 * sets (higher RPE) need more rest.
 * <ul>
 *   <li>Compound: 90 s + 30 s per RPE point above 6 (heavy deadlift @ RPE 9 = 180 s)</li>
 *   <li>Isolation: 60 s + 15 s per RPE point above 6 (biceps curl @ RPE 7 = 75 s)</li>
 * </ul>
 */
public class SmartRestEngine {

    public int computeRestSeconds(MovementType type, double rpe) {
        double r = Math.max(1, Math.min(10, rpe));
        double seconds = type == MovementType.COMPOUND
                ? clamp(90 + (r - 6) * 30, 60, 240)
                : clamp(60 + (r - 6) * 15, 45, 150);
        return (int) (Math.round(seconds / 5.0) * 5);
    }

    /** Heart rate below which the athlete is considered recovered: 60% of max HR. */
    public int recoveryHeartRate(int maxHeartRate) {
        return (int) Math.round(maxHeartRate * 0.6);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
