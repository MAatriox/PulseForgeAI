package com.pulseforge.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Aggregate root for the athlete and the ConcreteSubject of the Observer pattern.
 * Holds anthropometrics, biometric history, meals, completed sessions, the latest readiness
 * score and deload state. Every mutator calls {@link #notifyObservers(ProfileEvent)}.
 */
public class AthleteProfile {

    private String name = "Athlete";
    private int age = 21;
    private String sex = "M";
    private double weightKg = 75;
    private double heightCm = 175;
    private TrainingGoal goal = TrainingGoal.HYPERTROPHY;
    private double proteinPerKg = 1.8;

    private final TreeMap<LocalDate, BiometricRecord> records = new TreeMap<>();
    private final List<MealLog> meals = new ArrayList<>();
    private final List<WorkoutSession> sessions = new ArrayList<>();
    private final List<CoachNote> coachNotes = new ArrayList<>();
    private ReadinessResult latestReadiness;

    // Deload state (F08 / UC08)
    private final List<WorkoutRoutine> deloadCalendar = new ArrayList<>();
    private LocalDate deloadStartDate;
    private LocalDateTime deloadSuppressedUntil;
    private int rpeCapAdjustment;   // lowered by 1 when the athlete declines a deload

    private final List<ProfileObserver> observers = new CopyOnWriteArrayList<>();

    // ---------------------------------------------------------------- observer

    public void addObserver(ProfileObserver o) {
        if (!observers.contains(o)) {
            observers.add(o);
        }
    }

    public void removeObserver(ProfileObserver o) {
        observers.remove(o);
    }

    public void notifyObservers(ProfileEvent event) {
        for (ProfileObserver o : observers) {
            try {
                o.onProfileUpdated(this, event);
            } catch (RuntimeException e) {
                System.err.println("Observer " + o.getClass().getSimpleName() + " failed: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- mutators

    /** Merges imported records by date. Returns the number of distinct days touched. */
    public int storeRecords(Collection<BiometricRecord> incoming) {
        int days = 0;
        for (BiometricRecord r : incoming) {
            BiometricRecord existing = records.get(r.getDate());
            if (existing == null) {
                records.put(r.getDate(), r);
            } else {
                existing.mergeFrom(r);
            }
            days++;
        }
        notifyObservers(ProfileEvent.BIOMETRICS_IMPORTED);
        return days;
    }

    public void updateReadiness(ReadinessResult result) {
        this.latestReadiness = result;
        notifyObservers(ProfileEvent.READINESS_UPDATED);
    }

    public void addMeal(MealLog meal) {
        meals.add(meal);
        notifyObservers(ProfileEvent.MEAL_LOGGED);
    }

    public void addSession(WorkoutSession session) {
        sessions.add(session);
        notifyObservers(ProfileEvent.SESSION_COMPLETED);
    }

    public void addCoachNote(String category, String text) {
        coachNotes.add(new CoachNote(LocalDateTime.now(), category, text));
        notifyObservers(ProfileEvent.COACH_NOTE_ADDED);
    }

    public void updateSettings(String name, int age, String sex, double weightKg, double heightCm, TrainingGoal goal) {
        if (age < 10 || age > 100) throw new IllegalArgumentException("Age must be between 10 and 100");
        if (weightKg < 30 || weightKg > 300) throw new IllegalArgumentException("Weight must be between 30 and 300 kg");
        if (heightCm < 120 || heightCm > 230) throw new IllegalArgumentException("Height must be between 120 and 230 cm");
        this.name = name == null || name.isBlank() ? this.name : name.trim();
        this.age = age;
        this.sex = sex != null && sex.trim().toUpperCase().startsWith("F") ? "F" : "M";
        this.weightKg = weightKg;
        this.heightCm = heightCm;
        this.goal = goal;
        notifyObservers(ProfileEvent.SETTINGS_CHANGED);
    }

    public void setDeloadCalendar(List<WorkoutRoutine> week, LocalDate start) {
        deloadCalendar.clear();
        deloadCalendar.addAll(week);
        deloadStartDate = start;
        notifyObservers(ProfileEvent.DELOAD_UPDATED);
    }

    /** UC08 alt 2a: athlete declines the deload; suppress warnings 72h and lower RPE caps by 1. */
    public void declineDeload(LocalDateTime now) {
        deloadSuppressedUntil = now.plusHours(72);
        rpeCapAdjustment = 1;
        notifyObservers(ProfileEvent.DELOAD_UPDATED);
    }

    public boolean isDeloadWarningSuppressed(LocalDateTime now) {
        return deloadSuppressedUntil != null && now.isBefore(deloadSuppressedUntil);
    }

    // ----------------------------------------------------------------- queries

    /** Mifflin-St Jeor basal metabolic rate in kcal/day. */
    public double bmr() {
        double base = 10 * weightKg + 6.25 * heightCm - 5 * age;
        return "F".equals(sex) ? base - 161 : base + 5;
    }

    public double proteinTargetG() {
        return weightKg * proteinPerKg;
    }

    /** Estimated max heart rate (220 - age), used by the HR-gated rest timer. */
    public int maxHeartRate() {
        return 220 - age;
    }

    public NavigableMap<LocalDate, BiometricRecord> getRecords() {
        return Collections.unmodifiableNavigableMap(records);
    }

    public List<BiometricRecord> recordsBetween(LocalDate fromInclusive, LocalDate toInclusive) {
        return new ArrayList<>(records.subMap(fromInclusive, true, toInclusive, true).values());
    }

    public BiometricRecord latestRecord() {
        return records.isEmpty() ? null : records.lastEntry().getValue();
    }

    public List<MealLog> mealsOn(LocalDate date) {
        return meals.stream().filter(m -> m.getLoggedAt().toLocalDate().equals(date)).collect(Collectors.toList());
    }

    public List<WorkoutSession> sessionsOn(LocalDate date) {
        return sessions.stream().filter(s -> s.getStartedAt().toLocalDate().equals(date)).collect(Collectors.toList());
    }

    public List<WorkoutSession> sessionsBetween(LocalDate from, LocalDate to) {
        return sessions.stream().filter(s -> {
            LocalDate d = s.getStartedAt().toLocalDate();
            return !d.isBefore(from) && !d.isAfter(to);
        }).collect(Collectors.toList());
    }

    public String getName() { return name; }
    public int getAge() { return age; }
    public String getSex() { return sex; }
    public double getWeightKg() { return weightKg; }
    public double getHeightCm() { return heightCm; }
    public TrainingGoal getGoal() { return goal; }
    public double getProteinPerKg() { return proteinPerKg; }
    public ReadinessResult getLatestReadiness() { return latestReadiness; }
    public List<MealLog> getMeals() { return Collections.unmodifiableList(meals); }
    public List<WorkoutSession> getSessions() { return Collections.unmodifiableList(sessions); }
    public List<CoachNote> getCoachNotes() { return Collections.unmodifiableList(coachNotes); }
    public List<WorkoutRoutine> getDeloadCalendar() { return Collections.unmodifiableList(deloadCalendar); }
    public LocalDate getDeloadStartDate() { return deloadStartDate; }
    public LocalDateTime getDeloadSuppressedUntil() { return deloadSuppressedUntil; }
    public int getRpeCapAdjustment() { return rpeCapAdjustment; }

    // ------------------------------------------- raw loaders used by persistence

    /** Restores persisted state without notifying observers (used only by ProfileRepository). */
    public void restore(String name, int age, String sex, double weightKg, double heightCm, TrainingGoal goal,
                        double proteinPerKg, List<BiometricRecord> recs, List<MealLog> mealList,
                        List<WorkoutSession> sessionList, List<CoachNote> notes, ReadinessResult readiness,
                        List<WorkoutRoutine> deload, LocalDate deloadStart, LocalDateTime suppressedUntil, int rpeAdj) {
        this.name = name;
        this.age = age;
        this.sex = sex;
        this.weightKg = weightKg;
        this.heightCm = heightCm;
        this.goal = goal;
        this.proteinPerKg = proteinPerKg;
        records.clear();
        recs.forEach(r -> records.put(r.getDate(), r));
        meals.clear();
        meals.addAll(mealList);
        sessions.clear();
        sessions.addAll(sessionList);
        coachNotes.clear();
        coachNotes.addAll(notes);
        latestReadiness = readiness;
        deloadCalendar.clear();
        deloadCalendar.addAll(deload);
        deloadStartDate = deloadStart;
        deloadSuppressedUntil = suppressedUntil;
        rpeCapAdjustment = rpeAdj;
    }
}
