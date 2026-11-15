package com.pulseforge.facade;

import com.pulseforge.agent.AgentBridge;
import com.pulseforge.agent.AgentResponse;
import com.pulseforge.agent.AgentTool;
import com.pulseforge.agent.DeloadStrategy;
import com.pulseforge.agent.ExerciseLibrary;
import com.pulseforge.agent.HypertrophyStrategy;
import com.pulseforge.agent.StrategyContext;
import com.pulseforge.agent.StrengthStrategy;
import com.pulseforge.agent.ToolRegistry;
import com.pulseforge.agent.WorkoutStrategy;
import com.pulseforge.config.AppConfig;
import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;
import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.ProfileObserver;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.ReadinessTier;
import com.pulseforge.domain.TrainingGoal;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.domain.WorkoutSession;
import com.pulseforge.ingest.FitnessDataParser;
import com.pulseforge.ingest.HealthParserFactory;
import com.pulseforge.ingest.InvalidHealthDataException;
import com.pulseforge.json.Json;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.LlmClientFactory;
import com.pulseforge.llm.ModelInferenceException;
import com.pulseforge.nutrition.UnrecognizedFoodException;
import com.pulseforge.nutrition.VisionNutritionService;
import com.pulseforge.persistence.ProfileRepository;
import com.pulseforge.persistence.SampleDataGenerator;
import com.pulseforge.readiness.FatigueSentinel;
import com.pulseforge.readiness.ReadinessCalculator;
import com.pulseforge.report.ReportGenerator;
import com.pulseforge.session.Command;
import com.pulseforge.session.CommandInvoker;
import com.pulseforge.session.DeleteExerciseCommand;
import com.pulseforge.session.LogSetCommand;
import com.pulseforge.session.RestTimer;
import com.pulseforge.session.SimulatedHeartRateSource;
import com.pulseforge.session.WorkoutSessionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Facade pattern: the single high-level API used by both {@code WorkoutGUI} and
 * {@code WorkoutCLI}. It wires and coordinates the subsystems (ingestion adapters, readiness
 * engine, nutrition service, agent bridge, session manager / command invoker, report generator,
 * persistence) so the interfaces never orchestrate them directly.
 */
public class PulseForgeFacade {

    /** Result of a health import (UC01 step 6). */
    public record ImportResult(String format, int daysImported, ReadinessResult readiness) {
    }

    /** Result of workout generation; {@code fatigue} is non-null when a deload is recommended (UC05 alt 2a). */
    public record WorkoutResult(WorkoutPlan plan, FatigueAssessment fatigue) {
    }

    private final AthleteProfile profile;
    private final ProfileRepository repository;
    private final LLMClient llm;
    private final ReadinessCalculator readinessCalculator = new ReadinessCalculator();
    private final FatigueSentinel fatigueSentinel = new FatigueSentinel(readinessCalculator);
    private final VisionNutritionService nutrition;
    private final AgentBridge agent;
    private final WorkoutSessionManager sessionManager;
    private final CommandInvoker invoker = new CommandInvoker();
    private final ReportGenerator reportGenerator = new ReportGenerator(readinessCalculator);

    /** Production wiring from local configuration. */
    public static PulseForgeFacade create(AppConfig cfg) {
        ProfileRepository repo = new ProfileRepository(cfg.dataDir());
        return new PulseForgeFacade(repo.load(), repo, LlmClientFactory.fromConfig(cfg), new RestTimer());
    }

    public PulseForgeFacade(AthleteProfile profile, ProfileRepository repository, LLMClient llm, RestTimer timer) {
        this.profile = profile;
        this.repository = repository;
        this.llm = llm;
        this.nutrition = new VisionNutritionService(llm);
        this.sessionManager = new WorkoutSessionManager(timer, profile::maxHeartRate);
        this.agent = new AgentBridge(llm, buildTools(), defaultStrategyFor(profile.getGoal()));
        if (repository != null) {
            profile.addObserver(repository);
        }
    }

    // --------------------------------------------------------------- general

    public AthleteProfile getProfile() {
        return profile;
    }

    public void addProfileObserver(ProfileObserver o) {
        profile.addObserver(o);
    }

    public WorkoutSessionManager getSessionManager() {
        return sessionManager;
    }

    public String llmDescription() {
        return llm.describe() + (llm.isConfigured() ? "" : " - NOT CONFIGURED (offline fallbacks active)");
    }

    public boolean isLlmConfigured() {
        return llm.isConfigured();
    }

    public void updateProfile(String name, int age, String sex, double weightKg, double heightCm, TrainingGoal goal) {
        profile.updateSettings(name, age, sex, weightKg, heightCm, goal);
        if (!sessionManager.isActive()) {
            agent.setStrategy(defaultStrategyFor(goal));
        }
    }

