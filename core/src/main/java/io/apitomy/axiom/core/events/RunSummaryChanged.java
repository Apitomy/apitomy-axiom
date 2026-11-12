package io.apitomy.axiom.core.events;

/**
 * Fired inside a transaction when a run's AI summary changes; observed after commit to notify the UI.
 *
 * @param subjectId the run's subject
 * @param runId the run
 * @param runStatus the run's status
 */
public record RunSummaryChanged(long subjectId, long runId, String runStatus) { }
