package com.pulseforge.session;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Invoker role of the Command pattern: executes commands and keeps multi-level undo/redo stacks.
 * Executing a new command clears the redo stack (standard editor semantics).
 */
public class CommandInvoker {

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    public synchronized void executeCommand(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();
    }

    /** @return the undone command, or empty when there is nothing to undo (UC06a alt 1a) */
    public synchronized Optional<Command> undo() {
        if (undoStack.isEmpty()) {
            return Optional.empty();
        }
        Command c = undoStack.pop();
        c.undo();
        redoStack.push(c);
        return Optional.of(c);
    }

    public synchronized Optional<Command> redo() {
        if (redoStack.isEmpty()) {
            return Optional.empty();
        }
        Command c = redoStack.pop();
        c.execute();
        undoStack.push(c);
        return Optional.of(c);
    }

    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
