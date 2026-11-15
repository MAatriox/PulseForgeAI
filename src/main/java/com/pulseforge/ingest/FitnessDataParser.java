package com.pulseforge.ingest;

import com.pulseforge.domain.BiometricRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * Target interface of the Adapter pattern. Controllers depend only on this contract;
 * each wearable vendor format gets its own adapter that converts to {@link BiometricRecord}.
 */
public interface FitnessDataParser {

    /** Parses the file into validated, per-day normalized records (sorted by date). */
    List<BiometricRecord> parse(Path filePath) throws InvalidHealthDataException;

    /** Short name of the source format, e.g. "Apple Health XML". */
    String formatName();
}
