package msa.hw;

/** Any failure talking to the MSA. Unchecked so the sweep engine decides where to handle it. */
public class MsaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MsaException(String message) { super(message); }
    public MsaException(String message, Throwable cause) { super(message, cause); }
}
