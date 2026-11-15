package com.pulseforge.agent;

import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.ModelInferenceException;

/**
 * Strategy role of the Strategy pattern. Each training goal (hypertrophy, strength, deload)
 * encapsulates its own volume scaling, rep ranges, prompt guidelines and offline template.
 * {@link AgentBridge} is the Context that holds and swaps the active strategy at runtime.
 */
public interface WorkoutStrategy {

    /** Display name, e.g. "Hypertrophy". */
    String name();

    /** Deterministic volume/intensity limits for this readiness context. */
    VolumeLimits computeLimits(StrategyContext ctx);

    /**
     * Generates a plan with the LLM; output is clamped to {@link #computeLimits}.
     *
     * @throws ModelInferenceException if the model call fails or returns unusable output
     */
    WorkoutPlan generatePlan(StrategyContext ctx, LLMClient llm) throws ModelInferenceException;

    /** Deterministic offline template matched to the readiness tier (UC05 alt 3a). */
    WorkoutPlan fallbackPlan(StrategyContext ctx, String reason);
}
