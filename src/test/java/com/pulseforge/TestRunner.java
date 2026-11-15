package com.pulseforge;

import com.pulseforge.agent.AgentResponse;
import com.pulseforge.agent.Guardrails;
import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.MovementType;
import com.pulseforge.domain.ProfileEvent;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.ReadinessTier;
import com.pulseforge.domain.TrainingGoal;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.facade.PulseForgeFacade;
import com.pulseforge.ingest.AppleHealthXmlAdapter;
import com.pulseforge.ingest.CsvBiometricAdapter;
import com.pulseforge.ingest.GarminJsonAdapter;
import com.pulseforge.ingest.HealthParserFactory;
import com.pulseforge.ingest.InvalidHealthDataException;
import com.pulseforge.json.Json;
import com.pulseforge.json.JsonException;
import com.pulseforge.llm.LLMClient;
import com.pulseforge.llm.ModelInferenceException;
import com.pulseforge.nutrition.UnrecognizedFoodException;
import com.pulseforge.persistence.ProfileRepository;
import com.pulseforge.persistence.SampleDataGenerator;
import com.pulseforge.readiness.ReadinessCalculator;
import com.pulseforge.report.ReportGenerator;
import com.pulseforge.session.RestTimer;
import com.pulseforge.session.SimulatedHeartRateSource;
import com.pulseforge.session.SmartRestEngine;
import com.pulseforge.session.TimerListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free verification suite for Stage 2 (run with {@code build.ps1 test} or
 * {@code ./build.sh test}). Uses a scripted LLM double so AI paths are tested deterministically.
 * Full JUnit / KUMA validation is Stage 3 work.
 */
public final class TestRunner {

    private interface Check {
        void run() throws Exception;
    }

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static Path tmp;

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("pulseforge-test");
        SampleDataGenerator.generate(tmp, LocalDate.now().minusDays(1), 21);

        test("Json round-trips objects and parses fenced LLM output", TestRunner::json);
        test("F01 Apple XML adapter normalizes 21 days", TestRunner::appleAdapter);
        test("F01 Garmin and CSV adapters agree with Apple adapter", TestRunner::otherAdapters);
        test("F01 corrupt/unsupported files raise InvalidHealthDataException and leave state unchanged", TestRunner::badImports);
        test("F03 readiness uses default baselines with < 3 days (approximation)", TestRunner::readinessApprox);
        test("F03 readiness drops on fatigued days and notifies observers", TestRunner::readinessFatigue);
        test("F04 energy balance statuses and protein deficit", TestRunner::energy);
        test("F04 hybrid advice uses LLM, falls back to rules on failure", TestRunner::energyAdvice);
        test("F02 vision JSON becomes a MealLog; low confidence is rejected", TestRunner::vision);
        test("F05 LLM routine is clamped to deterministic limits", TestRunner::workoutClamped);
        test("F05 LLM failure falls back to the offline template", TestRunner::workoutFallback);
        test("F05 strategies differ: strength uses fewer sets and lower reps", TestRunner::strategiesDiffer);
        test("F06 log/undo/redo/delete via command invoker", TestRunner::commands);
        test("F06 invalid input never mutates the session", TestRunner::commandValidation);
        test("F07 rest durations scale with movement type and RPE", TestRunner::restEngine);
        test("F07 timer counts down, +30s, skip, and holds for heart rate", TestRunner::restTimer);
        test("F08 sentinel flags the sample fatigue pattern as CRITICAL", TestRunner::sentinel);
        test("F08 deload week has 7 days, <= 9 sets, RPE <= 7; decline lowers RPE cap", TestRunner::deload);
        test("F09 coach calls tools, then answers; memory keeps the turn", TestRunner::coachTools);
        test("F09 medical questions hit the guardrail without calling the LLM", TestRunner::coachGuardrail);
        test("F10 report exports Markdown and PDF; bad directory raises IOException", TestRunner::report);
        test("Persistence round-trips the profile", TestRunner::persistence);

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ tests

    private static void json() {
        Map<String, Object> m = Json.map("a", 1.5, "b", List.of("x", "y\n\"q\""), "c", null, "d", true);
        Map<String, Object> back = Json.parseObject(Json.pretty(m));
        eq(1.5, Json.num(back, "a", 0));
        eq("y\n\"q\"", Json.arr(back, "b").get(1));
        Map<String, Object> fenced = Json.parseObjectLenient("Sure!\n```json\n{\"reps\": \"8-12\", \"k\": {\"z\": [1,2]}}\n```");
        eq(12.0, Json.num(fenced, "reps", 0));
        throwsEx(JsonException.class, () -> Json.parse("{\"a\": }"));
    }

