package com.pulseforge.app;

import com.pulseforge.config.AppConfig;
import com.pulseforge.facade.PulseForgeFacade;
import com.pulseforge.ui.cli.WorkoutCLI;
import com.pulseforge.ui.gui.WorkoutGUI;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point.
 * <pre>
 *   java -jar pulseforge.jar                 launch the GUI
 *   java -jar pulseforge.jar --cli           interactive CLI shell
 *   java -jar pulseforge.jar --cli status    run one CLI command and exit
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        PulseForgeFacade facade = PulseForgeFacade.create(AppConfig.load());
        boolean cli = args.length > 0 && (args[0].equalsIgnoreCase("--cli") || args[0].equalsIgnoreCase("cli"));
        if (cli) {
            WorkoutCLI shell = new WorkoutCLI(facade);
            List<String> rest = Arrays.asList(args).subList(1, args.length);
            if (rest.isEmpty()) {
                shell.runInteractive();
                System.exit(0);
            }
            System.exit(shell.runOnce(rest));
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available - starting the CLI instead.");
            new WorkoutCLI(facade).runInteractive();
            return;
        }
        SwingUtilities.invokeLater(() -> new WorkoutGUI(facade).setVisible(true));
    }
}
