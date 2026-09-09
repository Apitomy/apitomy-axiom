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
        JsonNode safePayload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        return switch (eventName) {
            case "session.message.part" -> mapMessagePart(safePayload);
            case "session.tool.started" -> List.of(toolUse(safePayload));
            case "session.tool.completed" -> List.of(toolResult(safePayload));
            case "session.permission.requested" -> List.of(permission(safePayload));
            case "session.turn.completed" -> List.of(turnComplete(safePayload));
            default -> Collections.emptyList();
        };
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
        data.put("requestId", payload.path("requestId").asText(""));
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
        data.put("sessionId", payload.path("sessionID").asText(""));
        data.put("costUsd", payload.path("costUsd").asDouble(0));
        data.put("inputTokens", payload.path("inputTokens").asLong(0));
        data.put("outputTokens", payload.path("outputTokens").asLong(0));
        data.put("success", payload.path("success").asBoolean(false));
        return new SseEvent("turn_complete", data);
    }
}
