package io.apitomy.axiom.app.composition;

/**
 * Something happened in a composition's agent session that ends the current turn.
 *
 * @param compositionId the composition
 * @param kind what happened
 * @param detail the error message for {@link Kind#FAILED}, otherwise null
 * @param eventIndex the index of the triggering event in the session's event history
 */
public record WorkbenchSessionEvent(long compositionId, Kind kind, String detail, int eventIndex) {

    /**
     * An event not tied to a known history position; it is never mistaken for one from an earlier exchange.
     *
     * @param compositionId the composition
     * @param kind what happened
     * @param detail the error message for {@link Kind#FAILED}, otherwise null
     */
    public WorkbenchSessionEvent(long compositionId, Kind kind, String detail) {
        this(compositionId, kind, detail, Integer.MAX_VALUE);
    }

    /** Event kinds. */
    public enum Kind {
        /** The agent finished its turn normally. */
        TURN_COMPLETE,
        /** The agent's turn ended because of an error, or the session ended. */
        FAILED
    }
}
