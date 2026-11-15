package com.pulseforge.domain;

/**
 * Observer role of the Observer pattern. GUI widgets, the CLI console and the persistence
 * layer implement this to react to {@link AthleteProfile} state changes without the domain
 * model holding references to any concrete view.
 */
public interface ProfileObserver {
    void onProfileUpdated(AthleteProfile profile, ProfileEvent event);
}
