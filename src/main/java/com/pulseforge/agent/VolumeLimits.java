package com.pulseforge.agent;

/**
 * Deterministic guard-rails computed from readiness before the LLM is consulted. Whatever the
 * model proposes is clamped to these bounds, so the AI can choose exercises but cannot
 * prescribe more volume or intensity than recovery allows.
 */
public record VolumeLimits(int maxSets, int repLow, int repHigh, double rpeCap,
                           double loadPercentLow, double loadPercentHigh, double volumeMultiplier) {

    public String describe() {
        return String.format("max %d working sets, %d-%d reps, RPE cap %.1f, load %.0f-%.0f%% 1RM (volume x%.2f)",
                maxSets, repLow, repHigh, rpeCap, loadPercentLow, loadPercentHigh, volumeMultiplier);
    }
}
