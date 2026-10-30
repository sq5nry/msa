package msa.autotest;

import java.util.ArrayList;
import java.util.List;

import msa.autotest.AutotestModel.Limits;
import msa.autotest.AutotestModel.Measurement;
import msa.autotest.AutotestModel.Result;
import msa.autotest.AutotestModel.Status;
import msa.autotest.AutotestModel.Trace;

/** Collects a test's measurements, comparing each with the absolute limits and the stored reference. */
final class ResultBuilder {

    private final String id, title;
    private final ReferenceStore ref;
    private final long start = System.nanoTime();
    private final List<Measurement> measurements = new ArrayList<>();
    private final List<Trace> traces = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    private Status extra = Status.PASS;

    ResultBuilder(String id, String title, ReferenceStore ref) {
        this.id = id;
        this.title = title;
        this.ref = ref;
    }

    /**
     * @param refTolerance how far the value may move from this MSA's reference before it is a warning
     *                     (NaN: not compared)
     */
    Measurement measure(String key, String name, double value, String unit, Limits limits, double refTolerance, String note) {
        double r = key == null ? Double.NaN : ref.value(key);
        Status s = limits.judge(value);
        String n = note == null ? "" : note;
        if (Double.isNaN(value)) n = n.isEmpty() ? "not measurable" : n;
        if (!Double.isNaN(r) && !Double.isNaN(refTolerance) && !Double.isNaN(value) && Math.abs(value - r) > refTolerance) {
            s = s.worst(Status.WARN);
            String d = AutotestModel.num(value - r) + (unit.isEmpty() ? "" : " " + unit);
            n = (n.isEmpty() ? "" : n + "; ") + "changed by " + d + " since the reference";
        }
        Measurement m = new Measurement(key, name, value, unit, limits, r, s, n);
        measurements.add(m);
        return m;
    }

    Measurement info(String key, String name, double value, String unit, double refTolerance, String note) {
        return measure(key, name, value, unit, Limits.info(), refTolerance, note);
    }

    void trace(Trace t) { traces.add(t); }

    void hint(String h) { if (!hints.contains(h)) hints.add(h); }

    /** Forces at least this status (e.g. a check that is not a single number). */
    void atLeast(Status s) { extra = extra.worst(s); }

    ReferenceStore reference() { return ref; }

    Result build(String summary) {
        Status s = extra;
        for (Measurement m : measurements) s = s.worst(m.status());
        if (measurements.isEmpty() && s == Status.PASS) s = Status.INFO;
        return new Result(id, title, s, summary, List.copyOf(measurements), List.copyOf(traces), List.copyOf(hints),
                (System.nanoTime() - start) / 1e9);
    }

    Result error(String why) {
        return new Result(id, title, Status.ERROR, why, List.copyOf(measurements), List.copyOf(traces), List.copyOf(hints),
                (System.nanoTime() - start) / 1e9);
    }
}
