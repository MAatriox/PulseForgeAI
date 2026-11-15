package com.pulseforge.domain;

import java.time.LocalDate;

/**
 * F04 output: deterministic energy/protein reconciliation plus (optionally) AI fueling advice.
 */
public class EnergySufficiencyReport {

    public enum Status {
        SUFFICED("Sufficed"),
        UNDER_FUELED("Under-Fueled"),
        HYPER_CALORIC_SURPLUS("Hyper-Caloric Surplus");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final LocalDate date;
    private final double bmr;
    private final double wearableActiveKcal;
    private final double workoutKcal;
    private final double consumedKcal;
    private final double proteinConsumedG;
    private final double proteinTargetG;
    private final int mealsLogged;
    private final int workoutsLogged;
    private final Status status;
    private final boolean underFueledAlert;
    private String advice = "";
    private String adviceSource = "";

    public EnergySufficiencyReport(LocalDate date, double bmr, double wearableActiveKcal, double workoutKcal,
                                   double consumedKcal, double proteinConsumedG, double proteinTargetG,
                                   int mealsLogged, int workoutsLogged, Status status, boolean underFueledAlert) {
        this.date = date;
        this.bmr = bmr;
        this.wearableActiveKcal = wearableActiveKcal;
        this.workoutKcal = workoutKcal;
        this.consumedKcal = consumedKcal;
        this.proteinConsumedG = proteinConsumedG;
        this.proteinTargetG = proteinTargetG;
        this.mealsLogged = mealsLogged;
        this.workoutsLogged = workoutsLogged;
        this.status = status;
        this.underFueledAlert = underFueledAlert;
    }

    public double burnedKcal() {
        return bmr + wearableActiveKcal + workoutKcal;
    }

    /** Positive = surplus, negative = deficit. */
    public double netKcal() {
        return consumedKcal - burnedKcal();
    }

    public double proteinDeficitG() {
        return Math.max(0, proteinTargetG - proteinConsumedG);
    }

    public String summary() {
        return String.format(
                "Energy %s: burned %.0f kcal (BMR %.0f + wearable %.0f + lifting %.0f), consumed %.0f kcal, net %+.0f kcal. "
                        + "Protein %.0f/%.0f g. Status: %s%s",
                date, burnedKcal(), bmr, wearableActiveKcal, workoutKcal, consumedKcal, netKcal(),
                proteinConsumedG, proteinTargetG, status.label(), underFueledAlert ? " [UNDER-FUELED ALERT]" : "");
    }

    public void setAdvice(String advice, String source) {
        this.advice = advice;
        this.adviceSource = source;
    }

    public LocalDate getDate() { return date; }
    public double getBmr() { return bmr; }
    public double getWearableActiveKcal() { return wearableActiveKcal; }
    public double getWorkoutKcal() { return workoutKcal; }
    public double getConsumedKcal() { return consumedKcal; }
    public double getProteinConsumedG() { return proteinConsumedG; }
    public double getProteinTargetG() { return proteinTargetG; }
    public int getMealsLogged() { return mealsLogged; }
    public int getWorkoutsLogged() { return workoutsLogged; }
    public Status getStatus() { return status; }
    public boolean isUnderFueledAlert() { return underFueledAlert; }
    public String getAdvice() { return advice; }
    public String getAdviceSource() { return adviceSource; }
}
