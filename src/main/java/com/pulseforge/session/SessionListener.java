package com.pulseforge.session;

/** Observer for live-session changes (set logged/undone, exercise removed, session started/finished). */
public interface SessionListener {
    void onSessionChanged(WorkoutSessionManager manager);
}
