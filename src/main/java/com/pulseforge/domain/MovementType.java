package com.pulseforge.domain;

/** Movement classification used by the smart rest engine (F07) and calorie estimates. */
public enum MovementType {
    COMPOUND, ISOLATION;

    public static MovementType parse(String s) {
        if (s == null) {
            return COMPOUND;
        }
        return s.trim().toUpperCase().startsWith("ISO") ? ISOLATION : COMPOUND;
    }
}