    public List<Path> generateSampleData(Path dir) throws IOException {
        return SampleDataGenerator.generate(dir, LocalDate.now().minusDays(1), 21);
    }

    // ------------------------------------------------------------- F01 / F03

    /** UC01: parse via the matching adapter, validate, store, then recompute readiness (UC02). */
    public ImportResult importHealthData(Path file) throws InvalidHealthDataException {
        FitnessDataParser parser = HealthParserFactory.forFile(file);
        List<BiometricRecord> records = parser.parse(file);   // throws before any state changes
        int days = profile.storeRecords(records);
        return new ImportResult(parser.formatName(), days, getTodayReadiness());
    }

    /** UC02: compute and publish readiness (observers redraw gauges / print to console). */
    public ReadinessResult getTodayReadiness() {
        ReadinessResult r = readinessCalculator.computeReadiness(profile);
        profile.updateReadiness(r);
        return r;
    }

    public List<ReadinessResult> readinessHistory(int days) {
        LocalDate end = ReadinessCalculator.latestScorableDay(profile.getRecords());
        if (end == null) {
            return List.of();
        }
        return readinessCalculator.history(profile.getRecords(), end.minusDays(days - 1), end);
    }

    // ------------------------------------------------------------- F02 / F04

    /** UC03: AI meal analysis. The result is NOT saved until {@link #confirmMeal} (user review step). */
    public MealLog analyzeMealPhoto(Path image, String notes)
            throws IOException, ModelInferenceException, UnrecognizedFoodException {
        return nutrition.analyzeMeal(image, notes);
    }

    /** UC03 final step: save the (possibly edited) meal and trigger UC04 deterministically. */
    public EnergySufficiencyReport confirmMeal(MealLog meal) {
        profile.addMeal(meal);
        return reconcileEnergyBalance(false);
    }

    public EnergySufficiencyReport addManualMeal(String name, double kcal, double protein, double carbs, double fat, String notes) {
        return confirmMeal(nutrition.createManualMeal(name, kcal, protein, carbs, fat, notes));
    }

    /** UC04: deterministic reconciliation, optionally enriched with LLM fueling advice (hybrid). */
    public EnergySufficiencyReport reconcileEnergyBalance(boolean withAiAdvice) {
        EnergySufficiencyReport report = nutrition.calculateSufficiency(profile, LocalDate.now(), LocalDateTime.now());
        if (withAiAdvice) {
            nutrition.addFuelingAdvice(report, profile, trainingContextToday());
            if (report.getAdviceSource().startsWith("AI")) {
                profile.addCoachNote("Fueling advice", report.getAdvice());
            }
        }
        return report;
    }

