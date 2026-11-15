# Stage 2 Implementation Report - PulseForge AI (DRAFT)

> **Draft status.** This file is the source text for `stage2-report.pdf`. Items marked
> **TODO** need information only the team has: demonstration video links, commit links,
> AI tool token/money costs, and updated UML diagrams. Export to PDF/Word as `stage2-report`
> once they are filled in.

Repository: **TODO (GitHub URL)**

---

## 1. Project overview and implementation status

**Application.** PulseForge AI is a biometric-driven gym and recovery agent. It imports
wearable exports, computes a daily Recovery & Readiness Index, and uses an LLM-backed agent to
adapt workouts to recovery, analyze meal photos, reconcile energy intake against expenditure,
detect overtraining, plan deload weeks and answer coaching questions.

**Users.** University students, competitive and recreational lifters, working professionals and
everyday wearable users who have biometric data but no way to turn it into training decisions.

**Technology.** Java 17, Swing GUI, interactive CLI, no third-party libraries (JSON, PDF and
HTTP handling are implemented on the JDK). Data is persisted to `data/profile.json`.

**AI / LLM integration.** All model access goes through the `LLMClient` interface.
`GeminiLlmClient` (default model `gemini-flash-lite-latest`) and `OpenAiCompatibleLlmClient`
(OpenAI, Groq, OpenRouter, local Ollama) are provided; the provider is chosen in `.env`.
The AI component does more than forward prompts:

| Agent behaviour | Where |
|---|---|
| Tool use / multi-step execution | `AgentBridge.queryAgent` runs a bounded loop in which the model chooses read-only tools (`get_readiness`, `get_biometric_trend`, `get_energy_balance`, `get_current_workout`, `get_recent_sessions`, `get_fatigue_status`, `get_profile`), receives the observations, and only then answers. |
| Memory | `CoachMemory` replays the last 6 coach turns into each request. |
| Decision making under constraints | Workout strategies compute deterministic `VolumeLimits` from readiness; the model picks exercises inside them and its output is clamped (`WorkoutJsonMapper`). |
| Structured output | Workouts, deload weeks and meal analyses are requested as JSON and deserialized into domain objects. |
| Multimodal reasoning | `VisionNutritionService` sends the meal photo inline with the prompt. |
| Guardrails | `Guardrails.screen` intercepts medical/emergency questions before any model call. |
| Graceful degradation | Every AI path has a labeled deterministic fallback (`ModelInferenceException`). |

**Working functionality.** All ten Stage 1 features are implemented and reachable from both the
GUI and the CLI (see Section 2).

**Limitations and incomplete functionality (honest list).**

- F02 was verified against the live model only with a non-food image (correctly rejected).
  **TODO:** analyze a real meal photo and record the result here.
- The default model is the `gemini-flash-lite-latest` alias: `gemini-2.5-flash` is closed to
  new users and `gemini-3.8-flash` was timing out under load during testing.
- The live heart-rate stream for F07 is **simulated** (`SimulatedHeartRateSource`); no real
  wearable connection exists.
- F02 in the CLI supports confirm/discard only; editing individual estimates is GUI-only.
- The deload week (F08) is saved to the profile and displayed, but there is no calendar view
  and deload days cannot yet be started as live sessions.
- The "declined deload" RPE reduction stays in force until the profile file is reset.
- A live session is held in memory; closing the app mid-session loses unlogged progress.
- Readiness uses a rolling 7-day baseline, so several consecutive bad days gradually pull the
  baseline down and scores drift upward; the fatigue sentinel covers this case.
- PDF export is monospaced text; Markdown tables are not typeset.
- Garmin parsing follows Garmin Connect field names but was tested only with the bundled sample.
- Testing is a lightweight in-repo runner (22 checks). Full deterministic testing and KUMA
  agent validation are Stage 3.

---

## 2. Feature to implementation mapping

Video links: **TODO** for every feature.

### F01 - Multi-Source Biometric & Health Ingestion

