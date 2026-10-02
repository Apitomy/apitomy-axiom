package io.apitomy.axiom.manager;

/**
 * Thrown when the Manager AI output cannot be interpreted as a decision list.
 */
public class ManagerOutputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason the output was rejected
     */
    public ManagerOutputException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message the reason the output was rejected
     * @param cause   the underlying parse error
     */
    public ManagerOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
