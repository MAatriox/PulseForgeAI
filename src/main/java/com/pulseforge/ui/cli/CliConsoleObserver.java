package com.pulseforge.ui.cli;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.ProfileEvent;
import com.pulseforge.domain.ProfileObserver;
import com.pulseforge.domain.ReadinessResult;

import java.io.PrintStream;

/** ConcreteObserver: re-renders the relevant terminal status line whenever the profile changes. */
public class CliConsoleObserver implements ProfileObserver {

    private final PrintStream out;

    public CliConsoleObserver(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onProfileUpdated(AthleteProfile profile, ProfileEvent event) {
        switch (event) {
            case READINESS_UPDATED -> {
                ReadinessResult r = profile.getLatestReadiness();
                out.println("  [dashboard] " + r.summary() + " | sleep score " + Math.round(r.sleepScore()) + "/100");
                if (r.approximation()) {
                    out.println("  [dashboard] " + r.message());
                }
            }
            case BIOMETRICS_IMPORTED -> out.println("  [dashboard] Biometric history now covers "
                    + profile.getRecords().size() + " day(s)");
            case MEAL_LOGGED -> out.println("  [dashboard] Meal logged (" + profile.getMeals().size() + " total)");
            case SESSION_COMPLETED -> out.println("  [dashboard] Session saved to history ("
                    + profile.getSessions().size() + " total)");
            case DELOAD_UPDATED -> out.println("  [dashboard] Deload state updated");
            default -> { }
        }
    }
}
