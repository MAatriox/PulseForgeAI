package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.util.Map;

/** A prescribed set (targets) plus what was actually performed once logged. */
public class ExerciseSet {
    private final int targetReps;
    private final double targetRpe;
    private final double loadPercent;   // % of estimated 1RM, 0 when unspecified

    private boolean completed;
    private double actualWeight;
    private int actualReps;
    private double actualRpe;
    private Integer restSecondsTaken;

    public ExerciseSet(int targetReps, double targetRpe, double loadPercent) {
        this.targetReps = targetReps;
        this.targetRpe = targetRpe;
        this.loadPercent = loadPercent;
    }

    /** A copy with the same targets but no logged performance (used to snapshot/restore state). */
    public ExerciseSet copy() {
        ExerciseSet s = new ExerciseSet(targetReps, targetRpe, loadPercent);
        s.completed = completed;
        s.actualWeight = actualWeight;
        s.actualReps = actualReps;
        s.actualRpe = actualRpe;
        s.restSecondsTaken = restSecondsTaken;
        return s;
    }

    public void restoreFrom(ExerciseSet snapshot) {
        completed = snapshot.completed;
        actualWeight = snapshot.actualWeight;
        actualReps = snapshot.actualReps;
        actualRpe = snapshot.actualRpe;
        restSecondsTaken = snapshot.restSecondsTaken;
    }

    public void complete(double weight, int reps, double rpe) {
        this.completed = true;
        this.actualWeight = weight;
        this.actualReps = reps;
        this.actualRpe = rpe;
    }

    public void clear() {
        completed = false;
        actualWeight = 0;
        actualReps = 0;
        actualRpe = 0;
        restSecondsTaken = null;
    }

    /** Volume load contributed by this set (weight x reps). */
    public double tonnage() {
        return completed ? actualWeight * actualReps : 0;
    }

    public Map<String, Object> toJson() {
        return Json.map("targetReps", (double) targetReps, "targetRpe", targetRpe, "loadPercent", loadPercent,
                "completed", completed, "actualWeight", actualWeight, "actualReps", (double) actualReps,
                "actualRpe", actualRpe, "restSecondsTaken", restSecondsTaken == null ? null : (double) restSecondsTaken);
    }

    public static ExerciseSet fromJson(Map<String, Object> m) {
        ExerciseSet s = new ExerciseSet((int) Json.num(m, "targetReps", 8), Json.num(m, "targetRpe", 7),
                Json.num(m, "loadPercent", 0));
        s.completed = Json.bool(m, "completed", false);
        s.actualWeight = Json.num(m, "actualWeight", 0);
        s.actualReps = (int) Json.num(m, "actualReps", 0);
        s.actualRpe = Json.num(m, "actualRpe", 0);
        Double rest = Json.num(m, "restSecondsTaken");
        s.restSecondsTaken = rest == null ? null : rest.intValue();
        return s;
    }

    public int getTargetReps() { return targetReps; }
    public double getTargetRpe() { return targetRpe; }
    public double getLoadPercent() { return loadPercent; }
    public boolean isCompleted() { return completed; }
    public double getActualWeight() { return actualWeight; }
    public int getActualReps() { return actualReps; }
    public double getActualRpe() { return actualRpe; }
    public Integer getRestSecondsTaken() { return restSecondsTaken; }
    public void setRestSecondsTaken(Integer s) { restSecondsTaken = s; }
}
