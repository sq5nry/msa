package msa.autotest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import msa.autotest.AutotestModel.Measurement;
import msa.autotest.AutotestModel.Result;
import msa.autotest.AutotestModel.Setup;
import msa.autotest.AutotestModel.Status;
import msa.autotest.AutotestModel.Trace;

/** Runs a selection of tests in order, asking for the wiring before the first test that needs it. */
public final class AutotestRun {

    private AutotestRun() {}

    /**
     * @param confirmSetup asked before the first test and whenever the wiring changes; false skips the tests of
     *                     that setup
     * @param onResult     each result as it is ready (on the calling thread)
     * @return all results; stops early (remaining tests not run) when the context is cancelled
     */
    public static List<Result> run(List<AutoTest> tests, AutotestContext ctx, Predicate<Setup> confirmSetup,
                                   Consumer<Result> onResult) {
        List<Result> out = new ArrayList<>();
        Setup current = Setup.NONE;
        boolean asked = false;
        Map<Setup, Boolean> allowed = new LinkedHashMap<>();
        for (AutoTest t : tests) {
            Result r;
            String na = t.notApplicable(ctx.config(), ctx.inputSwitch());
            if (na != null) {
                r = Result.skipped(t.id(), t.title(), na);
            } else {
                if (t.setup() != current || !asked) {
                    try {
                        allowed.put(t.setup(), confirmSetup.test(t.setup()));
                    } catch (AutotestContext.Cancelled c) {
                        break;
                    }
                    current = t.setup();
                    asked = true;
                }
                if (!allowed.getOrDefault(t.setup(), true)) {
                    r = Result.skipped(t.id(), t.title(), "skipped: the wiring for these tests (" + (t.setup() == Setup.NONE ? "nothing connected"
                            : t.setup() == Setup.TG_LOOP ? "TG looped to the input" : "TG to the R input") + ") was not confirmed");
                } else {
                    try {
                        ctx.checkCancelled();
                        r = t.run(ctx);
                    } catch (AutotestContext.Cancelled c) {
                        break;
                    } catch (RuntimeException e) {
                        r = new Result(t.id(), t.title(), Status.ERROR,
                                e.getMessage() == null ? e.toString() : e.getMessage(), List.of(), List.of(), List.of(), 0);
                    }
                }
            }
            out.add(r);
            onResult.accept(r);
        }
        return out;
    }

    /** The curves that "Save as Reference" keeps: key -> {x[], y[]}. */
    public static Map<String, double[][]> curves(List<Result> results) {
        Map<String, double[][]> m = new LinkedHashMap<>();
        for (Result r : results)
            for (Trace t : r.traces())
                if (t.key() != null) m.put(t.key(), new double[][] {t.x().clone(), t.y().clone()});
        return m;
    }

    /** Plain text report. */
    public static String report(List<Result> results, List<String> header) {
        StringBuilder sb = new StringBuilder();
        for (String h : header) sb.append(h).append('\n');
        sb.append('\n');
        Status overall = Status.PASS;
        for (Result r : results) overall = overall.worst(r.status());
        sb.append("Overall: ").append(overall).append("\n\n");
        for (Result r : results) {
            sb.append(String.format(Locale.ROOT, "[%s] %s - %s (%.1f s)%n", r.status(), r.title(), r.summary(), r.seconds()));
            for (Measurement m : r.measurements()) {
                sb.append(String.format(Locale.ROOT, "    %-5s %-42s %14s   expected %s", m.status(), m.name(), m.valueText(),
                        m.limits().text(m.unit())));
                if (!Double.isNaN(m.reference())) sb.append("   reference ").append(AutotestModel.num(m.reference()));
                if (!m.note().isEmpty()) sb.append("   (").append(m.note()).append(')');
                sb.append('\n');
            }
            for (String h : r.hints()) sb.append("    -> ").append(h).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }
}
