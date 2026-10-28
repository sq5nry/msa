package msa.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import msa.model.DisplaySettings;
import msa.model.DisplaySettings.AxisRange;
import msa.model.MsaSettings;
import msa.sweep.SweepSettings;
import msa.synth.SynthPlanner;
import msa.trace.GraphData;
import msa.trace.Markers;
import msa.trace.SweepData;
import msa.trace.TraceStore;

/**
 * The graph window's drawing area (BASIC graphicbox #handle.g): grid, both Y axes, the traces in their
 * display modes, reference lines, markers, the marker table and the setup info column, plus the mouse
 * actions (query, marker by double-click or key, double-click on an axis to open its dialog).
 * Redraws are coalesced to one per frame.
 */
public final class GraphView extends Region {

    /** What the window does in response to the graph. */
    public interface Actions {
        void openAxisDialog(int axis);
        void openSweepParameters();
        /** A marker was added or moved with the mouse or keyboard. */
        void markersChanged();
        /** Text for the status line (mouse query), or "" to clear it. */
        void message(String text);
    }

    private static final Font FONT = Font.font("System", 11);
    private static final Font FONT_BOLD = Font.font("System", FontWeight.BOLD, 11);
    private static final Color MARKER = Color.rgb(255, 255, 255);

    private final Canvas canvas = new Canvas();
    private final Session session;
    private final GraphState state;
    private final Actions actions;
    private final Supplier<String> statusLine;
    private boolean dirty = true;
    private Runnable beforeDraw = () -> {};
    private double hoverIndex = Double.NaN;

