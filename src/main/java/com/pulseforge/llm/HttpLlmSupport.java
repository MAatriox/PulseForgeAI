package com.pulseforge.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP plumbing for REST-based LLM clients. */
final class HttpLlmSupport {

    private final HttpClient http;
    private final Duration timeout;

    HttpLlmSupport(int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    String postJson(String url, String body, Map<String, String> headers) throws ModelInferenceException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(rb::header);
        try {
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new ModelInferenceException("LLM HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            return resp.body();
        } catch (HttpTimeoutException e) {
            throw new ModelInferenceException("LLM request timed out after " + timeout.toSeconds() + "s", e);
        } catch (IOException e) {
            throw new ModelInferenceException("Network unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelInferenceException("LLM request interrupted", e);
        }
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