    private static void appleAdapter() throws Exception {
        List<BiometricRecord> recs = new AppleHealthXmlAdapter().parse(tmp.resolve("apple_health_export.xml"));
        eq(21, recs.size());
        BiometricRecord last = recs.get(recs.size() - 1);
        eq(LocalDate.now().minusDays(1), last.getDate());
        check(last.getHrvMs() != null && last.getHrvMs() < 45, "fatigued HRV expected, got " + last.getHrvMs());
        check(last.getSleepMinutes() > 250 && last.getSleepMinutes() < 400, "sleep minutes " + last.getSleepMinutes());
        check(last.getDeepSleepMinutes() > 0 && last.getRemSleepMinutes() > 0, "sleep stages parsed");
        check(last.getActiveEnergyKcal() > 200, "active energy summed across samples");
        check(last.sleepEfficiency() != null && last.sleepEfficiency() < 1.0, "efficiency derived from awake time");
    }

    private static void otherAdapters() throws Exception {
        List<BiometricRecord> apple = new AppleHealthXmlAdapter().parse(tmp.resolve("apple_health_export.xml"));
        List<BiometricRecord> garmin = new GarminJsonAdapter().parse(tmp.resolve("garmin_export.json"));
        List<BiometricRecord> csv = new CsvBiometricAdapter().parse(tmp.resolve("manual_biometrics.csv"));
        eq(apple.size(), garmin.size());
        eq(apple.size(), csv.size());
        near(apple.get(5).getSleepMinutes(), garmin.get(5).getSleepMinutes(), 1.5);
        near(apple.get(5).getHrvMs(), csv.get(5).getHrvMs(), 0.01);
        near(apple.get(5).getActiveEnergyKcal(), garmin.get(5).getActiveEnergyKcal(), 0.01);
    }

    private static void badImports() throws Exception {
        Path corrupt = tmp.resolve("corrupt.xml");
        Files.writeString(corrupt, "<HealthData><Record type=\"HKQuantityTypeIdentifierRestingHeartRate\" value=\"60\"");
        Path noMetrics = tmp.resolve("empty.xml");
        Files.writeString(noMetrics, "<HealthData><Record type=\"Other\" startDate=\"2026-01-01 00:00:00 -0500\" value=\"1\"/></HealthData>");
        Path badCsv = tmp.resolve("bad.csv");
        Files.writeString(badCsv, "date,resting_hr\n2026-01-01,abc\n");
        Path outOfRange = tmp.resolve("range.csv");
        Files.writeString(outOfRange, "date,resting_hr\n2026-01-01,500\n");
        Path txt = tmp.resolve("notes.txt");
        Files.writeString(txt, "hello");

        PulseForgeFacade f = facade(ScriptedLlm.failing());
        for (Path p : List.of(corrupt, noMetrics, badCsv, outOfRange, txt, tmp.resolve("missing.xml"))) {
            throwsEx(InvalidHealthDataException.class, () -> f.importHealthData(p));
        }
        eq(0, f.getProfile().getRecords().size());
        throwsEx(InvalidHealthDataException.class, () -> HealthParserFactory.forFile(txt));
    }

    private static void readinessApprox() {
        AthleteProfile p = new AthleteProfile();
        BiometricRecord r = new BiometricRecord(LocalDate.of(2026, 10, 20), "test");
        r.setHrvMs(55.0);
        r.setRestingHr(60.0);
        r.setSleepMinutes(450.0);
        p.storeRecords(List.of(r));
        ReadinessResult res = new ReadinessCalculator().computeReadiness(p);
        check(res.approximation(), "approximation flag expected");
        check(res.score() > 45 && res.score() < 75, "default-baseline score should be mid-range, got " + res.score());
        eq(ReadinessTier.PRIME, ReadinessTier.fromScore(80));
        eq(ReadinessTier.BALANCED, ReadinessTier.fromScore(79.9));
        eq(ReadinessTier.FATIGUED, ReadinessTier.fromScore(40));
        eq(ReadinessTier.DEPLETED, ReadinessTier.fromScore(39.9));
    }