    public GraphView(Session session, GraphState state, Actions actions, Supplier<String> statusLine) {
        this.session = session;
        this.state = state;
        this.actions = actions;
        this.statusLine = statusLine;
        getChildren().add(canvas);
        setMinSize(400, 300);
        setFocusTraversable(true);
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::mousePressed);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> endQuery());
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::mouseMoved);
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hoverIndex = Double.NaN);
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, this::mouseClicked);
        addEventHandler(KeyEvent.KEY_TYPED, this::keyTyped);
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (dirty) {
                    dirty = false;
                    beforeDraw.run();
                    draw();
                }
            }
        }.start();
    }

    /** Run once per frame before drawing, when a redraw is due (the window updates markers here). */
    public void setBeforeDraw(Runnable r) { beforeDraw = r; }

    /** Requests a redraw at the next frame. */
    public void invalidate() { dirty = true; }

    public Canvas canvas() { return canvas; }

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            dirty = true;
        }
    }

    // ================================================================ mouse and keys

    private int points() {
        SweepData d = state.shown();
        return d == null ? session.sweep().points() : d.size();
    }

    private PlotLayout plotLayout() { return PlotLayout.of(canvas.getWidth(), canvas.getHeight()); }

    private void mousePressed(MouseEvent e) {
        requestFocus();
        if (e.getButton() == MouseButton.PRIMARY && plotLayout().inPlot(e.getX(), e.getY())) query(e.getX());
    }

    private void mouseDragged(MouseEvent e) {
        if (!e.isPrimaryButtonDown()) return;
        if (plotLayout().inPlot(e.getX(), e.getY())) query(e.getX()); else endQuery();
    }

    private void mouseMoved(MouseEvent e) {
        PlotLayout l = plotLayout();
        hoverIndex = l.inPlot(e.getX(), e.getY()) ? Math.round(l.index(e.getX(), points())) : Double.NaN;
    }

    /** [gMouseQuery]: whole step under the mouse, its frequency and the values of both axes. */
    private void query(double px) {
        SweepData d = state.shown();
        if (d == null) return;
        double i = Math.round(plotLayout().index(px, d.size()));
        state.setQueryIndex(i);
        hoverIndex = i;
        DisplaySettings ds = session.display();
        GraphData g1 = GraphData.ofCode(ds.y1Data()), g2 = GraphData.ofCode(ds.y2Data());
        StringBuilder sb = new StringBuilder(Fmt.mhz(d.frequency(i)) + " MHz");
        for (int axis : new int[] {2, 1}) {
            GraphData g = axis == 1 ? g1 : g2;
            if (g != GraphData.NONE)
                sb.append("    ").append(g.unit).append('=').append(Fmt.value(g, state.value(d, g, axis, i)));
        }
        actions.message(sb.toString());
        invalidate();
    }

    private void endQuery() {
        if (Double.isNaN(state.queryIndex())) return;
        state.setQueryIndex(Double.NaN);
        actions.message("");
        invalidate();
    }

    private void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) return;
        PlotLayout l = plotLayout();
        double x = e.getX(), y = e.getY();
        if (l.inPlot(x, y)) {
            if (state.shown() == null) return;
            state.placeAtClick(l.index(x, points()), points());
            actions.markersChanged();
            invalidate();
        } else if (l.onY1Labels(x, y)) {
            actions.openAxisDialog(1);
        } else if (l.onY2Labels(x, y)) {
            actions.openAxisDialog(2);
        } else if (l.onSweepArea(x, y)) {
            actions.openSweepParameters();
        }
    }

    /** [mAddMarkerFromKeyboard]: L, R, 1..6 put that marker at the step under the mouse. */
    private void keyTyped(KeyEvent e) {
        if (Double.isNaN(hoverIndex) || state.shown() == null) return;
        double keep = state.queryIndex();
        state.setQueryIndex(hoverIndex);
        boolean placed = state.placeFromKey(e.getCharacter(), points());
        state.setQueryIndex(keep);
        if (placed) {
            actions.markersChanged();
            invalidate();
        }
    }

    // ================================================================ drawing

    private record Colors(Color bg, Color grid, Color text, Color t1, Color t2) {}

    private static Color fx(DisplaySettings.Rgb c) { return Color.rgb(c.r(), c.g(), c.b()); }

    private Colors colors(DisplaySettings ds) {
        Color bg = fx(ds.background());
        Color text = bg.getBrightness() < 0.5 ? Color.rgb(225, 225, 225) : Color.rgb(20, 20, 20);
        return new Colors(bg, fx(ds.gridLine()), text, fx(ds.trace1()), fx(ds.trace2()));
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 10 || h < 10) return;
        MsaSettings ms = session.settings();
        DisplaySettings ds = ms.display();
        SweepSettings ss = ms.sweep();
        Colors c = colors(ds);
        PlotLayout l = PlotLayout.of(w, h);
        GraphData g1 = GraphData.ofCode(ds.y1Data()), g2 = GraphData.ofCode(ds.y2Data());

        g.setFill(c.bg);
        g.fillRect(0, 0, w, h);
        g.setFont(FONT);
        g.setLineCap(StrokeLineCap.BUTT);

        drawGrid(g, l, ds, c);
        SweepData shown = state.shown();
        int points = shown == null ? ss.points() : shown.size();
        drawXLabels(g, l, ss, shown, ds, c);
        if (g1 != GraphData.NONE) drawYLabels(g, l, 1, g1, ds.y1Axis(), ds.gridDivY(), c.t1);
        if (g2 != GraphData.NONE) drawYLabels(g, l, 2, g2, ds.y2Axis(), ds.gridDivY(), c.t2);

        g.save();
        g.beginPath();
        g.rect(l.left(), l.top(), l.plotWidth(), l.plotHeight());
        g.clip();
        if (shown != null) {
            drawReference(g, l, shown, 1, g1, ds.y1Axis(), c.t1);
            drawReference(g, l, shown, 2, g2, ds.y2Axis(), c.t2);
            drawAxisTraces(g, l, shown, 1, g1, ds.y1Axis(), ds.y1Mode(), c.t1);
            drawAxisTraces(g, l, shown, 2, g2, ds.y2Axis(), ds.y2Mode(), c.t2);
            if (state.showMarkers()) drawMarkers(g, l, shown, primary(ds), ds);
            drawQuery(g, l, shown, ds, c);
        }
        g.restore();

        drawTopLine(g, l, c, g1 == GraphData.NONE ? "" : g1.axisTitle);
        drawSetupInfo(g, l, ss, c);
        if (shown != null) drawMarkerTable(g, l, shown, ds, c);
        if (shown == null || points == 0) {
            g.setFill(c.text);
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText("waiting for the first sweep...", (l.left() + l.right()) / 2, (l.top() + l.bottom()) / 2);
            g.setTextAlign(TextAlignment.LEFT);
        }
    }

    /** BASIC primaryAxisNum: Y2 unless only Y1 has data. */
    static int primary(DisplaySettings ds) {
        return ds.y2Data() == DisplaySettings.DATA_NONE && ds.y1Data() != DisplaySettings.DATA_NONE ? 1 : 2;
    }

    private static void drawGrid(GraphicsContext g, PlotLayout l, DisplaySettings ds, Colors c) {
        g.setStroke(c.grid);
        g.setLineWidth(1);
        for (int i = 0; i <= ds.gridDivX(); i++) {
            double x = snap(l.left() + l.plotWidth() * i / ds.gridDivX());
            g.strokeLine(x, l.top(), x, l.bottom());
        }
        for (int i = 0; i <= ds.gridDivY(); i++) {
            double y = snap(l.top() + l.plotHeight() * i / ds.gridDivY());
            g.strokeLine(l.left(), y, l.right(), y);
        }
    }

    private static double snap(double v) { return Math.floor(v) + 0.5; }

    private static void drawXLabels(GraphicsContext g, PlotLayout l, SweepSettings ss, SweepData d, DisplaySettings ds, Colors c) {
        g.setFill(c.text);
        g.setTextAlign(TextAlignment.CENTER);
        int div = ds.gridDivX();
        double start = d == null ? ss.startMHz() : d.frequency(0);
        double stop = d == null ? ss.stopMHz() : d.frequency(d.size() - 1);
        double step = (stop - start) / div;
        for (int i = 0; i <= div; i++) {
            double t = (double) i / div;
            double f = d == null || d.size() < 2 ? start + t * (stop - start) : d.frequency(t * (d.size() - 1));
            double x = l.left() + l.plotWidth() * t;
            // log axis: label spacing grows with frequency, so the decimals follow the local spacing
            double local = ss.scale() == SweepSettings.Scale.LOG && start > 0 && stop > 0
                    ? Math.abs(f) * Math.abs(1 - Math.pow(start / stop, 1.0 / div)) : Math.abs(step);
            g.fillText(Fmt.freqAxis(f, local == 0 ? 1e-6 : local), x, l.bottom() + 14);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("MHz", l.right() + 30, l.bottom() + 14);
    }

    private static void drawYLabels(GraphicsContext g, PlotLayout l, int axis, GraphData gd, AxisRange a, int div, Color col) {
        g.setFill(col);
        g.setTextAlign(axis == 1 ? TextAlignment.RIGHT : TextAlignment.LEFT);
        double xText = axis == 1 ? l.left() - 5 : l.right() + 5;
        double step = (a.max() - a.min()) / div;
        for (int i = 0; i <= div; i++) {
            double v = a.max() - i * step;
            double y = l.top() + l.plotHeight() * i / div;
            g.fillText(Fmt.axis(gd, v, Math.abs(step)), xText, y + 4);
        }
        g.setFont(FONT_BOLD);
        if (axis == 1) {                     // left title: from the window edge, so a long one is not cut off
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(gd.axisTitle, 4, l.top() - 8);
        } else {
            g.fillText(gd.axisTitle, xText, l.top() - 8);
        }
        g.setFont(FONT);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawAxisTraces(GraphicsContext g, PlotLayout l, SweepData shown, int axis, GraphData gd, AxisRange a,
                                int modeCode, Color col) {
        TraceStore.DisplayMode mode = TraceStore.DisplayMode.ofCode(modeCode);
        if (gd == GraphData.NONE || mode == TraceStore.DisplayMode.OFF) return;
        double width = Math.max(1, state.traceWidth(axis));
        if (mode.stick()) {
            List<SweepData> all = new ArrayList<>();
            for (SweepData d : state.store.history())
                if (d.number() >= state.stickFrom() && d.size() == shown.size()) all.add(d);
            SweepData cur = state.store.current();
            if (cur != null && !cur.complete()) all.add(cur);
            for (SweepData d : all) drawTrace(g, l, d, axis, gd, a, mode.histogram(), col, width);
        } else {
            drawTrace(g, l, shown, axis, gd, a, mode.histogram(), col, width);
        }
    }

    private void drawTrace(GraphicsContext g, PlotLayout l, SweepData d, int axis, GraphData gd, AxisRange a,
                           boolean histogram, Color col, double width) {
        int n = d.size();
        g.setStroke(col);
        g.setLineWidth(width);
        if (n > 2 * l.plotWidth()) {
            drawDense(g, l, d, axis, gd, a, histogram);
            return;
        }
        if (histogram) {
            for (int i = 0; i < n; i++) {
                double v = state.value(d, gd, axis, i);
                if (Double.isNaN(v)) continue;
                double x = l.x(i, n);
                g.strokeLine(x, l.bottom(), x, l.y(v, a));
            }
            return;
        }
        boolean open = false, angle = gd.isAngle();
        double prev = Double.NaN;
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double v = state.value(d, gd, axis, i);
            if (Double.isNaN(v)) {
                open = false;
                continue;
            }
            double x = l.x(i, n), y = l.y(v, a);
            // a phase wrapping from +180 to -180 is not a step across the graph
            boolean wrap = angle && Math.abs(v - prev) > 180;
            if (open && !wrap) g.lineTo(x, y); else g.moveTo(x, y);
            open = true;
            prev = v;
        }
        g.stroke();
    }

    /**
     * More points than pixels: one vertical stroke per pixel column from the lowest to the highest point in
     * it, joined to the neighbouring columns where the points are. This is exactly what the full line would
     * cover (it runs through every point, so it spans min..max in each column), drawn with about 4 points
     * per column instead of thousands, so a 64,000-point sweep draws as fast as a 1,000-point one.
     * Histogram: one bar per column up to the highest point.
     */
    private void drawDense(GraphicsContext g, PlotLayout l, SweepData d, int axis, GraphData gd, AxisRange a,
                           boolean histogram) {
        int n = d.size();
        int cols = (int) Math.ceil(l.plotWidth()) + 1;
        double[] first = new double[cols], last = new double[cols], top = new double[cols], bottom = new double[cols];
        boolean[] has = new boolean[cols];
        boolean[] gapBefore = new boolean[cols];
        boolean gap = false;
        for (int i = 0; i < n; i++) {
            double v = state.value(d, gd, axis, i);
            int c = (int) Math.max(0, Math.min(cols - 1, Math.floor(l.x(i, n) - l.left())));
            if (Double.isNaN(v)) {
                gap = true;
                continue;
            }
            double y = l.y(v, a);
            if (!has[c]) {
                has[c] = true;
                first[c] = y;
                top[c] = y;
                bottom[c] = y;
                gapBefore[c] = gap;
            } else {
                top[c] = Math.min(top[c], y);
                bottom[c] = Math.max(bottom[c], y);
            }
            last[c] = y;
            gap = false;
        }
        if (histogram) {
            for (int c = 0; c < cols; c++)
                if (has[c]) g.strokeLine(l.left() + c + 0.5, l.bottom(), l.left() + c + 0.5, top[c]);
            return;
        }
        g.beginPath();
        boolean open = false;
        for (int c = 0; c < cols; c++) {
            if (!has[c]) { open = false; continue; }
            double x = l.left() + c + 0.5;
            if (open && !gapBefore[c]) g.lineTo(x, first[c]); else g.moveTo(x, first[c]);
            g.lineTo(x, top[c]);
            g.lineTo(x, bottom[c]);
            g.lineTo(x, last[c]);
            open = true;
        }
        g.stroke();
    }

    private void drawReference(GraphicsContext g, PlotLayout l, SweepData d, int axis, GraphData gd, AxisRange a, Color col) {
        if (gd == GraphData.NONE || Double.isNaN(state.referenceLine(axis, d.frequency(0)))) return;
        g.setStroke(col.deriveColor(0, 0.6, 0.75, 0.9));
        g.setLineWidth(1);
        g.setLineDashes(6, 4);
        g.beginPath();
        int n = d.size();
        for (int i = 0; i < n; i++) {
            double y = l.y(state.referenceLine(axis, d.frequency(i)), a);
            if (i == 0) g.moveTo(l.x(i, n), y); else g.lineTo(l.x(i, n), y);
        }
        g.stroke();
        g.setLineDashes();
    }

    private void drawMarkers(GraphicsContext g, PlotLayout l, SweepData d, int primary, DisplaySettings ds) {
        GraphData gd = GraphData.ofCode(primary == 1 ? ds.y1Data() : ds.y2Data());
        AxisRange a = primary == 1 ? ds.y1Axis() : ds.y2Axis();
        state.markers.positions().forEach((id, index) -> {
            if (Double.isNaN(index)) return;
            double i = Math.min(index, d.size() - 1.0);
            double v = state.value(d, gd, primary, i);
            if (id == Markers.Id.HALT) {
                // where a halted sweep stopped; erased by Continue, Restart or Redraw
                double hx = snap(l.x(i, d.size()));
                g.setStroke(Color.GRAY);
                g.setLineWidth(1);
                g.strokeLine(hx, l.top() + 14, hx, l.bottom());
                g.setFill(Color.GRAY);
                g.setFont(FONT_BOLD);
                g.setTextAlign(TextAlignment.CENTER);
                g.fillText("Halt", hx, l.top() + 11);
                g.setTextAlign(TextAlignment.LEFT);
                g.setFont(FONT);
                return;
            }
            double x = l.x(i, d.size());
            // l.y clips to the plot, so a value above the top of the axis sits on the top edge
            double y = Double.isNaN(v) ? l.bottom() : Math.max(l.top(), Math.min(l.bottom(), l.y(v, a)));
            boolean sel = state.selected().map(s -> s == id).orElse(false);
            g.setFill(sel ? Color.YELLOW : MARKER);
            g.setFont(FONT_BOLD);
            g.setTextAlign(TextAlignment.CENTER);
            if (y < l.top() + 24) {
                // no room above (near or above the top of the axis): triangle points up from below, label to
                // its right so a steep trace through the marker does not cover it
                g.fillPolygon(new double[] {x - 4, x + 4, x}, new double[] {y + 10, y + 10, y + 3}, 3);
                g.setTextAlign(TextAlignment.LEFT);
                g.fillText(id.label, x + 7, y + 12);
            } else {
                g.fillPolygon(new double[] {x - 4, x + 4, x}, new double[] {y - 10, y - 10, y - 3}, 3);
                g.fillText(id.label, x, y - 13);
            }
            g.setTextAlign(TextAlignment.LEFT);
            g.setFont(FONT);
        });
    }

    private void drawQuery(GraphicsContext g, PlotLayout l, SweepData d, DisplaySettings ds, Colors c) {
        double qi = state.queryIndex();
        if (Double.isNaN(qi)) return;
        double x = snap(l.x(qi, d.size()));
        g.setStroke(c.text);
        g.setLineWidth(1);
        g.setLineDashes(3, 3);
        g.strokeLine(x, l.top(), x, l.bottom());
        g.setLineDashes();
        int p = primary(ds);
        GraphData gd = GraphData.ofCode(p == 1 ? ds.y1Data() : ds.y2Data());
        double v = state.value(d, gd, p, qi);
        if (!Double.isNaN(v)) {
            double y = l.y(v, p == 1 ? ds.y1Axis() : ds.y2Axis());
            g.strokeRect(x - 4, y - 4, 8, 8);
        }
    }

    /** Status text above the plot, after the Y1 axis title if that is long. */
    private void drawTopLine(GraphicsContext g, PlotLayout l, Colors c, String y1Title) {
        g.setFill(c.text);
        g.setFont(FONT_BOLD);
        javafx.scene.text.Text t = new javafx.scene.text.Text(y1Title);
        t.setFont(FONT_BOLD);
        double x = Math.max(l.left() + 34, 4 + t.getLayoutBounds().getWidth() + 14);
        g.fillText(statusLine.get(), x, 16);
        g.setFont(FONT);
    }

    /** [DrawSetupInfo]. */
    private void drawSetupInfo(GraphicsContext g, PlotLayout l, SweepSettings s, Colors c) {
        List<String> lines = new ArrayList<>();
        boolean tg = s.generator() instanceof SynthPlanner.Tracking;
        String calLine = null;
        boolean calDowngraded = false;
        switch (s.mode()) {
            case SA -> lines.add(tg ? "SA with TG" : "Spect. Analyzer");
            default -> {
                lines.add(s.mode() == msa.sweep.MsaMode.VECTOR_TRANS ? "VNA Transmission" : "SNA Transmission");
                msa.vna.TransmissionCal.Level applied = session.installedCal(s).level();
                calDowngraded = applied.ordinal() < session.vna().calLevel().ordinal();
                calLine = "Cal=" + switch (applied) { case NONE -> "None"; case BASE -> "Base"; case BAND -> "Band"; };
                lines.add(calLine);
            }
        }
        int bandShown = s.band() != 0 ? s.band() : SynthPlanner.autoBand((s.startMHz() + s.stopMHz()) / 2);
        lines.add((s.band() == 0 ? "Auto " : "") + bandShown + "G, Res Path " + s.path());
        lines.add("RBW=" + fmtK(session.config().filter(s.path()).bwKHz()) + "kHz");
        lines.add("Vid=" + MsaSettings.videoName(s.video(), session.config()));
        lines.add("Wait=" + s.waitMs() + " ms" + (session.autoWait() ? " (auto)" : ""));
        lines.add("Steps=" + s.steps());
        if (s.samples() > 1) lines.add("Samples=" + s.samples() + (session.autoSamples() ? " (auto)" : ""));
        if (s.scale() == SweepSettings.Scale.LINEAR) {
            lines.add(Fmt.hz(1e6 * (s.stopMHz() - s.startMHz()) / s.steps()) + "/Step");
            lines.add(Fmt.hz(1e6 * (s.stopMHz() - s.startMHz()) / session.display().gridDivX()) + "/Div");
        } else {
            lines.add("Log sweep");
        }
        if (session.hasTrackingGenerator() && s.mode() == msa.sweep.MsaMode.SA) {
            switch (s.generator()) {
                case SynthPlanner.SignalGen sg -> lines.add("SG=" + fmtPlain(sg.sgoutMHz()));
                case SynthPlanner.Tracking t -> {
                    lines.add(t.reverse() ? "TG=Reverse" : "TG=Normal");
                    lines.add("Offset=" + fmtPlain(t.offsetMHz()));
                }
            }
        }
        if (s.mode().readsPhase()) lines.add("Exten=" + fmtPlain(session.vna().planeExtNs()) + " ns");
        if (s.mode().networkAnalyser()) lines.add(">DUT");
        String math = state.referenceSpec().mathLabel();
        if (!math.isEmpty()) lines.add(math);
        if (!session.hasCalibration(s.path())) lines.add("NO PATH CAL");

        double x = l.infoLeft(), y = l.top() + 10;
        g.setFont(FONT_BOLD);
        for (String line : lines) {
            boolean warn = line.startsWith("NO PATH") || (line.equals(calLine) && calDowngraded);
            g.setFill(warn ? Color.ORANGE : c.text);
            g.fillText(line, x, y);
            y += 16;
        }
        g.setFont(FONT);
    }

    private static String fmtK(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.4g", v);
    }

    private static String fmtPlain(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** [gDrawMarkerInfo]: Mark, Freq (MHz), primary axis value, secondary axis value; two columns of six. */
    private void drawMarkerTable(GraphicsContext g, PlotLayout l, SweepData d, DisplaySettings ds, Colors c) {
        List<Markers.Readout> rows = new ArrayList<>();
        state.markers.positions().forEach((id, index) -> {
            if (Double.isNaN(index) || id == Markers.Id.HALT) return;
            rows.add(new Markers.Readout(id, Math.min(index, d.size() - 1.0), d.frequency(Math.min(index, d.size() - 1.0)), 0));
        });
        if (rows.isEmpty()) return;
        int p = primary(ds), s = 3 - p;
        GraphData gp = GraphData.ofCode(p == 1 ? ds.y1Data() : ds.y2Data());
        GraphData gs = GraphData.ofCode(s == 1 ? ds.y1Data() : ds.y2Data());
        double colW = 300, top = l.markerTop(), x0 = 8;
        int perCol = 6;
        g.setFont(FONT);
        for (int k = 0; k < rows.size() && k < 2 * perCol; k++) {
            double cx = x0 + (k / perCol) * colW;
            if (k % perCol == 0) {
                g.setFill(c.text);
                g.setFont(FONT_BOLD);
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText("Mark", cx + 32, top + 12);
                g.fillText("Freq (MHz)", cx + 120, top + 12);
                if (gp != GraphData.NONE) g.fillText(gp.unit, cx + 195, top + 12);
                if (gs != GraphData.NONE) g.fillText(gs.unit, cx + 280, top + 12);
                g.setFont(FONT);
            }
            Markers.Readout r = rows.get(k);
            double y = top + 26 + (k % perCol) * 13;
            g.setFill(state.selected().map(sel -> sel == r.id()).orElse(false) ? Color.YELLOW : c.text);
            g.fillText(r.id().label, cx + 32, y);
            g.fillText(Fmt.mhz(r.frequencyMHz()), cx + 120, y);
            if (gp != GraphData.NONE) g.fillText(Fmt.value(gp, state.value(d, gp, p, r.index())), cx + 195, y);
            if (gs != GraphData.NONE) g.fillText(Fmt.value(gs, state.value(d, gs, s, r.index())), cx + 280, y);
        }
        g.setTextAlign(TextAlignment.LEFT);
    }
}
