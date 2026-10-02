package io.apitomy.axiom.app;

import java.util.UUID;

/**
 * A workflow run that a stream event matched at a receive-event node, and either resumed or
 * failed to resume.
 *
 * @param runId        the matched workflow run
 * @param projectId    the run's project; may be null if the resume failed
 * @param nodeId       the receive-event node that was waiting for the event
 * @param traceId      the run's trace; null if the run has no trace
 * @param traceNodeId  the receive-event trace node the event completed; null if none
 * @param errorMessage why resuming the run failed; null if it was resumed
 */
public record ResumedRun(long runId, Long projectId, String nodeId, UUID traceId,
                         Long traceNodeId, String errorMessage) {

    /**
     * Creates a successfully resumed run.
     *
     * @param runId       the resumed workflow run
     * @param projectId   the run's project
     * @param nodeId      the receive-event node
     * @param traceId     the run's trace, or null
     * @param traceNodeId the completed receive-event trace node, or null
     */
    public ResumedRun(long runId, Long projectId, String nodeId, UUID traceId, Long traceNodeId) {
        this(runId, projectId, nodeId, traceId, traceNodeId, null);
    }

    /**
     * Whether the event matched the run but resuming it failed.
     *
     * @return true if resuming failed
     */
    public boolean failed() {
        return errorMessage != null;
    }
}
