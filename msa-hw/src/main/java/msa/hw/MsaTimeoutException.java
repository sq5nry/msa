package msa.hw;

/** A bulk transfer did not complete in time (msadll had no timeout; this is the Win7 "USB may hang" case). */
public class MsaTimeoutException extends MsaException {
    private static final long serialVersionUID = 1L;

    public MsaTimeoutException(String message) { super(message); }
}
