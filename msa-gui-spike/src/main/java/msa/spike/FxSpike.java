package msa.spike;

import java.util.List;

import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX trial entry point. Starts the toolkit with {@link Platform#startup} instead of
 * Application.launch: the java launcher refuses to run an Application subclass when JavaFX is on the
 * classpath rather than the module path ("Main method not found ... must extend
 * javafx.application.Application"), and no class here extends Application, so nothing can be started
 * the wrong way.
 */
public final class FxSpike {
    private FxSpike() {}

    public static void main(String[] args) {
        List<String> a = List.of(args);
        Platform.startup(() -> new FxSpikeApp().show(new Stage(), a));
    }
}
