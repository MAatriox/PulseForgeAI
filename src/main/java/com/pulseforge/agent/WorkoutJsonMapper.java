package com.pulseforge.agent;

import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;
import com.pulseforge.domain.MovementType;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.json.Json;
import com.pulseforge.llm.ModelInferenceException;

import java.util.List;
import java.util.Map;

/**
 * Deserializes LLM JSON into domain objects and enforces {@link VolumeLimits} on the result.
 */
public final class WorkoutJsonMapper {

    public static final String ROUTINE_SCHEMA = """
            {"title":"string","recoveryNotes":"string",
             "exercises":[{"name":"string","muscleGroup":"string","movementType":"COMPOUND or ISOLATION",
                           "sets":integer,"reps":integer,"targetRpe":number,"loadPercent":number (percent of 1RM, 0 if bodyweight),
                           "notes":"string"}]}""";

    private WorkoutJsonMapper() {
    }

    public static WorkoutRoutine toRoutine(Map<String, Object> json, String split, String strategyName,
                                           double readiness, String generatedBy, String dayLabel,
                                           VolumeLimits limits, int minReps, int maxReps) throws ModelInferenceException {
        List<Map<String, Object>> exercises = Json.objArr(json, "exercises");
        WorkoutRoutine routine = new WorkoutRoutine(Json.str(json, "title", split + " session"), split, strategyName,
                readiness, Json.str(json, "recoveryNotes", ""), generatedBy, dayLabel);
        for (Map<String, Object> e : exercises) {
            String name = Json.str(e, "name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            int sets = (int) Math.round(Json.num(e, "sets", 3));
            int reps = (int) Math.round(Json.num(e, "reps", (limits.repLow() + limits.repHigh()) / 2.0));
            double rpe = Json.num(e, "targetRpe", limits.rpeCap());
            double load = Json.num(e, "loadPercent", 0);
            if (sets <= 0) {
                continue;
            }
            reps = Math.max(minReps, Math.min(maxReps, reps));
            rpe = Math.max(3, Math.min(limits.rpeCap(), rpe));
            load = load <= 0 ? 0 : Math.max(limits.loadPercentLow() * 0.8, Math.min(limits.loadPercentHigh(), load));
            Exercise ex = new Exercise(name, Json.str(e, "muscleGroup", ""),
                    MovementType.parse(Json.str(e, "movementType", "COMPOUND")), Json.str(e, "notes", ""));
            ex.addSets(Math.min(sets, 8), reps, rpe, load);
            routine.getExercises().add(ex);
        }
        if (routine.getExercises().isEmpty()) {
            throw new ModelInferenceException("Model returned a routine with no usable exercises");
        }
        enforceMaxSets(routine, limits.maxSets());
        return routine;
    }

    /** Trims sets from the end of the routine (isolation work first) until within the cap. */
    static void enforceMaxSets(WorkoutRoutine routine, int maxSets) {
        List<Exercise> list = routine.getExercises();
        while (routine.totalSets() > maxSets) {
            Exercise target = null;
            for (int i = list.size() - 1; i >= 0; i--) {
                Exercise e = list.get(i);
                if (e.getSets().size() > 1 && e.getMovementType() == MovementType.ISOLATION) {
                    target = e;
                    break;
                }
            }
            if (target == null) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).getSets().size() > 1) {
                        target = list.get(i);
                        break;
                    }
                }
            }
            if (target == null) {
                list.remove(list.size() - 1);   // every exercise has one set: drop the last exercise
            } else {
                List<ExerciseSet> sets = target.getSets();
                sets.remove(sets.size() - 1);
            }
        }
    }
}
