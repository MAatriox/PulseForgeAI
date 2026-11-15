package com.pulseforge.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a WorkoutStrategy: one routine for a normal day, or seven for a deload week.
 * (Stage 1 had generatePlan() return a single WorkoutRoutine; a deload week needed a container.)
 */
public class WorkoutPlan {
    private final List<WorkoutRoutine> routines = new ArrayList<>();
    private final boolean fromLlm;
    private final String fallbackReason;

    public WorkoutPlan(List<WorkoutRoutine> routines, boolean fromLlm, String fallbackReason) {
        this.routines.addAll(routines);
        this.fromLlm = fromLlm;
        this.fallbackReason = fallbackReason;
    }

    public static WorkoutPlan single(WorkoutRoutine r, boolean fromLlm, String fallbackReason) {
        return new WorkoutPlan(List.of(r), fromLlm, fallbackReason);
    }

    public WorkoutRoutine first() {
        return routines.get(0);
    }

    public List<WorkoutRoutine> getRoutines() { return routines; }
    public boolean isFromLlm() { return fromLlm; }
    /** Why the deterministic fallback was used, or null when the LLM succeeded. */
    public String getFallbackReason() { return fallbackReason; }
}
