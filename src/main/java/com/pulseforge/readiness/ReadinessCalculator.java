package com.pulseforge.readiness;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.ReadinessTier;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.function.Function;

/**
 * F03 - deterministic Recovery &amp; Readiness Index (0-100).
 * <p>
 * The day's HRV, resting HR, sleep duration and deep sleep are converted to z-scores
 * against a rolling 7-day baseline (the 7 days before the scored day). The weighted
 * composite z (HRV 35%, RHR 25%, sleep 25%, deep sleep 15%) maps to a relative score
 * {@code 50 + 20z}. That is blended 65/35 with an absolute sleep-architecture score
 * (duration, efficiency, REM %, deep minutes) so that a chronically short sleeper does not
 * look "normal" just because their baseline is also poor.
 * <p>
 * With fewer than 3 baseline days, population defaults are used and the result is flagged
 * as an approximation (UC02 alt 1a).
 */
public class ReadinessCalculator {

    public static final int BASELINE_DAYS = 7;
    public static final int MIN_BASELINE_DAYS = 3;

    // Population default baselines {mean, sd}
    static final double[] DEFAULT_HRV = {55, 15};
    static final double[] DEFAULT_RHR = {60, 5};
    static final double[] DEFAULT_SLEEP = {450, 45};
    static final double[] DEFAULT_DEEP = {85, 20};

    private static final double W_HRV = 0.35, W_RHR = 0.25, W_SLEEP = 0.25, W_DEEP = 0.15;

    /** Computes readiness for the most recent day that has sleep or HRV data. */
    public ReadinessResult computeReadiness(AthleteProfile profile) {
        LocalDate day = latestScorableDay(profile.getRecords());
        if (day == null) {
            return new ReadinessResult(LocalDate.now(), 50, ReadinessTier.FATIGUED, 0, 0, 0, 0, 0, 0, true,
                    "No biometric data yet - import a health file to compute readiness.");
        }
        return computeFor(profile.getRecords(), day);
    }

    public static LocalDate latestScorableDay(NavigableMap<LocalDate, BiometricRecord> records) {
        for (BiometricRecord r : records.descendingMap().values()) {
            if (r.getHrvMs() != null || r.getSleepMinutes() != null || r.getRestingHr() != null) {
                return r.getDate();
            }
        }
        return null;
    }

