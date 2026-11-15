package com.pulseforge.nutrition;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.FoodItem;
import com.pulseforge.domain.MealLog;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmRequest;
import com.pulseforge.llm.ModelInferenceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nutrition subsystem.
 * <ul>
 *   <li>F02: packages a meal photo into a multimodal prompt and decodes the model's structured
 *       JSON into an (unconfirmed) {@link MealLog}.</li>
 *   <li>F04: deterministic energy reconciliation via {@link EnergyBalanceCalculator}, then
 *       LLM fueling advice grounded in goal and training load, with a rule-based fallback.</li>
 * </ul>
 */
public class VisionNutritionService {

    public static final double MIN_CONFIDENCE = 0.5;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final String VISION_SYSTEM = """
            You are a sports nutritionist with food recognition skills. Identify every distinct food
            in the photo, estimate its portion size, and estimate calories and macronutrients.
            Respond ONLY with JSON of this exact shape:
            {"foods":[{"name":"string","portion":"string","calories":number,"protein_g":number,
            "carbs_g":number,"fat_g":number}],"confidence":number between 0 and 1,
            "issue":"empty string, or why the photo could not be analyzed"}
            If the image is blurry, not food, or unidentifiable, return an empty foods array,
            a confidence below 0.3, and explain in "issue".""";

    private final LLMClient llm;
    private final EnergyBalanceCalculator calculator = new EnergyBalanceCalculator();

    public VisionNutritionService(LLMClient llm) {
        this.llm = llm;
    }

    // --------------------------------------------------------------------- F02

    public MealLog analyzeMeal(Path imagePath, String notes)
            throws IOException, ModelInferenceException, UnrecognizedFoodException {
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            throw new IOException("Image not found: " + imagePath);
        }
        String mime = mimeType(imagePath);
        long size = Files.size(imagePath);
        if (size == 0 || size > MAX_IMAGE_BYTES) {
            throw new IOException("Image must be between 1 byte and 10 MB (was " + size + " bytes)");
        }
        byte[] bytes = Files.readAllBytes(imagePath);
        String userText = "Analyze this meal photo." + (notes == null || notes.isBlank() ? "" : " Context from the athlete: " + notes);
        LlmRequest req = LlmRequest.builder()
                .system(VISION_SYSTEM)
                .userWithImage(userText, bytes, mime)
                .json(true)
                .temperature(0.2)
                .build();
        return parseMealResponse(llm.generate(req), notes);
    }

    /** Decodes the model JSON; package-visible for tests. */
    static MealLog parseMealResponse(String raw, String notes) throws ModelInferenceException, UnrecognizedFoodException {
        Map<String, Object> root;
        try {
            root = Json.parseObjectLenient(raw);
        } catch (JsonException e) {
            throw new ModelInferenceException("Vision model returned malformed JSON: " + e.getMessage(), e);
        }
        double confidence = Json.num(root, "confidence", 0);
        List<FoodItem> items = new ArrayList<>();
        for (Map<String, Object> f : Json.objArr(root, "foods")) {
            items.add(new FoodItem(Json.str(f, "name", "Unknown food"), Json.str(f, "portion", ""),
                    nonNeg(Json.num(f, "calories", 0)), nonNeg(Json.num(f, "protein_g", 0)),
                    nonNeg(Json.num(f, "carbs_g", 0)), nonNeg(Json.num(f, "fat_g", 0))));
        }
        if (items.isEmpty() || confidence < MIN_CONFIDENCE) {
            String issue = Json.str(root, "issue", "");
            throw new UnrecognizedFoodException("Could not clearly identify meal items"
                    + (issue.isBlank() ? "" : " (" + issue + ")")
                    + String.format(" [confidence %.2f]. Please retake the photo or enter the meal manually.", confidence));
        }
        return MealLog.create(notes, MealLog.Source.VISION_AI, confidence, items);
    }

    private static double nonNeg(double v) {
        return Math.max(0, Math.round(v * 10) / 10.0);
    }

    static String mimeType(Path p) throws IOException {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        throw new IOException("Unsupported image type - use PNG, JPEG or WEBP");
    }

    /** Manual entry path (UC03 alt 3a / 4a). */
    public MealLog createManualMeal(String name, double kcal, double protein, double carbs, double fat, String notes) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Meal name is required");
        if (kcal < 0 || protein < 0 || carbs < 0 || fat < 0) throw new IllegalArgumentException("Nutrition values cannot be negative");
        if (kcal > 10000) throw new IllegalArgumentException("Calories look unrealistic (> 10000)");
        return MealLog.create(notes, MealLog.Source.MANUAL, 1.0,
                List.of(new FoodItem(name.trim(), "manual entry", kcal, protein, carbs, fat)));
    }

    // --------------------------------------------------------------------- F04

    /** Deterministic reconciliation only. */
    public EnergySufficiencyReport calculateSufficiency(AthleteProfile profile, LocalDate date, LocalDateTime now) {
        return calculator.calculate(profile, date, now);
    }

    /**
     * Hybrid step: asks the LLM for fueling advice grounded in the computed numbers.
     * Falls back to deterministic guidance if the model is unavailable.
     */
    public void addFuelingAdvice(EnergySufficiencyReport report, AthleteProfile profile, String trainingContext) {
        String prompt = String.format("""
                Athlete goal: %s. Body weight %.0f kg.
                Today's energy reconciliation (computed deterministically, do not recompute):
                %s
                Training today: %s
                Give 3-5 concise, specific fueling recommendations (foods, grams of protein/carbs, timing)
                for the rest of today. If under-fueled after training, include post-workout refuel targets.
                Plain text bullet points, no medical claims.""",
                profile.getGoal().label(), profile.getWeightKg(), report.summary(), trainingContext);
        try {
            String advice = llm.generate(LlmRequest.builder()
                    .system("You are PulseForge AI's sports nutrition advisor. Be practical and brief.")
                    .user(prompt).temperature(0.4).build());
            report.setAdvice(advice.trim(), "AI (" + llm.describe() + ")");
        } catch (ModelInferenceException e) {
            report.setAdvice(fallbackAdvice(report, profile), "Rule-based fallback (" + e.getMessage() + ")");
        }
    }

    static String fallbackAdvice(EnergySufficiencyReport r, AthleteProfile p) {
        StringBuilder sb = new StringBuilder();
        if (r.isUnderFueledAlert()) {
            sb.append("- UNDER-FUELED ALERT: you trained today but logged no meals. Eat a recovery meal now: ")
                    .append(String.format("~%.0f g protein and ~%.0f g carbohydrate.%n", Math.max(30, p.getWeightKg() * 0.4), p.getWeightKg() * 1.0));
        }
        switch (r.getStatus()) {
            case UNDER_FUELED -> sb.append(String.format("- You are about %.0f kcal short of today's demand. Add a carbohydrate + protein meal.%n", -r.netKcal()));
            case HYPER_CALORIC_SURPLUS -> sb.append(String.format("- You are about %.0f kcal over today's demand; keep remaining meals lean (protein + vegetables).%n", r.netKcal()));
            default -> sb.append("- Energy intake matches today's demand. Keep meals balanced.\n");
        }
        if (r.proteinDeficitG() > 0) {
            sb.append(String.format("- Protein is %.0f g below target (%.0f g). Add lean protein (e.g. 200 g Greek yogurt ~20 g, 150 g chicken ~45 g).%n",
                    r.proteinDeficitG(), r.getProteinTargetG()));
        } else {
            sb.append("- Protein target met.\n");
        }
        return sb.toString().trim();
    }
}
