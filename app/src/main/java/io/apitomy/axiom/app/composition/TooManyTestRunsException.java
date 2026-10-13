package io.apitomy.axiom.app.composition;

/**
 * A composition already has the maximum number of test runs in progress.
 */
public class TooManyTestRunsException extends RuntimeException {

    /** The message of every instance. */
    public static final String MESSAGE =
            "Too many test runs in progress for this composition; try again when one finishes.";

    /**
     * Creates the exception.
     */
    public TooManyTestRunsException() {
        super(MESSAGE);
    }
}
