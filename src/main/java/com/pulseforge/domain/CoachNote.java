package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.time.LocalDateTime;
import java.util.Map;

/** An AI-generated insight kept in the profile so the audit exporter (F10) can include coach notes. */
public record CoachNote(LocalDateTime createdAt, String category, String text) {

    public Map<String, Object> toJson() {
        return Json.map("createdAt", createdAt.toString(), "category", category, "text", text);
    }

    public static CoachNote fromJson(Map<String, Object> m) {
        return new CoachNote(LocalDateTime.parse(Json.str(m, "createdAt", LocalDateTime.now().toString())),
                Json.str(m, "category", "Coach"), Json.str(m, "text", ""));
    }
}