    private static void readinessFatigue() throws Exception {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        List<ProfileEvent> events = new ArrayList<>();
        f.addProfileObserver((profile, event) -> events.add(event));
        PulseForgeFacade.ImportResult res = f.importHealthData(tmp.resolve("apple_health_export.xml"));
        eq(21, res.daysImported());
        check(events.contains(ProfileEvent.BIOMETRICS_IMPORTED) && events.contains(ProfileEvent.READINESS_UPDATED),
                "observers notified: " + events);
        check(!res.readiness().approximation(), "21 days should not be an approximation");
        check(res.readiness().score() < 45, "fatigued readiness expected, got " + res.readiness().score());
        List<ReadinessResult> hist = f.readinessHistory(14);
        double healthyMean = hist.subList(0, 10).stream().mapToDouble(ReadinessResult::score).average().orElse(0);
        check(healthyMean > res.readiness().score() + 15, "healthy days (mean " + healthyMean + ") should score well above the fatigued day");
    }

    private static void energy() {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        f.updateProfile("T", 25, "M", 80, 180, TrainingGoal.GENERAL_FITNESS);   // BMR 1805
        EnergySufficiencyReport r = f.reconcileEnergyBalance(false);
        eq(EnergySufficiencyReport.Status.UNDER_FUELED, r.getStatus());
        near(1805, r.getBmr(), 0.5);
        r = f.addManualMeal("Big meal", 1800, 100, 200, 60, "");
        eq(EnergySufficiencyReport.Status.SUFFICED, r.getStatus());
        near(44, r.proteinDeficitG(), 0.5);     // target 144 g
        r = f.addManualMeal("Feast", 1500, 60, 150, 70, "");
        eq(EnergySufficiencyReport.Status.HYPER_CALORIC_SURPLUS, r.getStatus());
        near(0, r.proteinDeficitG(), 0.01);
        throwsEx(IllegalArgumentException.class, () -> f.addManualMeal("Bad", -5, 0, 0, 0, ""));
        throwsEx(IllegalArgumentException.class, () -> f.addManualMeal(" ", 100, 0, 0, 0, ""));
    }

    private static void energyAdvice() {
        ScriptedLlm llm = new ScriptedLlm("- Eat 40 g protein now");
        PulseForgeFacade f = facade(llm);
        EnergySufficiencyReport r = f.reconcileEnergyBalance(true);
        check(r.getAdviceSource().startsWith("AI"), "advice source " + r.getAdviceSource());
        check(llm.requests.get(0).messages().get(0).text().contains("net"), "prompt is grounded in computed numbers");
        eq(1, f.getProfile().getCoachNotes().size());
        r = f.reconcileEnergyBalance(true);   // script exhausted -> failure
        check(r.getAdviceSource().startsWith("Rule-based"), "fallback source " + r.getAdviceSource());
        check(!r.getAdvice().isBlank(), "fallback advice present");
    }

