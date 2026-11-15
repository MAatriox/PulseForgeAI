package com.pulseforge.report;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.CoachNote;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.WorkoutSession;
import com.pulseforge.readiness.ReadinessCalculator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * F10 - deterministic audit exporter. Pulls biometrics, readiness, meals, sessions and saved
 * AI coach notes across a date range, computes daily rows and weekly averages, and writes a
 * Markdown or PDF document.
 */
public class ReportGenerator {

    public enum Format {
        MARKDOWN("md"), PDF("pdf");

        private final String extension;

        Format(String ext) {
            this.extension = ext;
        }

        public String extension() {
            return extension;
        }

        public static Format parse(String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("md") || v.equals("markdown")) return MARKDOWN;
            if (v.equals("pdf")) return PDF;
            throw new IllegalArgumentException("Format must be 'md' or 'pdf'");
        }
    }

    /** One row of the daily table. */
    record DayRow(LocalDate date, Double sleepH, Double hrv, Double rhr, Double readiness,
                  double kcalIn, double kcalOut, int meals, double tonnage) {
    }

    private final ReadinessCalculator calculator;

    public ReportGenerator(ReadinessCalculator calculator) {
        this.calculator = calculator;
    }

    /** Builds the report lines (Markdown syntax; the PDF renderer prints them verbatim). */
    public List<String> compileAudit(AthleteProfile p, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Start date must be on or before end date");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Date range cannot exceed one year");
        }
        List<DayRow> rows = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            BiometricRecord r = p.getRecords().get(d);
            List<MealLog> meals = p.mealsOn(d);
            List<WorkoutSession> sessions = p.sessionsOn(d);
            if (r == null && meals.isEmpty() && sessions.isEmpty()) {
                continue;
            }
            Double readiness = r == null ? null : calculator.computeFor(p.getRecords(), d).score();
            double active = com.pulseforge.nutrition.EnergyBalanceCalculator.wearableActive(p, d);
            double lifting = sessions.stream().mapToDouble(WorkoutSession::estimatedKcal).sum();
            rows.add(new DayRow(d, r == null ? null : r.getSleepHours(), r == null ? null : r.getHrvMs(),
                    r == null ? null : r.getRestingHr(), readiness,
                    meals.stream().mapToDouble(MealLog::totalCalories).sum(), p.bmr() + active + lifting,
                    meals.size(), sessions.stream().mapToDouble(WorkoutSession::tonnage).sum()));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No biometric, meal or workout data between " + from + " and " + to);
        }

        List<String> out = new ArrayList<>();
        out.add("# PulseForge AI - Fitness, Sleep & Diet Audit");
        out.add("");
        out.add("Athlete: " + p.getName() + " | Goal: " + p.getGoal().label() + " | Period: " + from + " to " + to);
        out.add("Generated: " + LocalDate.now() + " | BMR: " + Math.round(p.bmr()) + " kcal | Protein target: "
                + Math.round(p.proteinTargetG()) + " g/day");
        out.add("");

        out.add("## Summary");
        out.add("");
        out.add(String.format("- Days with data: %d", rows.size()));
        out.add("- Average sleep: " + avgText(rows.stream().map(DayRow::sleepH).toList(), "%.1f h"));
        out.add("- Average HRV: " + avgText(rows.stream().map(DayRow::hrv).toList(), "%.0f ms"));
        out.add("- Average resting HR: " + avgText(rows.stream().map(DayRow::rhr).toList(), "%.0f bpm"));
        out.add("- Average readiness: " + avgText(rows.stream().map(DayRow::readiness).toList(), "%.0f/100"));
        out.add(String.format("- Total training volume: %.0f kg across %d session(s)",
                rows.stream().mapToDouble(DayRow::tonnage).sum(), p.sessionsBetween(from, to).size()));
        List<Double> nets = rows.stream().filter(r -> r.meals() > 0).map(r -> r.kcalIn() - r.kcalOut()).toList();
        out.add("- Average net energy (days with meals logged): " + avgText(nets, "%+.0f kcal"));
        out.add("");

        out.add("## Weekly averages");
        out.add("");
        out.add("| Week starting | Sleep (h) | HRV (ms) | Readiness | Volume (kg) | Net kcal |");
        out.add("|---|---|---|---|---|---|");
        for (LocalDate ws = from; !ws.isAfter(to); ws = ws.plusDays(7)) {
            LocalDate we = ws.plusDays(6);
            LocalDate s = ws;
            List<DayRow> wk = rows.stream().filter(r -> !r.date().isBefore(s) && !r.date().isAfter(we)).toList();
            if (wk.isEmpty()) {
                continue;
            }
            out.add(String.format("| %s | %s | %s | %s | %.0f | %s |", ws,
                    avgText(wk.stream().map(DayRow::sleepH).toList(), "%.1f"),
                    avgText(wk.stream().map(DayRow::hrv).toList(), "%.0f"),
                    avgText(wk.stream().map(DayRow::readiness).toList(), "%.0f"),
                    wk.stream().mapToDouble(DayRow::tonnage).sum(),
                    avgText(wk.stream().filter(r -> r.meals() > 0).map(r -> r.kcalIn() - r.kcalOut()).toList(), "%+.0f")));
        }
        out.add("");

        out.add("## Daily detail");
        out.add("");
        out.add("| Date | Sleep (h) | HRV | RHR | Readiness | kcal in | kcal out | Volume (kg) |");
        out.add("|---|---|---|---|---|---|---|---|");
        for (DayRow r : rows) {
            out.add(String.format("| %s | %s | %s | %s | %s | %s | %.0f | %.0f |", r.date(),
                    f(r.sleepH(), "%.1f"), f(r.hrv(), "%.0f"), f(r.rhr(), "%.0f"), f(r.readiness(), "%.0f"),
                    r.meals() == 0 ? "-" : String.format("%.0f", r.kcalIn()), r.kcalOut(), r.tonnage()));
        }
        out.add("");

        List<WorkoutSession> sessions = p.sessionsBetween(from, to);
        out.add("## Training sessions");
        out.add("");
        if (sessions.isEmpty()) {
            out.add("No sessions logged in this period.");
        }
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (WorkoutSession s : sessions) {
            out.add(String.format("- %s: %s - %d/%d sets, %.0f kg volume", s.getStartedAt().format(tf),
                    s.getRoutine().getTitle(), s.getRoutine().completedSets(), s.getRoutine().totalSets(), s.tonnage()));
        }
        out.add("");

        out.add("## AI coach notes");
        out.add("");
        List<CoachNote> notes = p.getCoachNotes().stream().filter(n -> {
            LocalDate d = n.createdAt().toLocalDate();
            return !d.isBefore(from) && !d.isAfter(to);
        }).toList();
        if (notes.isEmpty()) {
            out.add("No AI coach notes saved in this period.");
        }
        for (CoachNote n : notes) {
            out.add("### " + n.category() + " (" + n.createdAt().format(tf) + ")");
            for (String line : n.text().split("\\R")) {
                out.add(line);
            }
            out.add("");
        }
        return out;
    }

    /** Writes the compiled report. IOException propagates so the UI can ask for another folder (UC10 4a). */
    public Path writeToFile(List<String> lines, Format format, Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            throw new IOException("Directory does not exist: " + parent);
        }
        if (format == Format.MARKDOWN) {
            Files.write(destination, lines, StandardCharsets.UTF_8);
        } else {
            SimplePdfWriter.write(lines, destination);
        }
        return destination;
    }

    private static String avgText(List<Double> values, String fmt) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? "-" : String.format(fmt, sum / n);
    }

    private static String f(Double v, String fmt) {
        return v == null ? "-" : String.format(fmt, v);
    }
}
