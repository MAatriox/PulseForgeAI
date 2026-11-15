package com.pulseforge.agent;

import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmRequest;
import com.pulseforge.llm.ModelInferenceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent controller.
 * <ul>
 *   <li><b>Strategy pattern Context</b>: holds the active {@link WorkoutStrategy}, swaps it at
 *       runtime ({@link #setStrategy}) and falls back to the strategy's offline template when the
 *       model fails ({@link #buildWorkout}).</li>
 *   <li><b>Tool-using coach</b> ({@link #queryAgent}): a bounded ReAct-style loop in which the LLM
 *       decides which read-only tools to call (readiness, biometric trend, energy balance, current
 *       workout, ...) before composing a grounded answer. Conversation memory and medical
 *       guardrails are applied around the loop.</li>
 *   <li><b>Fatigue diagnosis</b> ({@link #diagnoseFatigue}): turns deterministic 14-day sentinel
 *       findings into an explanatory report.</li>
 * </ul>
 */
public class AgentBridge {

    public static final int MAX_TOOL_STEPS = 4;

    private final LLMClient llm;
    private final ToolRegistry tools;
    private final CoachMemory memory;
    private WorkoutStrategy strategy;

    public AgentBridge(LLMClient llm, ToolRegistry tools, WorkoutStrategy initialStrategy) {
        this.llm = llm;
        this.tools = tools;
        this.strategy = initialStrategy;
        this.memory = new CoachMemory(6);
    }

    // ---------------------------------------------------------------- Strategy

    public void setStrategy(WorkoutStrategy strategy) {
        this.strategy = strategy;
    }

    public WorkoutStrategy getStrategy() {
        return strategy;
    }

    /** UC05: generate with the active strategy; on ModelInferenceException use the offline template. */
    public WorkoutPlan buildWorkout(StrategyContext ctx) {
        try {
            return strategy.generatePlan(ctx, llm);
        } catch (ModelInferenceException e) {
            return strategy.fallbackPlan(ctx, e.getMessage());
        }
    }

    // ------------------------------------------------------------------- Coach

    private String coachSystemPrompt() {
        return """
                You are PulseForge AI, a holistic training, recovery and diet coach. You answer questions about
                training modifications, exercise substitutions, form, fatigue management and meal adjustments,
                grounded in the athlete's LIVE data, which you obtain by calling tools.

                Available tools:
                %s
                Protocol - reply with exactly ONE JSON object per message, nothing else:
                  {"action":"tool","tool":"<tool name>","args":{...},"reason":"<why you need it>"}
                  {"action":"final","answer":"<your answer to the athlete>"}
                Rules:
                - Before answering anything about readiness, sleep, today's training or diet, call the relevant tools.
                - Use at most %d tool calls, then give a final answer.
                - Cite the actual numbers you looked up. Be concise (under 180 words) and practical.
                - Stay within fitness, recovery and nutrition. Politely redirect unrelated questions.
                - Never diagnose medical conditions; recommend a professional when appropriate.
                """.formatted(tools.describeForPrompt(), MAX_TOOL_STEPS);
    }

    /** UC09: multi-step, tool-using coach turn. */
    public AgentResponse queryAgent(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Please type a question for the coach");
        }
        Guardrails.Verdict verdict = Guardrails.screen(question);
        if (verdict == Guardrails.Verdict.EMERGENCY) {
            return new AgentResponse(Guardrails.EMERGENCY_MESSAGE, List.of(), verdict, "Guardrail (deterministic)");
        }
        if (verdict == Guardrails.Verdict.MEDICAL) {
            memory.add(question, Guardrails.MEDICAL_DISCLAIMER);
            return new AgentResponse(Guardrails.MEDICAL_DISCLAIMER, List.of(), verdict, "Guardrail (deterministic)");
        }

        List<LlmRequest.Message> convo = new ArrayList<>(memory.asMessages());
        convo.add(LlmRequest.Message.user(question));
        List<String> trace = new ArrayList<>();
        try {
            for (int step = 0; step <= MAX_TOOL_STEPS; step++) {
                boolean forceFinal = step == MAX_TOOL_STEPS;
                if (forceFinal) {
                    convo.add(LlmRequest.Message.user("Tool budget exhausted. Reply now with {\"action\":\"final\",\"answer\":...}."));
                }
                String raw = llm.generate(LlmRequest.builder().system(coachSystemPrompt())
                        .messages(convo).json(true).temperature(0.3).build());
                Map<String, Object> msg;
                try {
                    msg = Json.parseObjectLenient(raw);
                } catch (JsonException e) {
                    // Model ignored the protocol: treat its text as the final answer.
                    return finish(question, raw, trace, verdict);
                }
                String action = Json.str(msg, "action", "final");
                if ("tool".equalsIgnoreCase(action) && !forceFinal) {
                    String toolName = Json.str(msg, "tool", "");
                    AgentTool tool = tools.get(toolName);
                    Map<String, Object> args = Json.obj(msg, "args");
                    String observation = tool == null
                            ? "ERROR: unknown tool '" + toolName + "'. Valid tools: " + toolNames()
                            : safeExecute(tool, args == null ? new LinkedHashMap<>() : args);
                    trace.add(toolName);
                    convo.add(LlmRequest.Message.assistant(Json.stringify(msg)));
                    convo.add(LlmRequest.Message.user("TOOL RESULT [" + toolName + "]:\n" + observation));
                    continue;
                }
                String answer = Json.str(msg, "answer", null);
                if (answer == null) {
                    answer = Json.str(msg, "response", raw);
                }
                return finish(question, answer, trace, verdict);
            }
            return finish(question, "I couldn't complete my reasoning - please rephrase the question.", trace, verdict);
        } catch (ModelInferenceException e) {
            return offlineAnswer(e.getMessage(), trace);
        }
    }

    private AgentResponse finish(String question, String answer, List<String> trace, Guardrails.Verdict verdict) {
        memory.add(question, answer);
        return new AgentResponse(answer, trace, verdict, "AI (" + llm.describe() + ")");
    }

    /** Degraded mode when the LLM is unreachable: deterministic snapshot, clearly labeled. */
    private AgentResponse offlineAnswer(String reason, List<String> trace) {
        StringBuilder sb = new StringBuilder("The AI coach is unavailable right now (" + reason + ").\n"
                + "Here is your current data so you can decide:\n");
        for (String t : List.of("get_readiness", "get_energy_balance")) {
            AgentTool tool = tools.get(t);
            if (tool != null) {
                sb.append("\n").append(safeExecute(tool, new LinkedHashMap<>())).append('\n');
            }
        }
        return new AgentResponse(sb.toString(), trace, Guardrails.Verdict.ALLOWED, "Offline (no LLM)");
    }

    private static String safeExecute(AgentTool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (RuntimeException e) {
            return "ERROR running " + tool.name() + ": " + e.getMessage();
        }
    }

    private String toolNames() {
        List<String> names = new ArrayList<>();
        tools.all().forEach(t -> names.add(t.name()));
        return String.join(", ", names);
    }

    public CoachMemory getMemory() {
        return memory;
    }

    // ------------------------------------------------------- Fatigue diagnosis

    /** F08: AI explanation of the deterministic sentinel findings; rule-based text if the model fails. */
    public void diagnoseFatigue(FatigueAssessment a, String athleteSummary) {
        String prompt = String.format("""
                Athlete: %s
                Deterministic overtraining screen over %s to %s:
                %s
                14-day trend (HRV ms, resting HR bpm, sleep h, readiness; '*' = suppressed HRV + sleep debt, '?' = missing):
                %s
                Write a structured fatigue diagnosis with sections: "Findings" (cite the specific days/metrics),
                "Likely cause" (CNS/systemic fatigue vs. lifestyle/sleep), "Risk" (%s - explain), and
                "Recommendation" (deload or not, plus 2-3 concrete actions). If days are missing, say so and be
                conservative. Max 220 words, plain text, no medical diagnosis.""",
                athleteSummary, a.getWindowStart(), a.getWindowEnd(), a.summary(), a.getTrendTable(), a.getRisk());
        try {
            String text = llm.generate(LlmRequest.builder()
                    .system("You are PulseForge AI's recovery scientist. You explain fatigue patterns in wearable data.")
                    .user(prompt).temperature(0.3).build());
            a.setDiagnosis(text.trim(), "AI (" + llm.describe() + ")");
        } catch (ModelInferenceException e) {
            a.setDiagnosis(ruleBasedDiagnosis(a), "Rule-based fallback (" + e.getMessage() + ")");
        }
    }

    static String ruleBasedDiagnosis(FatigueAssessment a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Findings: ").append(a.summary()).append('\n');
        if (!a.getMissingDates().isEmpty()) {
            sb.append("Missing data on ").append(a.getMissingDates()).append(" - guidance is conservative.\n");
        }
        sb.append("Risk: ").append(a.getRisk()).append('\n');
        sb.append("Recommendation: ");
        if (a.isDeloadRecommended()) {
            sb.append("take a 7-day deload (~50% volume, RPE <= 7), sleep 8+ hours, keep protein high.");
        } else if (a.getRisk() == FatigueAssessment.Risk.ELEVATED) {
            sb.append("reduce working sets by ~20% for the next 3 days and prioritise sleep.");
        } else {
            sb.append("no deload needed; continue normal training.");
        }
        return sb.toString();
    }
}
