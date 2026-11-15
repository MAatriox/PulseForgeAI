package com.pulseforge.agent;

import java.util.Map;

/**
 * A capability the coach agent may invoke during multi-step reasoning, such as reading the
 * athlete's readiness or today's energy balance. Tools are deterministic and read-only.
 */
public interface AgentTool {

    String name();

    /** One-line description shown to the LLM, including any arguments. */
    String description();

    /** Executes the tool; returns a plain-text observation for the model. */
    String execute(Map<String, Object> args);

    /** Convenience factory for lambda-backed tools. */
    static AgentTool of(String name, String description, java.util.function.Function<Map<String, Object>, String> fn) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public String execute(Map<String, Object> args) { return fn.apply(args); }
        };
    }
}
