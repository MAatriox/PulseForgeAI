package com.pulseforge;

import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmRequest;
import com.pulseforge.llm.ModelInferenceException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Test double: replays scripted responses (or fails) and records every request it receives. */
public class ScriptedLlm implements LLMClient {

    private final Deque<String> responses = new ArrayDeque<>();
    public final List<LlmRequest> requests = new ArrayList<>();
    private final boolean alwaysFail;

    public ScriptedLlm(String... scripted) {
        this.alwaysFail = false;
        for (String s : scripted) {
            responses.add(s);
        }
    }

    private ScriptedLlm(boolean fail) {
        this.alwaysFail = fail;
    }

    public static ScriptedLlm failing() {
        return new ScriptedLlm(true);
    }

    @Override
    public String generate(LlmRequest request) throws ModelInferenceException {
        requests.add(request);
        if (alwaysFail || responses.isEmpty()) {
            throw new ModelInferenceException("simulated timeout");
        }
        return responses.poll();
    }

    @Override
    public String describe() {
        return "ScriptedLlm";
    }

    @Override
    public boolean isConfigured() {
        return !alwaysFail;
    }
}
