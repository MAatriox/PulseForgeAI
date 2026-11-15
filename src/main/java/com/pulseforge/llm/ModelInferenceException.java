package com.pulseforge.llm;

/** Raised when an LLM call fails (timeout, network outage, HTTP error, malformed output). */
public class ModelInferenceException extends Exception {
    public ModelInferenceException(String message) {
        super(message);
    }

    public ModelInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
