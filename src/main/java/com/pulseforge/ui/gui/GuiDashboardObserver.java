package com.pulseforge.ui.gui;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.ProfileEvent;
import com.pulseforge.domain.ProfileObserver;

import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;

/**
 * ConcreteObserver for the GUI: marshals profile change notifications onto the Swing event
 * thread, redraws the readiness gauge, and lets the window refresh the affected widgets.
 */
public class GuiDashboardObserver implements ProfileObserver {

    private final ReadinessGauge gauge;
    private final BiConsumer<AthleteProfile, ProfileEvent> refresher;

    public GuiDashboardObserver(ReadinessGauge gauge, BiConsumer<AthleteProfile, ProfileEvent> refresher) {
        this.gauge = gauge;
        this.refresher = refresher;
    }

    @Override
    public void onProfileUpdated(AthleteProfile profile, ProfileEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event == ProfileEvent.READINESS_UPDATED) {
                gauge.setResult(profile.getLatestReadiness());
            }
            refresher.accept(profile, event);
        });
    }
}
