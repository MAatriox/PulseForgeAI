package com.pulseforge.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic F08 sentinel result over the 14-day window; the AI diagnosis is attached later.
 */
public class FatigueAssessment {

    public enum Risk { LOW, ELEVATED, CRITICAL }

    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final int consecutiveSuppressedDays;
    private final int consecutiveLowReadinessDays;
    private final double tonnageLast7;
    private final double tonnagePrev7;
    private final List<LocalDate> missingDates;
    private final Risk risk;
    private final boolean deloadRecommended;
    private final String trendTable;
    private String diagnosis = "";
    private String diagnosisSource = "";

    public FatigueAssessment(LocalDate windowStart, LocalDate windowEnd, int consecutiveSuppressedDays,
                             int consecutiveLowReadinessDays, double tonnageLast7, double tonnagePrev7,
                             List<LocalDate> missingDates, Risk risk, boolean deloadRecommended, String trendTable) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.consecutiveSuppressedDays = consecutiveSuppressedDays;
        this.consecutiveLowReadinessDays = consecutiveLowReadinessDays;
        this.tonnageLast7 = tonnageLast7;
        this.tonnagePrev7 = tonnagePrev7;
        this.missingDates = List.copyOf(missingDates);
        this.risk = risk;
        this.deloadRecommended = deloadRecommended;
        this.trendTable = trendTable;
    }

    public String summary() {
        return String.format("Overtraining risk %s: %d consecutive day(s) of suppressed HRV + sleep debt, "
                        + "%d day(s) of readiness < 40; tonnage last 7d %.0f kg vs prior 7d %.0f kg%s.%s",
                risk, consecutiveSuppressedDays, consecutiveLowReadinessDays, tonnageLast7, tonnagePrev7,
                missingDates.isEmpty() ? "" : "; " + missingDates.size() + " day(s) missing data",
                deloadRecommended ? " Deload recommended." : "");
    }

    public void setDiagnosis(String text, String source) {
        this.diagnosis = text;
        this.diagnosisSource = source;
    }

    public LocalDate getWindowStart() { return windowStart; }
    public LocalDate getWindowEnd() { return windowEnd; }
    public int getConsecutiveSuppressedDays() { return consecutiveSuppressedDays; }
    public int getConsecutiveLowReadinessDays() { return consecutiveLowReadinessDays; }
    public double getTonnageLast7() { return tonnageLast7; }
    public double getTonnagePrev7() { return tonnagePrev7; }
    public List<LocalDate> getMissingDates() { return missingDates; }
    public Risk getRisk() { return risk; }
    public boolean isDeloadRecommended() { return deloadRecommended; }
    public String getTrendTable() { return trendTable; }
    public String getDiagnosis() { return diagnosis; }
    public String getDiagnosisSource() { return diagnosisSource; }
}
