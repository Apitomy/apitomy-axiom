package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;

import java.util.Collections;
import java.util.List;

/**
 * Normalizes OpenCode runtime events into assistant SSE events consumed by the UI.
 */
public class OpenCodeEventNormalizer {

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
            case "session.message.part", "message.part.updated" -> mapMessagePart(eventData);
            case "session.tool.started" -> List.of(toolUse(eventData));
            case "session.tool.completed" -> List.of(toolResult(eventData));
            case "session.permission.requested", "permission.asked", "permission.v2.asked" ->
                    List.of(permission(eventData));
            case "session.turn.completed", "session.idle" -> List.of(turnComplete(eventData));
            case "session.error" -> List.of(sessionError(eventData));
            default -> Collections.emptyList();
        };
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

    private List<SseEvent> mapMessagePart(JsonNode payload) {
        JsonNode part = payload.path("part");
        if (!"text".equals(part.path("type").asText(""))) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("text", part.path("text").asText(""));
        return List.of(new SseEvent("assistant_text", data));
    }

    private SseEvent toolUse(JsonNode payload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("id", payload.path("toolUseId").asText(""));
        data.put("name", payload.path("toolName").asText(""));
        data.set("input", payload.path("toolInput"));
        return new SseEvent("tool_use", data);
    }

    private SseEvent toolResult(JsonNode payload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("toolUseId", payload.path("toolUseId").asText(""));
        data.put("stdout", payload.path("stdout").asText(""));
        data.put("stderr", payload.path("stderr").asText(""));
        data.put("interrupted", payload.path("interrupted").asBoolean(false));
        return new SseEvent("tool_result", data);
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
