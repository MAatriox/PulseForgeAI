package com.pulseforge.llm;

import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for any OpenAI-compatible {@code /chat/completions} endpoint: OpenAI (gpt-4o-mini),
 * Groq, OpenRouter, or a local Ollama server ({@code http://localhost:11434/v1}, no key needed).
 */
public class OpenAiCompatibleLlmClient implements LLMClient {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpLlmSupport http;

    public OpenAiCompatibleLlmClient(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = new HttpLlmSupport(timeoutSeconds);
    }

    @Override
    public String generate(LlmRequest request) throws ModelInferenceException {
        if (!isConfigured()) {
            throw new ModelInferenceException("No API key configured for " + baseUrl + " (set LLM_API_KEY in .env)");
        }
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        String resp = http.postJson(baseUrl + "/chat/completions", Json.stringify(buildBody(request)), headers);
        return extractText(resp);
    }

    Map<String, Object> buildBody(LlmRequest request) {
        List<Object> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Json.map("role", "system", "content", request.systemPrompt()));
        }
        for (LlmRequest.Message m : request.messages()) {
            String role = m.role() == LlmRequest.Role.USER ? "user" : "assistant";
            if (m.hasImage()) {
                String dataUri = "data:" + m.imageMimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(m.imageBytes());
                List<Object> parts = List.of(
                        Json.map("type", "text", "text", m.text()),
                        Json.map("type", "image_url", "image_url", Json.map("url", dataUri)));
                messages.add(Json.map("role", role, "content", parts));
            } else {
                messages.add(Json.map("role", role, "content", m.text()));
            }
        }
        Map<String, Object> body = Json.map("model", model, "messages", messages, "temperature", request.temperature());
        if (request.jsonMode()) {
            body.put("response_format", Json.map("type", "json_object"));
        }
        return body;
    }

    static String extractText(String responseBody) throws ModelInferenceException {
        try {
            Map<String, Object> root = Json.parseObject(responseBody);
            List<Map<String, Object>> choices = Json.objArr(root, "choices");
            if (choices.isEmpty()) {
                throw new ModelInferenceException("LLM returned no choices");
            }
            String text = Json.str(Json.obj(choices.get(0), "message"), "content", "");
            if (text.isBlank()) {
                throw new ModelInferenceException("LLM returned an empty response");
            }
            return text;
        } catch (JsonException e) {
            throw new ModelInferenceException("Malformed LLM response: " + e.getMessage(), e);
        }
    }

    private boolean isLocal() {
        return baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
    }

    @Override
    public String describe() {
        return (isLocal() ? "Local OpenAI-compatible" : "OpenAI-compatible") + " (" + model + ")";
    }

    @Override
    public boolean isConfigured() {
        return isLocal() || (apiKey != null && !apiKey.isBlank());
    }
}