| Field | Entry |
|---|---|
| Stage 1 reference | F01 / UC01 |
| Status | Implemented (extended: CSV adapter added) |
| Classes / files | `ingest/FitnessDataParser` (target interface); `AppleHealthXmlAdapter`, `GarminJsonAdapter`, `CsvBiometricAdapter` (adapters); `XmlParser` (adaptee, StAX wrapper); `HealthParserFactory` (format detection); `InvalidHealthDataException`; `domain/BiometricRecord` (normalized day, range validation); `domain/AthleteProfile.storeRecords`; `persistence/ProfileRepository` |
| Key methods | `WorkoutGUI.onImportHealthClicked()`, `WorkoutCLI.importFile()`, `PulseForgeFacade.importHealthData()`, `HealthParserFactory.forFile()`, `FitnessDataParser.parse()`, `XmlParser.forEachElement()`, `BiometricRecord.validate()`, `AthleteProfile.storeRecords()` |
| Design patterns | Adapter, Facade, Observer |
| Type | Deterministic |
| Video | TODO |

Flow: the UI passes the chosen path to the facade. `HealthParserFactory` selects an adapter by
extension. The adapter parses the vendor format (Apple: averages resting HR/HRV, sums active
energy, sums sleep-stage intervals onto the wake-up date), validates each day and returns
`BiometricRecord`s. Only if parsing fully succeeds does the facade call `storeRecords`, which
notifies observers (the repository saves; views refresh), then recomputes readiness (UC02).
Errors: corrupt XML, missing mandatory fields, out-of-range values or unsupported file types
raise `InvalidHealthDataException`; the UI shows the reason and stored data is untouched.

### F02 - Multimodal Meal Photo Calorie & Macronutrient Analyzer

| Field | Entry |
|---|---|
| Stage 1 reference | F02 / UC03 |
| Status | Implemented |
| Classes / files | `nutrition/VisionNutritionService` (image packaging, JSON decoding); `llm/LLMClient`, `GeminiLlmClient`, `LlmRequest` (inline image message); `domain/MealLog`, `FoodItem`; `UnrecognizedFoodException` |
| Key methods | `WorkoutGUI.onUploadMealImageClicked()`, `onAnalyzeMealClicked()`, `onConfirmMealClicked()`, `WorkoutCLI.meal()`, `PulseForgeFacade.analyzeMealPhoto()`, `confirmMeal()`, `addManualMeal()`, `VisionNutritionService.analyzeMeal()`, `LLMClient.generate()`, `MealLog.create()` |
| Design patterns | Facade |
| Type | AI-based |
| Video | TODO |

Flow: the image is checked (type, size), attached to a multimodal request with a strict JSON
schema, and the reply is decoded into an unsaved `MealLog`. The GUI shows it in an editable
table; **Confirm Meal** saves it and triggers F04. Errors: empty food list or confidence below
0.5 raises `UnrecognizedFoodException` ("retake or enter manually"); a network/model failure
offers manual entry.

### F03 - Sleep Architecture & Circadian Recovery Scorer

| Field | Entry |
|---|---|
| Stage 1 reference | F03 / UC02 |
| Status | Implemented |
| Classes / files | `readiness/ReadinessCalculator`; `domain/ReadinessResult`, `ReadinessTier`; `AthleteProfile` (subject); `ProfileObserver`; `ui/gui/GuiDashboardObserver`, `ReadinessGauge`; `ui/cli/CliConsoleObserver` |
| Key methods | `PulseForgeFacade.getTodayReadiness()`, `ReadinessCalculator.computeReadiness()`, `computeFor()`, `stats()`, `sleepArchitectureScore()`, `AthleteProfile.updateReadiness()`, `notifyObservers()`, `ProfileObserver.onProfileUpdated()` |
| Design patterns | Observer, Facade |
| Type | Deterministic |
| Video | TODO |

Flow: z-scores for HRV, resting HR, sleep duration and deep sleep are computed against the
previous 7 days, combined (35/25/25/15 %), mapped to 0-100 and blended 65/35 with an absolute
sleep-architecture score (duration, efficiency, REM %, deep minutes). The result is stored via
`updateReadiness`, which notifies the GUI gauge and CLI console. Alternative: with fewer than
3 baseline days, population defaults are used and the score is flagged "Approximation".

### F04 - Energy Balance & Caloric Sufficiency Arbiter

