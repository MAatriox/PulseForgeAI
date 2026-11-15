package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.time.LocalDateTime;
import java.util.Map;

/** A completed (or in-progress) gym session wrapping the routine that was performed. */
public class WorkoutSession {
    private final WorkoutRoutine routine;
    private final LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public WorkoutSession(WorkoutRoutine routine, LocalDateTime startedAt) {
        this.routine = routine;
        this.startedAt = startedAt;
    }

    public void finish(LocalDateTime when) {
        this.endedAt = when;
    }

    public double tonnage() {
        return routine.tonnage();
    }

    /**
     * Rough active-energy estimate for logged lifting: ~8 kcal per compound set and
     * ~5 kcal per isolation set, plus 0.02 kcal per kg of tonnage.
     */
    public double estimatedKcal() {
        double kcal = 0;
        for (Exercise e : routine.getExercises()) {
            kcal += e.completedSets() * (e.getMovementType() == MovementType.COMPOUND ? 8 : 5);
        }
        return kcal + tonnage() * 0.02;
    }

    public Map<String, Object> toJson() {
        return Json.map("routine", routine.toJson(), "startedAt", startedAt.toString(),
                "endedAt", endedAt == null ? null : endedAt.toString());
    }

    public static WorkoutSession fromJson(Map<String, Object> m) {
        WorkoutSession s = new WorkoutSession(WorkoutRoutine.fromJson(Json.obj(m, "routine")),
                LocalDateTime.parse(Json.str(m, "startedAt", LocalDateTime.now().toString())));
        String end = Json.str(m, "endedAt", null);
        if (end != null) {
            s.endedAt = LocalDateTime.parse(end);
        }
        return s;
    }

    public WorkoutRoutine getRoutine() { return routine; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
}