    private static void vision() throws Exception {
        Path img = tmp.resolve("meal.png");
        Files.write(img, new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        ScriptedLlm llm = new ScriptedLlm(
                "{\"foods\":[{\"name\":\"Grilled chicken\",\"portion\":\"150 g\",\"calories\":250,\"protein_g\":45,\"carbs_g\":0,\"fat_g\":6},"
                        + "{\"name\":\"Rice\",\"portion\":\"1 cup\",\"calories\":200,\"protein_g\":4,\"carbs_g\":45,\"fat_g\":0.5}],\"confidence\":0.86,\"issue\":\"\"}",
                "{\"foods\":[],\"confidence\":0.1,\"issue\":\"image is too blurry\"}",
                "not json at all");
        PulseForgeFacade f = facade(llm);
        MealLog meal = f.analyzeMealPhoto(img, "lunch");
        eq(2, meal.getItems().size());
        near(450, meal.totalCalories(), 0.01);
        eq(MealLog.Source.VISION_AI, meal.getSource());
        check(llm.requests.get(0).messages().get(0).hasImage(), "image attached to multimodal request");
        check(llm.requests.get(0).jsonMode(), "JSON mode requested");
        eq(0, f.getProfile().getMeals().size());           // not saved until confirmed
        f.confirmMeal(meal);
        eq(1, f.getProfile().getMeals().size());
        throwsEx(UnrecognizedFoodException.class, () -> f.analyzeMealPhoto(img, ""));
        throwsEx(ModelInferenceException.class, () -> f.analyzeMealPhoto(img, ""));
        throwsEx(IOException.class, () -> f.analyzeMealPhoto(tmp.resolve("nope.png"), ""));
        throwsEx(IOException.class, () -> f.analyzeMealPhoto(tmp.resolve("notes.txt"), ""));
    }

    private static final String GREEDY_ROUTINE = "{\"title\":\"Leg day\",\"recoveryNotes\":\"go hard\",\"exercises\":["
            + "{\"name\":\"Back Squat\",\"muscleGroup\":\"Quads\",\"movementType\":\"COMPOUND\",\"sets\":8,\"reps\":10,\"targetRpe\":10,\"loadPercent\":95},"
            + "{\"name\":\"Leg Press\",\"muscleGroup\":\"Quads\",\"movementType\":\"COMPOUND\",\"sets\":8,\"reps\":\"8-12\",\"targetRpe\":10,\"loadPercent\":90},"
            + "{\"name\":\"Leg Curl\",\"muscleGroup\":\"Hams\",\"movementType\":\"ISOLATION\",\"sets\":8,\"reps\":15,\"targetRpe\":9.5,\"loadPercent\":0}]}";

    private static void workoutClamped() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(GREEDY_ROUTINE);
        PulseForgeFacade f = facade(llm);
        f.importHealthData(tmp.resolve("apple_health_export.xml"));   // depleted/fatigued readiness
        WorkoutPlan plan = f.generateAdaptedWorkout("legs", "hypertrophy").plan();
        check(plan.isFromLlm(), "plan should come from the LLM");
        WorkoutRoutine r = plan.first();
        check(r.totalSets() <= 12, "24 requested sets must be clamped for low readiness, got " + r.totalSets());
        r.getExercises().forEach(e -> e.getSets().forEach(s -> {
            check(s.getTargetRpe() <= 7.5, "RPE clamped, got " + s.getTargetRpe());
            check(s.getLoadPercent() <= 75, "load clamped, got " + s.getLoadPercent());
        }));
        String prompt = llm.requests.get(0).messages().get(0).text();
        check(prompt.contains("HARD LIMITS") && prompt.contains("readiness"), "prompt carries deterministic limits");
        check(f.getSessionManager().getStaged() == r, "routine staged in session manager");
    }

    private static void workoutFallback() throws Exception {
        PulseForgeFacade f = facade(new ScriptedLlm("{\"title\":\"x\",\"exercises\":[]}"));
        WorkoutPlan plan = f.generateAdaptedWorkout("Push", "strength").plan();
        check(!plan.isFromLlm(), "empty routine must trigger the fallback");
        check(plan.getFallbackReason() != null, "fallback reason recorded");
        check(plan.first().totalSets() > 0, "fallback has sets");
        eq("Offline fallback template", plan.first().getGeneratedBy());
        throwsEx(IllegalArgumentException.class, () -> f.generateAdaptedWorkout("arms-only", "strength"));
        throwsEx(IllegalArgumentException.class, () -> f.generateAdaptedWorkout("push", "cardio"));
    }

    private static void strategiesDiffer() {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        WorkoutRoutine hyp = f.generateAdaptedWorkout("upper", "hypertrophy").plan().first();
        WorkoutRoutine str = f.generateAdaptedWorkout("upper", "strength").plan().first();
        check(str.totalSets() < hyp.totalSets(), "strength " + str.totalSets() + " vs hypertrophy " + hyp.totalSets());
        check(str.getExercises().get(0).getSets().get(0).getTargetReps() <= 5, "strength reps 3-5");
        check(hyp.getExercises().get(0).getSets().get(0).getTargetReps() >= 8, "hypertrophy reps 8-12");
    }

    private static PulseForgeFacade sessionFacade() {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        f.generateAdaptedWorkout("legs", "hypertrophy");
        f.startSession();
        return f;
    }

