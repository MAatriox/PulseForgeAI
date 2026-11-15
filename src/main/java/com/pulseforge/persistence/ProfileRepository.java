package com.pulseforge.persistence;

import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.CoachNote;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.ProfileEvent;
import com.pulseforge.domain.ProfileObserver;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.TrainingGoal;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.domain.WorkoutSession;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON-file persistence for the athlete profile ({@code data/profile.json}).
 * It is itself a {@link ProfileObserver}: registered on the profile, it auto-saves after every
 * state change, so neither the GUI nor the CLI needs explicit save calls.
 */
public class ProfileRepository implements ProfileObserver {

    private final Path file;

    public ProfileRepository(Path dataDir) {
        this.file = dataDir.resolve("profile.json");
    }

    public Path getFile() {
        return file;
    }

    public AthleteProfile load() {
        AthleteProfile p = new AthleteProfile();
        if (!Files.isRegularFile(file)) {
            return p;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            List<BiometricRecord> recs = new ArrayList<>();
            Json.objArr(m, "records").forEach(o -> recs.add(BiometricRecord.fromJson(o)));
            List<MealLog> meals = new ArrayList<>();
            Json.objArr(m, "meals").forEach(o -> meals.add(MealLog.fromJson(o)));
            List<WorkoutSession> sessions = new ArrayList<>();
            Json.objArr(m, "sessions").forEach(o -> sessions.add(WorkoutSession.fromJson(o)));
            List<CoachNote> notes = new ArrayList<>();
            Json.objArr(m, "coachNotes").forEach(o -> notes.add(CoachNote.fromJson(o)));
            List<WorkoutRoutine> deload = new ArrayList<>();
            Json.objArr(m, "deloadCalendar").forEach(o -> deload.add(WorkoutRoutine.fromJson(o)));
            String deloadStart = Json.str(m, "deloadStartDate", null);
            String suppressed = Json.str(m, "deloadSuppressedUntil", null);
            p.restore(Json.str(m, "name", "Athlete"), (int) Json.num(m, "age", 21), Json.str(m, "sex", "M"),
                    Json.num(m, "weightKg", 75), Json.num(m, "heightCm", 175),
                    TrainingGoal.valueOf(Json.str(m, "goal", "HYPERTROPHY")), Json.num(m, "proteinPerKg", 1.8),
                    recs, meals, sessions, notes, ReadinessResult.fromJson(Json.obj(m, "latestReadiness")),
                    deload, deloadStart == null ? null : LocalDate.parse(deloadStart),
                    suppressed == null ? null : LocalDateTime.parse(suppressed), (int) Json.num(m, "rpeCapAdjustment", 0));
        } catch (IOException | JsonException | IllegalArgumentException | NullPointerException e) {
            System.err.println("Warning: could not load " + file + " (" + e.getMessage() + "). Starting with an empty profile.");
        }
        return p;
    }

    public synchronized void save(AthleteProfile p) throws IOException {
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        List<Object> recs = new ArrayList<>();
        p.getRecords().values().forEach(r -> recs.add(r.toJson()));
        List<Object> meals = new ArrayList<>();
        p.getMeals().forEach(m -> meals.add(m.toJson()));
        List<Object> sessions = new ArrayList<>();
        p.getSessions().forEach(s -> sessions.add(s.toJson()));
        List<Object> notes = new ArrayList<>();
        p.getCoachNotes().forEach(n -> notes.add(n.toJson()));
        List<Object> deload = new ArrayList<>();
        p.getDeloadCalendar().forEach(r -> deload.add(r.toJson()));
        Map<String, Object> m = Json.map(
                "name", p.getName(), "age", (double) p.getAge(), "sex", p.getSex(), "weightKg", p.getWeightKg(),
                "heightCm", p.getHeightCm(), "goal", p.getGoal().name(), "proteinPerKg", p.getProteinPerKg(),
                "latestReadiness", p.getLatestReadiness() == null ? null : p.getLatestReadiness().toJson(),
                "deloadStartDate", p.getDeloadStartDate() == null ? null : p.getDeloadStartDate().toString(),
                "deloadSuppressedUntil", p.getDeloadSuppressedUntil() == null ? null : p.getDeloadSuppressedUntil().toString(),
                "rpeCapAdjustment", (double) p.getRpeCapAdjustment(),
                "records", recs, "meals", meals, "sessions", sessions, "coachNotes", notes, "deloadCalendar", deload);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.pretty(m), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void onProfileUpdated(AthleteProfile profile, ProfileEvent event) {
        try {
            save(profile);
        } catch (IOException e) {
            System.err.println("Warning: could not save profile: " + e.getMessage());
        }
    }
}
