package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Aggregate: an ordered list of exercises with prescriptions, produced by a WorkoutStrategy. */
public class WorkoutRoutine {

    private final String title;
    private final String split;
    private final String strategyName;
    private final double readinessScore;
    private final String recoveryNotes;
    private final String generatedBy;   // "LLM: <model>" or "Offline fallback template"
    private final String dayLabel;      // e.g. "Day 3" in a deload week, otherwise ""
    private final List<Exercise> exercises = new ArrayList<>();

    public WorkoutRoutine(String title, String split, String strategyName, double readinessScore,
                          String recoveryNotes, String generatedBy, String dayLabel) {
        this.title = title;
        this.split = split;
        this.strategyName = strategyName;
        this.readinessScore = readinessScore;
        this.recoveryNotes = recoveryNotes == null ? "" : recoveryNotes;
        this.generatedBy = generatedBy;
        this.dayLabel = dayLabel == null ? "" : dayLabel;
    }

    public int totalSets() {
        return exercises.stream().mapToInt(e -> e.getSets().size()).sum();
    }

    public double tonnage() {
        return exercises.stream().mapToDouble(Exercise::tonnage).sum();
    }

    public long completedSets() {
        return exercises.stream().mapToLong(Exercise::completedSets).sum();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(dayLabel.isEmpty() ? "" : dayLabel + " - ").append(title)
                .append(" [").append(strategyName).append(", ").append(totalSets()).append(" sets]\n");
        for (int i = 0; i < exercises.size(); i++) {
            Exercise e = exercises.get(i);
            ExerciseSet first = e.getSets().isEmpty() ? null : e.getSets().get(0);
            sb.append(String.format("  %d. %-28s %d x %s @ RPE %s%s%n", i + 1, e.getName(), e.getSets().size(),
                    first == null ? "-" : first.getTargetReps(),
                    first == null ? "-" : trim(first.getTargetRpe()),
                    first != null && first.getLoadPercent() > 0 ? "  (~" + Math.round(first.getLoadPercent()) + "% 1RM)" : ""));
        }
        if (!recoveryNotes.isBlank()) {
            sb.append("  Notes: ").append(recoveryNotes).append('\n');
        }
        sb.append("  Source: ").append(generatedBy).append('\n');
        return sb.toString();
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public Map<String, Object> toJson() {
        List<Object> list = new ArrayList<>();
        exercises.forEach(e -> list.add(e.toJson()));
        return Json.map("title", title, "split", split, "strategyName", strategyName,
                "readinessScore", readinessScore, "recoveryNotes", recoveryNotes, "generatedBy", generatedBy,
                "dayLabel", dayLabel, "exercises", list);
    }

    public static WorkoutRoutine fromJson(Map<String, Object> m) {
        WorkoutRoutine r = new WorkoutRoutine(Json.str(m, "title", "Workout"), Json.str(m, "split", ""),
                Json.str(m, "strategyName", ""), Json.num(m, "readinessScore", 0), Json.str(m, "recoveryNotes", ""),
                Json.str(m, "generatedBy", ""), Json.str(m, "dayLabel", ""));
        Json.objArr(m, "exercises").forEach(e -> r.exercises.add(Exercise.fromJson(e)));
        return r;
    }

    public String getTitle() { return title; }
    public String getSplit() { return split; }
    public String getStrategyName() { return strategyName; }
    public double getReadinessScore() { return readinessScore; }
    public String getRecoveryNotes() { return recoveryNotes; }
    public String getGeneratedBy() { return generatedBy; }
    public String getDayLabel() { return dayLabel; }
    public List<Exercise> getExercises() { return exercises; }
}
