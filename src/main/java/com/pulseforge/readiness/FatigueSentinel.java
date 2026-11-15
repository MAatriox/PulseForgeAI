package com.pulseforge.readiness;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.FatigueAssessment.Risk;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.WorkoutSession;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic half of F08: scans a 14-day window for consecutive days of suppressed HRV
 * combined with sleep debt, consecutive low-readiness days, and rising lifting tonnage.
 * The AI diagnosis is layered on top by {@code AgentBridge.diagnoseFatigue}.
 */
public class FatigueSentinel {

    public static final int WINDOW_DAYS = 14;
    /** HRV z-score at or below this counts as suppressed. */
    public static final double SUPPRESSED_HRV_Z = -0.5;
    /** Less sleep than this (minutes) counts as sleep debt. */
    public static final double SLEEP_DEBT_MINUTES = 420;
    public static final int TRIGGER_DAYS = 3;
    public static final double LOW_READINESS = 40;

    private final ReadinessCalculator calculator;

    public FatigueSentinel(ReadinessCalculator calculator) {
        this.calculator = calculator;
    }

    public FatigueAssessment evaluate(AthleteProfile profile) {
        LocalDate end = ReadinessCalculator.latestScorableDay(profile.getRecords());
        if (end == null) {
            end = LocalDate.now();
        }
        LocalDate start = end.minusDays(WINDOW_DAYS - 1);

        List<LocalDate> missing = new ArrayList<>();
        StringBuilder table = new StringBuilder(String.format("%-10s %6s %5s %6s %5s%n", "date", "HRV", "RHR", "sleep", "ready"));
        boolean[] flagged = new boolean[WINDOW_DAYS];
        boolean[] present = new boolean[WINDOW_DAYS];
        double[] readiness = new double[WINDOW_DAYS];

        for (int i = 0; i < WINDOW_DAYS; i++) {
            LocalDate d = start.plusDays(i);
            BiometricRecord r = profile.getRecords().get(d);
            if (r == null) {
                missing.add(d);
                table.append(String.format("%-10s %6s %5s %6s %5s ?%n", d, "-", "-", "-", "-"));
                continue;
            }
            present[i] = true;
            ReadinessResult rr = calculator.computeFor(profile.getRecords(), d);
            readiness[i] = rr.score();
            boolean suppressed = r.getHrvMs() != null && rr.hrvComponent() <= SUPPRESSED_HRV_Z;
            boolean sleepDebt = r.getSleepMinutes() != null && r.getSleepMinutes() < SLEEP_DEBT_MINUTES;
            flagged[i] = suppressed && sleepDebt;
            table.append(String.format("%-10s %6s %5s %6s %5.0f%s%n", d, fmt(r.getHrvMs()), fmt(r.getRestingHr()),
                    r.getSleepHours() == null ? "-" : String.format("%.1f", r.getSleepHours()), rr.score(),
                    flagged[i] ? " *" : ""));
        }

        int consecutiveFlagged = 0;
        for (int i = WINDOW_DAYS - 1; i >= 0 && present[i] && flagged[i]; i--) {
            consecutiveFlagged++;
        }
        int consecutiveLow = 0;
        for (int i = WINDOW_DAYS - 1; i >= 0 && present[i] && readiness[i] < LOW_READINESS; i--) {
            consecutiveLow++;
        }

        // Tonnage trend uses the calendar weeks ending today (sessions are logged in real time).
        LocalDate today = LocalDate.now();
        double last7 = tonnage(profile.sessionsBetween(today.minusDays(6), today));
        double prev7 = tonnage(profile.sessionsBetween(today.minusDays(13), today.minusDays(7)));
        boolean tonnageRising = prev7 > 0 && last7 > prev7 * 1.2;

        Risk risk;
        if (consecutiveFlagged >= 5 || consecutiveLow >= TRIGGER_DAYS || (consecutiveFlagged >= TRIGGER_DAYS && tonnageRising)) {
            risk = Risk.CRITICAL;
        } else if (consecutiveFlagged >= TRIGGER_DAYS || (consecutiveFlagged >= 2 && tonnageRising) || missing.size() >= 4) {
            risk = Risk.ELEVATED;
        } else {
            risk = Risk.LOW;
        }
        boolean deload = consecutiveFlagged >= TRIGGER_DAYS || consecutiveLow >= TRIGGER_DAYS;
        return new FatigueAssessment(start, end, consecutiveFlagged, consecutiveLow, last7, prev7, missing, risk, deload,
                table.toString());
    }

    private static double tonnage(List<WorkoutSession> sessions) {
        return sessions.stream().mapToDouble(WorkoutSession::tonnage).sum();
    }

    private static String fmt(Double d) {
        return d == null ? "-" : String.valueOf(Math.round(d));
    }
}