| Field | Entry |
|---|---|
| Stage 1 reference | F04 / UC04 |
| Status | Modified (see Section 4, C5) |
| Classes / files | `nutrition/EnergyBalanceCalculator` (deterministic maths); `VisionNutritionService.calculateSufficiency`, `addFuelingAdvice`; `domain/EnergySufficiencyReport`; `AthleteProfile` (BMR, protein target) |
| Key methods | `WorkoutGUI.displayEnergyReport()`, `WorkoutCLI.energy()`, `PulseForgeFacade.reconcileEnergyBalance()`, `EnergyBalanceCalculator.calculate()`, `wearableActive()`, `AthleteProfile.bmr()`, `LLMClient.generate()` |
| Design patterns | Facade |
| Type | Hybrid |
| Video | TODO |

Flow: burned = BMR (Mifflin-St Jeor) + wearable active energy + estimated lifting energy;
consumed = today's meals. Goal-specific thresholds give Sufficed / Under-Fueled /
Hyper-Caloric Surplus, and protein is compared with 1.8 g/kg. The numbers are then given to
the LLM for fueling advice; if the model fails, rule-based advice is shown and labeled.
Alternative: a session logged with no meals after 19:00 raises the Under-Fueled Alert.

### F05 - Sleep- and Readiness-Calibrated Workout Generator

| Field | Entry |
|---|---|
| Stage 1 reference | F05 / UC05 |
| Status | Implemented |
| Classes / files | `agent/AgentBridge` (strategy context, fallback handling); `WorkoutStrategy`; `AbstractSessionStrategy`; `HypertrophyStrategy`, `StrengthStrategy`; `StrategyContext`, `VolumeLimits`; `WorkoutJsonMapper`; `ExerciseLibrary`; `domain/WorkoutPlan`, `WorkoutRoutine`, `Exercise`, `ExerciseSet` |
| Key methods | `WorkoutGUI.onGenerateRoutineClicked()`, `displayRoutine()`, `WorkoutCLI.generate()`, `PulseForgeFacade.generateAdaptedWorkout()`, `AgentBridge.setStrategy()`, `buildWorkout()`, `WorkoutStrategy.computeLimits()`, `generatePlan()`, `fallbackPlan()`, `WorkoutJsonMapper.toRoutine()`, `WorkoutSessionManager.stageRoutine()` |
| Design patterns | Strategy, Facade |
| Type | Hybrid |
| Video | TODO |

Flow: the facade sets the chosen strategy on `AgentBridge` and builds a `StrategyContext`
(readiness, tier, sleep, goal). The strategy computes hard limits (sets, rep range, RPE cap,
load) from the readiness tier, prompts the LLM for a JSON routine inside them, and the mapper
clamps anything that exceeds them. The routine is staged in `WorkoutSessionManager`.
Alternatives: `ModelInferenceException` (timeout, HTTP error, malformed or empty JSON) makes
`AgentBridge` use the strategy's offline template; if the fatigue sentinel fires, a Recovery
Warning offers UC08.

### F06 - Interactive Set & Rep Logger with Command Undo/Redo

| Field | Entry |
|---|---|
| Stage 1 reference | F06 / UC06, UC06a |
| Status | Implemented |
| Classes / files | `session/Command`; `LogSetCommand`, `DeleteExerciseCommand`; `CommandInvoker`; `WorkoutSessionManager` (receiver); `SessionListener` |
| Key methods | `WorkoutGUI.onLogSetClicked()`, `WorkoutCLI.log()`, `PulseForgeFacade.logSet()`, `deleteExercise()`, `undoLastAction()`, `redoLastAction()`, `CommandInvoker.executeCommand()`, `undo()`, `redo()`, `LogSetCommand.validate()`, `execute()`, `undo()` |
| Design patterns | Command, Facade, Observer (session listeners) |
| Type | Deterministic |
| Video | TODO |

Flow: the facade builds a `LogSetCommand` (validation happens in its constructor, so bad input
never creates a command) and hands it to `CommandInvoker`, which executes it and pushes it on
the undo stack. The command snapshots the target set, records the values on the receiver and
starts the rest timer. Undo restores the snapshot and moves the command to the redo stack.
Alternatives: negative or non-numeric input is rejected with no state change; an empty undo
stack disables the GUI button and prints "No actions to undo." in the CLI.

