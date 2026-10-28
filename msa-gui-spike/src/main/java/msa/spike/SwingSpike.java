package msa.spike;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import msa.sweep.SweepEngine;
import msa.trace.Markers;
import msa.trace.SweepData;

/** Swing trial: Java2D on a JPanel, repainted by a 60 Hz Swing Timer when new data arrived. Space = halt/continue. */
public final class SwingSpike {
    private SwingSpike() {}

    public static void main(String[] args) {
        SpikeSetup s = SpikeSetup.create(List.of(args));
        s.uiExecutor = SwingUtilities::invokeLater;
        SwingUtilities.invokeLater(() -> show(s));
        s.start();
    }

    private static void show(SpikeSetup s) {
        boolean[] dirty = {true};
        s.store.addChangeListener((cur, i) -> dirty[0] = true);
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g0) {
                long t0 = System.nanoTime();
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                PlotGeometry geo = new PlotGeometry(getWidth(), getHeight());
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(150, 150, 140));
                for (int i = 0; i <= PlotGeometry.DIVS; i++) {
                    int x = (int) (PlotGeometry.LEFT + geo.plotW * i / PlotGeometry.DIVS);
                    int y = (int) (PlotGeometry.TOP + geo.plotH * i / PlotGeometry.DIVS);
                    g.drawLine(x, PlotGeometry.TOP, x, (int) (PlotGeometry.TOP + geo.plotH));
                    g.drawLine(PlotGeometry.LEFT, y, (int) (PlotGeometry.LEFT + geo.plotW), y);
                    g.drawString(String.format("%.0f", PlotGeometry.Y_MAX - i * (PlotGeometry.Y_MAX - PlotGeometry.Y_MIN) / PlotGeometry.DIVS),
                            8, y + 4);
                }
                SweepData d = s.store.current();
                if (d != null) {
                    PlotGeometry.Line l = PlotGeometry.line(d, geo);
                    Path2D.Double path = new Path2D.Double();
                    for (int k = 0; k < l.count(); k++) {
                        if (k == 0) path.moveTo(l.xy()[0], l.xy()[1]);
                        else path.lineTo(l.xy()[2 * k], l.xy()[2 * k + 1]);
                    }
                    g.setColor(new Color(255, 200, 90));
                    g.setStroke(new BasicStroke(1f));
                    g.draw(path);
                    Markers.Peaks pk = PlotGeometry.peak(d);
                    if (pk != null) {
                        int x = (int) geo.x(pk.maxIndex(), d.size()), y = (int) geo.y(pk.max());
                        g.setColor(Color.CYAN);
                        g.drawString(String.format("P+ %.3f MHz  %.1f dBm", d.frequency(pk.maxIndex()), pk.max()), x + 6, y - 6);
                        g.fillOval(x - 3, y - 3, 6, 6);
                    }
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString(String.format("%.3f MHz", d.frequency(0)), PlotGeometry.LEFT, getHeight() - 22);
                    String r = String.format("%.3f MHz", d.frequency(d.size() - 1));
                    g.drawString(r, (int) (PlotGeometry.LEFT + geo.plotW) - g.getFontMetrics().stringWidth(r), getHeight() - 22);
                }
                g.setColor(Color.WHITE);
                g.drawString("Swing | " + s.source + " | " + s.engine.state() + " | " + s.stats.summary(), 8, 14);
                g.setColor(s.setupInfo.contains("!!") || s.setupInfo.contains("NO path") ? Color.ORANGE : Color.LIGHT_GRAY);
                g.drawString(s.setupInfo, 8, 27);
                s.stats.frame(System.nanoTime() - t0);
            }
        };
        panel.setPreferredSize(new Dimension(1100, 650));
        panel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JFrame f = new JFrame("MSA GUI trial - Swing");
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.add(panel);
        f.pack();
        f.setLocationByPlatform(true);
        f.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (s.engine.state() == SweepEngine.State.RUNNING) s.engine.halt(); else s.engine.resume();
                }
            }
        });
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                s.engine.close();
                System.exit(0);
            }
        });
        new Timer(16, e -> {
            if (dirty[0]) {
                dirty[0] = false;
                panel.repaint();
            }
        }).start();
        f.setVisible(true);
    }
}
