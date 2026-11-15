package com.pulseforge.llm;

import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini client using the public {@code generateContent} REST endpoint.
 * Supports system instructions, multi-turn chat, inline images (vision) and JSON output mode.
 */
public class GeminiLlmClient implements LLMClient {

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpLlmSupport http;

    public GeminiLlmClient(String apiKey, String model, String baseUrl, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = new HttpLlmSupport(timeoutSeconds);
    }

    @Override
    public String generate(LlmRequest request) throws ModelInferenceException {
        if (!isConfigured()) {
            throw new ModelInferenceException("No Gemini API key configured (set LLM_API_KEY in .env)");
        }
        String url = baseUrl + "/models/" + model + ":generateContent";
        String body = Json.stringify(buildBody(request));
        String resp = http.postJson(url, body, Map.of("x-goog-api-key", apiKey));
        return extractText(resp);
    }

    Map<String, Object> buildBody(LlmRequest request) {
        List<Object> contents = new ArrayList<>();
        for (LlmRequest.Message m : request.messages()) {
            List<Object> parts = new ArrayList<>();
            parts.add(Json.map("text", m.text()));
            if (m.hasImage()) {
                parts.add(Json.map("inline_data", Json.map(
                        "mime_type", m.imageMimeType(),
                        "data", Base64.getEncoder().encodeToString(m.imageBytes()))));
            }
            contents.add(Json.map("role", m.role() == LlmRequest.Role.USER ? "user" : "model", "parts", parts));
        }
        Map<String, Object> genConfig = Json.map("temperature", request.temperature());
        if (request.jsonMode()) {
            genConfig.put("responseMimeType", "application/json");
        }
        Map<String, Object> body = Json.map("contents", contents, "generationConfig", genConfig);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("systemInstruction", Json.map("parts", List.of(Json.map("text", request.systemPrompt()))));
        }
        return body;
    }

    static String extractText(String responseBody) throws ModelInferenceException {
        try {
            Map<String, Object> root = Json.parseObject(responseBody);
            List<Map<String, Object>> candidates = Json.objArr(root, "candidates");
            if (candidates.isEmpty()) {
                Map<String, Object> feedback = Json.obj(root, "promptFeedback");
                throw new ModelInferenceException("Gemini returned no candidates"
                        + (feedback != null ? " (" + Json.str(feedback, "blockReason", "blocked") + ")" : ""));
            }
            Map<String, Object> content = Json.obj(candidates.get(0), "content");
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> part : Json.objArr(content, "parts")) {
                sb.append(Json.str(part, "text", ""));
            }
            if (sb.length() == 0) {
                throw new ModelInferenceException("Gemini returned an empty response");
            }
            return sb.toString();
        } catch (JsonException e) {
            throw new ModelInferenceException("Malformed Gemini response: " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return "Gemini (" + model + ")";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