    private static void commands() {
        PulseForgeFacade f = sessionFacade();
        check(!f.canUndo(), "nothing to undo initially");
        check(f.undoLastAction().isEmpty(), "empty undo returns empty");
        f.logSet(0, 100, 8, 8);
        f.logSet(0, 100, 7, 9);
        near(1500, f.getSessionManager().tonnage(), 0.01);
        check(f.getSessionManager().getTimer().isRunning(), "rest timer auto-started by LogSetCommand");
        f.undoLastAction();
        near(800, f.getSessionManager().tonnage(), 0.01);
        check(f.canRedo(), "redo available");
        f.redoLastAction();
        near(1500, f.getSessionManager().tonnage(), 0.01);
        f.undoLastAction();
        f.logSet(1, 60, 10, 7);                    // new command clears redo stack
        check(!f.canRedo(), "redo cleared by new command");
        int before = f.getSessionManager().currentRoutine().getExercises().size();
        String name = f.getSessionManager().exerciseAt(1).getName();
        f.deleteExercise(1);
        eq(before - 1, f.getSessionManager().currentRoutine().getExercises().size());
        near(800, f.getSessionManager().tonnage(), 0.01);
        f.undoLastAction();
        eq(name, f.getSessionManager().exerciseAt(1).getName());
        near(1400, f.getSessionManager().tonnage(), 0.01);
        // Extra set beyond the prescription is appended and removable.
        int prescribed = f.getSessionManager().exerciseAt(2).getSets().size();
        for (int i = 0; i <= prescribed; i++) {
            f.logSet(2, 50, 10, 7);
        }
        eq(prescribed + 1, f.getSessionManager().exerciseAt(2).getSets().size());
        f.undoLastAction();
        eq(prescribed, f.getSessionManager().exerciseAt(2).getSets().size());
        var done = f.finishSession();
        eq(1, f.getProfile().getSessions().size());
        check(done.getEndedAt() != null && !f.getSessionManager().isActive(), "session closed");
    }

    private static void commandValidation() {
        PulseForgeFacade f = sessionFacade();
        throwsEx(IllegalArgumentException.class, () -> f.logSet(0, -10, 8, 8));
        throwsEx(IllegalArgumentException.class, () -> f.logSet(0, 100, 0, 8));
        throwsEx(IllegalArgumentException.class, () -> f.logSet(0, 100, 8, 11));
        throwsEx(IllegalArgumentException.class, () -> f.logSet(99, 100, 8, 8));
        throwsEx(IllegalArgumentException.class, () -> f.deleteExercise(42));
        near(0, f.getSessionManager().tonnage(), 0.0);
        check(!f.canUndo(), "failed commands are not pushed on the undo stack");
        PulseForgeFacade idle = facade(ScriptedLlm.failing());
        throwsEx(IllegalStateException.class, () -> idle.logSet(0, 100, 8, 8));
        throwsEx(IllegalStateException.class, idle::startSession);
    }

    private static void restEngine() {
        SmartRestEngine e = new SmartRestEngine();
        eq(180, e.computeRestSeconds(MovementType.COMPOUND, 9));
        eq(75, e.computeRestSeconds(MovementType.ISOLATION, 7));
        check(e.computeRestSeconds(MovementType.COMPOUND, 7) < e.computeRestSeconds(MovementType.COMPOUND, 9), "RPE scaling");
        eq(120, e.recoveryHeartRate(200));
    }

    private static void restTimer() {
        RestTimer t = new RestTimer(false);
        List<String> finished = new ArrayList<>();
        int[] ticks = {0};
        t.addListener(new TimerListener() {
            @Override public void onTick(int remaining, int total, boolean holding, int hr) { ticks[0]++; }
            @Override public void onFinished(int actual, String reason) { finished.add(reason + ":" + actual); }
        });
        t.start(3, null, 0);
        t.tick();
        t.addTime(30);
        eq(32, t.getRemaining());
        t.skip();
        eq("skipped:1", finished.get(0));
        check(!t.isRunning(), "stopped after skip");

        t.start(2, null, 0);
        t.tick();
        t.tick();
        eq("complete:2", finished.get(1));

        long[] clock = {0};
        SimulatedHeartRateSource hr = new SimulatedHeartRateSource(60, 200, () -> clock[0]);
        hr.onSetCompleted(9);                       // HR spikes to ~155
        t.start(2, hr, 120);
        t.tick();
        t.tick();
        check(t.isRunning() && t.isHolding(), "holds while HR is above threshold");
        clock[0] = 300_000;                         // five minutes later HR has recovered
        t.tick();
        check(!t.isRunning(), "releases once HR recovers");
        eq("complete:3", finished.get(2));

        // Starting the next rest early aborts the previous one.
        t.start(100, null, 0);
        t.tick();
        t.start(50, null, 0);
        eq("next set started early:1", finished.get(3));
        check(ticks[0] > 5, "listeners received ticks");
    }

