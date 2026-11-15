package com.pulseforge.ingest;

/** Raised when a health export is corrupt, unsupported or missing mandatory fields (F01 / UC01 3a). */
public class InvalidHealthDataException extends Exception {
    public InvalidHealthDataException(String message) {
        super(message);
    }

    public InvalidHealthDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
