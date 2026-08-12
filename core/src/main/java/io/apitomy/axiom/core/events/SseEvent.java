package io.apitomy.axiom.core.events;

import java.util.UUID;

/**
 * Represents an event to be sent to connected SSE clients.
 * Fired as a CDI event within the application and broadcast to all SSE subscribers.
 */
public record SseEvent(
        /**
         * Event type: "project-updated", "task-updated", "thread-entry",
         * "notification", "activity"
         */
        String type,

        /**
         * JSON payload with event-specific data.
         */
        String data
) {

    /**
     * Creates a project-updated event.
     *
     * @param projectId the project that changed
     * @return a new SSE event
     */
    public static SseEvent projectUpdated(Long projectId) {
        return new SseEvent("project-updated",
                "{\"projectId\":" + projectId + "}");
    }

    /**
     * Creates a task-updated event.
     *
     * @param projectId the project the task belongs to
     * @param taskId the task that changed
     * @param status the new task status
     * @return a new SSE event
     */
    public static SseEvent taskUpdated(Long projectId, Long taskId, String status) {
        return new SseEvent("task-updated",
                "{\"projectId\":" + projectId
                        + ",\"taskId\":" + taskId
                        + ",\"status\":\"" + status + "\"}");
    }

    /**
     * Creates a thread-entry event.
     *
     * @param projectId the project the thread belongs to
     * @return a new SSE event
     */
    public static SseEvent threadEntry(Long projectId) {
        return new SseEvent("thread-entry",
                "{\"projectId\":" + projectId + "}");
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
     * Creates a report-updated event.
     *
     * @param reportId the report that changed
     * @param status the new report status
     * @return a new SSE event
     */
    public static SseEvent reportUpdated(Long reportId, String status) {
        return new SseEvent("report-updated",
                "{\"reportId\":" + reportId
                        + ",\"status\":\"" + status + "\"}");
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
     * Creates a scheduled-job-updated event.
     *
     * @param jobId the scheduled job that changed
     * @return a new SSE event
     */
    public static SseEvent scheduledJobUpdated(Long jobId) {
        return new SseEvent("scheduled-job-updated",
                "{\"jobId\":" + jobId + "}");
    }

    /**
     * Creates a scheduled-job-run-updated event.
     *
     * @param runId the scheduled job run that changed
     * @param status the new run status
     * @return a new SSE event
     */
    public static SseEvent scheduledJobRunUpdated(Long runId, String status) {
        return new SseEvent("scheduled-job-run-updated",
                "{\"runId\":" + runId
                        + ",\"status\":\"" + status + "\"}");
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
