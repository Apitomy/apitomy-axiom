package io.apitomy.axiom.events.core;

/** Result of {@link EventStreamService#persistEvent}. */
public enum PersistOutcome {
    /** Stored; passed schema validation. */
    PERSISTED,
    /** Stored; failed schema validation (errors saved with the event). */
    PERSISTED_INVALID,
    /** Not stored; the event source already has an event with this source event ID. */
    DUPLICATE;

    /**
     * Whether the event was stored.
     *
     * @return true for {@link #PERSISTED} and {@link #PERSISTED_INVALID}
     */
    public boolean persisted() {
        return this != DUPLICATE;
    }
}