    private String trainingContextToday() {
        List<WorkoutSession> today = profile.sessionsOn(LocalDate.now());
        if (today.isEmpty()) {
            return sessionManager.isActive() ? "session in progress (" + Math.round(sessionManager.tonnage()) + " kg so far)" : "no session logged yet";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkoutSession s : today) {
            sb.append(s.getRoutine().getTitle()).append(" (").append(Math.round(s.tonnage())).append(" kg volume, ")
                    .append(s.getRoutine().completedSets()).append(" sets) ");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ F05

    public static WorkoutStrategy strategyFor(String key) {
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "hypertrophy", "h" -> new HypertrophyStrategy();
            case "strength", "s" -> new StrengthStrategy();
            case "deload", "d" -> new DeloadStrategy();
            default -> throw new IllegalArgumentException("Strategy must be 'hypertrophy' or 'strength'");
        };
    }

    static WorkoutStrategy defaultStrategyFor(TrainingGoal goal) {
        return goal == TrainingGoal.STRENGTH ? new StrengthStrategy() : new HypertrophyStrategy();
    }

    /**
     * UC05: switch to the requested strategy, compute deterministic limits from readiness, ask the
     * agent to build the routine (offline fallback on failure) and stage it for the live session.
     */
    public WorkoutResult generateAdaptedWorkout(String split, String strategyKey) {
        String normalized = ExerciseLibrary.normalizeSplit(split);
        if (sessionManager.isActive()) {
            throw new IllegalStateException("Finish the current session before generating a new workout");
        }
        agent.setStrategy(strategyKey == null || strategyKey.isBlank()
                ? defaultStrategyFor(profile.getGoal()) : strategyFor(strategyKey));
        if (agent.getStrategy() instanceof DeloadStrategy) {
            throw new IllegalArgumentException("Use 'Plan Deload Week' for deload programming");
        }
        WorkoutPlan plan = agent.buildWorkout(buildContext(normalized));
        sessionManager.stageRoutine(plan.first());
        invoker.clear();

        FatigueAssessment fatigue = fatigueSentinel.evaluate(profile);
        boolean warn = fatigue.isDeloadRecommended() && !profile.isDeloadWarningSuppressed(LocalDateTime.now());
        return new WorkoutResult(plan, warn ? fatigue : null);
    }

    private StrategyContext buildContext(String split) {
        ReadinessResult r = profile.getLatestReadiness();
        if (r == null) {
            r = getTodayReadiness();
        }
        BiometricRecord latest = profile.getRecords().get(r.date());
        return new StrategyContext(split, r.score(), r.tier(), latest == null ? null : latest.getSleepHours(),
                r.approximation(), profile.getGoal(), profile.getRpeCapAdjustment(), "full commercial gym",
                profile.getWeightKg());
    }

    // ------------------------------------------------------------- F06 / F07

    public void startSession() {
        sessionManager.startSession();
        invoker.clear();
    }

    /** UC06: build a LogSetCommand (validated before any mutation) and run it through the invoker. */
    public String logSet(int exerciseIndex, double weight, int reps, double rpe) {
        if (!sessionManager.isActive()) {
            throw new IllegalStateException("Start the session before logging sets");
        }
        sessionManager.exerciseAt(exerciseIndex);   // validates index
        LogSetCommand cmd = new LogSetCommand(sessionManager, exerciseIndex, weight, reps, rpe);
        invoker.executeCommand(cmd);
        return "Logged " + cmd.description();
    }

    public String deleteExercise(int exerciseIndex) {
        if (!sessionManager.isActive()) {
            throw new IllegalStateException("Start the session first");
        }
        DeleteExerciseCommand cmd = new DeleteExerciseCommand(sessionManager, exerciseIndex);
        invoker.executeCommand(cmd);
        return "Deleted: " + cmd.description();
    }

    /** UC06a. */
    public Optional<String> undoLastAction() {
        return invoker.undo().map(Command::description);
    }

    public Optional<String> redoLastAction() {
        return invoker.redo().map(Command::description);
    }

    public boolean canUndo() {
        return invoker.canUndo();
    }

    public boolean canRedo() {
        return invoker.canRedo();
    }

    public WorkoutSession finishSession() {
        WorkoutSession s = sessionManager.finishSession();
        invoker.clear();
        profile.addSession(s);
        return s;
    }

    public void addRestTime() {
        sessionManager.addRestTime(30);
    }

    public void skipRest() {
        sessionManager.skipRest();
    }

    /** Toggles the simulated live heart-rate stream used by the HR-gated rest timer. */
    public void setLiveHeartRateSimulation(boolean enabled) {
        if (enabled) {
            BiometricRecord latest = profile.latestRecord();
            int resting = latest != null && latest.getRestingHr() != null ? (int) Math.round(latest.getRestingHr()) : 60;
            sessionManager.setHeartRateSource(new SimulatedHeartRateSource(resting, profile.maxHeartRate()));
        } else {
            sessionManager.setHeartRateSource(null);
        }
    }

    // ------------------------------------------------------------------ F08

    /** Deterministic sentinel only. */
    public FatigueAssessment assessFatigue() {
        return fatigueSentinel.evaluate(profile);
    }

    /** Sentinel + AI diagnostic report; saved as a coach note for the audit report. */
    public FatigueAssessment diagnoseFatigue() {
        FatigueAssessment a = fatigueSentinel.evaluate(profile);
        agent.diagnoseFatigue(a, athleteSummary());
        profile.addCoachNote("Fatigue diagnosis (" + a.getRisk() + ")", a.getDiagnosis());
        return a;
    }

    /** UC08: swap to DeloadStrategy, build the 7-day plan, save it to the calendar, restore the previous strategy. */
    public WorkoutPlan planDeloadWeek() {
        WorkoutStrategy previous = agent.getStrategy();
        agent.setStrategy(new DeloadStrategy());
        try {
            ReadinessResult r = profile.getLatestReadiness() != null ? profile.getLatestReadiness() : getTodayReadiness();
            StrategyContext ctx = buildContext("FULL_BODY");
            if (r.tier() == ReadinessTier.PRIME) {
                ctx = new StrategyContext("FULL_BODY", r.score(), ReadinessTier.BALANCED, ctx.sleepHours(),
                        ctx.readinessApproximate(), ctx.goal(), ctx.rpeCapAdjustment(), ctx.equipment(), ctx.bodyWeightKg());
            }
            WorkoutPlan plan = agent.buildWorkout(ctx);
            profile.setDeloadCalendar(plan.getRoutines(), LocalDate.now().plusDays(1));
            return plan;
        } finally {
            agent.setStrategy(previous);
        }
    }

    /** UC08 alt 2a. */
    public void declineDeload() {
        profile.declineDeload(LocalDateTime.now());
    }

    // ------------------------------------------------------------------ F09

    public AgentResponse askCoach(String question) {
        AgentResponse resp = agent.queryAgent(question);
        if (resp.source().startsWith("AI")) {
            profile.addCoachNote("Coach Q&A", "Q: " + question + "\nA: " + resp.answer());
        }
        return resp;
    }

    private String athleteSummary() {
        return String.format("%s, %d y, %s, %.0f kg, goal %s", profile.getName(), profile.getAge(), profile.getSex(),
                profile.getWeightKg(), profile.getGoal().label());
    }

    /** Read-only tools exposed to the coach agent. */
    private ToolRegistry buildTools() {
        ToolRegistry t = new ToolRegistry();
        t.register(AgentTool.of("get_profile", "athlete profile (age, sex, weight, goal, BMR, protein target). No args.",
                a -> athleteSummary() + String.format(", BMR %.0f kcal, protein target %.0f g", profile.bmr(), profile.proteinTargetG())));
        t.register(AgentTool.of("get_readiness", "today's readiness score, tier and component z-scores. No args.", a -> {
            ReadinessResult r = profile.getLatestReadiness() != null ? profile.getLatestReadiness()
                    : readinessCalculator.computeReadiness(profile);
            BiometricRecord rec = profile.getRecords().get(r.date());
            return r.summary() + String.format("; z-scores HRV %.2f, RHR %.2f, sleep %.2f, deep %.2f; sleep score %.0f/100",
                    r.hrvComponent(), r.restingHrComponent(), r.sleepComponent(), r.deepSleepComponent(), r.sleepScore())
                    + (rec == null ? "" : "; last night: " + rec);
        }));
        t.register(AgentTool.of("get_biometric_trend", "daily HRV/RHR/sleep table. Args: {\"days\": 1-14} (default 7).", a -> {
            int days = (int) Math.max(1, Math.min(14, Json.num(a, "days", 7)));
            List<BiometricRecord> recs = profile.getRecords().descendingMap().values().stream().limit(days).toList();
            if (recs.isEmpty()) return "No biometric data imported.";
            StringBuilder sb = new StringBuilder();
            recs.forEach(r -> sb.append(r).append('\n'));
            return sb.toString();
        }));
        t.register(AgentTool.of("get_energy_balance", "today's calories burned vs consumed, protein and fueling status. No args.",
                a -> nutrition.calculateSufficiency(profile, LocalDate.now(), LocalDateTime.now()).summary()
                        + "; meals today: " + profile.mealsOn(LocalDate.now()).stream().map(MealLog::describe).toList()));
        t.register(AgentTool.of("get_current_workout", "the staged or in-progress workout with logged sets. No args.", a -> {
            WorkoutRoutine r = sessionManager.currentRoutine();
            if (r == null) return "No workout generated or in progress.";
            StringBuilder sb = new StringBuilder(sessionManager.isActive() ? "IN PROGRESS: " : "STAGED (not started): ");
            sb.append(r.describe());
            for (Exercise e : r.getExercises()) {
                for (int i = 0; i < e.getSets().size(); i++) {
                    ExerciseSet s = e.getSets().get(i);
                    if (s.isCompleted()) {
                        sb.append(String.format("  done: %s set %d - %.1f x %d @ RPE %.1f%n", e.getName(), i + 1,
                                s.getActualWeight(), s.getActualReps(), s.getActualRpe()));
                    }
                }
            }
            return sb.toString();
        }));
        t.register(AgentTool.of("get_recent_sessions", "completed sessions. Args: {\"days\": 1-30} (default 7).", a -> {
            int days = (int) Math.max(1, Math.min(30, Json.num(a, "days", 7)));
            List<WorkoutSession> s = profile.sessionsBetween(LocalDate.now().minusDays(days - 1), LocalDate.now());
            if (s.isEmpty()) return "No sessions in the last " + days + " days.";
            StringBuilder sb = new StringBuilder();
            s.forEach(x -> sb.append(x.getStartedAt().toLocalDate()).append(": ").append(x.getRoutine().getTitle())
                    .append(", ").append(Math.round(x.tonnage())).append(" kg\n"));
            return sb.toString();
        }));
        t.register(AgentTool.of("get_fatigue_status", "14-day overtraining sentinel result. No args.",
                a -> fatigueSentinel.evaluate(profile).summary()));
        return t;
    }

    // ------------------------------------------------------------------ F10

    public Path exportReport(LocalDate from, LocalDate to, ReportGenerator.Format format, Path destination) throws IOException {
        List<String> lines = reportGenerator.compileAudit(profile, from, to);
        return reportGenerator.writeToFile(lines, format, destination);
    }

}
