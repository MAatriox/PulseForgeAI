package com.pulseforge.ingest;

import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Adapter for Garmin Connect-style JSON daily summaries:
 * <pre>
 * { "dailySummaries": [ { "calendarDate": "2026-10-20", "restingHeartRate": 58,
 *     "hrvLastNightAvg": 62, "activeKilocalories": 540,
 *     "sleep": { "deepSleepSeconds": 5400, "remSleepSeconds": 6000,
 *                "lightSleepSeconds": 14400, "awakeSleepSeconds": 1200 } } ] }
 * </pre>
 */
public class GarminJsonAdapter implements FitnessDataParser {

    @Override
    public List<BiometricRecord> parse(Path filePath) throws InvalidHealthDataException {
        String text;
        try {
            text = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidHealthDataException("Could not read Garmin file: " + filePath, e);
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(text);
        } catch (JsonException e) {
            throw new InvalidHealthDataException("Corrupt Garmin JSON: " + e.getMessage(), e);
        }
        List<Map<String, Object>> days = Json.objArr(root, "dailySummaries");
        if (days.isEmpty()) {
            throw new InvalidHealthDataException("Garmin JSON is missing the mandatory 'dailySummaries' array");
        }
        List<BiometricRecord> out = new ArrayList<>();
        for (Map<String, Object> d : days) {
            String dateStr = Json.str(d, "calendarDate", null);
            if (dateStr == null) {
                throw new InvalidHealthDataException("A Garmin daily summary is missing 'calendarDate'");
            }
            LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (DateTimeParseException e) {
                throw new InvalidHealthDataException("Invalid calendarDate '" + dateStr + "'", e);
            }
            BiometricRecord r = new BiometricRecord(date, "Garmin");
            r.setRestingHr(Json.num(d, "restingHeartRate"));
            r.setHrvMs(Json.num(d, "hrvLastNightAvg"));
            r.setActiveEnergyKcal(Json.num(d, "activeKilocalories"));
            Map<String, Object> sleep = Json.obj(d, "sleep");
            if (sleep != null) {
                double deep = Json.num(sleep, "deepSleepSeconds", 0) / 60.0;
                double rem = Json.num(sleep, "remSleepSeconds", 0) / 60.0;
                double light = Json.num(sleep, "lightSleepSeconds", 0) / 60.0;
                double awake = Json.num(sleep, "awakeSleepSeconds", 0) / 60.0;
                double asleep = deep + rem + light;
                if (asleep > 0) {
                    r.setSleepMinutes(asleep);
                    r.setDeepSleepMinutes(deep);
                    r.setRemSleepMinutes(rem);
                    r.setInBedMinutes(asleep + awake);
                }
            }
            if (r.hasAnyMetric()) {
                r.validate();
                out.add(r);
            }
        }
        if (out.isEmpty()) {
            throw new InvalidHealthDataException("Garmin JSON contained no usable metrics");
        }
        out.sort(Comparator.comparing(BiometricRecord::getDate));
        return out;
    }

    @Override
    public String formatName() {
        return "Garmin JSON";
    }
}
