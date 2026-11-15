package com.pulseforge.session;

import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;

/**
 * ConcreteCommand: records weight/reps/RPE on the next open set of an exercise (or appends an
 * extra set if all prescribed sets are done). Captures a snapshot so undo restores the exact
 * previous state. The first execution also starts the adaptive rest timer (UC06 step 5 -> UC07).
 */
public class LogSetCommand implements Command {

    private final WorkoutSessionManager receiver;
    private final int exerciseIndex;
    private final double weight;
    private final int reps;
    private final double rpe;

    private int setIndex = -1;
    private boolean appended;
    private ExerciseSet snapshot;
    private boolean executedOnce;

    public LogSetCommand(WorkoutSessionManager receiver, int exerciseIndex, double weight, int reps, double rpe) {
        validate(weight, reps, rpe);
        this.receiver = receiver;
        this.exerciseIndex = exerciseIndex;
        this.weight = weight;
        this.reps = reps;
        this.rpe = rpe;
    }

    /** UC06 alt 1a: reject invalid input before any state is mutated. */
    public static void validate(double weight, int reps, double rpe) {
        if (Double.isNaN(weight) || weight < 0) throw new IllegalArgumentException("Weight must be 0 or more");
        if (weight > 1000) throw new IllegalArgumentException("Weight looks unrealistic (> 1000)");
        if (reps <= 0 || reps > 100) throw new IllegalArgumentException("Reps must be between 1 and 100");
        if (Double.isNaN(rpe) || rpe < 1 || rpe > 10) throw new IllegalArgumentException("RPE must be between 1 and 10");
    }

    @Override
    public void execute() {
        Exercise ex = receiver.exerciseAt(exerciseIndex);
        if (setIndex < 0) {
            setIndex = ex.nextOpenSetIndex();
            if (setIndex < 0) {
                ExerciseSet template = ex.getSets().isEmpty() ? null : ex.getSets().get(ex.getSets().size() - 1);
                setIndex = receiver.appendSet(exerciseIndex, template == null
                        ? new ExerciseSet(reps, rpe, 0)
                        : new ExerciseSet(template.getTargetReps(), template.getTargetRpe(), template.getLoadPercent()));
                appended = true;
            }
        } else if (appended) {
            // redo of an appended extra set: re-add it
            setIndex = receiver.appendSet(exerciseIndex, snapshot.copy());
        }
        snapshot = ex.getSets().get(setIndex).copy();
        receiver.recordSet(exerciseIndex, setIndex, weight, reps, rpe);
        if (!executedOnce) {
            executedOnce = true;
            receiver.startTimer(exerciseIndex, setIndex);
        }
    }

    @Override
    public void undo() {
        if (appended) {
            receiver.removeSet(exerciseIndex, setIndex);
        } else {
            receiver.restoreSet(exerciseIndex, setIndex, snapshot);
        }
    }

    @Override
    public String description() {
        String name = receiver.hasExercise(exerciseIndex) ? receiver.exerciseAt(exerciseIndex).getName() : "exercise";
        return String.format("log set %d of %s (%s x %d @ RPE %s)", setIndex + 1, name, fmt(weight), reps, fmt(rpe));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
