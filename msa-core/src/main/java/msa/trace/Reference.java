package msa.trace;

/**
 * A reference line ("Options > Reference Lines"): either a fixed value or a stored trace, evaluated at
 * any frequency, plus the BASIC trace math y = A * ref + B * y (referenceOpA / referenceOpB).
 */
public sealed interface Reference permits Reference.Fixed, Reference.Stored {
    // (TraceMath below is a plain nested record, not a Reference)

    /** Reference value (in the unit it was made in) at a frequency. */
    double at(double frequencyMHz);

    /** A horizontal line. */
    record Fixed(double value) implements Reference {
        public double at(double f) { return value; }
    }

    /**
     * A frozen copy of a trace (frequencies ascending), interpolated linearly to other frequencies and held
     * constant beyond its ends, as the BASIC program re-transforms the reference when the sweep changes.
     */
    record Stored(double[] freq, double[] value) implements Reference {
        public Stored {
            if (freq.length != value.length || freq.length == 0) throw new IllegalArgumentException("need equal, non-empty arrays");
            freq = freq.clone();
            value = value.clone();
            for (int i = 1; i < freq.length; i++)
                if (freq[i] < freq[i - 1]) throw new IllegalArgumentException("frequencies must be ascending");
        }

        /** Snapshot of a sweep in the given graph unit (unmeasured points are skipped). */
        public static Stored of(SweepData d, GraphData g) {
            int n = 0;
            for (int i = 0; i < d.size(); i++) if (d.isMeasured(i)) n++;
            double[] f = new double[n], v = new double[n];
            int k = 0;
            boolean descending = d.size() > 1 && d.frequency(d.size() - 1) < d.frequency(0);
            for (int j = 0; j < d.size(); j++) {
                int i = descending ? d.size() - 1 - j : j;
                if (!d.isMeasured(i)) continue;
                f[k] = d.frequency(i);
                v[k++] = d.value(g, i);
            }
            return new Stored(f, v);
        }

        public double at(double x) {
            int n = freq.length;
            if (x <= freq[0]) return value[0];
            if (x >= freq[n - 1]) return value[n - 1];
            int i = 1;
            while (x > freq[i]) i++;
            double span = freq[i] - freq[i - 1];
            return span == 0 ? value[i] : value[i - 1] + (x - freq[i - 1]) * (value[i] - value[i - 1]) / span;
        }
    }

    /** y' = a * ref + b * y; a = 0, b = 1 leaves the data unchanged. a = -1, b = 1 gives "data minus reference". */
    record TraceMath(double a, double b) {
        public static final TraceMath NONE = new TraceMath(0, 1);
        public static final TraceMath SUBTRACT_REFERENCE = new TraceMath(-1, 1);
        /** "Data + Ref" (referenceOpA = 1, referenceOpB = 1). */
        public static final TraceMath ADD_REFERENCE = new TraceMath(1, 1);
        /** "Ref - Data" (referenceOpA = 1, referenceOpB = -1). */
        public static final TraceMath REFERENCE_MINUS_DATA = new TraceMath(1, -1);

        public double apply(double ref, double y) { return a * ref + b * y; }
    }
}