    private static void sentinel() throws Exception {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        FatigueAssessment empty = f.assessFatigue();
        check(!empty.isDeloadRecommended() && empty.getMissingDates().size() == 14, "no data: all days missing, no deload");
        f.importHealthData(tmp.resolve("apple_health_export.xml"));
        FatigueAssessment a = f.diagnoseFatigue();
        check(a.getConsecutiveSuppressedDays() >= 3, "consecutive suppressed days " + a.getConsecutiveSuppressedDays());
        eq(FatigueAssessment.Risk.CRITICAL, a.getRisk());
        check(a.isDeloadRecommended(), "deload recommended");
        check(a.getDiagnosisSource().startsWith("Rule-based"), "LLM down -> rule-based diagnosis");
        check(f.generateAdaptedWorkout("push", "hypertrophy").fatigue() != null, "workout generation surfaces the warning");
    }

    private static void deload() throws Exception {
        PulseForgeFacade f = facade(ScriptedLlm.failing());
        f.importHealthData(tmp.resolve("apple_health_export.xml"));
        f.generateAdaptedWorkout("push", "strength");
        WorkoutPlan week = f.planDeloadWeek();
        eq(7, week.getRoutines().size());
        for (WorkoutRoutine r : week.getRoutines()) {
            check(r.totalSets() <= 9, r.getDayLabel() + " has " + r.totalSets() + " sets");
            r.getExercises().forEach(e -> e.getSets().forEach(s -> check(s.getTargetRpe() <= 7, "deload RPE <= 7")));
        }
        eq(7, f.getProfile().getDeloadCalendar().size());
        // Strategy is restored afterwards: the next normal generation is still a strength session.
        eq("Strength", f.generateAdaptedWorkout("push", "strength").plan().first().getStrategyName());

        double capBefore = f.generateAdaptedWorkout("push", "strength").plan().first().getExercises().get(0).getSets().get(0).getTargetRpe();
        f.declineDeload();
        var after = f.generateAdaptedWorkout("push", "strength");
        check(after.fatigue() == null, "warning suppressed for 72 h after declining");
        double capAfter = after.plan().first().getExercises().get(0).getSets().get(0).getTargetRpe();
        check(capAfter <= capBefore - 0.99 || capAfter == 5, "RPE cap lowered by 1: " + capBefore + " -> " + capAfter);
    }

