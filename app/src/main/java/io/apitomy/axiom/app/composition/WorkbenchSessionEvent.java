package io.apitomy.axiom.app.composition;

/**
 * Something happened in a composition's agent session that ends the current turn.
 *
 * @param compositionId the composition
 * @param kind what happened
 * @param detail the error message for {@link Kind#FAILED}, otherwise null
 */
public record WorkbenchSessionEvent(long compositionId, Kind kind, String detail) {

    /** Event kinds. */
    public enum Kind {
        /** The agent finished its turn normally. */
        TURN_COMPLETE,
        /** The agent's turn ended because of an error, or the session ended. */
        FAILED
    }
}