    /** Computes readiness for a specific day using the 7 preceding days as the baseline. */
    public ReadinessResult computeFor(NavigableMap<LocalDate, BiometricRecord> records, LocalDate day) {
        BiometricRecord today = records.get(day);
        if (today == null) {
            return new ReadinessResult(day, 50, ReadinessTier.FATIGUED, 0, 0, 0, 0, 0, 0, true,
                    "No biometric record for " + day + ".");
        }
        List<BiometricRecord> baseline = new ArrayList<>(
                records.subMap(day.minusDays(BASELINE_DAYS), true, day, false).values());

        double[] hrvB = stats(baseline, BiometricRecord::getHrvMs);
        double[] rhrB = stats(baseline, BiometricRecord::getRestingHr);
        double[] sleepB = stats(baseline, BiometricRecord::getSleepMinutes);
        double[] deepB = stats(baseline, BiometricRecord::getDeepSleepMinutes);

        int baselineDays = (int) Math.max(hrvB[2], sleepB[2]);
        boolean approximation = baselineDays < MIN_BASELINE_DAYS;
        if (approximation) {
            hrvB = DEFAULT_HRV;
            rhrB = DEFAULT_RHR;
            sleepB = DEFAULT_SLEEP;
            deepB = DEFAULT_DEEP;
        } else {
            // Fall back per-metric when a metric is missing from an otherwise good baseline.
            if (hrvB[2] < MIN_BASELINE_DAYS) hrvB = DEFAULT_HRV;
            if (rhrB[2] < MIN_BASELINE_DAYS) rhrB = DEFAULT_RHR;
            if (sleepB[2] < MIN_BASELINE_DAYS) sleepB = DEFAULT_SLEEP;
            if (deepB[2] < MIN_BASELINE_DAYS) deepB = DEFAULT_DEEP;
        }

        double zHrv = z(today.getHrvMs(), hrvB, false);
        double zRhr = z(today.getRestingHr(), rhrB, true);
        double zSleep = z(today.getSleepMinutes(), sleepB, false);
        double zDeep = z(today.getDeepSleepMinutes(), deepB, false);

        double wSum = 0, zSum = 0;
        if (today.getHrvMs() != null) { zSum += W_HRV * zHrv; wSum += W_HRV; }
        if (today.getRestingHr() != null) { zSum += W_RHR * zRhr; wSum += W_RHR; }
        if (today.getSleepMinutes() != null) { zSum += W_SLEEP * zSleep; wSum += W_SLEEP; }
        if (today.getDeepSleepMinutes() != null) { zSum += W_DEEP * zDeep; wSum += W_DEEP; }
        double composite = wSum == 0 ? 0 : zSum / wSum;
        double relative = clamp(50 + 20 * composite, 0, 100);

        double sleepScore = sleepArchitectureScore(today);
        double score = today.getSleepMinutes() == null ? relative : 0.65 * relative + 0.35 * sleepScore;
        score = Math.round(clamp(score, 0, 100) * 10) / 10.0;

        ReadinessTier tier = ReadinessTier.fromScore(score);
        String msg = approximation
                ? "Initial approximation: only " + baselineDays + " baseline day(s) available - import more history for a personalized score."
                : "Based on a " + baselineDays + "-day rolling baseline.";
        return new ReadinessResult(day, score, tier, zHrv, zRhr, zSleep, zDeep, Math.round(sleepScore), baselineDays,
                approximation, msg);
    }

    /** Readiness for each scorable day in [from, to]; used by the fatigue sentinel and audit report. */
    public List<ReadinessResult> history(NavigableMap<LocalDate, BiometricRecord> records, LocalDate from, LocalDate to) {
        List<ReadinessResult> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (records.containsKey(d)) {
                out.add(computeFor(records, d));
            }
        }
        return out;
    }

    /** Absolute sleep score 0-100: duration 60, efficiency 15, REM 12.5, deep 12.5. */
    static double sleepArchitectureScore(BiometricRecord r) {
        if (r.getSleepMinutes() == null) {
            return 0;
        }
        double duration = Math.min(r.getSleepMinutes() / 480.0, 1.0) * 60;
        Double eff = r.sleepEfficiency();
        double efficiency = (eff == null ? 0.88 : eff) * 15;
        Double remPct = r.remPercent();
        double rem = (remPct == null ? 0.7 : Math.min(remPct / 0.22, 1.0)) * 12.5;
        double deep = (r.getDeepSleepMinutes() == null ? 0.7 : Math.min(r.getDeepSleepMinutes() / 90.0, 1.0)) * 12.5;
        return duration + efficiency + rem + deep;
    }

    /** Returns {mean, sd, n} for the non-null metric values. */
    static double[] stats(List<BiometricRecord> records, Function<BiometricRecord, Double> metric) {
        double sum = 0;
        int n = 0;
        for (BiometricRecord r : records) {
            Double v = metric.apply(r);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        if (n == 0) {
            return new double[]{0, 1, 0};
        }
        double mean = sum / n;
        double sq = 0;
        for (BiometricRecord r : records) {
            Double v = metric.apply(r);
            if (v != null) {
                sq += (v - mean) * (v - mean);
            }
        }
        double sd = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;
        // Floor the SD so a very stable baseline does not turn tiny changes into huge z-scores.
        sd = Math.max(sd, Math.max(mean * 0.05, 1.0));
        return new double[]{mean, sd, n};
    }

    private static double z(Double value, double[] baseline, boolean invert) {
        if (value == null) {
            return 0;
        }
        double z = (value - baseline[0]) / baseline[1];
        return clamp(invert ? -z : z, -2.5, 2.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
