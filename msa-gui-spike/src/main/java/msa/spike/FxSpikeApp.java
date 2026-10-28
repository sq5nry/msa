package msa.spike;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import msa.sweep.SweepEngine;
import msa.trace.Markers;
import msa.trace.SweepData;

/**
 * JavaFX trial window: a Canvas redrawn from an AnimationTimer (once per screen frame) when new data
 * arrived. Deliberately not an {@code Application} subclass: the java launcher refuses to start
 * Application subclasses when JavaFX is on the classpath, so {@link FxSpike#main} starts the toolkit
 * with {@code Platform.startup} and calls {@link #show}. Space = halt/continue.
 */
public final class FxSpikeApp {

    private SpikeSetup s;
    private boolean dirty = true;

    /** Same as {@link FxSpike#main}, so starting either class works. */
    public static void main(String[] args) {
        FxSpike.main(args);
    }

    /** Builds the window on the JavaFX thread. */
    public void show(Stage stage, java.util.List<String> args) {
        s = SpikeSetup.create(args);
        s.uiExecutor = Platform::runLater;
        s.store.addChangeListener((cur, i) -> dirty = true);

        Canvas canvas = new Canvas(1100, 650);
        Pane root = new Pane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());
        canvas.widthProperty().addListener(o -> dirty = true);
        canvas.heightProperty().addListener(o -> dirty = true);
        Scene scene = new Scene(root, 1100, 650);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE) {
                if (s.engine.state() == SweepEngine.State.RUNNING) s.engine.halt(); else s.engine.resume();
            }
        });
        stage.setTitle("MSA GUI trial - JavaFX");
        stage.setScene(scene);
        stage.setOnHidden(e -> {
            s.engine.close();
            Platform.exit();
            System.exit(0);
        });
        stage.show();

        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!dirty) return;
                dirty = false;
                draw(canvas);
            }
        }.start();
        s.start();
    }

    private void draw(Canvas c) {
        long t0 = System.nanoTime();
        GraphicsContext g = c.getGraphicsContext2D();
        PlotGeometry geo = new PlotGeometry(c.getWidth(), c.getHeight());
        g.setFill(Color.BLACK);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        g.setFont(Font.font("System", 12));
        g.setStroke(Color.rgb(150, 150, 140));
        g.setLineWidth(1);
        g.setFill(Color.rgb(150, 150, 140));
        for (int i = 0; i <= PlotGeometry.DIVS; i++) {
            double x = Math.floor(PlotGeometry.LEFT + geo.plotW * i / PlotGeometry.DIVS) + 0.5;
            double y = Math.floor(PlotGeometry.TOP + geo.plotH * i / PlotGeometry.DIVS) + 0.5;
            g.strokeLine(x, PlotGeometry.TOP, x, PlotGeometry.TOP + geo.plotH);
            g.strokeLine(PlotGeometry.LEFT, y, PlotGeometry.LEFT + geo.plotW, y);
            g.fillText(String.format("%.0f", PlotGeometry.Y_MAX - i * (PlotGeometry.Y_MAX - PlotGeometry.Y_MIN) / PlotGeometry.DIVS), 8, y + 4);
        }
        SweepData d = s.store.current();
        if (d != null) {
            PlotGeometry.Line l = PlotGeometry.line(d, geo);
            g.setStroke(Color.rgb(255, 200, 90));
            g.beginPath();
            for (int k = 0; k < l.count(); k++) {
                if (k == 0) g.moveTo(l.xy()[0], l.xy()[1]);
                else g.lineTo(l.xy()[2 * k], l.xy()[2 * k + 1]);
            }
            g.stroke();
            Markers.Peaks pk = PlotGeometry.peak(d);
            if (pk != null) {
                double x = geo.x(pk.maxIndex(), d.size()), y = geo.y(pk.max());
                g.setFill(Color.CYAN);
                g.fillOval(x - 3, y - 3, 6, 6);
                g.fillText(String.format("P+ %.3f MHz  %.1f dBm", d.frequency(pk.maxIndex()), pk.max()), x + 6, y - 6);
            }
            g.setFill(Color.LIGHTGRAY);
            g.fillText(String.format("%.3f MHz", d.frequency(0)), PlotGeometry.LEFT, c.getHeight() - 22);
            g.fillText(String.format("%.3f MHz", d.frequency(d.size() - 1)), PlotGeometry.LEFT + geo.plotW - 90, c.getHeight() - 22);
        }
        g.setFill(Color.WHITE);
        g.fillText("JavaFX | " + s.source + " | " + s.engine.state() + " | " + s.stats.summary(), 8, 14);
        g.setFill(s.setupInfo.contains("!!") || s.setupInfo.contains("NO path") ? Color.ORANGE : Color.LIGHTGRAY);
        g.fillText(s.setupInfo, 8, 27);
        s.stats.frame(System.nanoTime() - t0);
    }
}
