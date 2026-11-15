package com.pulseforge.agent;

import java.util.List;

/**
 * Coach output: the answer plus a trace of the tools the agent chose to call, so the user
 * (and the demo) can see the multi-step reasoning that grounded the answer.
 */
public record AgentResponse(String answer, List<String> toolTrace, Guardrails.Verdict verdict, String source) {

    public String formatted() {
        StringBuilder sb = new StringBuilder(answer.trim());
        if (!toolTrace.isEmpty()) {
            sb.append("\n\n[Agent steps] ");
            sb.append(String.join(" -> ", toolTrace));
        }
        sb.append("\n[Source] ").append(source);
        return sb.toString();
    }
}