### F07 - Biometric-Adaptive Smart Rest & Recovery Timer

| Field | Entry |
|---|---|
| Stage 1 reference | F07 / UC07 |
| Status | Modified (live heart rate is simulated; see Section 4, C6) |
| Classes / files | `session/SmartRestEngine` (duration rules); `RestTimer` (clock); `TimerListener`; `HeartRateSource`, `SimulatedHeartRateSource`; `WorkoutSessionManager.startTimer` |
| Key methods | `LogSetCommand.execute()`, `WorkoutSessionManager.startTimer()`, `SmartRestEngine.computeRestSeconds()`, `RestTimer.start()`, `tick()`, `addTime()`, `skip()`, `abort()`, `WorkoutGUI.updateTimerDisplay()` |
| Design patterns | Command (trigger), Observer (tick listeners) |
| Type | Deterministic |
| Video | TODO |

Flow: after a set is logged, rest length is computed from movement type and RPE (compound
RPE 9 = 180 s, isolation RPE 7 = 75 s). `RestTimer` ticks every second and notifies listeners;
the GUI shows the countdown with **+30s** and **Skip** and beeps at zero. With the simulated
heart-rate source enabled, the timer holds at zero until heart rate is under 60 % of maximum.
Alternative: skipping, or logging the next set early, stops the countdown and records the
actual rest taken on the set.

### F08 - Proactive Overtraining & CNS Fatigue Sentinel / Deload Week Planner

| Field | Entry |
|---|---|
| Stage 1 reference | F08 / UC08 |
| Status | Implemented (both the sentinel from the feature spec and the deload planner from the traceability table; see Section 4, C7) |
| Classes / files | `readiness/FatigueSentinel` (14-day rules); `domain/FatigueAssessment`; `AgentBridge.diagnoseFatigue`; `agent/DeloadStrategy`; `AthleteProfile` (deload calendar, 72 h suppression, RPE adjustment) |
| Key methods | `PulseForgeFacade.assessFatigue()`, `diagnoseFatigue()`, `planDeloadWeek()`, `declineDeload()`, `FatigueSentinel.evaluate()`, `AgentBridge.setStrategy()`, `DeloadStrategy.generatePlan()`, `fallbackPlan()`, `AthleteProfile.setDeloadCalendar()`, `declineDeload()` |
| Design patterns | Strategy, Facade, Observer |
| Type | Hybrid |
| Video | TODO |

Flow: the sentinel counts consecutive days of suppressed HRV with under 7 h sleep, consecutive
days of readiness below 40, the tonnage trend and missing days, and assigns Low / Elevated /
Critical. The 14-day table goes to the LLM for an explanatory diagnosis. **Plan Deload Week**
swaps `AgentBridge` to `DeloadStrategy`, which produces seven days (at most 9 sets per lifting
day, RPE 7 or lower), saves them to the profile and restores the previous strategy.
Alternatives: missing days are listed and push the guidance conservative; declining suppresses
the warning for 72 h and lowers RPE caps by 1.

### F09 - Natural Language Holistic Training & Diet Coach

| Field | Entry |
|---|---|
| Stage 1 reference | F09 / UC09 |
| Status | Modified (prompt augmentation replaced by a tool-using agent loop; see Section 4, C8) |
| Classes / files | `agent/AgentBridge.queryAgent`; `AgentTool`, `ToolRegistry`; `CoachMemory`; `Guardrails`; `AgentResponse`; tools defined in `PulseForgeFacade.buildTools` |
| Key methods | `WorkoutGUI.onAskCoachClicked()`, `WorkoutCLI.coach()`, `PulseForgeFacade.askCoach()`, `AgentBridge.queryAgent()`, `Guardrails.screen()`, `AgentTool.execute()`, `CoachMemory.add()`, `LLMClient.generate()` |
| Design patterns | Facade |
| Type | AI-based |
| Video | TODO |

Flow: the question is screened; if allowed, the model replies with either a tool request or a
final answer in JSON. Tool results are appended to the conversation and the loop continues
(at most 4 tool calls). The answer, with the list of tools used, is shown and stored in memory.
Alternatives: medical questions get a disclaimer and emergencies an urgent-care message, both
without calling the model; if the model is unreachable, the user gets a labeled offline
snapshot of readiness and energy data.

