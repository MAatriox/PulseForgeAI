package com.pulseforge.ingest;

import com.pulseforge.domain.BiometricRecord;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adapter: converts Apple Health {@code export.xml} {@code <Record>} elements (streamed by the
 * {@link XmlParser} adaptee) into normalized per-day {@link BiometricRecord}s.
 * <ul>
 *   <li>Resting HR and HRV (SDNN) samples are averaged per day.</li>
 *   <li>Active energy samples are summed per day.</li>
 *   <li>Sleep stage intervals are summed and attributed to the wake-up (end) date.</li>
 * </ul>
 */
public class AppleHealthXmlAdapter implements FitnessDataParser {

    static final String RESTING_HR = "HKQuantityTypeIdentifierRestingHeartRate";
    static final String HRV = "HKQuantityTypeIdentifierHeartRateVariabilitySDNN";
    static final String ACTIVE_ENERGY = "HKQuantityTypeIdentifierActiveEnergyBurned";
    static final String SLEEP = "HKCategoryTypeIdentifierSleepAnalysis";

    private static final DateTimeFormatter APPLE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    private final XmlParser xmlParser;

    public AppleHealthXmlAdapter() {
        this(new XmlParser());
    }

    public AppleHealthXmlAdapter(XmlParser xmlParser) {
        this.xmlParser = xmlParser;
    }

    /** Per-day accumulator. */
    private static final class Day {
        double rhrSum; int rhrN;
        double hrvSum; int hrvN;
        double active; boolean hasActive;
        double deep, rem, core, inBed, awake;
        boolean hasSleep;
    }

    @Override
    public List<BiometricRecord> parse(Path filePath) throws InvalidHealthDataException {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new InvalidHealthDataException("Health file not found: " + filePath);
        }
        TreeMap<LocalDate, Day> days = new TreeMap<>();
        int[] badDates = {0};
        try {
            int visited = xmlParser.forEachElement(filePath, "Record", attrs -> accumulate(attrs, days, badDates));
            if (visited == 0) {
                throw new InvalidHealthDataException("No <Record> elements found - is this an Apple Health export.xml?");
            }
        } catch (NoSuchFileException e) {
            throw new InvalidHealthDataException("Health file not found: " + filePath, e);
        } catch (XMLStreamException e) {
            throw new InvalidHealthDataException("Corrupt XML near line "
                    + (e.getLocation() != null ? e.getLocation().getLineNumber() : "?") + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new InvalidHealthDataException("Could not read health file: " + e.getMessage(), e);
        }
        if (days.isEmpty()) {
            throw new InvalidHealthDataException("Export contains no supported metrics (resting HR, HRV, sleep, active energy)"
                    + (badDates[0] > 0 ? "; " + badDates[0] + " record(s) had unreadable timestamps" : ""));
        }
        List<BiometricRecord> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Day> e : days.entrySet()) {
            BiometricRecord r = toRecord(e.getKey(), e.getValue());
            if (r.hasAnyMetric()) {
                r.validate();
                out.add(r);
            }
        }
        return out;
    }

    private void accumulate(Map<String, String> a, TreeMap<LocalDate, Day> days, int[] badDates) {
        String type = a.get("type");
        if (type == null) {
            return;
        }
        boolean supported = type.equals(RESTING_HR) || type.equals(HRV) || type.equals(ACTIVE_ENERGY) || type.equals(SLEEP);
        if (!supported) {
            return;
        }
        ZonedDateTime start;
        ZonedDateTime end;
        try {
            start = ZonedDateTime.parse(a.get("startDate"), APPLE_DATE);
            end = a.get("endDate") == null ? start : ZonedDateTime.parse(a.get("endDate"), APPLE_DATE);
        } catch (DateTimeParseException | NullPointerException ex) {
            badDates[0]++;
            return;
        }
        if (type.equals(SLEEP)) {
            Day d = days.computeIfAbsent(end.toLocalDate(), k -> new Day());
            double minutes = Math.max(0, Duration.between(start, end).toSeconds() / 60.0);
            String v = a.getOrDefault("value", "");
            d.hasSleep = true;
            if (v.endsWith("AsleepDeep")) d.deep += minutes;
            else if (v.endsWith("AsleepREM")) d.rem += minutes;
            else if (v.endsWith("AsleepCore") || v.endsWith("AsleepUnspecified") || v.endsWith("Asleep")) d.core += minutes;
            else if (v.endsWith("InBed")) d.inBed += minutes;
            else if (v.endsWith("Awake")) d.awake += minutes;
            return;
        }
        double value;
        try {
            value = Double.parseDouble(a.get("value"));
        } catch (NumberFormatException | NullPointerException ex) {
            return;
        }
        Day d = days.computeIfAbsent(start.toLocalDate(), k -> new Day());
        switch (type) {
            case RESTING_HR -> { d.rhrSum += value; d.rhrN++; }
            case HRV -> { d.hrvSum += value; d.hrvN++; }
            case ACTIVE_ENERGY -> {
                // Apple may report kJ for some locales.
                d.active += "kJ".equalsIgnoreCase(a.get("unit")) ? value / 4.184 : value;
                d.hasActive = true;
            }
            default -> { }
        }
    }

    private BiometricRecord toRecord(LocalDate date, Day d) {
        BiometricRecord r = new BiometricRecord(date, "Apple Health");
        if (d.rhrN > 0) r.setRestingHr(d.rhrSum / d.rhrN);
        if (d.hrvN > 0) r.setHrvMs(d.hrvSum / d.hrvN);
        if (d.hasActive) r.setActiveEnergyKcal(d.active);
        double asleep = d.deep + d.rem + d.core;
        if (d.hasSleep && asleep > 0) {
            r.setSleepMinutes(asleep);
            r.setDeepSleepMinutes(d.deep);
            r.setRemSleepMinutes(d.rem);
            if (d.inBed > 0) {
                r.setInBedMinutes(Math.max(d.inBed, asleep));
            } else if (d.awake > 0) {
                r.setInBedMinutes(asleep + d.awake);
            }
        }
        return r;
    }

    @Override
    public String formatName() {
        return "Apple Health XML";
    }
}
