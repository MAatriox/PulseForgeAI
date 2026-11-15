package com.pulseforge.llm;

/**
 * Abstraction over any LLM provider (Stage 1: "Architectural Decoupling").
 * Business logic depends only on this interface, so Gemini, OpenAI-compatible endpoints
 * (OpenAI, Groq, OpenRouter, local Ollama) or test doubles can be swapped freely.
 */
public interface LLMClient {

    /**
     * Runs one inference call.
     *
     * @return the model's raw text output (JSON text when {@link LlmRequest#jsonMode()} is set)
     * @throws ModelInferenceException on network errors, timeouts, HTTP errors or empty output
     */
    String generate(LlmRequest request) throws ModelInferenceException;

    /** Human-readable provider/model description, e.g. "Gemini (gemini-flash-lite-latest)". */
    String describe();

    /** Whether the client has what it needs (e.g. an API key) to attempt a call. */
    boolean isConfigured();
}