### F10 - Integrated Fitness, Sleep & Diet Audit Exporter

| Field | Entry |
|---|---|
| Stage 1 reference | F10 / UC10 |
| Status | Implemented |
| Classes / files | `report/ReportGenerator`; `SimplePdfWriter`; `domain/CoachNote` |
| Key methods | `WorkoutGUI.onExportReportClicked()`, `WorkoutCLI.export()`, `PulseForgeFacade.exportReport()`, `ReportGenerator.compileAudit()`, `writeToFile()`, `SimplePdfWriter.write()` |
| Design patterns | Facade |
| Type | Deterministic |
| Video | TODO |

Flow: `compileAudit` gathers records, readiness, meals, sessions and saved AI notes for the
range, builds summary, weekly-average and daily tables, and `writeToFile` writes Markdown or
PDF. Alternative: an unwritable or missing folder raises `IOException`; the GUI reopens the
file chooser and the CLI asks for another `--out`; data stays in memory.

---

## 3. Implemented design patterns

All five Stage 1 patterns are implemented. **TODO:** insert the updated UML class diagram
(and updated SD02/SD03 where Section 4 changes them).

### 3.1 Adapter

- **Problem.** Wearable vendors export incompatible formats; the core must not depend on them.
- **Roles.** Target: `FitnessDataParser`. Adapters: `AppleHealthXmlAdapter`,
  `GarminJsonAdapter`, `CsvBiometricAdapter`. Adaptee: `XmlParser` (StAX) for Apple, the JSON
  reader for Garmin. Client: `PulseForgeFacade.importHealthData`.
- **Files / methods.** `ingest/*`; `parse(Path)`, `XmlParser.forEachElement`.
- **How / why.** `AppleHealthXmlAdapter` holds an `XmlParser` by composition and translates
  `<Record>` attributes into `BiometricRecord`s. Adding the CSV format required one new class
  and one line in `HealthParserFactory`, with no change to existing parsers.
- **Versus Stage 1.** Matches; `CsvBiometricAdapter` and `HealthParserFactory` are additions.

### 3.2 Strategy

- **Problem.** Hypertrophy, strength and deload programming differ in volume, rep ranges,
  intensity caps and prompt guidance.
- **Roles.** Strategy: `WorkoutStrategy`. Concrete strategies: `HypertrophyStrategy`,
  `StrengthStrategy` (via `AbstractSessionStrategy`), `DeloadStrategy`. Context: `AgentBridge`.
- **Files / methods.** `agent/*`; `setStrategy`, `buildWorkout`, `computeLimits`,
  `generatePlan`, `fallbackPlan`.
- **How / why.** `AgentBridge` delegates to whichever strategy is set; the facade swaps it at
  runtime (user choice, or `DeloadStrategy` for UC08). No goal-based conditionals exist in the
  generator.
- **Versus Stage 1.** Same pattern and classes; the interface changed (Section 4, C2).

### 3.3 Facade

- **Problem.** GUI and CLI would otherwise duplicate orchestration of seven subsystems.
- **Roles.** Facade: `PulseForgeFacade`. Subsystems: ingestion adapters, `ReadinessCalculator`,
  `FatigueSentinel`, `VisionNutritionService`, `AgentBridge`, `WorkoutSessionManager` +
  `CommandInvoker`, `ReportGenerator`, `ProfileRepository`. Clients: `WorkoutGUI`, `WorkoutCLI`.
- **Files / methods.** `facade/PulseForgeFacade.java`; `importHealthData`, `getTodayReadiness`,
  `analyzeMealPhoto`, `confirmMeal`, `reconcileEnergyBalance`, `generateAdaptedWorkout`,
  `logSet`, `undoLastAction`, `planDeloadWeek`, `askCoach`, `exportReport`.
- **How / why.** Neither UI class imports a parser, calculator, strategy or command class for
  orchestration; the same facade instance type also drives the automated checks.
- **Versus Stage 1.** Matches.

### 3.4 Command

- **Problem.** Lifters mistype sets; logging and deleting must be reversible to any depth.
- **Roles.** Command: `Command`. Concrete commands: `LogSetCommand`, `DeleteExerciseCommand`.
  Invoker: `CommandInvoker`. Receiver: `WorkoutSessionManager`.
