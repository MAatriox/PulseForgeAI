package com.pulseforge.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes safe, synthetic sample exports (Apple Health XML, Garmin JSON, CSV) ending on a given
 * date. The last four days model accumulating fatigue (falling HRV, rising resting HR, short
 * sleep) so readiness, the fatigue sentinel and the deload planner can be demonstrated.
 * Deterministic: a fixed random seed is used.
 */
public final class SampleDataGenerator {

    private static final DateTimeFormatter APPLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One synthetic day. */
    record Day(LocalDate date, double rhr, double hrv, double deep, double rem, double core, double awake, double active) {
        double asleep() {
            return deep + rem + core;
        }
    }

    private SampleDataGenerator() {
    }

    static List<Day> days(LocalDate end, int count) {
        Random rnd = new Random(3311);
        List<Day> out = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            LocalDate d = end.minusDays(i);
            boolean fatigued = i < 4;             // last 4 days: overreaching pattern
            int fatigueDepth = fatigued ? 4 - i : 0;
            double hrv = fatigued ? 46 - fatigueDepth * 3 + rnd.nextGaussian() : 64 + rnd.nextGaussian() * 4;
            double rhr = fatigued ? 61 + fatigueDepth * 1.5 + rnd.nextGaussian() * 0.5 : 55 + rnd.nextGaussian() * 1.5;
            double sleep = fatigued ? 330 + rnd.nextGaussian() * 15 : 455 + rnd.nextGaussian() * 25;
            double deep = sleep * (fatigued ? 0.13 : 0.19);
            double rem = sleep * (fatigued ? 0.17 : 0.23);
            double core = sleep - deep - rem;
            double awake = fatigued ? 40 : 20;
            double active = 480 + rnd.nextGaussian() * 90 + (d.getDayOfWeek().getValue() <= 5 ? 80 : 0);
            out.add(new Day(d, round1(rhr), round1(hrv), Math.round(deep), Math.round(rem), Math.round(core), awake,
                    Math.round(active)));
        }
        return out;
    }

    /** Generates all three formats into {@code dir}. Returns the files written. */
    public static List<Path> generate(Path dir, LocalDate end, int count) throws IOException {
        Files.createDirectories(dir);
        List<Day> days = days(end, count);
        List<Path> written = new ArrayList<>();
        written.add(writeApple(dir.resolve("apple_health_export.xml"), days));
        written.add(writeGarmin(dir.resolve("garmin_export.json"), days));
        written.add(writeCsv(dir.resolve("manual_biometrics.csv"), days));
        return written;
    }

    private static Path writeApple(Path file, List<Day> days) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE HealthData [\n<!ELEMENT HealthData (ExportDate,Me,(Record)*)>\n]>\n");
        sb.append("<HealthData locale=\"en_CA\">\n <ExportDate value=\"").append(days.get(days.size() - 1).date())
                .append(" 08:00:00 -0500\"/>\n <Me HKCharacteristicTypeIdentifierBiologicalSex=\"HKBiologicalSexMale\"/>\n");
        for (Day d : days) {
            String date = d.date().toString();
            // Sleep: night before, ending on this date's morning.
            var bed = d.date().minusDays(1).atTime(23, 15);
            var t = bed;
            t = sleep(sb, t, d.core() * 0.4, "AsleepCore");
            t = sleep(sb, t, d.deep(), "AsleepDeep");
            t = sleep(sb, t, d.awake() / 2, "Awake");
            t = sleep(sb, t, d.core() * 0.6, "AsleepCore");
            t = sleep(sb, t, d.rem(), "AsleepREM");
            t = sleep(sb, t, d.awake() / 2, "Awake");
            record(sb, AppleTypes.RHR, "count/min", date + " 07:30:00", d.rhr());
            record(sb, AppleTypes.HRV, "ms", date + " 06:45:00", d.hrv());
            // Active energy split into three samples per day.
            record(sb, AppleTypes.ENERGY, "kcal", date + " 09:00:00", Math.round(d.active() * 0.3));
            record(sb, AppleTypes.ENERGY, "kcal", date + " 13:00:00", Math.round(d.active() * 0.3));
            record(sb, AppleTypes.ENERGY, "kcal", date + " 18:00:00", d.active() - 2 * Math.round(d.active() * 0.3));
            // An unrelated record type the adapter must ignore.
            record(sb, "HKQuantityTypeIdentifierStepCount", "count", date + " 12:00:00", 8000);
        }
        sb.append("</HealthData>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static final class AppleTypes {
        static final String RHR = "HKQuantityTypeIdentifierRestingHeartRate";
        static final String HRV = "HKQuantityTypeIdentifierHeartRateVariabilitySDNN";
        static final String ENERGY = "HKQuantityTypeIdentifierActiveEnergyBurned";
    }

    private static java.time.LocalDateTime sleep(StringBuilder sb, java.time.LocalDateTime start, double minutes, String stage) {
        var end = start.plusSeconds(Math.round(minutes * 60));
        sb.append(String.format(" <Record type=\"HKCategoryTypeIdentifierSleepAnalysis\" sourceName=\"Apple Watch\" "
                        + "startDate=\"%s -0500\" endDate=\"%s -0500\" value=\"HKCategoryValueSleepAnalysis%s\"/>%n",
                start.format(APPLE), end.format(APPLE), stage));
        return end;
    }

    private static void record(StringBuilder sb, String type, String unit, String when, double value) {
        sb.append(String.format(" <Record type=\"%s\" sourceName=\"Apple Watch\" unit=\"%s\" startDate=\"%s -0500\" "
                + "endDate=\"%s -0500\" value=\"%s\"/>%n", type, unit, when, when, trim(value)));
    }

    private static Path writeGarmin(Path file, List<Day> days) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"dailySummaries\": [\n");
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            sb.append(String.format("    {\"calendarDate\": \"%s\", \"restingHeartRate\": %s, \"hrvLastNightAvg\": %s, "
                            + "\"activeKilocalories\": %s, \"sleep\": {\"deepSleepSeconds\": %d, \"remSleepSeconds\": %d, "
                            + "\"lightSleepSeconds\": %d, \"awakeSleepSeconds\": %d}}%s%n",
                    d.date(), trim(d.rhr()), trim(d.hrv()), trim(d.active()), Math.round(d.deep() * 60),
                    Math.round(d.rem() * 60), Math.round(d.core() * 60), Math.round(d.awake() * 60),
                    i < days.size() - 1 ? "," : ""));
        }
        sb.append("  ]\n}\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static Path writeCsv(Path file, List<Day> days) throws IOException {
        StringBuilder sb = new StringBuilder("date,resting_hr,hrv_ms,sleep_minutes,deep_minutes,rem_minutes,in_bed_minutes,active_kcal\n");
        for (Day d : days) {
            sb.append(String.format("%s,%s,%s,%s,%s,%s,%s,%s%n", d.date(), trim(d.rhr()), trim(d.hrv()), trim(d.asleep()),
                    trim(d.deep()), trim(d.rem()), trim(d.asleep() + d.awake()), trim(d.active())));
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
