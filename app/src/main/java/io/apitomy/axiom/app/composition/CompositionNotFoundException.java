package io.apitomy.axiom.app.composition;

/** A composition, item or revision does not exist (HTTP 404). */
public class CompositionNotFoundException extends RuntimeException {
    /**
     * @param message what was not found
     */
    public CompositionNotFoundException(String message) {
        super(message);
    }
}
