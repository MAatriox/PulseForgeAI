package com.pulseforge.json;

/** Thrown when JSON text cannot be parsed. */
public class JsonException extends RuntimeException {
    public JsonException(String message) {
        super(message);
    }
}
