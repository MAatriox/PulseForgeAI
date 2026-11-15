package com.pulseforge.ui.cli;

import com.pulseforge.agent.AgentResponse;
import com.pulseforge.agent.ExerciseLibrary;
import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;
import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.FoodItem;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.ReadinessResult;
import com.pulseforge.domain.TrainingGoal;
import com.pulseforge.domain.WorkoutPlan;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.domain.WorkoutSession;
import com.pulseforge.facade.PulseForgeFacade;
import com.pulseforge.ingest.InvalidHealthDataException;
import com.pulseforge.llm.ModelInferenceException;
import com.pulseforge.nutrition.UnrecognizedFoodException;
import com.pulseforge.report.ReportGenerator;
import com.pulseforge.session.TimerListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line interface. Works as an interactive shell ({@code pulseforge --cli}) or for
 * one-shot commands ({@code pulseforge --cli import sample-data/apple_health_export.xml}).
 * All behaviour goes through {@link PulseForgeFacade}.
 */
public class WorkoutCLI implements TimerListener {

    private final PulseForgeFacade facade;
    private final BufferedReader in;
    private final PrintStream out;
    private boolean interactive;
    private boolean announcedHold;

    public WorkoutCLI(PulseForgeFacade facade) {
        this(facade, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    public WorkoutCLI(PulseForgeFacade facade, BufferedReader in, PrintStream out) {
        this.facade = facade;
        this.in = in;
        this.out = out;
        facade.addProfileObserver(new CliConsoleObserver(out));
        facade.getSessionManager().getTimer().addListener(this);
    }

    // --------------------------------------------------------------- REPL

    public void runInteractive() {
        interactive = true;
        out.println("==============================================================");
        out.println(" PulseForge AI - Biometric-Driven Gym & Recovery Agent (CLI)");
        out.println("==============================================================");
        out.println(" LLM: " + facade.llmDescription());
        out.println(" Type 'help' for commands, 'exit' to quit.");
        ReadinessResult r = facade.getProfile().getLatestReadiness();
        if (r != null) {
            out.println(" Last known: " + r.summary());
        }
        while (true) {
            out.print("\npulseforge> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                out.println("Goodbye - recover well!");
                break;
            }
            execute(tokenize(line));
        }
    }

    /** Runs a single command; returns process exit code. */
    public int runOnce(List<String> args) {
        interactive = false;
        return execute(args) ? 0 : 1;
    }

    // ----------------------------------------------------------- dispatch

    boolean execute(List<String> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        List<String> t = new ArrayList<>(tokens);
        if (t.get(0).equalsIgnoreCase("pulseforge")) {
            t.remove(0);
            if (t.isEmpty()) {
                return true;
            }
        }
        String cmd = t.get(0).toLowerCase();
        List<String> args = t.subList(1, t.size());
        try {
            switch (cmd) {
                case "help", "?" -> help();
                case "status", "readiness" -> status();
                case "recalc" -> facade.getTodayReadiness();
                case "import" -> importFile(args);
                case "history" -> history(args);
                case "profile" -> profile(args);
                case "meal" -> meal(args);
                case "meals" -> meals();
                case "energy" -> energy(args);
                case "generate" -> generate(args);
                case "workout", "show" -> showWorkout();
                case "start" -> { facade.startSession(); out.println("Session started. Log sets with: log --weight 100 --reps 8 --rpe 8"); showWorkout(); }
                case "log" -> log(args);
                case "undo" -> out.println(facade.undoLastAction().map(d -> "Undone: " + d).orElse("No actions to undo."));
                case "redo" -> out.println(facade.redoLastAction().map(d -> "Redone: " + d).orElse("No actions to redo."));
                case "delete-exercise" -> out.println(facade.deleteExercise(index(args)));
                case "finish" -> finish();
                case "timer" -> timer();
                case "skip" -> facade.skipRest();
                case "+30", "addtime" -> { facade.addRestTime(); out.println("Added 30 s rest."); }
                case "hr" -> hr(args);
                case "fatigue" -> fatigue();
                case "deload" -> deload(args);
                case "coach", "ask" -> coach(args);
                case "export" -> export(args);
                case "sample" -> sample(args);
                case "llm" -> out.println("LLM: " + facade.llmDescription());
                default -> {
                    out.println("Unknown command '" + cmd + "'. Type 'help'.");
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.println("Error: " + e.getMessage());
            return false;
        } catch (InvalidHealthDataException e) {
            out.println("Import failed (previous data unchanged): " + e.getMessage());
            return false;
        } catch (IOException e) {
            out.println("File error: " + e.getMessage());
            return false;
        }
    }

    private void help() {
        out.println("""
                Biometrics & readiness (F01, F03)
                  import <file>                      Import Apple Health .xml, Garmin .json or .csv
                  status | recalc                    Show / recompute today's readiness
                  history [days]                     Show stored biometric records
                  sample [dir]                       Generate fresh synthetic sample exports
                Nutrition (F02, F04)
                  meal --img <photo> [--notes "..."] AI meal photo analysis (review, then confirm)
                  meal --manual "<name>" --kcal N [--protein N --carbs N --fat N]
                  meals                              Today's meals
                  energy [--advice]                  Burned vs consumed (+ AI fueling advice)
                Training (F05-F08)
                  generate <split> [--strategy hypertrophy|strength]
                                                     Splits: push, pull, legs, upper, lower, full_body
                  workout | start | finish           Show / start / finish the session
                  log [--exercise N] --weight W --reps R --rpe X
                  undo | redo | delete-exercise N    Command history
                  timer | skip | +30 | hr on|off     Smart rest timer (hr = simulated live HR)
                  fatigue                            14-day overtraining sentinel + AI diagnosis
                  deload [--decline]                 Generate 7-day deload plan (or decline)
                Coach & reports (F09, F10)
                  coach <question>                   Ask the tool-using AI coach
                  export [--from YYYY-MM-DD] [--to YYYY-MM-DD] [--format md|pdf] [--out file]
                Other
                  profile [set name=.. age=.. sex=M|F weight=kg height=cm goal=hypertrophy|strength|fat_loss|general_fitness]
                  llm | help | exit""");
    }

    // ------------------------------------------------------ F01 / F03

    private void importFile(List<String> args) throws InvalidHealthDataException {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Usage: import <path-to-export.xml|.json|.csv>");
        }
        PulseForgeFacade.ImportResult res = facade.importHealthData(Paths.get(args.get(0)));
        out.printf("Imported %d day(s) from %s.%n", res.daysImported(), res.format());
    }

    private void status() {
        ReadinessResult r = facade.getProfile().getLatestReadiness();
        if (r == null) {
            r = facade.getTodayReadiness();
            return;
        }
        out.println(r.summary());
        out.printf("  Components (z): HRV %+.2f | RHR %+.2f | sleep %+.2f | deep %+.2f%n", r.hrvComponent(),
                r.restingHrComponent(), r.sleepComponent(), r.deepSleepComponent());
        out.printf("  Sleep score: %.0f/100. %s%n", r.sleepScore(), r.message());
        BiometricRecord rec = facade.getProfile().getRecords().get(r.date());
        if (rec != null) {
            out.println("  Data: " + rec);
        }
    }

    private void history(List<String> args) {
        int days = args.isEmpty() ? 14 : Integer.parseInt(args.get(0));
        var recs = facade.getProfile().getRecords().descendingMap().values().stream().limit(days).toList();
        if (recs.isEmpty()) {
            out.println("No biometric data. Try: import sample-data/apple_health_export.xml");
        }
        for (int i = recs.size() - 1; i >= 0; i--) {
            out.println("  " + recs.get(i));
        }
    }

    private void sample(List<String> args) throws IOException {
        Path dir = Paths.get(args.isEmpty() ? "sample-data" : args.get(0));
        for (Path p : facade.generateSampleData(dir)) {
            out.println("Wrote " + p);
        }
    }

    // ----------------------------------------------------------- profile

    private void profile(List<String> args) {
        AthleteProfile p = facade.getProfile();
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("set")) {
            Map<String, String> kv = new HashMap<>();
            for (String a : args.subList(1, args.size())) {
                int eq = a.indexOf('=');
                if (eq < 1) throw new IllegalArgumentException("Use key=value pairs, e.g. weight=80");
                kv.put(a.substring(0, eq).toLowerCase(), a.substring(eq + 1));
            }
            facade.updateProfile(kv.getOrDefault("name", p.getName()),
                    Integer.parseInt(kv.getOrDefault("age", String.valueOf(p.getAge()))),
                    kv.getOrDefault("sex", p.getSex()),
                    Double.parseDouble(kv.getOrDefault("weight", String.valueOf(p.getWeightKg()))),
                    Double.parseDouble(kv.getOrDefault("height", String.valueOf(p.getHeightCm()))),
                    kv.containsKey("goal") ? TrainingGoal.parse(kv.get("goal")) : p.getGoal());
            out.println("Profile updated.");
        }
        out.printf("%s | %d y | %s | %.1f kg | %.0f cm | goal: %s%n", p.getName(), p.getAge(), p.getSex(),
                p.getWeightKg(), p.getHeightCm(), p.getGoal().label());
        out.printf("BMR %.0f kcal/day | protein target %.0f g | est. max HR %d bpm%n", p.bmr(), p.proteinTargetG(), p.maxHeartRate());
    }

    // ------------------------------------------------------- F02 / F04

    private void meal(List<String> args) throws IOException {
        Map<String, String> o = options(args);
        if (o.containsKey("manual")) {
            EnergySufficiencyReport rep = facade.addManualMeal(o.get("manual"), num(o, "kcal", -1),
                    num(o, "protein", 0), num(o, "carbs", 0), num(o, "fat", 0), o.getOrDefault("notes", ""));
            out.println("Manual meal saved.");
            printEnergy(rep);
            return;
        }
        if (!o.containsKey("img")) {
            throw new IllegalArgumentException("Usage: meal --img <photo> [--notes \"...\"]  or  meal --manual \"name\" --kcal N");
        }
        out.println("Analyzing meal photo with " + facade.llmDescription() + " ...");
        MealLog meal;
        try {
            meal = facade.analyzeMealPhoto(Paths.get(o.get("img")), o.getOrDefault("notes", ""));
        } catch (ModelInferenceException e) {
            out.println("Network/model unavailable (" + e.getMessage() + ").");
            out.println("Enter the meal manually instead: meal --manual \"name\" --kcal N --protein N --carbs N --fat N");
            return;
        } catch (UnrecognizedFoodException e) {
            out.println(e.getMessage());
            return;
        }
        printMeal(meal);
        out.print("Confirm and save this meal? [y = save / n = discard]: ");
        out.flush();
        String answer = in.readLine();
        if (answer != null && answer.trim().toLowerCase().startsWith("y")) {
            printEnergy(facade.confirmMeal(meal));
        } else {
            out.println("Meal discarded. Re-run with a better photo or use 'meal --manual'.");
        }
    }

    private void printMeal(MealLog meal) {
        out.printf("Detected foods (confidence %.0f%%):%n", meal.getConfidence() * 100);
        out.printf("  %-28s %-16s %7s %7s %7s %7s%n", "food", "portion", "kcal", "prot g", "carb g", "fat g");
        for (FoodItem f : meal.getItems()) {
            out.printf("  %-28s %-16s %7.0f %7.1f %7.1f %7.1f%n", cut(f.getName(), 28), cut(f.getPortion(), 16),
                    f.getCalories(), f.getProteinG(), f.getCarbsG(), f.getFatG());
        }
        out.printf("  %-45s %7.0f %7.1f %7.1f %7.1f%n", "TOTAL", meal.totalCalories(), meal.totalProtein(),
                meal.totalCarbs(), meal.totalFat());
    }

    private void meals() {
        List<MealLog> list = facade.getProfile().mealsOn(LocalDate.now());
        if (list.isEmpty()) {
            out.println("No meals logged today.");
        }
        for (MealLog m : list) {
            out.printf("  %s  %-40s %5.0f kcal  %4.0f g protein  [%s]%n", m.getLoggedAt().toLocalTime().withNano(0),
                    cut(m.describe(), 40), m.totalCalories(), m.totalProtein(), m.getSource());
        }
    }

    private void energy(List<String> args) {
        boolean advice = args.contains("--advice");
        if (advice) {
            out.println("Asking the nutrition advisor ...");
        }
        printEnergy(facade.reconcileEnergyBalance(advice));
    }

    private void printEnergy(EnergySufficiencyReport r) {
        out.println(r.summary());
        if (r.isUnderFueledAlert()) {
            out.println("  !!! UNDER-FUELED ALERT: you trained today but have logged no meals. Refuel now.");
        }
        if (!r.getAdvice().isBlank()) {
            out.println("Fueling advice [" + r.getAdviceSource() + "]:");
            out.println(indent(r.getAdvice()));
        }
    }

    // ------------------------------------------------------------- F05

    private void generate(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Usage: generate <split> [--strategy hypertrophy|strength]. Splits: "
                    + ExerciseLibrary.SPLITS);
        }
        Map<String, String> o = options(args.subList(1, args.size()));
        out.println("Generating adapted workout ...");
        PulseForgeFacade.WorkoutResult res = facade.generateAdaptedWorkout(args.get(0), o.get("strategy"));
        WorkoutPlan plan = res.plan();
        if (!plan.isFromLlm()) {
            out.println("Note: LLM unavailable (" + plan.getFallbackReason() + ") - using the offline template.");
        }
        out.print(plan.first().describe());
        out.println("Type 'start' to begin this session.");
        if (res.fatigue() != null) {
            out.println();
            out.println("!!! RECOVERY WARNING: " + res.fatigue().summary());
            out.println("    Run 'fatigue' for the diagnosis, 'deload' to plan a deload week, or 'deload --decline'.");
        }
    }

