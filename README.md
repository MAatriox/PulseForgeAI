# PulseForge AI - Biometric-Driven Gym & Recovery Agent

EECS 3311 course project (Stage 2 implementation). PulseForge AI imports wearable data,
computes a daily readiness score, and uses an LLM-backed agent to adapt workouts, analyze
meals, detect overtraining and answer coaching questions. It has a Swing **GUI** and a **CLI**,
both built on the same facade.

## Prerequisites

- **JDK 17 or newer** (`java -version`). Nothing else: the project has no third-party
  dependencies and builds with plain `javac`.
- An LLM API key for the AI features. A free Google Gemini key works
  (<https://aistudio.google.com/apikey>). Without a key the app still runs and uses its
  deterministic offline fallbacks.

## Build

Windows (PowerShell):

```powershell
.\build.ps1          # compiles to build/ and creates build/pulseforge.jar
.\build.ps1 test     # also runs the verification suite
```

macOS / Linux / Git Bash:

```bash
./build.sh
./build.sh test
```

## Local configuration (API key)

Real keys live only in a local `.env` file, which is git-ignored.

```powershell
Copy-Item .env.example .env      # bash: cp .env.example .env
```

Open `.env` and replace the placeholder:

```
LLM_PROVIDER=gemini
LLM_API_KEY=your-real-key-here
LLM_MODEL=gemini-flash-lite-latest
```

Other providers: set `LLM_PROVIDER=openai` (or `groq`, `openrouter`, `ollama`) with the matching
`LLM_BASE_URL` and `LLM_MODEL`. Meal photo analysis needs a vision-capable model. Environment
variables with the same names override `.env`. Never commit `.env`.

## Run

Run from the project root so `.env`, `data/` and `sample-data/` are found.

```bash
java -jar build/pulseforge.jar                 # GUI
java -jar build/pulseforge.jar --cli           # interactive CLI
java -jar build/pulseforge.jar --cli status    # one CLI command, then exit
```

Your profile is saved automatically to `data/profile.json` (git-ignored).

## Sample data

`sample-data/` contains synthetic exports in all three supported formats
(`apple_health_export.xml`, `garmin_export.json`, `manual_biometrics.csv`): 21 days, where the
last 4 days show a fatigue pattern (low HRV, short sleep) so the fatigue sentinel and deload
planner have something to find. To regenerate them ending yesterday, use `sample` in the CLI or
**Biometrics > Generate Sample Data** in the GUI. For meal photo analysis, use any food photo
of your own (PNG/JPEG/WEBP, up to 10 MB).

## Example usage (CLI)

```text
pulseforge> import sample-data/apple_health_export.xml
pulseforge> status
pulseforge> profile set name=Joe age=22 weight=80 height=180 goal=hypertrophy
pulseforge> generate legs --strategy strength
pulseforge> start
pulseforge> log --weight 100 --reps 5 --rpe 9
pulseforge> undo
pulseforge> redo
pulseforge> timer
pulseforge> finish
pulseforge> meal --img my-lunch.jpg --notes "post-workout"
pulseforge> meal --manual "Chicken rice bowl" --kcal 750 --protein 55 --carbs 80 --fat 18
pulseforge> energy --advice
pulseforge> fatigue
pulseforge> deload
pulseforge> coach I only slept 5 hours - should I still squat heavy today?
pulseforge> export --format pdf --out audit.pdf
```

Type `help` for the full command list. A live workout session exists only while the CLI shell
is open, so use the interactive shell (not one-shot commands) for `start` / `log` / `finish`.

## Example usage (GUI)

1. **Biometrics > Import Health File** and pick a file from `sample-data/`. The Dashboard gauge
   updates automatically.
2. **Gym Planner**: choose split and strategy, **Generate Adapted Workout**, then **Start Session**.
3. **Live Session**: enter weight / reps / RPE, **Log Set**; the rest timer starts on its own.
   **Undo** / **Redo** fix mistakes.
4. **Diet & Nutrition**: **Upload Meal Image**, **Analyze Meal**, edit the table, **Confirm Meal**.
5. **AI Coach**: ask a question; the reply lists which data tools the agent used.
6. **Analytics & Export**: pick dates and format, **Export Report**.

## Features

| ID | Feature | Type |
|----|---------|------|
| F01 | Multi-source biometric ingestion (Apple XML, Garmin JSON, CSV) | Deterministic |
| F02 | Meal photo calorie & macro analyzer | AI |
| F03 | Sleep & recovery readiness score (0-100) | Deterministic |
| F04 | Energy balance & caloric sufficiency | Hybrid |
| F05 | Readiness-calibrated workout generator | Hybrid |
| F06 | Set & rep logger with undo/redo | Deterministic |
| F07 | Adaptive smart rest timer | Deterministic |
| F08 | Overtraining sentinel & deload week planner | Hybrid |
| F09 | Natural-language training & diet coach (tool-using agent) | AI |
| F10 | Fitness, sleep & diet audit exporter (Markdown/PDF) | Deterministic |

Design patterns: Adapter (`ingest`), Strategy (`agent`), Facade (`facade`), Command (`session`),
Observer (`domain` + `ui`). See `docs/stage2-report-draft.md` for the class-level mapping.

## Project layout

```
src/main/java/com/pulseforge/
  app/          Main entry point
  config/       .env loading
  domain/       AthleteProfile (observer subject), records, meals, workouts
  ingest/       FitnessDataParser + vendor adapters
  readiness/    ReadinessCalculator, FatigueSentinel
  nutrition/    VisionNutritionService, EnergyBalanceCalculator
  llm/          LLMClient, Gemini and OpenAI-compatible clients
  agent/        AgentBridge, workout strategies, coach tools, guardrails
  session/      Commands, invoker, session manager, rest timer
  report/       ReportGenerator, PDF writer
  persistence/  ProfileRepository, sample data generator
  facade/       PulseForgeFacade
  ui/cli, ui/gui
src/test/java/  TestRunner (dependency-free checks with a scripted LLM)
sample-data/    Synthetic exports
```

## Known limitations

- Live heart rate for the rest timer is **simulated** (toggle in the GUI, `hr on` in the CLI);
  there is no real wearable stream.
- Apple/Garmin parsing covers resting HR, HRV, sleep stages and active energy only. The Garmin
  schema follows Garmin Connect field names but was tested only with the bundled sample.
- PDF export is plain monospaced text (tables are not typeset).
- PulseForge AI is not a medical device; the coach refuses diagnostic questions.
