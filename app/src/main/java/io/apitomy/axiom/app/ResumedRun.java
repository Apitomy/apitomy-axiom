package io.apitomy.axiom.app;

import java.util.UUID;

/**
 * A workflow run that a stream event resumed at a receive-event node.
 *
 * @param runId       the resumed workflow run
 * @param projectId   the run's project
 * @param nodeId      the receive-event node that was waiting for the event
 * @param traceId     the run's trace; null if the run has no trace
 * @param traceNodeId the receive-event trace node the event completed; null if none
 */
public record ResumedRun(long runId, Long projectId, String nodeId, UUID traceId,
                         Long traceNodeId) {
}