    // ------------------------------------------------------------- F06

    private void showWorkout() {
        WorkoutRoutine r = facade.getSessionManager().currentRoutine();
        if (r == null) {
            out.println("No workout staged. Use: generate <split>");
            return;
        }
        out.println((facade.getSessionManager().isActive() ? "IN PROGRESS: " : "STAGED: ") + r.getTitle());
        for (int i = 0; i < r.getExercises().size(); i++) {
            Exercise e = r.getExercises().get(i);
            out.printf("  %d. %s (%s)%n", i + 1, e.getName(), e.getMovementType());
            for (int s = 0; s < e.getSets().size(); s++) {
                ExerciseSet set = e.getSets().get(s);
                out.printf("      set %d: target %d @ RPE %s  %s%n", s + 1, set.getTargetReps(), trim(set.getTargetRpe()),
                        set.isCompleted() ? String.format("[x] %s x %d @ RPE %s%s", trim(set.getActualWeight()),
                                set.getActualReps(), trim(set.getActualRpe()),
                                set.getRestSecondsTaken() == null ? "" : ", rested " + set.getRestSecondsTaken() + " s") : "[ ]");
            }
        }
        out.printf("  Session volume: %.0f kg%n", facade.getSessionManager().tonnage());
    }

    private void log(List<String> args) {
        Map<String, String> o = options(args);
        int exercise;
        if (o.containsKey("exercise")) {
            exercise = Integer.parseInt(o.get("exercise")) - 1;
        } else {
            exercise = firstOpenExercise();
        }
        if (!o.containsKey("weight") || !o.containsKey("reps") || !o.containsKey("rpe")) {
            throw new IllegalArgumentException("Usage: log [--exercise N] --weight W --reps R --rpe X");
        }
        double weight = num(o, "weight", Double.NaN);
        double repsD = num(o, "reps", Double.NaN);
        if (repsD != Math.rint(repsD)) {
            throw new IllegalArgumentException("Reps must be a whole number");
        }
        out.println(facade.logSet(exercise, weight, (int) repsD, num(o, "rpe", Double.NaN)));
        out.printf("Session volume: %.0f kg%n", facade.getSessionManager().tonnage());
    }

