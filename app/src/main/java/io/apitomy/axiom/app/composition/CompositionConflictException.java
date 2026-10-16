package io.apitomy.axiom.app.composition;

/** The request conflicts with the composition's current state (HTTP 409). */
public class CompositionConflictException extends RuntimeException {
    /**
     * @param message the conflict
     */
    public CompositionConflictException(String message) {
        super(message);
    }
}
