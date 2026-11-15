package com.pulseforge.nutrition;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.EnergySufficiencyReport.Status;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.TrainingGoal;
import com.pulseforge.domain.WorkoutSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Deterministic half of F04: burned = BMR + wearable active energy + estimated lifting energy;
 * consumed = sum of logged meals. Status thresholds depend on the athlete's goal so that a
 * planned fat-loss deficit is not flagged the same way as under-fueling during a bulk.
 */
public class EnergyBalanceCalculator {

    /** Evening cut-off for the "workouts but no meals" alert (UC04 alt 1a). */
    public static final int UNDER_FUELED_ALERT_HOUR = 19;

    public EnergySufficiencyReport calculate(AthleteProfile profile, LocalDate date, LocalDateTime now) {
        List<MealLog> meals = profile.mealsOn(date);
        List<WorkoutSession> sessions = profile.sessionsOn(date);

        double consumed = meals.stream().mapToDouble(MealLog::totalCalories).sum();
        double protein = meals.stream().mapToDouble(MealLog::totalProtein).sum();
        double lifting = sessions.stream().mapToDouble(WorkoutSession::estimatedKcal).sum();
        double wearable = wearableActive(profile, date);

        double burned = profile.bmr() + wearable + lifting;
        double net = consumed - burned;
        double[] t = thresholds(profile.getGoal());

        Status status;
        if (net < t[0] || (meals.isEmpty() && !sessions.isEmpty())) {
            status = Status.UNDER_FUELED;
        } else if (net > t[1]) {
            status = Status.HYPER_CALORIC_SURPLUS;
        } else {
            status = Status.SUFFICED;
        }
        boolean alert = !sessions.isEmpty() && meals.isEmpty()
                && date.equals(now.toLocalDate()) && now.getHour() >= UNDER_FUELED_ALERT_HOUR;

        return new EnergySufficiencyReport(date, profile.bmr(), wearable, lifting, consumed, protein,
                profile.proteinTargetG(), meals.size(), sessions.size(), status, alert);
    }

    /**
     * Active energy for the date from the wearable; if that day was not imported, the mean of
     * the 7 most recent imported days is used as an estimate.
     */
    public static double wearableActive(AthleteProfile profile, LocalDate date) {
        BiometricRecord r = profile.getRecords().get(date);
        if (r != null && r.getActiveEnergyKcal() != null) {
            return r.getActiveEnergyKcal();
        }
        return profile.getRecords().headMap(date, false).descendingMap().values().stream()
                .filter(x -> x.getActiveEnergyKcal() != null)
                .limit(7)
                .mapToDouble(BiometricRecord::getActiveEnergyKcal)
                .average().orElse(0);
    }

    /** {under-fueled below, surplus above} net kcal thresholds per goal. */
    static double[] thresholds(TrainingGoal goal) {
        return switch (goal) {
            case FAT_LOSS -> new double[]{-750, 250};
            case HYPERTROPHY -> new double[]{-300, 700};
            case STRENGTH -> new double[]{-400, 600};
            default -> new double[]{-500, 500};
        };
    }
}
