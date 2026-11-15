package com.pulseforge.agent;

import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.MovementType;
import com.pulseforge.domain.ReadinessTier;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmRequest;
import com.pulseforge.llm.ModelInferenceException;

import java.util.List;

/**
 * Shared algorithm skeleton for single-session strategies (hypertrophy, strength):
 * readiness-scaled limits -> structured prompt -> LLM JSON -> clamp to limits.
 * Concrete strategies only supply their goal-specific parameters and prompt guidance.
 */
public abstract class AbstractSessionStrategy implements WorkoutStrategy {

    protected abstract int baseSets();
    protected abstract int repLow();
    protected abstract int repHigh();
    protected abstract double baseRpeCap();
    protected abstract double loadLow();
    protected abstract double loadHigh();
    protected abstract String guidelines();

    /** Volume multiplier per readiness tier (Stage 1 F05: volume scales with readiness). */
    static double tierMultiplier(ReadinessTier tier) {
        return switch (tier) {
            case PRIME -> 1.0;
            case BALANCED -> 0.85;
            case FATIGUED -> 0.65;
            case DEPLETED -> 0.45;
        };
    }

    static double tierRpeReduction(ReadinessTier tier) {
        return switch (tier) {
            case PRIME -> 0;
            case BALANCED -> 0.5;
            case FATIGUED -> 1.0;
            case DEPLETED -> 2.0;
        };
    }

    @Override
    public VolumeLimits computeLimits(StrategyContext ctx) {
        double mult = tierMultiplier(ctx.tier());
        if (ctx.sleepHours() != null && ctx.sleepHours() < 6) {
            mult *= 0.9;   // acute sleep debt: extra 10% volume cut
        }
        int maxSets = Math.max(6, (int) Math.round(baseSets() * mult));
        double rpeCap = Math.max(5, baseRpeCap() - tierRpeReduction(ctx.tier()) - ctx.rpeCapAdjustment());
        double loadAdj = ctx.tier() == ReadinessTier.DEPLETED ? 10 : ctx.tier() == ReadinessTier.FATIGUED ? 5 : 0;
        return new VolumeLimits(maxSets, repLow(), repHigh(), rpeCap, loadLow() - loadAdj, loadHigh() - loadAdj, mult);
    }

    @Override
    public WorkoutPlan generatePlan(StrategyContext ctx, LLMClient llm) throws ModelInferenceException {
        VolumeLimits limits = computeLimits(ctx);
        String split = ExerciseLibrary.normalizeSplit(ctx.split());
        String prompt = String.format("""
                Design today's %s resistance training session (%s goal).
                Athlete: %.0f kg, goal %s. Equipment: %s.
                Recovery: readiness %.0f/100 (%s)%s, last night's sleep %s.
                HARD LIMITS (deterministic, computed from recovery - do not exceed):
                  %s
                Programming guidelines: %s
                Choose 4-6 exercises, compounds first. Put a short recovery-aware rationale in recoveryNotes.
                Respond ONLY with JSON in this schema:
                %s""",
                split, name(), ctx.bodyWeightKg(), ctx.goal().label(), ctx.equipment(), ctx.readinessScore(),
                ctx.tier().label(), ctx.readinessApproximate() ? " (approximate)" : "", ctx.sleepText(),
                limits.describe(), guidelines(), WorkoutJsonMapper.ROUTINE_SCHEMA);
        String raw = llm.generate(LlmRequest.builder()
                .system("You are PulseForge AI, an evidence-based strength coach. You output strict JSON only.")
                .user(prompt).json(true).temperature(0.5).build());
        try {
            WorkoutRoutine routine = WorkoutJsonMapper.toRoutine(Json.parseObjectLenient(raw), split, name(),
                    ctx.readinessScore(), "LLM: " + llm.describe(), "", limits,
                    Math.max(1, repLow() - 2), repHigh() + 3);
            return WorkoutPlan.single(routine, true, null);
        } catch (JsonException e) {
            throw new ModelInferenceException("Malformed workout JSON from model: " + e.getMessage(), e);
        }
    }

    @Override
    public WorkoutPlan fallbackPlan(StrategyContext ctx, String reason) {
        VolumeLimits limits = computeLimits(ctx);
        String split = ExerciseLibrary.normalizeSplit(ctx.split());
        List<ExerciseLibrary.Entry> entries = ExerciseLibrary.forSplit(split);
        int count = Math.max(3, Math.min(entries.size(), limits.maxSets() / 3));
        int baseSetsEach = limits.maxSets() / count;
        int remainder = limits.maxSets() % count;
        int reps = (repLow() + repHigh()) / 2;
        WorkoutRoutine routine = new WorkoutRoutine(split.replace('_', ' ') + " - " + name() + " (offline template)",
                split, name(), ctx.readinessScore(),
                String.format("Offline template scaled to %s readiness: %s.", ctx.tier().label(), limits.describe()),
                "Offline fallback template", "");
        for (int i = 0; i < count; i++) {
            ExerciseLibrary.Entry e = entries.get(i);
            int sets = baseSetsEach + (i < remainder ? 1 : 0);
            boolean compound = e.type() == MovementType.COMPOUND;
            double rpe = compound ? limits.rpeCap() : Math.max(5, limits.rpeCap() - 0.5);
            int r = compound ? reps : Math.min(repHigh() + 3, reps + 2);
            double load = compound ? Math.round((limits.loadPercentLow() + limits.loadPercentHigh()) / 2) : 0;
            routine.getExercises().add(new Exercise(e.name(), e.muscleGroup(), e.type(), "")
                    .addSets(Math.max(1, sets), r, rpe, load));
        }
        return WorkoutPlan.single(routine, false, reason);
    }
}
