package com.pulseforge.ui.gui;

import com.pulseforge.agent.AgentResponse;
import com.pulseforge.domain.AthleteProfile;
import com.pulseforge.domain.BiometricRecord;
import com.pulseforge.domain.EnergySufficiencyReport;
import com.pulseforge.domain.Exercise;
import com.pulseforge.domain.ExerciseSet;
import com.pulseforge.domain.FatigueAssessment;
import com.pulseforge.domain.FoodItem;
import com.pulseforge.domain.MealLog;
import com.pulseforge.domain.ProfileEvent;
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
import com.pulseforge.session.SessionListener;
import com.pulseforge.session.TimerListener;
import com.pulseforge.session.WorkoutSessionManager;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Swing GUI. A pure client of {@link PulseForgeFacade}: every button handler calls one facade
 * method (on a background thread when it may hit the network) and renders the result.
 * Display refreshes are driven by observers ({@link GuiDashboardObserver},
 * {@link SessionListener}, {@link TimerListener}) rather than by the handlers themselves.
 */
public class WorkoutGUI extends JFrame implements SessionListener, TimerListener {

    private final PulseForgeFacade facade;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel statusBar = new JLabel(" Ready");

    // Dashboard
    private final ReadinessGauge gauge = new ReadinessGauge();
    private final JTextArea readinessDetails = area(8, 40);
    private final JTextArea fatigueArea = area(14, 60);
    private final JLabel fatigueBanner = new JLabel(" ");

    // Biometrics
    private final DefaultTableModel bioModel = model(false, "Date", "Resting HR", "HRV (ms)", "Sleep (h)", "Deep (min)", "REM (min)", "Active kcal", "Source");

    // Nutrition
    private final JLabel mealPreview = new JLabel("No image selected", SwingConstants.CENTER);
    private final JTextField mealNotes = new JTextField(20);
    private final DefaultTableModel foodModel = model(true, "Food", "Portion", "kcal", "Protein g", "Carbs g", "Fat g");
    private final JTextArea energyArea = area(12, 40);
    private final JLabel energyBanner = new JLabel(" ", SwingConstants.CENTER);
    private final DefaultTableModel mealsModel = model(false, "Time", "Foods", "kcal", "Protein g", "Source");
    private final JButton confirmMealButton = new JButton("Confirm Meal");
    private File selectedMealImage;
    private MealLog pendingMeal;

    // Planner
    private final JComboBox<String> splitBox = new JComboBox<>(new String[]{"PUSH", "PULL", "LEGS", "UPPER", "LOWER", "FULL_BODY"});
    private final JComboBox<String> strategyBox = new JComboBox<>(new String[]{"Hypertrophy", "Strength"});
    private final JTextArea routineArea = area(18, 70);
    private final JButton startButton = new JButton("Start Session");

    // Live session
    private final DefaultTableModel setModel = model(false, "#", "Exercise", "Set", "Target", "Logged", "Done", "Rest (s)");
    private final JComboBox<String> exerciseBox = new JComboBox<>();
    private final JTextField weightField = new JTextField(6);
    private final JTextField repsField = new JTextField(4);
    private final JTextField rpeField = new JTextField(4);
    private final JButton logButton = new JButton("Log Set");
    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");
    private final JButton deleteExerciseButton = new JButton("Delete Exercise");
    private final JButton finishButton = new JButton("Finish Session");
    private final JLabel tonnageLabel = new JLabel("Session volume: 0 kg");
    private final JLabel timerLabel = new JLabel("--:--", SwingConstants.CENTER);
    private final JLabel timerStatus = new JLabel("Rest timer idle", SwingConstants.CENTER);
    private final JProgressBar timerBar = new JProgressBar(0, 100);
    private final JButton plus30Button = new JButton("+30s");
    private final JButton skipButton = new JButton("Skip");

    // Coach
    private final JTextArea chatArea = area(22, 70);
    private final JTextField chatInput = new JTextField(50);
    private final JButton sendButton = new JButton("Send");

    // Analytics
    private final JTextField fromField = new JTextField(10);
    private final JTextField toField = new JTextField(10);
    private final JComboBox<String> formatBox = new JComboBox<>(new String[]{"Markdown", "PDF"});
    private final DefaultTableModel historyModel = model(false, "Date", "Readiness", "Tier", "HRV z", "RHR z", "Sleep z", "Sleep score");

    // Profile
    private final JTextField nameField = new JTextField(14);
    private final JTextField ageField = new JTextField(4);
    private final JComboBox<String> sexBox = new JComboBox<>(new String[]{"M", "F"});
    private final JTextField weightKgField = new JTextField(5);
    private final JTextField heightField = new JTextField(5);
    private final JComboBox<TrainingGoal> goalBox = new JComboBox<>(TrainingGoal.values());

    public WorkoutGUI(PulseForgeFacade facade) {
        super("PulseForge AI - Biometric-Driven Gym & Recovery Agent");
        this.facade = facade;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.updateComponentTreeUI(this);
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1120, 760);
        setLocationRelativeTo(null);

