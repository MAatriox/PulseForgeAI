package com.pulseforge.agent;

import com.pulseforge.domain.ReadinessTier;
import com.pulseforge.domain.TrainingGoal;

/**
 * Inputs a WorkoutStrategy needs (UC05 step 2: readiness, sleep and constraints injected into
 * the strategy context).
 */
public record StrategyContext(
        String split,
        double readinessScore,
        ReadinessTier tier,
        Double sleepHours,
        boolean readinessApproximate,
        TrainingGoal goal,
        int rpeCapAdjustment,
        String equipment,
        double bodyWeightKg) {

    public String sleepText() {
        return sleepHours == null ? "unknown" : String.format("%.1f h", sleepHours);
    }
}