- **Files / methods.** `session/*`; `executeCommand`, `undo`, `redo`, `execute`, `undo`.
- **How / why.** Each command stores what it needs to reverse itself (a set snapshot, or the
  removed exercise and its index), so no full-session copies are needed.
- **Versus Stage 1.** Matches (Stage 1 text also called the invoker `SessionCommandInvoker`;
  the class diagram name `CommandInvoker` is used).

### 3.5 Observer

- **Problem.** GUI widgets, CLI output and storage must react to profile changes without the
  domain model knowing about them.
- **Roles.** Subject: `AthleteProfile`. Observer: `ProfileObserver`. Concrete observers:
  `GuiDashboardObserver`, `CliConsoleObserver`, `ProfileRepository`. The same pattern is used
  for `RestTimer` -> `TimerListener` and `WorkoutSessionManager` -> `SessionListener`.
- **Files / methods.** `domain/AthleteProfile.java` (`addObserver`, `notifyObservers`),
  `onProfileUpdated`.
- **How / why.** An import triggers one `storeRecords` call; the gauge redraws, the console
  prints and the profile file is saved, with no references from the domain to any of them.
- **Versus Stage 1.** Same pattern; `onProfileUpdated` gained an event argument and the
  repository became an observer (Section 4, C3, C9).

---

## 4. Changes and rationale

No Stage 1 feature was removed or replaced. No design pattern was replaced.

| # | Original design | Implemented change | Reason | Impact |
|---|---|---|---|---|
| C1 | Gemini 1.5 Flash via `GeminiLlmClient` | Model is configurable; default `gemini-flash-lite-latest`; `OpenAiCompatibleLlmClient` and `LlmClientFactory` added | Gemini 1.5 Flash is no longer offered; Stage 1 already planned provider swapping behind `LLMClient` | New classes in `llm/`; no change to business logic |
| C2 | `WorkoutStrategy.generatePlan(Context): WorkoutRoutine` | `generatePlan(StrategyContext, LLMClient): WorkoutPlan`, plus `computeLimits` and `fallbackPlan`; shared steps in `AbstractSessionStrategy` | A deload is seven routines, so a container (`WorkoutPlan`) was needed; limits and offline templates are goal-specific and belong to the strategy | UC05, UC08; SD02 return type; `AgentBridge.buildWorkout` |
| C3 | `ProfileObserver.onProfileUpdated(AthleteProfile)` | Added a `ProfileEvent` argument | Lets each observer refresh only what changed | All observers |
| C4 | F01 via Apple and Garmin adapters | Added `CsvBiometricAdapter`, `HealthParserFactory`; `XmlParser` is an explicit class over StAX | Stage 1 F01 text lists manual CSV; streaming handles large Apple exports | UC01 step 2 |
| C5 | F04 computed inside `VisionNutritionService`, result entity `Report` | Maths moved to `EnergyBalanceCalculator`; entity named `EnergySufficiencyReport`; AI advice is requested on demand while the deterministic part runs automatically | Single responsibility; avoids spending tokens on every meal | UC04; F04 key methods |
| C6 | F07 `RestTimer` computes durations and reads live wearable heart rate | Rules moved to `SmartRestEngine`; heart rate comes from a `HeartRateSource` interface with a simulated implementation | No wearable hardware API was available; the interface keeps a real source pluggable | UC07; SD03 |
| C7 | F08 described as AI-based sentinel in 1.2 and as hybrid deload planner in the traceability table | Both built: deterministic `FatigueSentinel`, AI diagnosis, `DeloadStrategy` planner; classified Hybrid | Reconciles the two Stage 1 descriptions; the trigger rule must be reproducible | UC05 alt 2a, UC08 |
| C8 | F09 merges context into one prompt | Tool-using loop (`AgentTool`, `ToolRegistry`), `CoachMemory`, `Guardrails`, `AgentResponse` | Meets the requirement for meaningful agent behaviour; the model fetches only the data it needs | UC09; SD02 variant |
| C9 | Records persisted to a "database" via `ProfileRepository` | JSON file store; the repository is a `ProfileObserver` that auto-saves | No external dependency; removes save calls from UI code | UC01 postcondition |
| C10 | F10 PDF output (library unspecified) | `SimplePdfWriter` (text PDF); AI outputs are stored as `CoachNote`s for the report | Keeps the build dependency-free; "coach notes" needed a source | UC10 |
| C11 | Java/Python left open; JSON handling unspecified | Java 17 only, in-repo `Json` parser | Reproducible build with only a JDK | Whole codebase |

