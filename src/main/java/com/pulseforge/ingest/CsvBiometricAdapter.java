package com.pulseforge.ingest;

import com.pulseforge.domain.BiometricRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for manual CSV entry. Required header column: {@code date}. Optional columns:
 * {@code resting_hr, hrv_ms, sleep_minutes, deep_minutes, rem_minutes, in_bed_minutes, active_kcal}.
 */
public class CsvBiometricAdapter implements FitnessDataParser {

    @Override
    public List<BiometricRecord> parse(Path filePath) throws InvalidHealthDataException {
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidHealthDataException("Could not read CSV file: " + filePath, e);
        }
        lines.removeIf(l -> l.isBlank());
        if (lines.size() < 2) {
            throw new InvalidHealthDataException("CSV needs a header row and at least one data row");
        }
        String[] header = lines.get(0).replace("﻿", "").split(",");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim().toLowerCase(), i);
        }
        if (!col.containsKey("date")) {
            throw new InvalidHealthDataException("CSV is missing the mandatory 'date' column");
        }
        List<BiometricRecord> out = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            String[] cells = lines.get(row).split(",", -1);
            LocalDate date;
            try {
                date = LocalDate.parse(cell(cells, col, "date"));
            } catch (DateTimeParseException | NullPointerException e) {
                throw new InvalidHealthDataException("Row " + (row + 1) + ": invalid or missing date (expected YYYY-MM-DD)");
            }
            BiometricRecord r = new BiometricRecord(date, "CSV");
            try {
                r.setRestingHr(num(cells, col, "resting_hr"));
                r.setHrvMs(num(cells, col, "hrv_ms"));
                r.setSleepMinutes(num(cells, col, "sleep_minutes"));
                r.setDeepSleepMinutes(num(cells, col, "deep_minutes"));
                r.setRemSleepMinutes(num(cells, col, "rem_minutes"));
                r.setInBedMinutes(num(cells, col, "in_bed_minutes"));
                r.setActiveEnergyKcal(num(cells, col, "active_kcal"));
            } catch (NumberFormatException e) {
                throw new InvalidHealthDataException("Row " + (row + 1) + ": non-numeric value (" + e.getMessage() + ")");
            }
            r.validate();
            out.add(r);
        }
        out.sort(Comparator.comparing(BiometricRecord::getDate));
        return out;
    }

    private static String cell(String[] cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= cells.length) {
            return null;
        }
        String v = cells[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static Double num(String[] cells, Map<String, Integer> col, String name) {
        String v = cell(cells, col, name);
        return v == null ? null : Double.parseDouble(v);
    }

    @Override
    public String formatName() {
        return "CSV";
    }
}
