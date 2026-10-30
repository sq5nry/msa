package msa.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import msa.autotest.AutoTest;
import msa.autotest.AutotestContext;
import msa.autotest.AutotestModel.Measurement;
import msa.autotest.AutotestModel.Result;
import msa.autotest.AutotestModel.Setup;
import msa.autotest.AutotestModel.Status;
import msa.autotest.AutotestModel.Trace;
import msa.autotest.AutotestRun;
import msa.autotest.EngineContext;
import msa.autotest.ReferenceStore;
import msa.autotest.Tests;

import static msa.autotest.AutotestModel.num;

/**
 * "Setup &gt; Autotest": self tests the MSA can run on itself, with nothing connected or with the TG looped to
 * the input. The tests drive the main window's sweep engine (so the main graph shows what is measured) and the
 * window blocks the main window while it is open; the user's sweep, markers and demo fixture are restored after
 * a run. Results can be saved as a text report, or kept as this MSA's reference in MSA_Info/Autotest.
 */
final class AutotestWindow {

    /** What the main window does around a run. */
    interface Host {
        /** Before the first test (FX thread). */
        void runStarting();
        /** After the last test or a stop (FX thread): put the user's sweep back. */
        void runFinished();
        void message(String text);
        /** The user confirmed this wiring (worker thread; the demo connects its simulated cable accordingly). */
        default void wiring(Setup s) {}
    }

    private final Stage stage = new Stage();
    private final Session session;
    private final Host host;
    private ReferenceStore reference;

    private final Map<AutoTest, CheckBox> boxes = new LinkedHashMap<>();
    private final Map<AutoTest, Label> metas = new LinkedHashMap<>();
    private final CheckBox inputSwitch = new CheckBox("MSA has an SA/VNA input switch");
    private final Button run = new Button("Run"), stop = new Button("Stop"), saveReport = new Button("Save Report..."),
            saveReference = new Button("Save as Reference"), close = new Button("Close"),
            all = new Button("All"), none = new Button("None");
    private final Label progress = new Label("Select the tests and click Run."), referenceInfo = new Label(),
            overall = new Label();
    private final ObservableList<Result> results = FXCollections.observableArrayList();
    private final TableView<Result> resultTable = new TableView<>(results);
    private final TableView<Measurement> measurementTable = new TableView<>();
    private final ComboBox<Trace> traceBox = new ComboBox<>();
    private final TraceChart chart = new TraceChart();
    private final TextArea details = new TextArea();

    private volatile EngineContext context;
    private Thread worker;
    private String runStartedAt = "";

    AutotestWindow(Window owner, Session session, Host host) {
        this.session = session;
        this.host = host;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);   // the tests own the sweep engine while this is open
        stage.setTitle("Autotest");
        loadReference();