        tabs.addTab("Dashboard", buildDashboard());
        tabs.addTab("Biometrics", buildBiometrics());
        tabs.addTab("Diet & Nutrition", buildNutrition());
        tabs.addTab("Gym Planner", buildPlanner());
        tabs.addTab("Live Session", buildSession());
        tabs.addTab("AI Coach", buildCoach());
        tabs.addTab("Analytics & Export", buildAnalytics());
        tabs.addTab("Profile", buildProfile());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(tabs, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Observer registrations
        facade.addProfileObserver(new GuiDashboardObserver(gauge, this::onProfileEvent));
        facade.getSessionManager().addListener(this);
        facade.getSessionManager().getTimer().addListener(this);

        refreshAll();
    }

    // =============================================================== builders

    private JPanel buildDashboard() {
        JPanel p = padded(new BorderLayout(12, 12));
        JPanel left = new JPanel(new BorderLayout());
        left.setBorder(BorderFactory.createTitledBorder("Recovery & Readiness Index"));
        left.add(gauge, BorderLayout.CENTER);
        JButton recalc = new JButton("Recalculate Readiness");
        recalc.addActionListener(e -> facade.getTodayReadiness());
        left.add(recalc, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(8, 8));
        JScrollPane details = new JScrollPane(readinessDetails);
        details.setBorder(BorderFactory.createTitledBorder("Sleep score breakdown"));
        right.add(details, BorderLayout.NORTH);

        JPanel fatigue = new JPanel(new BorderLayout(6, 6));
        fatigue.setBorder(BorderFactory.createTitledBorder("Overtraining & CNS Fatigue Sentinel"));
        fatigueBanner.setOpaque(true);
        fatigueBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        fatigue.add(fatigueBanner, BorderLayout.NORTH);
        fatigue.add(new JScrollPane(fatigueArea), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton diagnose = new JButton("View Fatigue Diagnosis");
        diagnose.addActionListener(e -> onViewFatigueDiagnosisClicked());
        JButton deload = new JButton("Plan Deload Week");
        deload.addActionListener(e -> onPlanDeloadClicked());
        JButton decline = new JButton("Decline Deload");
        decline.addActionListener(e -> {
            facade.declineDeload();
            info("Deload declined. Warnings are suppressed for 72 hours and working RPE caps are lowered by 1.");
        });
        buttons.add(diagnose);
        buttons.add(deload);
        buttons.add(decline);
        fatigue.add(buttons, BorderLayout.SOUTH);
        right.add(fatigue, BorderLayout.CENTER);

        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildBiometrics() {
        JPanel p = padded(new BorderLayout(8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton imp = new JButton("Import Health File");
        imp.addActionListener(e -> onImportHealthClicked());
        JButton sample = new JButton("Generate Sample Data");
        sample.setToolTipText("Writes synthetic Apple/Garmin/CSV exports ending yesterday into ./sample-data");
        sample.addActionListener(e -> {
            try {
                List<Path> files = facade.generateSampleData(Path.of("sample-data"));
                info("Sample exports written:\n" + join(files) + "\nUse 'Import Health File' to load one.");
            } catch (IOException ex) {
                error("Could not write sample data: " + ex.getMessage());
            }
        });
        top.add(imp);
        top.add(sample);
        top.add(new JLabel("  Supported: Apple Health export.xml, Garmin .json, manual .csv"));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(new JTable(bioModel)), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildNutrition() {
        JPanel p = padded(new BorderLayout(8, 8));

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.setBorder(BorderFactory.createTitledBorder("Meal Photo Analyzer (AI vision)"));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton upload = new JButton("Upload Meal Image");
        upload.addActionListener(e -> onUploadMealImageClicked());
        JButton analyze = new JButton("Analyze Meal");
        analyze.addActionListener(e -> onAnalyzeMealClicked());
        top.add(upload);
        top.add(new JLabel("Notes:"));
        top.add(mealNotes);
        top.add(analyze);
        left.add(top, BorderLayout.NORTH);
        mealPreview.setPreferredSize(new Dimension(200, 160));
        mealPreview.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        JPanel mid = new JPanel(new BorderLayout(6, 6));
        mid.add(mealPreview, BorderLayout.WEST);
        JTable foodTable = new JTable(foodModel);
        foodTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane foodScroll = new JScrollPane(foodTable);
        foodScroll.setBorder(BorderFactory.createTitledBorder("Editable preview - adjust estimates before confirming"));
        mid.add(foodScroll, BorderLayout.CENTER);
        left.add(mid, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addRow = new JButton("Add Manual Row");
        addRow.addActionListener(e -> {
            foodModel.addRow(new Object[]{"", "manual entry", "0", "0", "0", "0"});
            confirmMealButton.setEnabled(true);
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clearPendingMeal());
        confirmMealButton.setEnabled(false);
        confirmMealButton.addActionListener(e -> onConfirmMealClicked());
        bottom.add(addRow);
        bottom.add(clear);
        bottom.add(confirmMealButton);
        left.add(bottom, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(6, 6));
        right.setBorder(BorderFactory.createTitledBorder("Energy & Diet Balance"));
        energyBanner.setOpaque(true);
        energyBanner.setFont(energyBanner.getFont().deriveFont(Font.BOLD, 14f));
        energyBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        right.add(energyBanner, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(energyArea),
                new JScrollPane(new JTable(mealsModel)));
        split.setResizeWeight(0.6);
        right.add(split, BorderLayout.CENTER);
        JPanel eb = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Refresh Balance");
        refresh.addActionListener(e -> displayEnergyReport(facade.reconcileEnergyBalance(false)));
        JButton advice = new JButton("Get AI Fueling Advice");
        advice.addActionListener(e -> runAsync("Asking the nutrition advisor...",
                () -> facade.reconcileEnergyBalance(true), this::displayEnergyReport));
        eb.add(refresh);
        eb.add(advice);
        right.add(eb, BorderLayout.SOUTH);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setResizeWeight(0.55);
        p.add(main, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildPlanner() {
        JPanel p = padded(new BorderLayout(8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Split:"));
        top.add(splitBox);
        top.add(new JLabel("Strategy:"));
        top.add(strategyBox);
        JButton gen = new JButton("Generate Adapted Workout");
        gen.addActionListener(e -> onGenerateRoutineClicked());
        top.add(gen);
        startButton.setEnabled(false);
        startButton.addActionListener(e -> {
            try {
                facade.startSession();
                tabs.setSelectedIndex(4);
            } catch (IllegalStateException ex) {
                error(ex.getMessage());
            }
        });
        top.add(startButton);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(routineArea), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildSession() {
        JPanel p = padded(new BorderLayout(8, 8));
        p.add(new JScrollPane(new JTable(setModel)), BorderLayout.CENTER);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(300, 400));

        JPanel entry = new JPanel(new GridLayout(0, 2, 6, 6));
        entry.setBorder(BorderFactory.createTitledBorder("Log a set"));
        entry.add(new JLabel("Exercise:"));
        entry.add(exerciseBox);
        entry.add(new JLabel("Weight (kg):"));
        entry.add(weightField);
        entry.add(new JLabel("Reps:"));
        entry.add(repsField);
        entry.add(new JLabel("RPE (1-10):"));
        entry.add(rpeField);
        logButton.addActionListener(e -> onLogSetClicked());
        undoButton.addActionListener(e -> {
            facade.undoLastAction().ifPresent(d -> status("Undone: " + d));
            refreshSession();
        });
        redoButton.addActionListener(e -> {
            facade.redoLastAction().ifPresent(d -> status("Redone: " + d));
            refreshSession();
        });
        deleteExerciseButton.addActionListener(e -> {
            try {
                status(facade.deleteExercise(exerciseBox.getSelectedIndex()));
            } catch (RuntimeException ex) {
                error(ex.getMessage());
            }
        });
        finishButton.addActionListener(e -> onFinishSessionClicked());
        entry.add(logButton);
        entry.add(deleteExerciseButton);
        entry.add(undoButton);
        entry.add(redoButton);
        side.add(entry);

        JPanel timer = new JPanel(new BorderLayout(4, 4));
        timer.setBorder(BorderFactory.createTitledBorder("Smart rest timer"));
        timerLabel.setFont(timerLabel.getFont().deriveFont(Font.BOLD, 44f));
        timer.add(timerLabel, BorderLayout.NORTH);
        JPanel tc = new JPanel(new GridLayout(0, 1, 4, 4));
        tc.add(timerBar);
        tc.add(timerStatus);
        JPanel tb = new JPanel(new FlowLayout());
        plus30Button.addActionListener(e -> facade.addRestTime());
        skipButton.addActionListener(e -> facade.skipRest());
        tb.add(plus30Button);
        tb.add(skipButton);
        tc.add(tb);
        JCheckBox hr = new JCheckBox("Simulated live heart rate (hold until HR < 60% max)");
        hr.addActionListener(e -> facade.setLiveHeartRateSimulation(hr.isSelected()));
        tc.add(hr);
        timer.add(tc, BorderLayout.CENTER);
        side.add(timer);

        JPanel foot = new JPanel(new GridLayout(0, 1, 4, 4));
        tonnageLabel.setFont(tonnageLabel.getFont().deriveFont(Font.BOLD, 15f));
        foot.add(tonnageLabel);
        foot.add(finishButton);
        side.add(foot);
        side.add(Box.createVerticalGlue());
        p.add(side, BorderLayout.EAST);
        return p;
    }

    private JPanel buildCoach() {
        JPanel p = padded(new BorderLayout(8, 8));
        chatArea.setText("Coach: Ask me about training changes, substitutions, fatigue or meals. "
                + "I look up your live readiness, energy balance and workout before answering.\n"
                + "LLM: " + facade.llmDescription() + "\n\n");
        p.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(chatInput, BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);
        sendButton.addActionListener(e -> onAskCoachClicked());
        chatInput.addActionListener(e -> onAskCoachClicked());
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildAnalytics() {
        JPanel p = padded(new BorderLayout(8, 8));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setBorder(BorderFactory.createTitledBorder("Export audit report"));
        toField.setText(LocalDate.now().toString());
        fromField.setText(LocalDate.now().minusDays(29).toString());
        top.add(new JLabel("From:"));
        top.add(fromField);
        top.add(new JLabel("To:"));
        top.add(toField);
        JButton last7 = new JButton("Last 7 Days");
        last7.addActionListener(e -> setRange(7));
        JButton last30 = new JButton("Last 30 Days");
        last30.addActionListener(e -> setRange(30));
        top.add(last7);
        top.add(last30);
        top.add(new JLabel("Format:"));
        top.add(formatBox);
        JButton export = new JButton("Export Report");
        export.addActionListener(e -> onExportReportClicked());
        top.add(export);
        p.add(top, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(new JTable(historyModel));
        sp.setBorder(BorderFactory.createTitledBorder("Readiness history (last 21 days)"));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildProfile() {
        JPanel p = padded(new BorderLayout(8, 8));
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.setBorder(BorderFactory.createTitledBorder("Athlete profile"));
        form.add(new JLabel("Name:"));
        form.add(nameField);
        form.add(new JLabel("Age:"));
        form.add(ageField);
        form.add(new JLabel("Sex:"));
        form.add(sexBox);
        form.add(new JLabel("Weight (kg):"));
        form.add(weightKgField);
        form.add(new JLabel("Height (cm):"));
        form.add(heightField);
        form.add(new JLabel("Primary goal:"));
        form.add(goalBox);
        JButton save = new JButton("Save Profile");
        save.addActionListener(e -> {
            try {
                facade.updateProfile(nameField.getText(), Integer.parseInt(ageField.getText().trim()),
                        (String) sexBox.getSelectedItem(), Double.parseDouble(weightKgField.getText().trim()),
                        Double.parseDouble(heightField.getText().trim()), (TrainingGoal) goalBox.getSelectedItem());
                status("Profile saved.");
            } catch (NumberFormatException ex) {
                error("Age, weight and height must be numbers.");
            } catch (IllegalArgumentException ex) {
                error(ex.getMessage());
            }
        });
        form.add(new JLabel("LLM: " + facade.llmDescription()));
        form.add(save);
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT));
        wrap.add(form);
        p.add(wrap, BorderLayout.NORTH);
        return p;
    }

    // ================================================================ handlers

    /** F01 / UC01. */
    void onImportHealthClicked() {
        JFileChooser fc = new JFileChooser(new File("sample-data").isDirectory() ? new File("sample-data") : new File("."));
        fc.setFileFilter(new FileNameExtensionFilter("Health exports (*.xml, *.json, *.csv)", "xml", "json", "csv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = fc.getSelectedFile().toPath();
        runAsync("Importing " + file.getFileName() + "...", () -> facade.importHealthData(file), res -> {
            info(String.format("Imported %d day(s) from %s.%n%s", res.daysImported(), res.format(), res.readiness().summary()));
            checkFatigueBanner(true);
        }, ex -> {
            if (ex instanceof InvalidHealthDataException) {
                error("Import failed - your existing data is unchanged.\n\n" + ex.getMessage()
                        + "\n\nPlease select a valid health export.");
            } else {
                error(ex.getMessage());
            }
        });
    }

    void onUploadMealImageClicked() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg, *.webp)", "png", "jpg", "jpeg", "webp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        selectedMealImage = fc.getSelectedFile();
        try {
            BufferedImage img = ImageIO.read(selectedMealImage);
            if (img != null) {
                double scale = Math.min(200.0 / img.getWidth(), 160.0 / img.getHeight());
                mealPreview.setIcon(new ImageIcon(img.getScaledInstance((int) (img.getWidth() * scale),
                        (int) (img.getHeight() * scale), Image.SCALE_SMOOTH)));
                mealPreview.setText("");
            } else {
                mealPreview.setIcon(null);
                mealPreview.setText(selectedMealImage.getName());
            }
        } catch (IOException e) {
            mealPreview.setIcon(null);
            mealPreview.setText(selectedMealImage.getName());
        }
        status("Selected " + selectedMealImage.getName() + " - click 'Analyze Meal'.");
    }

    /** F02 / UC03. */
    void onAnalyzeMealClicked() {
        if (selectedMealImage == null) {
            error("Upload a meal image first.");
            return;
        }
        Path img = selectedMealImage.toPath();
        String notes = mealNotes.getText();
        runAsync("Analyzing meal photo with AI vision...", () -> facade.analyzeMealPhoto(img, notes), meal -> {
            pendingMeal = meal;
            foodModel.setRowCount(0);
            for (FoodItem f : meal.getItems()) {
                foodModel.addRow(new Object[]{f.getName(), f.getPortion(), fmt(f.getCalories()), fmt(f.getProteinG()),
                        fmt(f.getCarbsG()), fmt(f.getFatG())});
            }
            confirmMealButton.setEnabled(true);
            status(String.format("Detected %d food(s), confidence %.0f%%. Review/edit, then Confirm Meal.",
                    meal.getItems().size(), meal.getConfidence() * 100));
        }, ex -> {
            if (ex instanceof ModelInferenceException) {
                int choice = JOptionPane.showConfirmDialog(this, "Network/model unavailable:\n" + ex.getMessage()
                        + "\n\nWould you like to enter meal calories manually?", "AI unavailable", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    foodModel.addRow(new Object[]{"", "manual entry", "0", "0", "0", "0"});
                    confirmMealButton.setEnabled(true);
                }
            } else if (ex instanceof UnrecognizedFoodException) {
                error(ex.getMessage());
            } else {
                error(ex.getMessage());
            }
        });
    }

    void onConfirmMealClicked() {
        List<FoodItem> items = new ArrayList<>();
        try {
            for (int r = 0; r < foodModel.getRowCount(); r++) {
                String name = String.valueOf(foodModel.getValueAt(r, 0)).trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Row " + (r + 1) + ": food name is required.");
                }
                double[] v = new double[4];
                for (int c = 0; c < 4; c++) {
                    v[c] = Double.parseDouble(String.valueOf(foodModel.getValueAt(r, c + 2)).trim());
                    if (v[c] < 0) {
                        throw new IllegalArgumentException("Row " + (r + 1) + ": values cannot be negative.");
                    }
                }
                items.add(new FoodItem(name, String.valueOf(foodModel.getValueAt(r, 1)), v[0], v[1], v[2], v[3]));
            }
        } catch (NumberFormatException e) {
            error("Calories and macros must be numbers.");
            return;
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        if (items.isEmpty()) {
            error("Nothing to confirm - analyze a photo or add a manual row.");
            return;
        }
        boolean fromAi = pendingMeal != null;
        MealLog meal = MealLog.create(mealNotes.getText(), fromAi ? MealLog.Source.VISION_AI : MealLog.Source.MANUAL,
                fromAi ? pendingMeal.getConfidence() : 1.0, items);
        displayEnergyReport(facade.confirmMeal(meal));
        clearPendingMeal();
        status("Meal saved; energy balance updated.");
    }

    private void clearPendingMeal() {
        pendingMeal = null;
        foodModel.setRowCount(0);
        confirmMealButton.setEnabled(false);
    }

    /** F04 / UC04. */
    void displayEnergyReport(EnergySufficiencyReport r) {
        Color c = switch (r.getStatus()) {
            case SUFFICED -> new Color(0xD7, 0xF0, 0xDD);
            case UNDER_FUELED -> new Color(0xF8, 0xD7, 0xD7);
            case HYPER_CALORIC_SURPLUS -> new Color(0xFB, 0xE9, 0xC6);
        };
        energyBanner.setBackground(c);
        energyBanner.setForeground(Color.BLACK);
        energyBanner.setText(r.getStatus().label() + (r.isUnderFueledAlert() ? "  -  UNDER-FUELED ALERT" : ""));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Burned:    %6.0f kcal%n", r.burnedKcal()));
        sb.append(String.format("   BMR %.0f + wearable active %.0f + lifting %.0f%n", r.getBmr(), r.getWearableActiveKcal(), r.getWorkoutKcal()));
        sb.append(String.format("Consumed:  %6.0f kcal  (%d meal(s))%n", r.getConsumedKcal(), r.getMealsLogged()));
        sb.append(String.format("Net:       %+6.0f kcal%n", r.netKcal()));
        sb.append(String.format("Protein:   %.0f / %.0f g  (deficit %.0f g)%n", r.getProteinConsumedG(), r.getProteinTargetG(), r.proteinDeficitG()));
        if (r.isUnderFueledAlert()) {
            sb.append("\nYou trained today but have logged no meals - refuel now.\n");
        }
        if (!r.getAdvice().isBlank()) {
            sb.append("\nFueling advice [").append(r.getAdviceSource()).append("]:\n").append(r.getAdvice()).append('\n');
        }
        energyArea.setText(sb.toString());
        energyArea.setCaretPosition(0);
        refreshMeals();
    }

    /** F05 / UC05. */
    void onGenerateRoutineClicked() {
        String split = (String) splitBox.getSelectedItem();
        String strategy = (String) strategyBox.getSelectedItem();
        runAsync("Generating adapted workout...", () -> facade.generateAdaptedWorkout(split, strategy), res -> {
            displayRoutine(res.plan());
            if (res.fatigue() != null) {
                showRecoveryWarning(res.fatigue());
            }
        });
    }

    void displayRoutine(WorkoutPlan plan) {
        StringBuilder sb = new StringBuilder();
        ReadinessResult r = facade.getProfile().getLatestReadiness();
        if (r != null) {
            sb.append("Calibrated to: ").append(r.summary()).append("\n\n");
        }
        if (!plan.isFromLlm()) {
            sb.append("NOTE: LLM unavailable (").append(plan.getFallbackReason())
                    .append(").\nUsing the deterministic offline template matched to your readiness tier.\n\n");
        }
        for (WorkoutRoutine routine : plan.getRoutines()) {
            sb.append(routine.describe()).append('\n');
        }
        routineArea.setText(sb.toString());
        routineArea.setCaretPosition(0);
    }

    /** F08 modal (UC05 alt 2a). */
    private void showRecoveryWarning(FatigueAssessment a) {
        Object[] options = {"View Fatigue Diagnosis", "Plan Deload Week", "Decline", "Close"};
        int choice = JOptionPane.showOptionDialog(this, "RECOVERY WARNING\n\n" + a.summary(), "Recovery Warning",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            tabs.setSelectedIndex(0);
            onViewFatigueDiagnosisClicked();
        } else if (choice == 1) {
            tabs.setSelectedIndex(0);
            onPlanDeloadClicked();
        } else if (choice == 2) {
            facade.declineDeload();
            status("Deload declined: warnings suppressed for 72 h, RPE caps lowered by 1.");
        }
    }

    void onViewFatigueDiagnosisClicked() {
        runAsync("Running the fatigue sentinel and AI diagnosis...", facade::diagnoseFatigue, a -> {
            fatigueArea.setText(a.summary() + "\n\n" + a.getTrendTable() + "\nDiagnosis [" + a.getDiagnosisSource()
                    + "]:\n" + a.getDiagnosis());
            fatigueArea.setCaretPosition(0);
            setFatigueBanner(a);
        });
    }

    void onPlanDeloadClicked() {
        runAsync("Planning a 7-day deload week...", facade::planDeloadWeek, plan -> {
            StringBuilder sb = new StringBuilder("7-DAY DELOAD WEEK (starts "
                    + facade.getProfile().getDeloadStartDate() + ")\n");
            if (!plan.isFromLlm()) {
                sb.append("NOTE: LLM unavailable (").append(plan.getFallbackReason()).append(") - offline deload template.\n");
            }
            sb.append('\n');
            plan.getRoutines().forEach(r -> sb.append(r.describe()).append('\n'));
            fatigueArea.setText(sb.toString());
            fatigueArea.setCaretPosition(0);
            status("Deload week saved to your calendar.");
        });
    }

    /** F06 / UC06. Validation happens before a command is built; invalid input never mutates state. */
    void onLogSetClicked() {
        double weight;
        double rpe;
        int reps;
        try {
            weight = Double.parseDouble(weightField.getText().trim());
            reps = Integer.parseInt(repsField.getText().trim());
            rpe = Double.parseDouble(rpeField.getText().trim());
        } catch (NumberFormatException e) {
            error("Weight and RPE must be numbers and reps a whole number.");
            return;
        }
        try {
            status(facade.logSet(exerciseBox.getSelectedIndex(), weight, reps, rpe));
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(e.getMessage());
        }
    }

    private void onFinishSessionClicked() {
        try {
            WorkoutSession s = facade.finishSession();
            EnergySufficiencyReport rep = facade.reconcileEnergyBalance(false);
            displayEnergyReport(rep);
            info(String.format("Session finished: %d/%d sets, %.0f kg volume, ~%.0f kcal.%n%nEnergy status: %s%s",
                    s.getRoutine().completedSets(), s.getRoutine().totalSets(), s.tonnage(), s.estimatedKcal(),
                    rep.getStatus().label(), rep.isUnderFueledAlert() ? " - UNDER-FUELED ALERT: log a recovery meal." : ""));
            routineArea.setText("");
        } catch (IllegalStateException e) {
            error(e.getMessage());
        }
    }

    /** F09 / UC09. */
    void onAskCoachClicked() {
        String q = chatInput.getText().trim();
        if (q.isEmpty()) {
            return;
        }
        chatInput.setText("");
        chatArea.append("You: " + q + "\n");
        sendButton.setEnabled(false);
        runAsync("Coach is thinking...", () -> facade.askCoach(q), (AgentResponse r) -> {
            chatArea.append("Coach: " + r.formatted() + "\n\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            sendButton.setEnabled(true);
        }, ex -> {
            chatArea.append("Coach: (error) " + ex.getMessage() + "\n\n");
            sendButton.setEnabled(true);
        });
    }

    /** F10 / UC10. */
    void onExportReportClicked() {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromField.getText().trim());
            to = LocalDate.parse(toField.getText().trim());
        } catch (DateTimeParseException e) {
            error("Dates must be in YYYY-MM-DD format.");
            return;
        }
        ReportGenerator.Format format = formatBox.getSelectedIndex() == 0 ? ReportGenerator.Format.MARKDOWN : ReportGenerator.Format.PDF;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("pulseforge-audit." + format.extension()));
        while (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Path written = facade.exportReport(from, to, format, fc.getSelectedFile().toPath());
                info("Audit report saved to:\n" + written.toAbsolutePath());
                return;
            } catch (IOException e) {
                error("Unable to write file to selected folder. Please choose a different directory.\n\n(" + e.getMessage() + ")");
                // loop: the chooser opens again
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
                return;
            }
        }
    }

    // ================================================================ observers

    private void onProfileEvent(AthleteProfile profile, ProfileEvent event) {
        switch (event) {
            case BIOMETRICS_IMPORTED -> refreshBiometrics();
            case READINESS_UPDATED -> {
                refreshReadiness();
                refreshHistory();
            }
            case MEAL_LOGGED -> refreshMeals();
            case SETTINGS_CHANGED -> refreshProfile();
            case DELOAD_UPDATED -> checkFatigueBanner(false);
            default -> { }
        }
    }

    @Override
    public void onSessionChanged(WorkoutSessionManager manager) {
        SwingUtilities.invokeLater(this::refreshSession);
    }

    @Override
    public void onTick(int remaining, int total, boolean holdingForHr, int heartRate) {
        SwingUtilities.invokeLater(() -> updateTimerDisplay(remaining, total, holdingForHr, heartRate));
    }

    void updateTimerDisplay(int remaining, int total, boolean holding, int heartRate) {
        timerLabel.setText(String.format("%d:%02d", remaining / 60, remaining % 60));
        timerBar.setValue(total == 0 ? 0 : (int) Math.round(100.0 * (total - remaining) / total));
        String hr = heartRate > 0 ? "  |  HR " + heartRate + " bpm (simulated)" : "";
        timerStatus.setText(holding ? "Holding until heart rate recovers" + hr : "Resting - " + total + " s prescribed" + hr);
        plus30Button.setEnabled(true);
        skipButton.setEnabled(true);
    }

    @Override
    public void onFinished(int actualRestSeconds, String reason) {
        SwingUtilities.invokeLater(() -> {
            timerLabel.setText("0:00");
            timerBar.setValue("complete".equals(reason) ? 100 : 0);
            timerStatus.setText("Rest " + reason + " after " + actualRestSeconds + " s"
                    + ("complete".equals(reason) ? " - ready for the next set!" : ""));
            plus30Button.setEnabled(false);
            skipButton.setEnabled(false);
            if ("complete".equals(reason)) {
                Toolkit.getDefaultToolkit().beep();
            }
        });
    }

    // ================================================================ refreshers

    private void refreshAll() {
        gauge.setResult(facade.getProfile().getLatestReadiness());
        refreshReadiness();
        refreshBiometrics();
        refreshMeals();
        refreshSession();
        refreshHistory();
        refreshProfile();
        displayEnergyReport(facade.reconcileEnergyBalance(false));
        checkFatigueBanner(false);
        plus30Button.setEnabled(false);
        skipButton.setEnabled(false);
    }

    private void refreshReadiness() {
        ReadinessResult r = facade.getProfile().getLatestReadiness();
        if (r == null) {
            readinessDetails.setText("No readiness yet.\nGo to Biometrics > Import Health File\n(sample files are in ./sample-data).");
            return;
        }
        BiometricRecord rec = facade.getProfile().getRecords().get(r.date());
        StringBuilder sb = new StringBuilder();
        sb.append(r.summary()).append('\n');
        sb.append(r.message()).append("\n\n");
        sb.append(String.format("Sleep score:        %.0f / 100%n", r.sleepScore()));
        sb.append(String.format("HRV vs baseline:    %+.2f SD%n", r.hrvComponent()));
        sb.append(String.format("Resting HR:         %+.2f SD (positive = lower HR)%n", r.restingHrComponent()));
        sb.append(String.format("Sleep duration:     %+.2f SD%n", r.sleepComponent()));
        sb.append(String.format("Deep sleep:         %+.2f SD%n", r.deepSleepComponent()));
        if (rec != null) {
            sb.append(String.format("%nLast night: %s h sleep, deep %s min, REM %s min, HRV %s ms, RHR %s bpm",
                    rec.getSleepHours() == null ? "-" : String.format("%.1f", rec.getSleepHours()),
                    fmt(rec.getDeepSleepMinutes()), fmt(rec.getRemSleepMinutes()), fmt(rec.getHrvMs()), fmt(rec.getRestingHr())));
        }
        readinessDetails.setText(sb.toString());
        readinessDetails.setCaretPosition(0);
    }

    private void checkFatigueBanner(boolean popup) {
        FatigueAssessment a = facade.assessFatigue();
        setFatigueBanner(a);
        if (popup && a.isDeloadRecommended() && !facade.getProfile().isDeloadWarningSuppressed(java.time.LocalDateTime.now())) {
            showRecoveryWarning(a);
        }
    }

    private void setFatigueBanner(FatigueAssessment a) {
        Color c = switch (a.getRisk()) {
            case LOW -> new Color(0xD7, 0xF0, 0xDD);
            case ELEVATED -> new Color(0xFB, 0xE9, 0xC6);
            case CRITICAL -> new Color(0xF8, 0xD7, 0xD7);
        };
        fatigueBanner.setBackground(c);
        fatigueBanner.setForeground(Color.BLACK);
        fatigueBanner.setText("Overtraining risk: " + a.getRisk() + (a.isDeloadRecommended() ? "  -  deload recommended" : ""));
    }

    private void refreshBiometrics() {
        bioModel.setRowCount(0);
        for (BiometricRecord r : facade.getProfile().getRecords().descendingMap().values()) {
            bioModel.addRow(new Object[]{r.getDate(), fmt(r.getRestingHr()), fmt(r.getHrvMs()),
                    r.getSleepHours() == null ? "-" : String.format("%.1f", r.getSleepHours()),
                    fmt(r.getDeepSleepMinutes()), fmt(r.getRemSleepMinutes()), fmt(r.getActiveEnergyKcal()), r.getSource()});
        }
    }

    private void refreshMeals() {
        mealsModel.setRowCount(0);
        for (MealLog m : facade.getProfile().mealsOn(LocalDate.now())) {
            mealsModel.addRow(new Object[]{m.getLoggedAt().toLocalTime().withNano(0), m.describe(),
                    fmt(m.totalCalories()), fmt(m.totalProtein()), m.getSource()});
        }
    }

    private void refreshSession() {
        WorkoutSessionManager sm = facade.getSessionManager();
        WorkoutRoutine r = sm.currentRoutine();
        int selected = exerciseBox.getSelectedIndex();
        setModel.setRowCount(0);
        exerciseBox.removeAllItems();
        if (r != null) {
            for (int i = 0; i < r.getExercises().size(); i++) {
                Exercise e = r.getExercises().get(i);
                exerciseBox.addItem((i + 1) + ". " + e.getName());
                for (int s = 0; s < e.getSets().size(); s++) {
                    ExerciseSet set = e.getSets().get(s);
                    setModel.addRow(new Object[]{i + 1, e.getName(), s + 1,
                            set.getTargetReps() + " @ RPE " + fmt(set.getTargetRpe()),
                            set.isCompleted() ? fmt(set.getActualWeight()) + " kg x " + set.getActualReps() + " @ " + fmt(set.getActualRpe()) : "",
                            set.isCompleted() ? "✓" : "",
                            set.getRestSecondsTaken() == null ? "" : set.getRestSecondsTaken()});
                }
            }
            if (selected >= 0 && selected < exerciseBox.getItemCount()) {
                exerciseBox.setSelectedIndex(selected);
            }
        }
        boolean active = sm.isActive();
        logButton.setEnabled(active);
        deleteExerciseButton.setEnabled(active);
        finishButton.setEnabled(active);
        undoButton.setEnabled(facade.canUndo());     // UC06a alt 1a: disabled when the stack is empty
        redoButton.setEnabled(facade.canRedo());
        startButton.setEnabled(!active && sm.getStaged() != null);
        tonnageLabel.setText(String.format("Session volume: %.0f kg%s", sm.tonnage(), active ? "" : "  (no active session)"));
    }

    private void refreshHistory() {
        historyModel.setRowCount(0);
        List<ReadinessResult> list = facade.readinessHistory(21);
        for (int i = list.size() - 1; i >= 0; i--) {
            ReadinessResult r = list.get(i);
            historyModel.addRow(new Object[]{r.date(), Math.round(r.score()), r.tier().label() + (r.approximation() ? " (approx.)" : ""),
                    String.format("%+.2f", r.hrvComponent()), String.format("%+.2f", r.restingHrComponent()),
                    String.format("%+.2f", r.sleepComponent()), Math.round(r.sleepScore())});
        }
    }

    private void refreshProfile() {
        AthleteProfile p = facade.getProfile();
        nameField.setText(p.getName());
        ageField.setText(String.valueOf(p.getAge()));
        sexBox.setSelectedItem(p.getSex());
        weightKgField.setText(fmt(p.getWeightKg()));
        heightField.setText(fmt(p.getHeightCm()));
        goalBox.setSelectedItem(p.getGoal());
    }

    private void setRange(int days) {
        toField.setText(LocalDate.now().toString());
        fromField.setText(LocalDate.now().minusDays(days - 1).toString());
    }

    // ================================================================== helpers

    private <T> void runAsync(String busy, Callable<T> work, Consumer<T> onOk) {
        runAsync(busy, work, onOk, ex -> error(ex.getMessage()));
    }

    /** Runs facade calls that may block on the network off the event thread. */
    private <T> void runAsync(String busy, Callable<T> work, Consumer<T> onOk, Consumer<Throwable> onError) {
        status(busy);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    T result = get();
                    status("Ready");
                    onOk.accept(result);
                } catch (ExecutionException e) {
                    status("Ready");
                    onError.accept(e.getCause() == null ? e : e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void status(String s) {
        statusBar.setText(" " + s);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "PulseForge AI", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "PulseForge AI", JOptionPane.ERROR_MESSAGE);
    }

    private static JPanel padded(java.awt.LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return p;
    }

    private static JTextArea area(int rows, int cols) {
        JTextArea a = new JTextArea(rows, cols);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    private static DefaultTableModel model(boolean editable, String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return editable;
            }
        };
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "-";
        }
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.format("%.1f", d);
    }

    private static String join(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        files.forEach(f -> sb.append("  ").append(f).append('\n'));
        return sb.toString();
    }
}
