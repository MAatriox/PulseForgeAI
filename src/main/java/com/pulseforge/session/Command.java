package com.pulseforge.session;

/** Command role of the Command pattern: a reversible user action on the live session. */
public interface Command {
    void execute();

    void undo();

    /** Human-readable label for history/undo messages. */
    String description();
}
