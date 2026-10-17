package io.apitomy.axiom.events.core;

/**
 * Thrown when a normalized event could not be persisted for a reason other than being a
 * duplicate (for example, payload serialization failure). Pollers must treat this as an
 * incomplete ingestion and must not advance their checkpoint past the event.
 */
public class EventPersistenceException extends RuntimeException {

    /**
     * Creates a new exception.
     *
     * @param message description of the failure
     * @param cause   the underlying cause
     */
    public EventPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
