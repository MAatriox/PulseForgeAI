package com.pulseforge.domain;

/** Describes what changed in an {@link AthleteProfile}, so observers can refresh selectively. */
public enum ProfileEvent {
    BIOMETRICS_IMPORTED,
    READINESS_UPDATED,
    MEAL_LOGGED,
    SESSION_COMPLETED,
    SETTINGS_CHANGED,
    DELOAD_UPDATED,
    COACH_NOTE_ADDED
}
