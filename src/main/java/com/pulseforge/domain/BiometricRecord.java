package com.pulseforge.domain;

import com.pulseforge.ingest.InvalidHealthDataException;
import com.pulseforge.json.Json;

import java.time.LocalDate;
import java.util.Map;

/**
 * One day of normalized wearable data. Every adapter (Apple XML, Garmin JSON, CSV) produces
 * this vendor-neutral shape, so the rest of the system never sees vendor schemas.
 * Fields are nullable because not every device reports every metric.
 */
public class BiometricRecord {

    private final LocalDate date;
    private Double restingHr;          // bpm
    private Double hrvMs;              // SDNN / RMSSD in milliseconds
    private Double sleepMinutes;       // total asleep
    private Double deepSleepMinutes;
    private Double remSleepMinutes;
    private Double inBedMinutes;       // used to derive sleep efficiency
    private Double activeEnergyKcal;
    private String source;

    public BiometricRecord(LocalDate date, String source) {
        if (date == null) {
            throw new IllegalArgumentException("BiometricRecord requires a date");
        }
        this.date = date;
        this.source = source;
    }

    /** Range checks applied before records reach the profile (UC01 step 4). */
    public void validate() throws InvalidHealthDataException {
        check("resting heart rate", restingHr, 25, 140);
        check("HRV", hrvMs, 1, 300);
        check("sleep minutes", sleepMinutes, 0, 1080);
        check("deep sleep minutes", deepSleepMinutes, 0, 600);
        check("REM sleep minutes", remSleepMinutes, 0, 600);
        check("active energy", activeEnergyKcal, 0, 10000);
        if (!hasAnyMetric()) {
            throw new InvalidHealthDataException("Record for " + date + " has no usable metrics");
        }
    }

    private void check(String name, Double v, double min, double max) throws InvalidHealthDataException {
        if (v != null && (v.isNaN() || v < min || v > max)) {
            throw new InvalidHealthDataException("Out-of-range " + name + " (" + v + ") on " + date);
        }
    }

    public boolean hasAnyMetric() {
        return restingHr != null || hrvMs != null || sleepMinutes != null || activeEnergyKcal != null;
    }

    /** Merges non-null values from {@code other} (same date) into this record. */
    public void mergeFrom(BiometricRecord other) {
        if (other.restingHr != null) restingHr = other.restingHr;
        if (other.hrvMs != null) hrvMs = other.hrvMs;
        if (other.sleepMinutes != null) sleepMinutes = other.sleepMinutes;
        if (other.deepSleepMinutes != null) deepSleepMinutes = other.deepSleepMinutes;
        if (other.remSleepMinutes != null) remSleepMinutes = other.remSleepMinutes;
        if (other.inBedMinutes != null) inBedMinutes = other.inBedMinutes;
        if (other.activeEnergyKcal != null) activeEnergyKcal = other.activeEnergyKcal;
        if (other.source != null) source = other.source;
    }

    /** Sleep efficiency (asleep / in bed), or null when in-bed time is unknown. */
    public Double sleepEfficiency() {
        if (sleepMinutes == null || inBedMinutes == null || inBedMinutes <= 0) {
            return null;
        }
        return Math.min(1.0, sleepMinutes / inBedMinutes);
    }

    public Double remPercent() {
        if (remSleepMinutes == null || sleepMinutes == null || sleepMinutes <= 0) {
            return null;
        }
        return remSleepMinutes / sleepMinutes;
    }

    public Double getSleepHours() {
        return sleepMinutes == null ? null : sleepMinutes / 60.0;
    }

    // ------------------------------------------------------------ persistence

    public Map<String, Object> toJson() {
        return Json.map("date", date.toString(), "restingHr", restingHr, "hrvMs", hrvMs,
                "sleepMinutes", sleepMinutes, "deepSleepMinutes", deepSleepMinutes,
                "remSleepMinutes", remSleepMinutes, "inBedMinutes", inBedMinutes,
                "activeEnergyKcal", activeEnergyKcal, "source", source);
    }

    public static BiometricRecord fromJson(Map<String, Object> m) {
        BiometricRecord r = new BiometricRecord(LocalDate.parse(Json.str(m, "date", null)), Json.str(m, "source", null));
        r.restingHr = Json.num(m, "restingHr");
        r.hrvMs = Json.num(m, "hrvMs");
        r.sleepMinutes = Json.num(m, "sleepMinutes");
        r.deepSleepMinutes = Json.num(m, "deepSleepMinutes");
        r.remSleepMinutes = Json.num(m, "remSleepMinutes");
        r.inBedMinutes = Json.num(m, "inBedMinutes");
        r.activeEnergyKcal = Json.num(m, "activeEnergyKcal");
        return r;
    }

    // -------------------------------------------------------- getters/setters

    public LocalDate getDate() { return date; }
    public Double getRestingHr() { return restingHr; }
    public void setRestingHr(Double v) { restingHr = v; }
    public Double getHrvMs() { return hrvMs; }
    public void setHrvMs(Double v) { hrvMs = v; }
    public Double getSleepMinutes() { return sleepMinutes; }
    public void setSleepMinutes(Double v) { sleepMinutes = v; }
    public Double getDeepSleepMinutes() { return deepSleepMinutes; }
    public void setDeepSleepMinutes(Double v) { deepSleepMinutes = v; }
    public Double getRemSleepMinutes() { return remSleepMinutes; }
    public void setRemSleepMinutes(Double v) { remSleepMinutes = v; }
    public Double getInBedMinutes() { return inBedMinutes; }
    public void setInBedMinutes(Double v) { inBedMinutes = v; }
    public Double getActiveEnergyKcal() { return activeEnergyKcal; }
    public void setActiveEnergyKcal(Double v) { activeEnergyKcal = v; }
    public String getSource() { return source; }

    @Override
    public String toString() {
        return String.format("%s  RHR=%s HRV=%s sleep=%s deep=%s rem=%s active=%s",
                date, fmt(restingHr), fmt(hrvMs), fmt(sleepMinutes), fmt(deepSleepMinutes),
                fmt(remSleepMinutes), fmt(activeEnergyKcal));
    }

    private static String fmt(Double d) {
        return d == null ? "-" : String.valueOf(Math.round(d));
    }
}
