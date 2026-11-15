package com.pulseforge.llm;

import com.pulseforge.config.AppConfig;

/** Chooses the concrete {@link LLMClient} from local configuration (LLM_PROVIDER). */
public final class LlmClientFactory {

    private LlmClientFactory() {
    }

    public static LLMClient fromConfig(AppConfig cfg) {
        String provider = cfg.llmProvider();
        return switch (provider) {
            case "openai", "groq", "openrouter", "ollama", "openai-compatible" ->
                    new OpenAiCompatibleLlmClient(cfg.llmApiKey(), cfg.llmModel(), cfg.llmBaseUrl(), cfg.llmTimeoutSeconds());
            default ->
                    new GeminiLlmClient(cfg.llmApiKey(), cfg.llmModel(), cfg.llmBaseUrl(), cfg.llmTimeoutSeconds());
        };
    }
}
