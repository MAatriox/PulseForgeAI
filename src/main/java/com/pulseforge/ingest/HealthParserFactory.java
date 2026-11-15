package com.pulseforge.ingest;

import java.nio.file.Path;

/** Selects the right adapter for a file (UC01 step 2: "The system determines the file format"). */
public final class HealthParserFactory {

    private HealthParserFactory() {
    }

    public static FitnessDataParser forFile(Path file) throws InvalidHealthDataException {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        if (name.endsWith(".xml")) {
            return new AppleHealthXmlAdapter();
        }
        if (name.endsWith(".json")) {
            return new GarminJsonAdapter();
        }
        if (name.endsWith(".csv")) {
            return new CsvBiometricAdapter();
        }
        throw new InvalidHealthDataException("Unsupported file type '" + name
                + "'. Use an Apple Health export.xml, a Garmin .json export or a .csv file.");
    }
}
