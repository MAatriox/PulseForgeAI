package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An exercise inside a routine, with its ordered prescribed sets. */
public class Exercise {
    private final String name;
    private final String muscleGroup;
    private final MovementType movementType;
    private final String notes;
    private final List<ExerciseSet> sets = new ArrayList<>();

    public Exercise(String name, String muscleGroup, MovementType movementType, String notes) {
        this.name = name;
        this.muscleGroup = muscleGroup == null ? "" : muscleGroup;
        this.movementType = movementType;
        this.notes = notes == null ? "" : notes;
    }

    public Exercise addSets(int count, int reps, double rpe, double loadPercent) {
        for (int i = 0; i < count; i++) {
            sets.add(new ExerciseSet(reps, rpe, loadPercent));
        }
        return this;
    }

    public double tonnage() {
        return sets.stream().mapToDouble(ExerciseSet::tonnage).sum();
    }

    public long completedSets() {
        return sets.stream().filter(ExerciseSet::isCompleted).count();
    }

    /** Index of the first set not yet logged, or -1 if all are done. */
    public int nextOpenSetIndex() {
        for (int i = 0; i < sets.size(); i++) {
            if (!sets.get(i).isCompleted()) {
                return i;
            }
        }
        return -1;
    }

    public Map<String, Object> toJson() {
        List<Object> list = new ArrayList<>();
        sets.forEach(s -> list.add(s.toJson()));
        return Json.map("name", name, "muscleGroup", muscleGroup, "movementType", movementType.name(),
                "notes", notes, "sets", list);
    }

    public static Exercise fromJson(Map<String, Object> m) {
        Exercise e = new Exercise(Json.str(m, "name", "Exercise"), Json.str(m, "muscleGroup", ""),
                MovementType.parse(Json.str(m, "movementType", "COMPOUND")), Json.str(m, "notes", ""));
        Json.objArr(m, "sets").forEach(s -> e.sets.add(ExerciseSet.fromJson(s)));
        return e;
    }

    public String getName() { return name; }
    public String getMuscleGroup() { return muscleGroup; }
    public MovementType getMovementType() { return movementType; }
    public String getNotes() { return notes; }
    public List<ExerciseSet> getSets() { return sets; }
}
