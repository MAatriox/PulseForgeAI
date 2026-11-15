package com.pulseforge.session;

import com.pulseforge.domain.Exercise;

/** ConcreteCommand: removes an exercise from the live session; undo re-inserts it at the same position. */
public class DeleteExerciseCommand implements Command {

    private final WorkoutSessionManager receiver;
    private final int index;
    private Exercise removed;

    public DeleteExerciseCommand(WorkoutSessionManager receiver, int index) {
        if (!receiver.hasExercise(index)) {
            throw new IllegalArgumentException("No exercise #" + (index + 1) + " in the current session");
        }
        this.receiver = receiver;
        this.index = index;
    }

    @Override
    public void execute() {
        removed = receiver.removeExercise(index);
    }

    @Override
    public void undo() {
        receiver.insertExercise(index, removed);
    }

    @Override
    public String description() {
        return "delete exercise " + (removed != null ? removed.getName() : "#" + (index + 1));
    }
}