---

## 5. AI tools and collaboration log

| Tool / platform | Model | Used for | Tokens | Cost |
|---|---|---|---|---|
| Claude Code (VS Code extension) | **TODO - confirm exact model(s) from the session** | Implementation of all packages, test runner, sample data generator, README, this draft | **TODO** | **TODO** |
| **TODO: any other tools used (including for Stage 1)** | | | | |

Representative interactions (**TODO: add 3-5 real prompt/response excerpts with what the team
reviewed or changed**). Examples from the implementation session:

1. *Prompt:* implement the Stage 1 design under the Stage 2 instructions. *Outcome:* package
   structure, the five patterns and all ten features generated. *Human review:* **TODO**.
2. *Defect found during generation:* source files containing non-ASCII characters failed to
   compile under the Windows default code page. *Fix:* numeric character comparisons and
   `-encoding UTF-8` in both build scripts.
3. *Defect found by the check suite:* a readiness assertion compared against a single arbitrary
   day. *Fix:* compare against the mean of the healthy days.

---

## 6. Development and verification summary

### Weekly commit summary

| Period | Dates | Commits (links) |
|---|---|---|
| 1 | Oct 20-26, 2026 | **TODO** |
| 2 | Oct 27-Nov 2, 2026 | **TODO** |
| 3 | Nov 3-9, 2026 | **TODO** |

### Checks performed

- `build.ps1 test` / `./build.sh test`: 22 automated checks, all passing, covering every
  feature F01-F10 plus JSON and persistence. AI paths use `ScriptedLlm`, a test double that
  replays model output and records requests.
- Manual CLI run of the full flow without an API key (import, readiness, generate, log, undo,
  redo, delete, timer, finish, meal, energy, fatigue, deload, coach, export, error cases).
- Exported PDF opened and read back with `pdftotext`.
- GUI launched without errors. **TODO:** record the manual GUI walkthrough results.
- Live run against Gemini (`gemini-flash-lite-latest`) through the CLI: workout generation
  (F05) returned a routine inside the computed limits; fueling advice (F04), fatigue diagnosis
  and the 7-day deload plan (F08) came from the model; the coach (F09) called `get_readiness`
  and `get_energy_balance` before answering, used the previous turn as context, and declined
  an off-topic question; the vision path (F02) rejected a non-food image.

### Defects found and corrected

| Defect | Correction |
|---|---|
| Non-ASCII characters in source broke compilation on Windows | Numeric comparisons; `-encoding UTF-8` in build scripts |
| Audit report's energy figures ignored the wearable estimate used on the energy screen | `ReportGenerator` now uses `EnergyBalanceCalculator.wearableActive` |
| Brittle readiness check (single-day comparison) | Compare against the healthy-day mean |

### Remaining limitations

See Section 1.

---

## 7. Demonstration index

| Feature | Interface shown | Video link / timestamp |
|---|---|---|
| F01 Biometric ingestion (incl. corrupt-file error) | GUI + CLI | TODO |
| F02 Meal photo analyzer (incl. unrecognized image) | GUI | TODO |
| F03 Readiness scorer (incl. approximation with < 3 days) | GUI + CLI | TODO |
| F04 Energy balance (incl. under-fueled alert) | GUI | TODO |
| F05 Workout generator (incl. offline fallback) | GUI + CLI | TODO |
| F06 Set logger with undo/redo (incl. invalid input) | GUI + CLI | TODO |
| F07 Smart rest timer (incl. skip, +30s, HR hold) | GUI | TODO |
| F08 Fatigue sentinel and deload planner (incl. decline) | GUI + CLI | TODO |
| F09 AI coach (incl. medical guardrail) | GUI + CLI | TODO |
| F10 Audit exporter (incl. unwritable folder) | GUI + CLI | TODO |