    private static void coachTools() throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"action\":\"tool\",\"tool\":\"get_readiness\",\"args\":{},\"reason\":\"need recovery state\"}",
                "{\"action\":\"tool\",\"tool\":\"get_biometric_trend\",\"args\":{\"days\":3}}",
                "{\"action\":\"tool\",\"tool\":\"no_such_tool\",\"args\":{}}",
                "{\"action\":\"final\",\"answer\":\"Readiness is low - swap heavy squats for technique work.\"}",
                "Plain text follow-up without JSON");
        PulseForgeFacade f = facade(llm);
        f.importHealthData(tmp.resolve("apple_health_export.xml"));
        AgentResponse r = f.askCoach("I only slept 5 hours - should I still hit heavy squats today?");
        eq(List.of("get_readiness", "get_biometric_trend", "no_such_tool"), r.toolTrace());
        check(r.answer().contains("technique"), "final answer returned");
        eq(4, llm.requests.size());
        String observation = llm.requests.get(1).messages().get(llm.requests.get(1).messages().size() - 1).text();
        check(observation.startsWith("TOOL RESULT [get_readiness]") && observation.contains("Readiness"),
                "tool observation fed back to the model: " + observation);
        check(llm.requests.get(3).messages().get(llm.requests.get(3).messages().size() - 1).text().contains("unknown tool"),
                "unknown tools are reported back, not crashed on");
        check(llm.requests.get(0).systemPrompt().contains("get_energy_balance"), "tools advertised in system prompt");

        AgentResponse second = f.askCoach("and tomorrow?");
        eq("Plain text follow-up without JSON", second.answer());
        check(llm.requests.get(4).messages().size() == 3, "previous turn replayed from memory");
        check(f.getProfile().getCoachNotes().size() == 2, "coach turns saved as notes");

        AgentResponse offline = f.askCoach("one more?");    // script exhausted -> model failure
        check(offline.source().startsWith("Offline") && offline.answer().contains("Readiness"), "offline degraded answer");
        throwsEx(IllegalArgumentException.class, () -> f.askCoach("   "));
    }

    private static void coachGuardrail() {
        ScriptedLlm llm = new ScriptedLlm("{\"action\":\"final\",\"answer\":\"should not be used\"}");
        PulseForgeFacade f = facade(llm);
        AgentResponse r = f.askCoach("Can you diagnose my knee and prescribe medication?");
        eq(Guardrails.Verdict.MEDICAL, r.verdict());
        check(r.answer().contains("can't diagnose"), "disclaimer returned");
        eq(Guardrails.Verdict.EMERGENCY, f.askCoach("I have chest pain during squats").verdict());
        eq(0, llm.requests.size());
        eq(Guardrails.Verdict.ALLOWED, Guardrails.screen("My lower back is tight - what should I replace Romanian Deadlifts with?"));
    }

    private static void report() throws Exception {
        PulseForgeFacade f = sessionFacade();
        f.importHealthData(tmp.resolve("apple_health_export.xml"));
        f.logSet(0, 100, 8, 8);
        f.finishSession();
        f.addManualMeal("Oats", 400, 15, 60, 8, "");
        LocalDate to = LocalDate.now();
        Path md = f.exportReport(to.minusDays(29), to, ReportGenerator.Format.MARKDOWN, tmp.resolve("audit.md"));
        String text = Files.readString(md);
        check(text.contains("## Weekly averages") && text.contains("800 kg") && text.contains("| " + to.minusDays(1)),
                "markdown has weekly table, volume and daily rows");
        Path pdf = f.exportReport(to.minusDays(29), to, ReportGenerator.Format.PDF, tmp.resolve("audit.pdf"));
        byte[] bytes = Files.readAllBytes(pdf);
        check(new String(bytes, 0, 8).startsWith("%PDF-1.4") && new String(bytes).trim().endsWith("%%EOF"), "valid PDF framing");
        throwsEx(IOException.class, () -> f.exportReport(to.minusDays(7), to, ReportGenerator.Format.MARKDOWN,
                tmp.resolve("no-such-dir").resolve("x.md")));
        throwsEx(IllegalArgumentException.class, () -> f.exportReport(to, to.minusDays(7), ReportGenerator.Format.PDF, tmp.resolve("y.pdf")));
        throwsEx(IllegalArgumentException.class, () -> f.exportReport(LocalDate.of(2001, 1, 1), LocalDate.of(2001, 1, 31),
                ReportGenerator.Format.PDF, tmp.resolve("z.pdf")));
    }

    private static void persistence() throws Exception {
        Path dir = tmp.resolve("store");
        ProfileRepository repo = new ProfileRepository(dir);
        PulseForgeFacade f = new PulseForgeFacade(repo.load(), repo, ScriptedLlm.failing(), new RestTimer(false));
        f.updateProfile("Joe", 30, "F", 62, 168, TrainingGoal.STRENGTH);
        f.importHealthData(tmp.resolve("garmin_export.json"));
        f.addManualMeal("Eggs", 300, 20, 2, 22, "breakfast");
        f.generateAdaptedWorkout("pull", "strength");
        f.startSession();
        f.logSet(0, 80, 5, 8);
        f.finishSession();
        f.planDeloadWeek();

        AthleteProfile loaded = new ProfileRepository(dir).load();   // auto-saved by the observer
        eq("Joe", loaded.getName());
        eq("F", loaded.getSex());
        eq(TrainingGoal.STRENGTH, loaded.getGoal());
        eq(21, loaded.getRecords().size());
        eq(1, loaded.getMeals().size());
        eq(1, loaded.getSessions().size());
        near(400, loaded.getSessions().get(0).tonnage(), 0.01);
        eq(7, loaded.getDeloadCalendar().size());
        near(f.getProfile().getLatestReadiness().score(), loaded.getLatestReadiness().score(), 0.01);
    }

    // ---------------------------------------------------------------- helpers

    private static PulseForgeFacade facade(LLMClient llm) {
        return new PulseForgeFacade(new AthleteProfile(), null, llm, new RestTimer(false));
    }

    private static void test(String name, Check c) {
        try {
            c.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + " -> " + t);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void near(double expected, double actual, double tol) {
        if (Math.abs(expected - actual) > tol) {
            throw new AssertionError("expected " + expected + " +/- " + tol + " but was " + actual);
        }
    }

    private static void throwsEx(Class<? extends Throwable> type, Check c) {
        try {
            c.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
