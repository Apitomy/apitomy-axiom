package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalizes OpenCode runtime events into assistant SSE events consumed by the UI.
 *
 * <p>Instances are per-session and single-reader: each driver creates one instance and calls it only from its
 * SSE reader thread. Instances are stateful - they de-duplicate tool calls, text and reasoning parts across the
 * repeated {@code message.part.updated} events OpenCode sends. Because OpenCode call IDs and part IDs are globally
 * unique, the de-dup state is kept for the whole session lifetime (it is not reset at turn end), so late part
 * updates arriving after {@code session.idle} are not re-emitted.
 */
public class OpenCodeEventNormalizer {

    /** Session events that are understood but not (yet) surfaced to the UI. */
    private static final Set<String> IGNORED_EVENT_TYPES = Set.of(
            "message.updated",
            "message.removed",
            "message.part.delta",
            "message.part.removed",
            "session.created",
            "session.updated",
            "session.deleted",
            "session.status",
            "session.diff",
            "session.compacted",
            "permission.replied",
            "todo.updated");

    /** Message part types that are understood but not surfaced to the UI. */
    private static final Set<String> IGNORED_PART_TYPES = Set.of(
            "step-start", "step-finish", "snapshot", "patch", "file", "agent", "retry", "compaction", "subtask");

    private final Set<String> toolUsesEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> toolResultsEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> reasoningPartsSeen = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastTextByPart = new ConcurrentHashMap<>();

    /**
     * Converts a single OpenCode event into zero or more normalized assistant events.
     *
     * @param eventName OpenCode event name
     * @param payload OpenCode event payload
     * @return normalized assistant events (empty when the event is ignored)
     */
    public List<SseEvent> normalize(String eventName, JsonNode payload) {
        if (eventName == null || eventName.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode safePayload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        JsonNode eventData = eventData(safePayload);
        String resolvedType = resolvedEventType(eventName, safePayload);

        return switch (resolvedType) {
            case "message.part.updated" -> mapMessagePart(eventData, safePayload);
            case "session.permission.requested", "permission.asked", "permission.v2.asked" ->
                    List.of(permission(eventData));
            case "session.turn.completed", "session.idle" -> List.of(turnComplete(eventData));
            case "session.error" -> List.of(sessionError(eventData));
            default -> IGNORED_EVENT_TYPES.contains(resolvedType)
                    ? Collections.emptyList()
                    : List.of(unhandled(resolvedType, safePayload));
        };
    }

    private List<SseEvent> mapMessagePart(JsonNode eventData, JsonNode rawPayload) {
        JsonNode part = eventData.path("part");
        String partType = part.path("type").asText("");
        return switch (partType) {
            case "text" -> mapTextPart(part);
            case "reasoning" -> mapReasoningPart(part);
            case "tool" -> mapToolPart(part);
            default -> IGNORED_PART_TYPES.contains(partType)
                    ? Collections.emptyList()
                    : List.of(unhandled("message.part.updated:" + partType, rawPayload));
        };
    }

    private List<SseEvent> mapTextPart(JsonNode part) {
        String text = part.path("text").asText("");
        if (text.isBlank()) {
            return Collections.emptyList();
        }
        String partId = part.path("id").asText("");
        if (!partId.isEmpty() && text.equals(lastTextByPart.put(partId, text))) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("text", text);
        return List.of(new SseEvent("assistant_text", data));
    }

    private List<SseEvent> mapReasoningPart(JsonNode part) {
        String partId = part.path("id").asText("");
        if (!partId.isEmpty() && !reasoningPartsSeen.add(partId)) {
            return Collections.emptyList();
        }
        return List.of(new SseEvent("thinking", JsonNodeFactory.instance.objectNode()));
    }

    private List<SseEvent> mapToolPart(JsonNode part) {
        String callId = firstNonBlank(part.path("callID").asText(""), part.path("id").asText(""));
        JsonNode state = part.path("state");
        String status = state.path("status").asText("");
        if (callId.isEmpty() || "pending".equals(status)) {
            return Collections.emptyList();
        }
        List<SseEvent> events = new ArrayList<>();
        if (toolUsesEmitted.add(callId)) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("id", callId);
            data.put("name", part.path("tool").asText(""));
            data.set("input", state.path("input").isObject()
                    ? state.path("input")
                    : JsonNodeFactory.instance.objectNode());
            events.add(new SseEvent("tool_use", data));
        }
        if (("completed".equals(status) || "error".equals(status)) && toolResultsEmitted.add(callId)) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", callId);
            if ("completed".equals(status)) {
                data.put("stdout", state.path("output").asText(""));
                data.put("stderr", "");
            } else {
                data.put("stdout", "");
                data.put("stderr", firstNonBlank(state.path("error").asText(""), "Tool failed"));
            }
            data.put("interrupted", false);
            events.add(new SseEvent("tool_result", data));
        }
        return events;
    }

    private SseEvent unhandled(String rawType, JsonNode rawPayload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("rawType", rawType);
        data.put("raw", rawPayload.toString());
        return new SseEvent("unhandled_event", data);
    }

    private JsonNode eventData(JsonNode payload) {
        JsonNode properties = payload.path("properties");
        return properties.isObject() ? properties : payload;
    }

    private String resolvedEventType(String eventName, JsonNode payload) {
        String payloadType = payload.path("type").asText("");
        if (!payloadType.isBlank()) {
            return payloadType;
        }
        return eventName;
    }

    private SseEvent permission(JsonNode payload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String requestId = firstNonBlank(
                payload.path("requestId").asText(""),
                payload.path("requestID").asText(""),
                payload.path("permissionId").asText(""),
                payload.path("permissionID").asText("")
        );
        data.put("requestId", requestId);
        data.put("toolName", payload.path("toolName").asText(""));
        data.set("toolInput", payload.path("toolInput"));
        if (!payload.path("subagentToolUseId").asText("").isEmpty()) {
            data.put("subagentToolUseId", payload.path("subagentToolUseId").asText(""));
        }
        if (!payload.path("agentId").asText("").isEmpty()) {
            data.put("agentId", payload.path("agentId").asText(""));
        }
        return new SseEvent("permission_request", data);
    }

    private SseEvent turnComplete(JsonNode payload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("sessionId", firstNonBlank(
                payload.path("sessionID").asText(""),
                payload.path("sessionId").asText(""),
                payload.path("session_id").asText("")));
        data.put("costUsd", payload.path("costUsd").asDouble(0));
        data.put("inputTokens", payload.path("inputTokens").asLong(0));
        data.put("outputTokens", payload.path("outputTokens").asLong(0));
        data.put("success", payload.path("success").asBoolean(true));
        return new SseEvent("turn_complete", data);
    }

    private SseEvent sessionError(JsonNode payload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        JsonNode error = payload.path("error");
        String message = firstNonBlank(
                error.path("data").path("message").asText(""),
                error.path("message").asText(""),
                payload.path("message").asText(""),
                "Session error"
        );
        data.put("message", message);
        data.put("name", firstNonBlank(error.path("name").asText(""), "UnknownError"));
        return new SseEvent("session_error", data);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