    private int firstOpenExercise() {
        WorkoutRoutine r = facade.getSessionManager().currentRoutine();
        if (r == null) {
            throw new IllegalStateException("No workout staged");
        }
        for (int i = 0; i < r.getExercises().size(); i++) {
            if (r.getExercises().get(i).nextOpenSetIndex() >= 0) {
                return i;
            }
        }
        return Math.max(0, r.getExercises().size() - 1);
    }

    private void finish() {
        WorkoutSession s = facade.finishSession();
        out.printf("Session finished: %d/%d sets, %.0f kg volume, ~%.0f kcal.%n", s.getRoutine().completedSets(),
                s.getRoutine().totalSets(), s.tonnage(), s.estimatedKcal());
        printEnergy(facade.reconcileEnergyBalance(false));
    }

    // ------------------------------------------------------------- F07

    private void timer() {
        var t = facade.getSessionManager().getTimer();
        if (!t.isRunning()) {
            out.println("No rest timer running. " + facade.getSessionManager().getLastRestSummary());
            return;
        }
        out.printf("Rest: %d s remaining of %d s%s%n", t.getRemaining(), t.getTotal(),
                t.isHolding() ? " (holding until heart rate < " + t.getHrThreshold() + " bpm)" : "");
    }

    private void hr(List<String> args) {
        boolean on = !args.isEmpty() && args.get(0).equalsIgnoreCase("on");
        facade.setLiveHeartRateSimulation(on);
        out.println("Simulated live heart rate " + (on ? "ON - rest will hold until HR < 60% max" : "OFF"));
    }

