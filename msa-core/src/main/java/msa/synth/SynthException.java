package msa.synth;

/** A frequency the synthesisers cannot produce (BASIC: errora$ / error$ followed by "RequireRestart"). */
public class SynthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SynthException(String message) { super(message); }
}
