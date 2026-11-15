package com.pulseforge.agent;

import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.MovementType;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmRequest;
import com.pulseforge.llm.ModelInferenceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ConcreteStrategy for F08/UC08: a 7-day deload week with total volume cut ~50% and RPE capped
 * at 6-7, mixing light technique sessions, mobility and rest days.
 */
public class DeloadStrategy implements WorkoutStrategy {

    /** Day layout: label, focus split (null = mobility/rest). */
    private static final String[][] WEEK = {
            {"Day 1", "UPPER"}, {"Day 2", "MOBILITY"}, {"Day 3", "LOWER"}, {"Day 4", "REST"},
            {"Day 5", "FULL_BODY"}, {"Day 6", "MOBILITY"}, {"Day 7", "REST"}};

    @Override
    public String name() {
        return "Deload";
    }

    @Override
    public VolumeLimits computeLimits(StrategyContext ctx) {
        // 50% of the hypertrophy baseline (18 sets) per training day; RPE 6-7.
        double rpeCap = Math.max(6, 7 - ctx.rpeCapAdjustment());
        return new VolumeLimits(9, 6, 10, rpeCap, 50, 65, 0.5);
    }

    @Override
    public WorkoutPlan generatePlan(StrategyContext ctx, LLMClient llm) throws ModelInferenceException {
        VolumeLimits limits = computeLimits(ctx);
        String prompt = String.format("""
                The athlete shows systemic/CNS fatigue (readiness %.0f/100, %s; sleep %s). Build a 7-day DELOAD week.
                Rules: total weekly lifting volume ~50%% of normal, every lifting day at most %d working sets,
                RPE never above %.1f, loads %.0f-%.0f%% 1RM, focus on technique, mobility and light aerobic work.
                Use exactly this layout: Day 1 upper technique, Day 2 mobility, Day 3 lower technique, Day 4 rest
                (walk only, empty exercises), Day 5 light full body, Day 6 mobility + zone 2 cardio, Day 7 rest.
                Respond ONLY with JSON: {"days":[{"dayLabel":"Day 1","title":"string","recoveryNotes":"string",
                "exercises":[same exercise objects as: %s ]}]}""",
                ctx.readinessScore(), ctx.tier().label(), ctx.sleepText(), limits.maxSets(), limits.rpeCap(),
                limits.loadPercentLow(), limits.loadPercentHigh(), WorkoutJsonMapper.ROUTINE_SCHEMA);
        String raw = llm.generate(LlmRequest.builder()
                .system("You are PulseForge AI, a periodization expert. You output strict JSON only.")
                .user(prompt).json(true).temperature(0.4).build());
        Map<String, Object> root;
        try {
            root = Json.parseObjectLenient(raw);
        } catch (JsonException e) {
            throw new ModelInferenceException("Malformed deload JSON from model: " + e.getMessage(), e);
        }
        List<Map<String, Object>> days = Json.objArr(root, "days");
        if (days.size() < 7) {
            throw new ModelInferenceException("Model returned " + days.size() + " deload days instead of 7");
        }
        List<WorkoutRoutine> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> d = days.get(i);
            String label = Json.str(d, "dayLabel", "Day " + (i + 1));
            List<Map<String, Object>> exercises = Json.objArr(d, "exercises");
            if (exercises.isEmpty()) {
                week.add(new WorkoutRoutine(Json.str(d, "title", "Rest day"), "REST", name(), ctx.readinessScore(),
                        Json.str(d, "recoveryNotes", "Rest, light walking, prioritize sleep."), "LLM: " + llm.describe(), label));
            } else {
                week.add(WorkoutJsonMapper.toRoutine(d, WEEK[i][1], name(), ctx.readinessScore(),
                        "LLM: " + llm.describe(), label, limits, 1, 30));
            }
        }
        return new WorkoutPlan(week, true, null);
    }

    @Override
    public WorkoutPlan fallbackPlan(StrategyContext ctx, String reason) {
        VolumeLimits limits = computeLimits(ctx);
        List<WorkoutRoutine> week = new ArrayList<>();
        for (String[] day : WEEK) {
            String label = day[0];
            String focus = day[1];
            switch (focus) {
                case "REST" -> week.add(new WorkoutRoutine("Rest day", "REST", name(), ctx.readinessScore(),
                        "Full rest. 20-30 min easy walk, 8+ hours sleep target.", "Offline fallback template", label));
                case "MOBILITY" -> {
                    WorkoutRoutine r = new WorkoutRoutine("Mobility & Zone 2", "MOBILITY", name(), ctx.readinessScore(),
                            "Easy movement only - keep heart rate conversational.", "Offline fallback template", label);
                    for (ExerciseLibrary.Entry e : ExerciseLibrary.MOBILITY.subList(0, 4)) {
                        r.getExercises().add(new Exercise(e.name(), e.muscleGroup(), e.type(), "").addSets(2, 10, 4, 0));
                    }
                    r.getExercises().add(new Exercise("Zone 2 Cycling (minutes)", "Cardio", MovementType.ISOLATION, "")
                            .addSets(1, 25, 4, 0));
                    week.add(r);
                }
                default -> {
                    WorkoutRoutine r = new WorkoutRoutine(focus.replace('_', ' ') + " technique (deload)", focus, name(),
                            ctx.readinessScore(), "Technique focus: " + limits.describe(), "Offline fallback template", label);
                    List<ExerciseLibrary.Entry> entries = ExerciseLibrary.forSplit(focus);
                    int remaining = limits.maxSets();
                    for (int i = 0; i < 4 && remaining > 0; i++) {
                        ExerciseLibrary.Entry e = entries.get(i);
                        int sets = Math.min(remaining, i < 1 ? 3 : 2);
                        remaining -= sets;
                        double load = e.type() == MovementType.COMPOUND ? limits.loadPercentLow() + 5 : 0;
                        r.getExercises().add(new Exercise(e.name(), e.muscleGroup(), e.type(), "Stop 3-4 reps shy of failure")
                                .addSets(sets, 8, Math.min(6.5, limits.rpeCap()), load));
                    }
                    week.add(r);
                }
            }
        }
        return new WorkoutPlan(week, false, reason);
    }
}
