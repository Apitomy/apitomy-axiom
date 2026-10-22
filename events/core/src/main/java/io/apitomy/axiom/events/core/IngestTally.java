package io.apitomy.axiom.events.core;

/** Counts persisted and schema-invalid events during one poll. Not thread-safe; one per poll. */
public class IngestTally {

    private int ingested;
    private int invalid;

    /**
     * Records the outcome of one persist call.
     *
     * @param outcome the outcome
     */
    public void record(PersistOutcome outcome) {
        if (outcome.persisted()) {
            ingested++;
        }
        if (outcome == PersistOutcome.PERSISTED_INVALID) {
            invalid++;
        }
    }

    /**
     * Number of events stored.
     *
     * @return stored count, valid and invalid
     */
    public int ingested() {
        return ingested;
    }

    /**
     * Number of stored events that failed schema validation.
     *
     * @return invalid count
     */
    public int invalid() {
        return invalid;
    }

    /**
     * Suffix for poll-log messages.
     *
     * @return {@code ""} when no event was invalid, else {@code " (N failed schema validation)"}
     */
    public String validationSuffix() {
        return invalid == 0 ? "" : " (" + invalid + " failed schema validation)";
    }
}
