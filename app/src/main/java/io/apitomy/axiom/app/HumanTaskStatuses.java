package io.apitomy.axiom.app;

/**
 * Task status values used by human (Inbox) tasks. They mirror the {@code Task.status} enum in
 * the OpenAPI spec.
 */
public final class HumanTaskStatuses {

    /** A human task waiting for a person; the only status listed in the Inbox. */
    public static final String AWAITING_INPUT = "AwaitingInput";

    /** A human task whose workflow run ended before anyone completed it. */
    public static final String CANCELLED = "Cancelled";

    private HumanTaskStatuses() {
    }
}
