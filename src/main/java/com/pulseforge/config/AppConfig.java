package com.pulseforge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads local configuration. Values come from (highest priority first):
 * real environment variables, then a git-ignored {@code .env} file in the working directory.
 * <p>
 * No secret is ever hard-coded; {@code .env.example} documents the keys with placeholders.
 */
public final class AppConfig {

    private final Map<String, String> values = new HashMap<>();

    private AppConfig() {
    }

    public static AppConfig load() {
        return load(Paths.get(".env"));
    }

    public static AppConfig load(Path envFile) {
        AppConfig cfg = new AppConfig();
        if (envFile != null && Files.isRegularFile(envFile)) {
            try {
                List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                            || val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    cfg.values.put(key, val);
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read " + envFile + ": " + e.getMessage());
            }
        }
        return cfg;
    }

    /** Creates a config from explicit values (used by tests). */
    public static AppConfig of(Map<String, String> values) {
        AppConfig cfg = new AppConfig();
        cfg.values.putAll(values);
        return cfg;
    }

    public String get(String key, String def) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String v = values.get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String llmProvider() {
        return get("LLM_PROVIDER", "gemini").toLowerCase();
    }

    public String llmApiKey() {
        String key = get("LLM_API_KEY", "");
        // Treat the placeholder from .env.example as "not configured".
        return key.startsWith("YOUR_") ? "" : key;
    }

    public String llmModel() {
        return get("LLM_MODEL", llmProvider().equals("gemini") ? "gemini-flash-lite-latest" : "gpt-4o-mini");
    }

    public String llmBaseUrl() {
        return get("LLM_BASE_URL", llmProvider().equals("gemini")
                ? "https://generativelanguage.googleapis.com/v1beta"
                : "https://api.openai.com/v1");
    }

    public int llmTimeoutSeconds() {
        return getInt("LLM_TIMEOUT_SECONDS", 45);
    }

    public Path dataDir() {
        return Paths.get(get("PULSEFORGE_DATA_DIR", "data"));
    }
}
