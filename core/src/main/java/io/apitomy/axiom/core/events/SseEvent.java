package io.apitomy.axiom.core.events;

import java.util.UUID;

/**
 * Represents an event to be sent to connected SSE clients.
 * Fired as a CDI event within the application and broadcast to all SSE subscribers.
 */
public record SseEvent(
        /**
         * Event type: "subject-updated", "task-updated", "thread-entry",
         * "notification", "activity"
         */
        String type,

        /**
         * JSON payload with event-specific data.
         */
        String data
) {

    /**
     * Creates a subject-updated event.
     *
     * @param subjectId the subject that changed
     * @return a new SSE event
     */
    public static SseEvent subjectUpdated(Long subjectId) {
        return new SseEvent("subject-updated",
                "{\"subjectId\":" + subjectId + "}");
    }

    /**
     * Creates a task-updated event.
     *
     * @param subjectId the subject the task belongs to
     * @param taskId the task that changed
     * @param status the new task status
     * @return a new SSE event
     */
    public static SseEvent taskUpdated(Long subjectId, Long taskId, String status) {
        return new SseEvent("task-updated",
                "{\"subjectId\":" + subjectId
                        + ",\"taskId\":" + taskId
                        + ",\"status\":\"" + status + "\"}");
    }

    /**
     * Creates a thread-entry event.
     *
     * @param subjectId the subject the thread belongs to
     * @return a new SSE event
     */
    public static SseEvent threadEntry(Long subjectId) {
        return new SseEvent("thread-entry",
                "{\"subjectId\":" + subjectId + "}");
    }

    /**
     * Creates a notification event.
     *
     * @param message the notification message
     * @param severity "info", "warning", or "error"
     * @return a new SSE event
     */
    public static SseEvent notification(String message, String severity) {
        return new SseEvent("notification",
                "{\"message\":\"" + escapeJson(message)
                        + "\",\"severity\":\"" + severity + "\"}");
    }

    /**
     * Creates an activity log event.
     *
     * @param entryType the activity entry type
     * @param summary the activity summary
     * @return a new SSE event
     */
    public static SseEvent activity(String entryType, String summary) {
        return new SseEvent("activity",
                "{\"entryType\":\"" + entryType
                        + "\",\"summary\":\"" + escapeJson(summary) + "\"}");
    }

    /**
     * Creates a trace-updated event.
     *
     * @param traceId the trace that changed
     * @return a new SSE event
     */
    public static SseEvent traceUpdated(UUID traceId) {
        return new SseEvent("trace-updated",
                "{\"traceId\":\"" + traceId + "\"}");
    }

    /**
     * Creates an inbox-updated event when a task enters or leaves AwaitingInput.
     *
     * @param taskId the task that changed
     * @param action "added" or "removed"
     * @param count the current total inbox count
     * @return a new SSE event
     */
    public static SseEvent inboxUpdated(Long taskId, String action, long count) {
        return new SseEvent("inbox-updated",
                "{\"taskId\":" + taskId
                        + ",\"action\":\"" + action + "\""
                        + ",\"count\":" + count + "}");
    }

    /**
     * Creates an assistant-session-event that wraps an assistant event for
     * broadcast via the global SSE channel.
     *
     * @param sessionId the assistant session ID
     * @param eventType the assistant event type (e.g. "assistant_text", "tool_use")
     * @param eventData the assistant event payload as a JSON string
     * @param eventIndex the event's position in the session's event history
     * @return a new SSE event
     */
    public static SseEvent assistantSessionEvent(String sessionId, String eventType,
                                                  String eventData, int eventIndex) {
        return new SseEvent("assistant-session-event",
                "{\"sessionId\":\"" + sessionId + "\","
                        + "\"eventType\":\"" + eventType + "\","
                        + "\"eventData\":" + eventData + ","
                        + "\"eventIndex\":" + eventIndex + "}");
    }

    /**
     * Fires when a workflow run changes state.
     *
     * @param subjectId the subject the run belongs to
     * @param runId the workflow run that changed (nullable)
     * @param status the run status (nullable)
     */
    public static SseEvent workflowUpdated(long subjectId, Long runId, String status) {
        StringBuilder json = new StringBuilder("{\"subjectId\":").append(subjectId);
        if (runId != null) {
            json.append(",\"runId\":").append(runId);
        }
        if (status != null) {
            json.append(",\"status\":\"").append(escapeJson(status)).append("\"");
        }
        json.append("}");
        return new SseEvent("workflow-updated", json.toString());
    }

    /**
     * A composition draft item changed (saved by the user or the AI, or restored).
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param contentRevision the item's new content revision
     * @param author {@code user} or {@code ai}
     * @param turnId the AI turn id, or null
     * @return the event
     */
    public static SseEvent compositionItemChanged(long compositionId, long itemId, String contentRevision,
                                                  String author, String turnId) {
        return new SseEvent("composition-item-changed",
                "{\"compositionId\":" + compositionId
                        + ",\"itemId\":" + itemId
                        + ",\"contentRevision\":\"" + escapeJson(contentRevision) + "\""
                        + ",\"author\":\"" + escapeJson(author) + "\""
                        + ",\"turnId\":" + (turnId == null ? "null" : "\"" + escapeJson(turnId) + "\"")
                        + "}");
    }

    /**
     * Creates a stream-event-received event, indicating a new event
     * has been added to the event stream.
     *
     * @param eventId      the stream event UUID
     * @param eventType    the normalized event type (e.g., "issue.created")
     * @param eventSourceId the event source slug that produced the event
     * @return a new SSE event
     */
    public static SseEvent streamEventReceived(String eventId, String eventType,
                                                String eventSourceId) {
        return new SseEvent("stream-event",
                "{\"eventId\":\"" + eventId
                        + "\",\"eventType\":\"" + escapeJson(eventType)
                        + "\",\"eventSourceId\":\"" + escapeJson(eventSourceId) + "\"}");
    }

    /**
     * Creates a heartbeat event to keep SSE connections alive through proxies.
     *
     * @return a new heartbeat SSE event
     */
    public static SseEvent heartbeat() {
        return new SseEvent("heartbeat", "{}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
