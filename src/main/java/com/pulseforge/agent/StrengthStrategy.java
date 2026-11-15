package com.pulseforge.agent;

/** ConcreteStrategy: intensity-driven programming (fewer sets, heavy 3-5 reps, higher RPE, longer rest). */
public class StrengthStrategy extends AbstractSessionStrategy {

    @Override public String name() { return "Strength"; }
    @Override protected int baseSets() { return 14; }
    @Override protected int repLow() { return 3; }
    @Override protected int repHigh() { return 5; }
    @Override protected double baseRpeCap() { return 9.0; }
    @Override protected double loadLow() { return 80; }
    @Override protected double loadHigh() { return 90; }

    @Override
    protected String guidelines() {
        return "Intensity-driven strength: heavy compound lifts in the 3-5 rep range at 80-90% 1RM, "
                + "4-5 sets on the main lift, 3-5 minutes rest, minimal isolation accessories.";
    }
}
