package com.pulseforge.domain;

import com.pulseforge.json.Json;

import java.util.Map;

/** One recognized (or manually entered) food with its macronutrients. Mutable so the user can edit estimates. */
public class FoodItem {
    private String name;
    private String portion;
    private double calories;
    private double proteinG;
    private double carbsG;
    private double fatG;

    public FoodItem(String name, String portion, double calories, double proteinG, double carbsG, double fatG) {
        this.name = name;
        this.portion = portion;
        this.calories = calories;
        this.proteinG = proteinG;
        this.carbsG = carbsG;
        this.fatG = fatG;
    }

    public Map<String, Object> toJson() {
        return Json.map("name", name, "portion", portion, "calories", calories,
                "proteinG", proteinG, "carbsG", carbsG, "fatG", fatG);
    }

    public static FoodItem fromJson(Map<String, Object> m) {
        return new FoodItem(Json.str(m, "name", "Food"), Json.str(m, "portion", ""),
                Json.num(m, "calories", 0), Json.num(m, "proteinG", 0),
                Json.num(m, "carbsG", 0), Json.num(m, "fatG", 0));
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPortion() { return portion; }
    public void setPortion(String portion) { this.portion = portion; }
    public double getCalories() { return calories; }
    public void setCalories(double calories) { this.calories = calories; }
    public double getProteinG() { return proteinG; }
    public void setProteinG(double proteinG) { this.proteinG = proteinG; }
    public double getCarbsG() { return carbsG; }
    public void setCarbsG(double carbsG) { this.carbsG = carbsG; }
    public double getFatG() { return fatG; }
    public void setFatG(double fatG) { this.fatG = fatG; }
}