    @Override
    public void onTick(int remaining, int total, boolean holdingForHr, int heartRate) {
        if (remaining == total && !holdingForHr) {
            announcedHold = false;
            out.printf("%n  [rest timer] %d s rest started%s%n", total, heartRate > 0 ? " (HR " + heartRate + " bpm)" : "");
        } else if (holdingForHr && !announcedHold) {
            announcedHold = true;
            out.printf("%n  [rest timer] Countdown done, holding: HR %d bpm still above recovery threshold%n", heartRate);
        }
    }

    @Override
    public void onFinished(int actualRestSeconds, String reason) {
        out.printf("%n  [rest timer] Rest %s after %d s.%s%n", reason, actualRestSeconds,
                "complete".equals(reason) ? " Ready for your next set! \u0007" : "");
        if (interactive) {
            out.print("pulseforge> ");
            out.flush();
        }
    }

    // ------------------------------------------------------------- F08

    private void fatigue() {
        out.println("Running 14-day overtraining sentinel and asking the agent for a diagnosis ...");
        FatigueAssessment a = facade.diagnoseFatigue();
        out.println(a.summary());
        out.println(indent(a.getTrendTable()));
        out.println("Diagnosis [" + a.getDiagnosisSource() + "]:");
        out.println(indent(a.getDiagnosis()));
        if (a.isDeloadRecommended()) {
            out.println("Run 'deload' to generate a 7-day deload plan, or 'deload --decline'.");
        }
    }

