package com.pulseforge.agent;

/** ConcreteStrategy: volume-driven programming (more sets, 8-12 reps, moderate RPE). */
public class HypertrophyStrategy extends AbstractSessionStrategy {

    @Override public String name() { return "Hypertrophy"; }
    @Override protected int baseSets() { return 18; }
    @Override protected int repLow() { return 8; }
    @Override protected int repHigh() { return 12; }
    @Override protected double baseRpeCap() { return 8.5; }
    @Override protected double loadLow() { return 65; }
    @Override protected double loadHigh() { return 75; }

    @Override
    protected String guidelines() {
        return "Volume-driven hypertrophy: 8-12 reps, 3-4 sets per exercise, 1-2 reps in reserve, "
                + "60-120 s rest, include isolation work for lagging muscles.";
    }
}
