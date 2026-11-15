package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.time.LocalDate;
import java.util.Map;

/**
 * Output of the Recovery &amp; Readiness Index (F03): a 0-100 score, its tier and the
 * component breakdown shown on the dashboard's sleep score card.
 *
 * @param approximation true when fewer than 3 baseline days existed and population defaults were used
 */
public record ReadinessResult(
        LocalDate date,
        double score,
        ReadinessTier tier,
        double hrvComponent,
        double restingHrComponent,
        double sleepComponent,
        double deepSleepComponent,
        double sleepScore,
        int baselineDays,
        boolean approximation,
        String message) {

    public Map<String, Object> toJson() {
        return Json.map("date", date.toString(), "score", score, "tier", tier.name(),
                "hrvComponent", hrvComponent, "restingHrComponent", restingHrComponent,
                "sleepComponent", sleepComponent, "deepSleepComponent", deepSleepComponent,
                "sleepScore", sleepScore, "baselineDays", (double) baselineDays,
                "approximation", approximation, "message", message);
    }

    public static ReadinessResult fromJson(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        return new ReadinessResult(LocalDate.parse(Json.str(m, "date", LocalDate.now().toString())),
                Json.num(m, "score", 50), ReadinessTier.valueOf(Json.str(m, "tier", "BALANCED")),
                Json.num(m, "hrvComponent", 0), Json.num(m, "restingHrComponent", 0),
                Json.num(m, "sleepComponent", 0), Json.num(m, "deepSleepComponent", 0),
                Json.num(m, "sleepScore", 0), (int) Json.num(m, "baselineDays", 0),
                Json.bool(m, "approximation", false), Json.str(m, "message", ""));
    }

    public String summary() {
        return String.format("Readiness %d/100 (%s)%s for %s", Math.round(score), tier.label(),
                approximation ? " [Approximation]" : "", date);
    }
}
