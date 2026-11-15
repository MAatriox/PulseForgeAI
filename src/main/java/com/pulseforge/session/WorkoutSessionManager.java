package com.pulseforge.session;

import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;
import com.pulseforge.domain.WorkoutRoutine;
import com.pulseforge.domain.WorkoutSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;

/**
 * Receiver of the Command pattern: holds the staged routine and the live session state
 * (sets, tonnage) and owns the adaptive rest timer. Commands mutate state only through the
 * small set of primitive operations below, which makes every action reversible.
 */
public class WorkoutSessionManager implements TimerListener {

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final SmartRestEngine restEngine = new SmartRestEngine();
    private final RestTimer timer;
    private final IntSupplier maxHeartRate;

    private WorkoutRoutine staged;
    private WorkoutSession active;
    private HeartRateSource heartRateSource;   // null = no live HR connected
    private int restingExercise = -1;
    private int restingSet = -1;
    private String lastRestSummary = "";

    public WorkoutSessionManager(RestTimer timer, IntSupplier maxHeartRate) {
        this.timer = timer;
        this.maxHeartRate = maxHeartRate;
        timer.addListener(this);
    }

    public void addListener(SessionListener l) {
        listeners.add(l);
    }

    private void fireChanged() {
        for (SessionListener l : listeners) {
            l.onSessionChanged(this);
        }
    }

    // --------------------------------------------------------------- lifecycle

    public void stageRoutine(WorkoutRoutine routine) {
        if (active != null) {
            throw new IllegalStateException("Finish the current session before staging a new routine");
        }
        this.staged = routine;
        fireChanged();
    }

    public void startSession() {
        if (active != null) {
            throw new IllegalStateException("A session is already in progress");
        }
        if (staged == null) {
            throw new IllegalStateException("Generate a workout first (no routine is staged)");
        }
        active = new WorkoutSession(staged, LocalDateTime.now());
        fireChanged();
    }

    public WorkoutSession finishSession() {
        requireActive();
        timer.skip();
        WorkoutSession done = active;
        done.finish(LocalDateTime.now());
        active = null;
        staged = null;
        restingExercise = -1;
        fireChanged();
        return done;
    }

    // ------------------------------------------------- receiver primitives

    public boolean hasExercise(int index) {
        WorkoutRoutine r = active != null ? active.getRoutine() : staged;
        return r != null && index >= 0 && index < r.getExercises().size();
    }

    public Exercise exerciseAt(int index) {
        requireActive();
        if (!hasExercise(index)) {
            throw new IllegalArgumentException("No exercise #" + (index + 1) + " in this session");
        }
        return active.getRoutine().getExercises().get(index);
    }

    void recordSet(int exerciseIndex, int setIndex, double weight, int reps, double rpe) {
        exerciseAt(exerciseIndex).getSets().get(setIndex).complete(weight, reps, rpe);
        fireChanged();
    }

    void restoreSet(int exerciseIndex, int setIndex, ExerciseSet snapshot) {
        exerciseAt(exerciseIndex).getSets().get(setIndex).restoreFrom(snapshot);
        fireChanged();
    }

    int appendSet(int exerciseIndex, ExerciseSet set) {
        List<ExerciseSet> sets = exerciseAt(exerciseIndex).getSets();
        sets.add(set);
        fireChanged();
        return sets.size() - 1;
    }

    void removeSet(int exerciseIndex, int setIndex) {
        exerciseAt(exerciseIndex).getSets().remove(setIndex);
        fireChanged();
    }

    Exercise removeExercise(int index) {
        Exercise e = exerciseAt(index);
        active.getRoutine().getExercises().remove(index);
        if (restingExercise == index) {
            restingExercise = -1;
        } else if (restingExercise > index) {
            restingExercise--;
        }
        fireChanged();
        return e;
    }

    void insertExercise(int index, Exercise e) {
        requireActive();
        List<Exercise> list = active.getRoutine().getExercises();
        list.add(Math.min(index, list.size()), e);
        if (restingExercise >= index) {
            restingExercise++;
        }
        fireChanged();
    }

    // ---------------------------------------------------------- rest timer

    /** Called by LogSetCommand after the set is recorded (Stage 1 SD03). */
    void startTimer(int exerciseIndex, int setIndex) {
        Exercise ex = exerciseAt(exerciseIndex);
        ExerciseSet set = ex.getSets().get(setIndex);
        if (timer.isRunning()) {
            timer.abort();   // lifter started this set early: previous rest gets its actual duration
        }
        int seconds = restEngine.computeRestSeconds(ex.getMovementType(), set.getActualRpe());
        if (heartRateSource != null) {
            heartRateSource.onSetCompleted(set.getActualRpe());
        }
        restingExercise = exerciseIndex;
        restingSet = setIndex;
        timer.start(seconds, heartRateSource, restEngine.recoveryHeartRate(maxHeartRate.getAsInt()));
    }

    @Override
    public void onTick(int remaining, int total, boolean holdingForHr, int heartRate) {
        // GUI/CLI listen to the timer directly; nothing to do per tick here.
    }

    @Override
    public void onFinished(int actualRestSeconds, String reason) {
        if (active != null && restingExercise >= 0 && hasExercise(restingExercise)) {
            List<ExerciseSet> sets = active.getRoutine().getExercises().get(restingExercise).getSets();
            if (restingSet >= 0 && restingSet < sets.size()) {
                sets.get(restingSet).setRestSecondsTaken(actualRestSeconds);
            }
        }
        lastRestSummary = "Rest " + reason + " after " + actualRestSeconds + " s";
        restingExercise = -1;
        fireChanged();
    }

    public void addRestTime(int seconds) {
        timer.addTime(seconds);
    }

    public void skipRest() {
        timer.skip();
    }

    public void setHeartRateSource(HeartRateSource source) {
        this.heartRateSource = source;
    }

    // ---------------------------------------------------------------- queries

    private void requireActive() {
        if (active == null) {
            throw new IllegalStateException("No active session - start the workout first");
        }
    }

    public double tonnage() {
        return active == null ? 0 : active.tonnage();
    }

    public boolean isActive() { return active != null; }
    public WorkoutRoutine getStaged() { return staged; }
    public WorkoutSession getActive() { return active; }
    public WorkoutRoutine currentRoutine() { return active != null ? active.getRoutine() : staged; }
    public RestTimer getTimer() { return timer; }
    public HeartRateSource getHeartRateSource() { return heartRateSource; }
    public String getLastRestSummary() { return lastRestSummary; }
    public SmartRestEngine getRestEngine() { return restEngine; }
}