        stage.setScene(new Scene(layout(), 1100, 760));
        stage.setOnCloseRequest(e -> {
            if (worker != null) {
                e.consume();
                stopRun();
            }
        });
        updateButtons();
    }

    void show() { stage.show(); }

    // ================================================================ layout

    private Region layout() {
        VBox list = new VBox(4);
        inputSwitch.setSelected(loadInputSwitch());
        inputSwitch.setWrapText(true);
        inputSwitch.setTooltip(new Tooltip("The MSA has an SA/VNA input switch with separate T and R inputs, the T/R"
                + " selection driven by the control board's transmit/reflect line (Wetterlin's MSA Input Switch)."
                + " Adds the switch tests; remembered in MSA_Info/Autotest."));
        inputSwitch.setOnAction(e -> {
            saveInputSwitch(inputSwitch.isSelected());
            boxes.forEach((t, b) -> {
                boolean ok = t.notApplicable(session.config(), inputSwitch.isSelected()) == null;
                if (ok && b.isDisabled()) b.setSelected(Tests.defaultSelected(t));
                if (!ok) b.setSelected(false);
            });
            refreshMeta();
            updateButtons();
        });
        for (AutoTest t : Tests.all()) {
            String na = t.notApplicable(session.config(), inputSwitch.isSelected());
            CheckBox b = new CheckBox(t.title());
            b.setSelected(na == null && Tests.defaultSelected(t));
            b.setDisable(na != null);
            Label meta = new Label();
            metas.put(t, meta);
            meta.setStyle("-fx-text-fill: #666; -fx-font-size: 0.9em;");
            Tooltip tip = new Tooltip(t.description());
            tip.setWrapText(true);
            tip.setMaxWidth(480);
            b.setTooltip(tip);
            meta.setTooltip(tip);
            VBox item = new VBox(0, b, meta);
            meta.setPadding(new Insets(0, 0, 0, 22));
            list.getChildren().add(item);
            boxes.put(t, b);
            b.selectedProperty().addListener((o, a, v) -> updateButtons());
        }
        refreshMeta();
        ScrollPane listScroll = new ScrollPane(list);
        listScroll.setFitToWidth(true);
        listScroll.setPrefViewportWidth(300);
        all.setOnAction(e -> boxes.values().forEach(b -> { if (!b.isDisabled()) b.setSelected(true); }));
        none.setOnAction(e -> boxes.values().forEach(b -> b.setSelected(false)));
        Label hover = new Label("Hover a test for what it checks.");
        hover.setStyle("-fx-text-fill: #666;");
        referenceInfo.setWrapText(true);
        referenceInfo.setMinHeight(Region.USE_PREF_SIZE);
        VBox left = new VBox(8, new Label("Tests"), listScroll, new HBox(6, all, none), inputSwitch, hover, referenceInfo);
        VBox.setVgrow(listScroll, Priority.ALWAYS);
        left.setPadding(new Insets(10));
        left.setMinWidth(260);

        // results
        TableColumn<Result, Status> rs = new TableColumn<>("Result");
        rs.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().status()));
        rs.setCellFactory(c -> statusCell());
        rs.setPrefWidth(70);
        TableColumn<Result, String> rt = new TableColumn<>("Test");
        rt.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().title()));
        rt.setPrefWidth(280);
        TableColumn<Result, String> rsum = new TableColumn<>("Summary");
        rsum.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().summary()));
        rsum.setPrefWidth(330);
        TableColumn<Result, String> rtime = new TableColumn<>("Time");
        rtime.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().seconds() > 0
                ? String.format(Locale.ROOT, "%.1f s", c.getValue().seconds()) : ""));
        rtime.setPrefWidth(60);
        resultTable.getColumns().addAll(List.of(rs, rt, rsum, rtime));
        resultTable.setPlaceholder(new Label("No results yet."));
        resultTable.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> showResult(r));

        TableColumn<Measurement, Status> ms = new TableColumn<>("");
        ms.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().status()));
        ms.setCellFactory(c -> statusCell());
        ms.setPrefWidth(55);
        TableColumn<Measurement, String> mn = new TableColumn<>("Measurement");
        mn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        mn.setPrefWidth(250);
        TableColumn<Measurement, String> mv = new TableColumn<>("Value");
        mv.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().valueText()));
        mv.setPrefWidth(100);
        TableColumn<Measurement, String> ml = new TableColumn<>("Expected");
        ml.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().limits().text(c.getValue().unit())));
        ml.setPrefWidth(130);
        TableColumn<Measurement, String> mref = new TableColumn<>("Reference");
        mref.setCellValueFactory(c -> new ReadOnlyStringWrapper(Double.isNaN(c.getValue().reference()) ? ""
                : num(c.getValue().reference()) + (c.getValue().unit().isEmpty() ? "" : " " + c.getValue().unit())));
        mref.setPrefWidth(90);
        TableColumn<Measurement, String> mnote = new TableColumn<>("Note");
        mnote.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().note() == null ? "" : c.getValue().note()));
        mnote.setPrefWidth(300);
        measurementTable.getColumns().addAll(List.of(ms, mn, mv, ml, mref, mnote));
        measurementTable.setPlaceholder(new Label("Select a result above."));

        traceBox.setCellFactory(c -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(Trace t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.title());
            }
        });
        traceBox.setButtonCell(traceBox.getCellFactory().call(null));
        traceBox.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> chart.show(t));
        traceBox.setMaxWidth(Double.MAX_VALUE);
        Label legend = new Label("blue: measured   grey dashed: reference   red: limits");
        legend.setStyle("-fx-text-fill: #666;");
        HBox traceBar = new HBox(10, new Label("Curve"), traceBox, legend);
        traceBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(traceBox, Priority.ALWAYS);
        Pane chartPane = new Pane(chart);
        chart.widthProperty().bind(chartPane.widthProperty());
        chart.heightProperty().bind(chartPane.heightProperty());
        chart.widthProperty().addListener(o -> chart.redraw());
        chart.heightProperty().addListener(o -> chart.redraw());
        chartPane.setMinHeight(160);
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(4);

        VBox curve = new VBox(6, traceBar, chartPane);
        VBox.setVgrow(chartPane, Priority.ALWAYS);
        SplitPane lower = new SplitPane(measurementTable, curve, details);
        lower.setOrientation(Orientation.VERTICAL);
        lower.setDividerPositions(0.36, 0.84);
        SplitPane right = new SplitPane(resultTable, lower);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.3);

        overall.setStyle("-fx-font-weight: bold;");
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        run.setDefaultButton(true);
        run.setOnAction(e -> startRun());
        stop.setOnAction(e -> stopRun());
        saveReport.setOnAction(e -> saveReport());
        saveReference.setOnAction(e -> saveReference());
        close.setOnAction(e -> { if (worker == null) stage.close(); });
        HBox bottom = new HBox(8, run, stop, overall, progress, saveReport, saveReference, close);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 10, 10, 10));

        SplitPane main = new SplitPane(left, right);
        main.setDividerPositions(0.27);
        SplitPane.setResizableWithParent(left, false);
        BorderPane root = new BorderPane(main);
        root.setBottom(bottom);
        return root;
    }

    private void refreshMeta() {
        metas.forEach((t, meta) -> {
            String na = t.notApplicable(session.config(), inputSwitch.isSelected());
            String wiring = switch (t.setup()) {
                case NONE -> "nothing connected";
                case TG_LOOP -> "TG looped to input";
                case TG_REFLECT -> "TG to R input";
            };
            meta.setText(wiring + ", about " + duration(t.seconds()) + (na != null ? " - " + na : ""));
        });
    }

    private Path optionsFile() {
        return session.msaInfo() == null ? null : session.msaInfo().resolve("Autotest").resolve("autotest.properties");
    }

    private boolean loadInputSwitch() {
        Path f = optionsFile();
        if (f == null || !Files.exists(f)) return false;
        java.util.Properties p = new java.util.Properties();
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(p.getProperty("inputSwitch", "false").strip());
    }

    private void saveInputSwitch(boolean on) {
        Path f = optionsFile();
        if (f == null) return;
        java.util.Properties p = new java.util.Properties();
        p.setProperty("inputSwitch", String.valueOf(on));
        try {
            Files.createDirectories(f.getParent());
            try (var w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "Autotest options");
            }
        } catch (IOException e) {
            host.message("Could not save " + f + ": " + e.getMessage());
        }
    }

    private static <S> TableCell<S, Status> statusCell() {
        return new TableCell<>() {
            @Override protected void updateItem(Status s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.name());
                setStyle(empty || s == null ? "" : "-fx-font-weight: bold; -fx-text-fill: " + color(s) + ";");
            }
        };
    }

    static String color(Status s) {
        return switch (s) {
            case PASS -> "#1a7f37";
            case INFO -> "#3060a0";
            case WARN -> "#b26a00";
            case FAIL, ERROR -> "#c62828";
            case SKIP -> "#777777";
        };
    }

    private static String duration(int seconds) {
        return seconds < 60 ? seconds + " s" : (seconds + 30) / 60 + " min";
    }

    private void showResult(Result r) {
        measurementTable.getItems().setAll(r == null ? List.of() : r.measurements());
        traceBox.getItems().setAll(r == null ? List.of() : r.traces());
        if (!traceBox.getItems().isEmpty()) traceBox.getSelectionModel().select(0);
        else chart.show(null);
        traceBox.setDisable(traceBox.getItems().isEmpty());
        if (r == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(r.summary());
        for (String h : r.hints()) sb.append("\n- ").append(h);
        AutoTest t = Tests.all().stream().filter(x -> x.id().equals(r.id())).findFirst().orElse(null);
        if (t != null) sb.append("\n\n").append(t.description());
        details.setText(sb.toString());
    }

    private void loadReference() {
        Path folder = session.msaInfo() == null ? null : session.msaInfo().resolve("Autotest");
        try {
            reference = folder == null ? ReferenceStore.empty() : ReferenceStore.load(folder);
        } catch (IOException e) {
            reference = new ReferenceStore(folder);
            host.message("Autotest reference not readable: " + e.getMessage());
        }
        referenceInfo.setText(reference.isEmpty()
                ? "No reference saved for this MSA yet. Levels are judged against design values only; once the MSA"
                + " checks out, click Save as Reference so later runs show any change."
                : "Reference of this MSA: saved " + (reference.savedAt().isEmpty() ? "(date unknown)" : reference.savedAt())
                + ". Values that moved since then are marked WARN or FAIL.");
    }

    private void updateButtons() {
        boolean running = worker != null;
        boolean any = boxes.values().stream().anyMatch(b -> b.isSelected() && !b.isDisabled());
        run.setDisable(running || !any);
        stop.setDisable(!running);
        close.setDisable(running);
        boolean have = !results.isEmpty() && !running;
        saveReport.setDisable(!have);
        saveReference.setDisable(!have || reference.folder() == null);
        all.setDisable(running);
        none.setDisable(running);
        boxes.forEach((t, b) -> b.setDisable(running || t.notApplicable(session.config(), inputSwitch.isSelected()) != null));
        inputSwitch.setDisable(running);
    }

    // ================================================================ run

    private void startRun() {
        List<AutoTest> tests = new ArrayList<>();
        boxes.forEach((t, b) -> { if (b.isSelected() && !b.isDisabled()) tests.add(t); });
        if (tests.isEmpty()) return;
        results.clear();
        showResult(null);
        overall.setText("");
        runStartedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT));
        EngineContext ctx = new EngineContext(session.engine(), session.config(), session::hasCalibration, reference,
                text -> Platform.runLater(() -> progress.setText(text)));
        ctx.setInputSwitch(inputSwitch.isSelected());
        context = ctx;
        host.runStarting();
        worker = new Thread(() -> {
            String end;
            try {
                List<Result> rs = AutotestRun.run(tests, ctx, this::confirmSetup,
                        r -> Platform.runLater(() -> {
                            results.add(r);
                            if (results.size() == 1) resultTable.getSelectionModel().select(0);
                        }));
                end = rs.size() < tests.size() ? "Stopped after " + rs.size() + " of " + tests.size() + " test(s)."
                        : "Done: " + rs.size() + " test(s).";
            } catch (RuntimeException e) {
                end = "Run failed: " + e.getMessage();
            } finally {
                ctx.detach();
            }
            String text = end;
            Platform.runLater(() -> finished(text));
        }, "autotest");
        worker.setDaemon(true);
        updateButtons();
        progress.setText("Starting...");
        worker.start();
    }

    private void finished(String text) {
        worker = null;
        context = null;
        host.runFinished();
        progress.setText(text);
        Status worst = results.stream().map(Result::status).reduce(Status.SKIP, Status::worst);
        if (!results.isEmpty()) {
            overall.setText(worst.name());
            overall.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color(worst) + ";");
        }
        updateButtons();
    }

    private void stopRun() {
        EngineContext c = context;
        if (c != null) {
            c.cancel();
            progress.setText("Stopping...");
        }
    }

    /** Worker thread: asks on the FX thread, waits for the answer. */
    private boolean confirmSetup(Setup s) {
        CompletableFuture<ButtonType> answer = new CompletableFuture<>();
        ButtonType go = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType skip = new ButtonType("Skip these tests", ButtonBar.ButtonData.NO);
        ButtonType halt = new ButtonType("Stop", ButtonBar.ButtonData.CANCEL_CLOSE);
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", go, skip, halt);
            a.initOwner(stage);
            a.setTitle("Autotest");
            boolean sw = inputSwitch.isSelected();
            switch (s) {
                case NONE -> {
                    a.setHeaderText("Disconnect the MSA input");
                    a.setContentText("Remove any cable from the MSA input. A 50 ohm termination may stay on."
                            + (sw ? "\n\nInput switch: set it to SA and leave the SA input open or terminated." : ""));
                }
                case TG_LOOP -> {
                    a.setHeaderText(sw ? "Connect the tracking generator to the T input" : "Connect the tracking generator to the input");
                    a.setContentText("Connect the TG output to the MSA input with a short coax cable (the one used for the"
                            + " path calibrations, 1-2 feet). No attenuator."
                            + (sw ? "\n\nInput switch: set it to VNA and connect the TG to the transmission (T) input; leave"
                            + " the R input open or terminated. The T/R line follows the mode of each test. The switch's"
                            + " buffer amplifier and attenuators are part of the loop, so levels and the phase curve are"
                            + " judged best against this MSA's saved reference." : ""));
                }
                case TG_REFLECT -> {
                    a.setHeaderText("Move the TG cable to the R input");
                    a.setContentText("Input switch in the VNA position. Connect the TG output to the reflection (R) input"
                            + " with the same short cable; leave the T input open or terminated.");
                }
            }
            a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            answer.complete(a.showAndWait().orElse(halt));
        });
        ButtonType b;
        try {
            b = answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AutotestContext.Cancelled();
        } catch (ExecutionException e) {
            throw new AutotestContext.Cancelled();
        }
        if (b == halt) throw new AutotestContext.Cancelled();
        if (b == go) host.wiring(s);
        return b == go;
    }

    // ================================================================ saving

    private List<String> header() {
        List<String> h = new ArrayList<>();
        h.add("MSA autotest " + runStartedAt + ", " + session.source().label());
        h.add("MSA_Info: " + (session.msaInfo() == null ? "-" : session.msaInfo().toAbsolutePath()));
        h.add("Reference: " + (reference.isEmpty() ? "none" : "saved " + reference.savedAt()));
        return h;
    }

    private void saveReport() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Autotest Report");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text", "*.txt"));
        fc.setInitialFileName("autotest-" + runStartedAt.replace(' ', '_').replace(":", "") + ".txt");
        if (session.msaInfo() != null) {
            Path f = session.msaInfo().resolve("Autotest");
            if (Files.isDirectory(f)) fc.setInitialDirectory(f.toFile());
            else if (Files.isDirectory(session.msaInfo())) fc.setInitialDirectory(session.msaInfo().toFile());
        }
        java.io.File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), AutotestRun.report(List.copyOf(results), header()), StandardCharsets.UTF_8);
            progress.setText("Report saved: " + f.getName());
        } catch (IOException e) {
            progress.setText("Could not save the report: " + e.getMessage());
        }
    }

    private void saveReference() {
        long bad = results.stream().filter(r -> r.status() == Status.FAIL || r.status() == Status.ERROR).count();
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "The values and curves of these results become the reference of this MSA; later runs are compared"
                + " with them. Tests not in this run keep their earlier reference.\n\nDo this only when the MSA works"
                + " properly." + (bad > 0 ? "\n\n" + bad + " test(s) of this run FAILED or had an ERROR." : ""),
                ButtonType.OK, ButtonType.CANCEL);
        a.initOwner(stage);
        a.setTitle("Save as Reference");
        a.setHeaderText("Make these results the reference?");
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            reference.update(List.copyOf(results), AutotestRun.curves(List.copyOf(results)));
            loadReference();
            progress.setText("Reference saved in " + reference.folder());
        } catch (IOException e) {
            progress.setText("Could not save the reference: " + e.getMessage());
        }
    }

    // ================================================================ chart

    /** One curve with its reference and limit curves; autoscaled, nothing interactive. */
    static final class TraceChart extends Canvas {
        private Trace trace;

        void show(Trace t) {
            trace = t;
            redraw();
        }

        @Override public boolean isResizable() { return true; }

        void redraw() {
            GraphicsContext g = getGraphicsContext2D();
            double w = getWidth(), h = getHeight();
            g.setFill(Color.WHITE);
            g.fillRect(0, 0, w, h);
            if (trace == null || trace.x().length == 0) {
                g.setFill(Color.GRAY);
                g.fillText("No curve for this result.", 20, 24);
                return;
            }
            double left = 62, right = 12, top = 10, bottom = 34;
            double pw = w - left - right, ph = h - top - bottom;
            if (pw < 20 || ph < 20) return;
            double[] x = trace.x();
            double xmin = finiteMin(x), xmax = finiteMax(x);
            double ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
            for (double[] c : new double[][] {trace.y(), trace.reference(), trace.low(), trace.high()}) {
                if (c == null) continue;
                ymin = Math.min(ymin, finiteMin(c));
                ymax = Math.max(ymax, finiteMax(c));
            }
            if (!(ymin <= ymax)) { ymin = 0; ymax = 1; }
            if (ymax - ymin < 1e-9) { ymin -= 1; ymax += 1; }
            double padY = (ymax - ymin) * 0.06;
            ymin -= padY;
            ymax += padY;
            if (xmax - xmin < 1e-12) { xmin -= 1; xmax += 1; }
            double[] xt = ticks(xmin, xmax, Math.max(3, (int) (pw / 90))), yt = ticks(ymin, ymax, Math.max(3, (int) (ph / 40)));
            final double fxmin = xmin, fxmax = xmax, fymin = ymin, fymax = ymax;
            java.util.function.DoubleUnaryOperator px = v -> left + (v - fxmin) / (fxmax - fxmin) * pw;
            java.util.function.DoubleUnaryOperator py = v -> top + (fymax - v) / (fymax - fymin) * ph;

            g.setFont(Font.font(11));
            g.setLineWidth(1);
            g.setLineDashes();
            for (double t : xt) {
                double p = px.applyAsDouble(t);
                g.setStroke(Color.rgb(225, 225, 225));
                g.strokeLine(p, top, p, top + ph);
                g.setFill(Color.rgb(80, 80, 80));
                String s = num(t);
                g.fillText(s, p - s.length() * 3, top + ph + 14);
            }
            for (double t : yt) {
                double p = py.applyAsDouble(t);
                g.setStroke(Color.rgb(225, 225, 225));
                g.strokeLine(left, p, left + pw, p);
                g.setFill(Color.rgb(80, 80, 80));
                String s = num(t);
                g.fillText(s, left - 8 - s.length() * 6, p + 4);
            }
            g.setStroke(Color.rgb(150, 150, 150));
            g.strokeRect(left, top, pw, ph);
            g.setFill(Color.rgb(80, 80, 80));
            g.fillText(trace.xLabel(), left + pw / 2 - trace.xLabel().length() * 3, h - 4);
            g.fillText(trace.yUnit(), 4, top + 10);

            g.save();
            g.beginPath();
            g.rect(left, top, pw, ph);
            g.clip();
            line(g, x, trace.low(), px, py, Color.rgb(210, 40, 40), 1.5, false);
            line(g, x, trace.high(), px, py, Color.rgb(210, 40, 40), 1.5, false);
            line(g, x, trace.reference(), px, py, Color.rgb(130, 130, 130), 1.5, true);
            line(g, x, trace.y(), px, py, Color.rgb(30, 90, 200), 1.6, false);
            g.restore();
        }

        private static void line(GraphicsContext g, double[] x, double[] y, java.util.function.DoubleUnaryOperator px,
                                 java.util.function.DoubleUnaryOperator py, Color c, double width, boolean dashed) {
            if (y == null) return;
            g.setStroke(c);
            g.setLineWidth(width);
            if (dashed) g.setLineDashes(6, 4);
            else g.setLineDashes();
            boolean open = false;
            int n = Math.min(x.length, y.length);
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(y[i]) || !Double.isFinite(x[i])) {
                    if (open) g.stroke();
                    open = false;
                    continue;
                }
                double a = px.applyAsDouble(x[i]), b = py.applyAsDouble(y[i]);
                if (!open) {
                    g.beginPath();
                    g.moveTo(a, b);
                    open = true;
                } else {
                    g.lineTo(a, b);
                }
            }
            if (open) g.stroke();
            g.setLineDashes();
        }

        private static double finiteMin(double[] v) {
            double m = Double.POSITIVE_INFINITY;
            for (double d : v) if (Double.isFinite(d)) m = Math.min(m, d);
            return m;
        }

        private static double finiteMax(double[] v) {
            double m = Double.NEGATIVE_INFINITY;
            for (double d : v) if (Double.isFinite(d)) m = Math.max(m, d);
            return m;
        }

        /** Round tick values (1, 2, 5 x 10^n) inside [lo, hi]. */
        static double[] ticks(double lo, double hi, int wanted) {
            double raw = (hi - lo) / wanted, mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double step = raw / mag < 1.5 ? mag : raw / mag < 3.5 ? 2 * mag : raw / mag < 7.5 ? 5 * mag : 10 * mag;
            List<Double> out = new ArrayList<>();
            for (double t = Math.ceil(lo / step) * step; t <= hi + step * 1e-9; t += step)
                out.add(Math.abs(t) < step * 1e-9 ? 0 : t);
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }
}
