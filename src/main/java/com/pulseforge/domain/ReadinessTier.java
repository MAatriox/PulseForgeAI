package com.pulseforge.domain;

/** Readiness tiers from the Stage 1 F03 specification. */
public enum ReadinessTier {
    PRIME("Prime"),
    BALANCED("Balanced"),
    FATIGUED("Fatigued"),
    DEPLETED("Depleted");

    private final String label;

    ReadinessTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ReadinessTier fromScore(double score) {
        if (score >= 80) return PRIME;
        if (score >= 60) return BALANCED;
        if (score >= 40) return FATIGUED;
        return DEPLETED;
    }
}