    private void deload(List<String> args) {
        if (args.contains("--decline")) {
            facade.declineDeload();
            out.println("Deload declined. Warnings suppressed for 72 h; working RPE caps lowered by 1.");
            return;
        }
        out.println("Planning deload week (DeloadStrategy) ...");
        WorkoutPlan plan = facade.planDeloadWeek();
        if (!plan.isFromLlm()) {
            out.println("Note: LLM unavailable (" + plan.getFallbackReason() + ") - using the offline deload template.");
        }
        for (WorkoutRoutine r : plan.getRoutines()) {
            out.print(r.describe());
        }
        out.println("Deload week saved to your calendar starting " + facade.getProfile().getDeloadStartDate() + ".");
    }

    // ------------------------------------------------------------- F09

    private void coach(List<String> args) {
        String q = String.join(" ", args).trim();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("Usage: coach <your question>");
        }
        out.println("Coach is thinking ...");
        AgentResponse r = facade.askCoach(q);
        out.println(indent(r.formatted()));
    }

    // ------------------------------------------------------------- F10

    private void export(List<String> args) throws IOException {
        Map<String, String> o = options(args);
        LocalDate to = o.containsKey("to") ? date(o.get("to")) : LocalDate.now();
        LocalDate from = o.containsKey("from") ? date(o.get("from")) : to.minusDays(29);
        ReportGenerator.Format fmt = ReportGenerator.Format.parse(o.getOrDefault("format", "md"));
        Path dest = Paths.get(o.getOrDefault("out", "pulseforge-audit." + fmt.extension()));
        try {
            Path written = facade.exportReport(from, to, fmt, dest);
            out.println("Audit report written to " + written.toAbsolutePath());
        } catch (IOException e) {
            out.println("Unable to write file to selected folder (" + e.getMessage() + "). "
                    + "Please choose a different directory with --out. Your data is unchanged.");
        }
    }

    // ------------------------------------------------------------ helpers

    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean has = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                has = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) {
            out.add(cur.toString());
        }
        return out;
    }

    static Map<String, String> options(List<String> args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("--")) {
                String key = a.substring(2).toLowerCase();
                String val = i + 1 < args.size() && !args.get(i + 1).startsWith("--") ? args.get(++i) : "true";
                m.put(key, val);
            }
        }
        return m;
    }

    private static double num(Map<String, String> o, String key, double def) {
        String v = o.get(key);
        if (v == null) {
            if (def == -1) throw new IllegalArgumentException("--" + key + " is required");
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " must be a number (got '" + v + "')");
        }
    }

    private static int index(List<String> args) {
        if (args.isEmpty()) throw new IllegalArgumentException("Give the exercise number, e.g. delete-exercise 2");
        try {
            return Integer.parseInt(args.get(0)) - 1;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Exercise number must be an integer");
        }
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must be YYYY-MM-DD (got '" + s + "')");
        }
    }

    private static String indent(String s) {
        return "  " + s.replace("\n", "\n  ");
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
