package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 *
 * <p>Usage is collected from assistant {@code message.updated} events (which emit no UI events). On
 * {@code turn_complete}, {@code costUsd} is the cumulative session cost, while {@code inputTokens},
 * {@code outputTokens} and {@code durationMs} are per turn. Messages counted in a finished turn are never counted
 * again, even if late updates for them arrive.
 */
public class OpenCodeEventNormalizer {

    /** Session events that are understood but not (yet) surfaced to the UI. */
    private static final Set<String> IGNORED_EVENT_TYPES = Set.of(
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
    /** Permission keys that guard a tool call rather than name a tool (e.g. directory access). */
    private static final Set<String> GUARD_PERMISSIONS = Set.of("external_directory", "doom_loop");

    private static final Set<String> IGNORED_PART_TYPES = Set.of(
            "step-start", "step-finish", "snapshot", "patch", "file", "agent", "retry", "compaction", "subtask");

    private final Set<String> toolUsesEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> toolResultsEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> reasoningPartsSeen = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastTextByPart = new ConcurrentHashMap<>();
    private final Map<String, ToolCall> toolCalls = new ConcurrentHashMap<>();

    /** Tool name and input recorded from a tool part, keyed by call ID. */
    private record ToolCall(String name, JsonNode input) {
    }

    /** Usage reported by one assistant message, keyed by message ID. */
    private record MessageUsage(double cost, long inputTokens, long outputTokens, long created, long completed) {
    }

    private final Map<String, MessageUsage> turnUsage = new ConcurrentHashMap<>();
    private final Set<String> settledMessages = ConcurrentHashMap.newKeySet();
    private double sessionCostUsd;

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
            case "message.updated" -> trackUsage(eventData);
            case "permission.asked", "permission.updated" -> permission(eventData);
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
        if (!callId.isEmpty()) {
            JsonNode input = state.path("input");
            ToolCall previous = toolCalls.get(callId);
            boolean hasInput = input.isObject() && input.size() > 0;
            if (previous == null || hasInput) {
                toolCalls.put(callId, new ToolCall(part.path("tool").asText(""),
                        hasInput ? input : JsonNodeFactory.instance.objectNode()));
            }
        }
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
        if ("completed".equals(status) || "error".equals(status)) {
            toolCalls.remove(callId);
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

    /**
     * Maps a permission request. When OpenCode asks before the tool part left {@code pending} (e.g. glob), no
     * {@code tool_use} has been emitted yet, so one is emitted first so the UI can attach the permission to it.
     */
    private List<SseEvent> permission(JsonNode payload) {
        String callId = firstNonBlank(payload.path("tool").path("callID").asText(""),
                payload.path("callID").asText(""));
        ToolCall call = callId.isEmpty() ? null : toolCalls.get(callId);
        String permissionKey = firstNonBlank(payload.path("permission").asText(""),
                payload.path("type").asText(""));
        String callName = call != null && !call.name().isEmpty() ? call.name() : permissionKey;
        ArrayNode patterns = JsonNodeFactory.instance.arrayNode();
        if (payload.path("patterns").isArray()) {
            payload.path("patterns").forEach(patterns::add);
        } else if (payload.path("pattern").isTextual()) {
            patterns.add(payload.path("pattern").asText());
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("requestId", payload.path("id").asText(""));
        data.put("permission", permissionKey);
        data.set("patterns", patterns);
        boolean guard = GUARD_PERMISSIONS.contains(permissionKey);
        data.put("toolName", guard ? permissionKey : callName);
        if (guard) {
            ObjectNode guardInput = JsonNodeFactory.instance.objectNode();
            guardInput.set("patterns", patterns.deepCopy());
            if (payload.path("metadata").isObject()) {
                guardInput.setAll((ObjectNode) payload.path("metadata"));
            }
            data.set("toolInput", guardInput);
        } else if (call != null && call.input().size() > 0) {
            data.set("toolInput", call.input());
        } else if (payload.path("metadata").isObject()) {
            data.set("toolInput", payload.path("metadata"));
        } else {
            data.set("toolInput", JsonNodeFactory.instance.objectNode());
        }
        SseEvent request = new SseEvent("permission_request", data);
        if (callId.isEmpty()) {
            return List.of(request);
        }
        data.put("toolUseId", callId);
        if (!toolUsesEmitted.add(callId)) {
            return List.of(request);
        }
        ObjectNode toolUse = JsonNodeFactory.instance.objectNode();
        toolUse.put("id", callId);
        toolUse.put("name", callName);
        toolUse.set("input", data.path("toolInput"));
        return List.of(new SseEvent("tool_use", toolUse), request);
    }

    private List<SseEvent> trackUsage(JsonNode eventData) {
        JsonNode info = eventData.path("info");
        String messageId = info.path("id").asText("");
        if (!"assistant".equals(info.path("role").asText("")) || messageId.isEmpty()
                || settledMessages.contains(messageId)) {
            return Collections.emptyList();
        }
        JsonNode tokens = info.path("tokens");
        long input = tokens.path("input").asLong(0)
                + tokens.path("cache").path("read").asLong(0)
                + tokens.path("cache").path("write").asLong(0);
        long output = tokens.path("output").asLong(0) + tokens.path("reasoning").asLong(0);
        turnUsage.put(messageId, new MessageUsage(info.path("cost").asDouble(0), input, output,
                info.path("time").path("created").asLong(0), info.path("time").path("completed").asLong(0)));
        return Collections.emptyList();
    }

    private SseEvent turnComplete(JsonNode payload) {
        List<MessageUsage> usages = new ArrayList<>(turnUsage.values());
        double turnCost = usages.stream().mapToDouble(MessageUsage::cost).sum();
        sessionCostUsd += turnCost;
        long inputTokens = usages.stream().mapToLong(MessageUsage::inputTokens).sum();
        long outputTokens = usages.stream().mapToLong(MessageUsage::outputTokens).sum();
        List<MessageUsage> timed = usages.stream()
                .filter(usage -> usage.created() > 0 && usage.completed() > 0)
                .toList();
        long durationMs = timed.isEmpty() ? 0
                : timed.stream().mapToLong(MessageUsage::completed).max().getAsLong()
                        - timed.stream().mapToLong(MessageUsage::created).min().getAsLong();
        settledMessages.addAll(turnUsage.keySet());
        turnUsage.clear();

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("sessionId", firstNonBlank(
                payload.path("sessionID").asText(""),
                payload.path("sessionId").asText(""),
                payload.path("session_id").asText("")));
        data.put("costUsd", sessionCostUsd);
        data.put("inputTokens", inputTokens);
        data.put("outputTokens", outputTokens);
        data.put("durationMs", durationMs);
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
