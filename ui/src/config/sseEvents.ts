/**
 * Names of the global SSE events sent by the backend (see core SseEvent.java). Use these for
 * dashboard widget `liveEvents`.
 */
export const SSE_EVENTS = {
    /** A workflow run changed status. */
    workflowUpdated: "workflow-updated",
    /** A task changed status. */
    taskUpdated: "task-updated",
    /** A human task was added to or removed from the inbox. */
    inboxUpdated: "inbox-updated",
    /** An activity log entry was written. */
    activity: "activity",
    /** A subject changed. */
    subjectUpdated: "subject-updated",
    /** A thread entry was added to a subject. */
    threadEntry: "thread-entry",
    /** A trace changed. */
    traceUpdated: "trace-updated",
    /** A notification for the user. */
    notification: "notification",
    /** A new stream event was committed: { eventId, eventType, eventSourceId }. */
    streamEvent: "stream-event",
    /** Event processing ledger entries changed (one per orchestrator tick): { changes }. */
    streamProcessing: "stream-processing",
} as const;
