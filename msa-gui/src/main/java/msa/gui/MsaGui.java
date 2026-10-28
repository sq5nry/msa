package msa.gui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

/**
 * Starts the spectrum analyser GUI.
 * <pre>
 *   java -jar msa-gui.jar [--msa-info DIR] [--replay RECORDING.csv | --demo]
 * </pre>
 * Without --replay or --demo the MSA is opened over USB. The MSA_Info folder is the project's
 * msa-info folder (see {@link msa.config.MsaInfo}) unless --msa-info names another.
 *
 * <p>JavaFX is started with {@link Platform#startup} rather than an Application subclass, so the jar runs
 * from the class path without the JavaFX launcher check.
 */
public final class MsaGui {

    private MsaGui() {}

    public static void main(String[] args) {
        String info = null, replay = null;
        boolean demo = false;
        List<String> a = List.of(args);
        for (int i = 0; i < a.size(); i++) {
            switch (a.get(i)) {
                case "--msa-info" -> info = a.get(++i);
                case "--replay" -> replay = a.get(++i);
                case "--demo" -> demo = true;
                case "-h", "--help" -> {
                    System.out.println("usage: msa-gui [--msa-info DIR] [--replay RECORDING.csv | --demo]");
                    return;
                }
                default -> {
                    System.err.println("unknown argument " + a.get(i) + " (try --help)");
                    System.exit(2);
                }
            }
        }
        Session.Source source = replay != null ? new Session.Replay(Path.of(replay)) : demo ? new Session.Demo() : new Session.Usb();
        String infoArg = info;
        Platform.setImplicitExit(true);
        Platform.startup(() -> {
            try {
                Session session = new Session(Session.findMsaInfo(infoArg), source);
                new MainWindow(session, new Stage()).show();
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Cannot start: " + e.getMessage(), ButtonType.OK);
                alert.setHeaderText("MSA");
                alert.showAndWait();
                Platform.exit();
            }
        });
    }
}
