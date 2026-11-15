package com.pulseforge.agent;

import com.pulseforge.domain.MovementType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Exercise catalogue used by the offline fallback templates. Compounds are listed first. */
public final class ExerciseLibrary {

    public record Entry(String name, String muscleGroup, MovementType type) {
    }

    private static Entry c(String n, String m) {
        return new Entry(n, m, MovementType.COMPOUND);
    }

    private static Entry i(String n, String m) {
        return new Entry(n, m, MovementType.ISOLATION);
    }

    public static final List<String> SPLITS = List.of("PUSH", "PULL", "LEGS", "UPPER", "LOWER", "FULL_BODY");

    private static final Map<String, List<Entry>> BY_SPLIT = Map.of(
            "PUSH", List.of(c("Barbell Bench Press", "Chest"), c("Overhead Press", "Shoulders"),
                    c("Incline Dumbbell Press", "Upper chest"), i("Cable Lateral Raise", "Side delts"),
                    i("Triceps Rope Pushdown", "Triceps"), i("Pec Deck Fly", "Chest")),
            "PULL", List.of(c("Weighted Pull-up", "Lats"), c("Barbell Row", "Upper back"),
                    c("Seated Cable Row", "Mid back"), i("Face Pull", "Rear delts"),
                    i("EZ-Bar Curl", "Biceps"), i("Hammer Curl", "Brachialis")),
            "LEGS", List.of(c("Back Squat", "Quads"), c("Romanian Deadlift", "Hamstrings"),
                    c("Leg Press", "Quads"), i("Lying Leg Curl", "Hamstrings"),
                    i("Leg Extension", "Quads"), i("Standing Calf Raise", "Calves")),
            "UPPER", List.of(c("Barbell Bench Press", "Chest"), c("Barbell Row", "Upper back"),
                    c("Overhead Press", "Shoulders"), c("Lat Pulldown", "Lats"),
                    i("EZ-Bar Curl", "Biceps"), i("Triceps Rope Pushdown", "Triceps")),
            "LOWER", List.of(c("Deadlift", "Posterior chain"), c("Front Squat", "Quads"),
                    c("Bulgarian Split Squat", "Glutes"), i("Lying Leg Curl", "Hamstrings"),
                    i("Leg Extension", "Quads"), i("Seated Calf Raise", "Calves")),
            "FULL_BODY", List.of(c("Back Squat", "Quads"), c("Barbell Bench Press", "Chest"),
                    c("Barbell Row", "Upper back"), c("Romanian Deadlift", "Hamstrings"),
                    i("Cable Lateral Raise", "Side delts"), i("Plank", "Core")));

    public static final List<Entry> MOBILITY = List.of(
            i("Hip Mobility Flow", "Hips"), i("Thoracic Spine Rotations", "Upper back"),
            i("Couch Stretch", "Hip flexors"), i("Band Shoulder Dislocates", "Shoulders"),
            i("Zone 2 Cycling (minutes)", "Cardio"), i("Dead Bug", "Core"));

    private ExerciseLibrary() {
    }

    public static String normalizeSplit(String split) {
        if (split == null || split.isBlank()) {
            return "FULL_BODY";
        }
        String s = split.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (s.endsWith("_DAY")) s = s.substring(0, s.length() - 4);
        if (s.equals("LEG")) s = "LEGS";
        if (s.equals("FULL") || s.equals("FULLBODY")) s = "FULL_BODY";
        if (!BY_SPLIT.containsKey(s)) {
            throw new IllegalArgumentException("Unknown split '" + split + "'. Choose one of " + SPLITS);
        }
        return s;
    }

    public static List<Entry> forSplit(String split) {
        return BY_SPLIT.get(normalizeSplit(split));
    }
}
