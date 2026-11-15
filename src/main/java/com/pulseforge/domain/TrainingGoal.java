package com.pulseforge.domain;

/** The athlete's primary goal; drives default strategy choice and fueling thresholds. */
public enum TrainingGoal {
    HYPERTROPHY("Hypertrophy (muscle gain)"),
    STRENGTH("Strength"),
    FAT_LOSS("Fat loss"),
    GENERAL_FITNESS("General fitness");

    private final String label;

    TrainingGoal(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static TrainingGoal parse(String s) {
        for (TrainingGoal g : values()) {
            if (g.name().equalsIgnoreCase(s.trim().replace(' ', '_').replace('-', '_'))) {
                return g;
            }
        }
        throw new IllegalArgumentException("Unknown goal '" + s + "'. Use one of: hypertrophy, strength, fat_loss, general_fitness");
    }
}
