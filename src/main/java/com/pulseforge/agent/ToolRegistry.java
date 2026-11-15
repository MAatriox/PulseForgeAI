package com.pulseforge.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Named collection of {@link AgentTool}s available to the coach agent. */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(AgentTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public AgentTool get(String name) {
        return name == null ? null : tools.get(name.trim());
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (AgentTool t : tools.values()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        }
        return sb.toString();
    }
}
