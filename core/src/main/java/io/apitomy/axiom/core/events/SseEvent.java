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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
