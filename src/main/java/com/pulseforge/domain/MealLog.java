package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A logged meal: the food items plus how they were obtained (vision AI or manual entry). */
public class MealLog {

    public enum Source { VISION_AI, MANUAL }

    private final LocalDateTime loggedAt;
    private final String notes;
    private final Source source;
    private final double confidence;
    private final List<FoodItem> items;

    public MealLog(LocalDateTime loggedAt, String notes, Source source, double confidence, List<FoodItem> items) {
        this.loggedAt = loggedAt;
        this.notes = notes == null ? "" : notes;
        this.source = source;
        this.confidence = confidence;
        this.items = new ArrayList<>(items);
    }

    public static MealLog create(String notes, Source source, double confidence, List<FoodItem> items) {
        return new MealLog(LocalDateTime.now(), notes, source, confidence, items);
    }

    public double totalCalories() {
        return items.stream().mapToDouble(FoodItem::getCalories).sum();
    }

    public double totalProtein() {
        return items.stream().mapToDouble(FoodItem::getProteinG).sum();
    }

    public double totalCarbs() {
        return items.stream().mapToDouble(FoodItem::getCarbsG).sum();
    }

    public double totalFat() {
        return items.stream().mapToDouble(FoodItem::getFatG).sum();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (FoodItem f : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    public Map<String, Object> toJson() {
        List<Object> list = new ArrayList<>();
        items.forEach(i -> list.add(i.toJson()));
        return Json.map("loggedAt", loggedAt.toString(), "notes", notes, "source", source.name(),
                "confidence", confidence, "items", list);
    }

    public static MealLog fromJson(Map<String, Object> m) {
        List<FoodItem> items = new ArrayList<>();
        Json.objArr(m, "items").forEach(o -> items.add(FoodItem.fromJson(o)));
        return new MealLog(LocalDateTime.parse(Json.str(m, "loggedAt", LocalDateTime.now().toString())),
                Json.str(m, "notes", ""), Source.valueOf(Json.str(m, "source", "MANUAL")),
                Json.num(m, "confidence", 1), items);
    }

    public LocalDateTime getLoggedAt() { return loggedAt; }
    public String getNotes() { return notes; }
    public Source getSource() { return source; }
    public double getConfidence() { return confidence; }
    public List<FoodItem> getItems() { return items; }
}
