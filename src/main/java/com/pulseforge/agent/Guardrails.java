package com.pulseforge.agent;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic pre-screening of coach questions (F09 error case / UC09 alt 1a). Clinical
 * questions get a medical disclaimer instead of an LLM answer; emergencies get urgent guidance.
 */
public final class Guardrails {

    public enum Verdict { ALLOWED, MEDICAL, EMERGENCY }

    private static final List<String> EMERGENCY = List.of(
            "chest pain", "heart attack", "can't breathe", "cannot breathe", "fainted", "passed out", "stroke");

    private static final List<String> MEDICAL = List.of(
            "diagnos", "prescri", "medication", "medicine", "dosage", "fracture", "broken bone", "torn ",
            "tear my", "concussion", "disease", "infection", "cancer", "tumor", "diabetes", "blood pressure",
            "arrhythmia", "symptom", "is it serious", "should i see a doctor", "steroid", "sarm");

    public static final String MEDICAL_DISCLAIMER =
            "I can't diagnose injuries or medical conditions, and I can't advise on medication. "
                    + "Please consult a physician or physiotherapist for that. What I can help with is "
                    + "adjusting your training and recovery - e.g. lowering today's volume, swapping exercises "
                    + "that aggravate the area, or planning lighter sessions while you get it checked.";

    public static final String EMERGENCY_MESSAGE =
            "This may be a medical emergency. Stop exercising now and contact emergency services (911) "
                    + "or seek immediate medical attention. PulseForge AI is not a medical device.";

    private Guardrails() {
    }

    public static Verdict screen(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        for (String k : EMERGENCY) {
            if (q.contains(k)) return Verdict.EMERGENCY;
        }
        for (String k : MEDICAL) {
            if (q.contains(k)) return Verdict.MEDICAL;
        }
        return Verdict.ALLOWED;
    }
}
