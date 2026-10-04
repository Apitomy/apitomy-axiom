package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "session.diff",
            "session.compacted",
            "permission.replied",
            "question.replied",
            "question.rejected");

    /** OpenCode's tool that asks the user questions. */
    static final String QUESTION_TOOL = "question";
    /** Tool name the UI renders as an interactive question form; also the {@code permission} of question requests. */
    public static final String ASK_USER_QUESTION = "AskUserQuestion";

    /** Message part types that are understood but not surfaced to the UI. */
    /** Permission keys that guard a tool call rather than name a tool (e.g. directory access). */
    private static final Set<String> GUARD_PERMISSIONS = Set.of("external_directory", "doom_loop");

    private static final Set<String> IGNORED_PART_TYPES = Set.of(
            "step-start", "step-finish", "snapshot", "patch", "file", "agent", "retry", "compaction", "subtask");

    private final Set<String> toolUsesEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> toolResultsEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> reasoningPartsSeen = ConcurrentHashMap.newKeySet();
    private final Set<String> reasoningTextEmitted = ConcurrentHashMap.newKeySet();
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

    /** Maximum length of a subagent summary taken from the raw task output. */
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final Pattern TASK_RESULT = Pattern.compile("<task_result>\\s*(.*?)\\s*</task_result>",
            Pattern.DOTALL);

    /** Known child (subagent) session ids. This normalizer is the single owner of this set. */
    private final Set<String> childSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> parentCallByChild = new ConcurrentHashMap<>();
    private final Map<String, String> childByParentCall = new ConcurrentHashMap<>();
    private final Map<String, Long> subagentStartMillis = new ConcurrentHashMap<>();
    private final Set<String> subagentsCompleted = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> childToolCallIds = new ConcurrentHashMap<>();
    private final Map<String, ToolCall> childToolCalls = new ConcurrentHashMap<>();
    private final Set<String> childProgressEmitted = ConcurrentHashMap.newKeySet();
    /** Nested (grandchild and deeper) session id to the id of the child session that spawned it. */
    private final Map<String, String> parentSessionByNested = new ConcurrentHashMap<>();

    /**
     * Registers a child (subagent) session of the current session, e.g. from its {@code session.created} event.
     *
     * @param childSessionId child session id
     */
    public void registerChildSession(String childSessionId) {
        if (childSessionId != null && !childSessionId.isBlank()) {
            childSessions.add(childSessionId);
        }
    }

    /**
     * Registers a nested subagent session (a session spawned by a known child session). Nested sessions are treated
     * as children and are attributed to the same top-level parent {@code task} call as their ancestor child session,
     * including when that mapping only becomes known later.
     *
     * @param nestedSessionId nested session id
     * @param parentSessionId id of the known child session that created it
     */
    public void registerNestedSession(String nestedSessionId, String parentSessionId) {
        if (nestedSessionId == null || nestedSessionId.isBlank() || parentSessionId == null
                || parentSessionId.isBlank() || nestedSessionId.equals(parentSessionId)) {
            return;
        }
        parentSessionByNested.put(nestedSessionId, parentSessionId);
        childSessions.add(nestedSessionId);
    }

    /** Resolves a (possibly nested) child session to its top-level child session. */
    private String rootChildSession(String sessionId) {
        String current = sessionId;
        Set<String> seen = new java.util.HashSet<>();
        while (current != null && seen.add(current)) {
            String parent = parentSessionByNested.get(current);
            if (parent == null) {
                return current;
            }
            current = parent;
        }
        return sessionId;
    }

    /**
     * Returns the known child (subagent) session ids, learned from parent {@code task} parts and from
     * {@link #registerChildSession(String)}.
     *
     * @return read-only view of the child session ids
     */
    public Set<String> childSessionIds() {
        return Collections.unmodifiableSet(childSessions);
    }

    /**
     * Converts an event of a child (subagent) session. Child tool parts become {@code subagent_progress} and child
     * permission requests become {@code permission_request} (with {@code subagentToolUseId} when the parent task
     * call is known). Every other child event is ignored, so a child never ends the parent turn or adds text,
     * todos or cost.
     *
     * @param eventName OpenCode event name
     * @param payload OpenCode event payload
     * @param childSessionId the child session the event belongs to
     * @return normalized assistant events (empty when the event is ignored)
     */
    public List<SseEvent> normalizeChild(String eventName, JsonNode payload, String childSessionId) {
        if (eventName == null || eventName.isBlank()) {
            return Collections.emptyList();
        }
        JsonNode safePayload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
        JsonNode eventData = eventData(safePayload);
        String resolvedType = resolvedEventType(eventName, safePayload);
        String rootChildId = childSessionId == null ? null : rootChildSession(childSessionId);
        String parentCallId = rootChildId == null ? null : parentCallByChild.get(rootChildId);
        return switch (resolvedType) {
            case "message.part.updated" -> "tool".equals(eventData.path("part").path("type").asText(""))
                    ? childToolProgress(eventData.path("part"), rootChildId, parentCallId)
                    : Collections.emptyList();
            case "permission.asked", "permission.updated" -> {
                ObjectNode data = permissionData(eventData, childToolCalls);
                if (parentCallId != null) {
                    data.put("subagentToolUseId", parentCallId);
                }
                yield List.of(new SseEvent("permission_request", data));
            }
            case "question.asked" -> {
                ObjectNode data = questionData(eventData);
                if (parentCallId != null) {
                    data.put("subagentToolUseId", parentCallId);
                }
                yield List.of(new SseEvent("permission_request", data));
            }
            default -> Collections.emptyList();
        };
    }

    private List<SseEvent> childToolProgress(JsonNode part, String childSessionId, String parentCallId) {
        String callId = firstNonBlank(part.path("callID").asText(""), part.path("id").asText(""));
        if (callId.isEmpty()) {
            return Collections.emptyList();
        }
        JsonNode state = part.path("state");
        String tool = part.path("tool").asText("");
        JsonNode input = state.path("input");
        if (input.isObject() && input.size() > 0) {
            childToolCalls.put(callId, new ToolCall(tool, input));
        } else {
            childToolCalls.putIfAbsent(callId, new ToolCall(tool, JsonNodeFactory.instance.objectNode()));
        }
        Set<String> calls = childToolCallIds.computeIfAbsent(childSessionId, key -> ConcurrentHashMap.newKeySet());
        calls.add(callId);
        if ("pending".equals(state.path("status").asText("")) || parentCallId == null
                || !childProgressEmitted.add(callId)) {
            return Collections.emptyList();
        }
        Long start = subagentStartMillis.get(parentCallId);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("toolUseId", parentCallId);
        data.put("taskId", childSessionId);
        data.put("description", firstNonBlank(state.path("title").asText(""),
                input.path("description").asText(""), tool));
        data.put("lastToolName", tool);
        data.put("toolCount", calls.size());
        data.put("durationMs", start == null ? 0 : Math.max(0, System.currentTimeMillis() - start));
        return List.of(new SseEvent("subagent_progress", data));
    }

    /** Emits subagent lifecycle events for a parent {@code task} tool part. */
    private void subagentLifecycle(String callId, JsonNode state, String status, List<SseEvent> events) {
        String childSessionId = firstNonBlank(state.path("metadata").path("sessionId").asText(""),
                childByParentCall.getOrDefault(callId, ""));
        if (childSessionId.isEmpty()) {
            return;
        }
        if (childByParentCall.putIfAbsent(callId, childSessionId) == null) {
            childSessions.add(childSessionId);
            parentCallByChild.put(childSessionId, callId);
            subagentStartMillis.put(callId, System.currentTimeMillis());
            JsonNode input = state.path("input");
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", callId);
            data.put("taskId", childSessionId);
            data.put("description", firstNonBlank(input.path("description").asText(""),
                    state.path("title").asText("")));
            data.put("subagentType", input.path("subagent_type").asText(""));
            events.add(new SseEvent("subagent_started", data));
        }
        if (("completed".equals(status) || "error".equals(status)) && subagentsCompleted.add(callId)) {
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("toolUseId", callId);
            data.put("taskId", childSessionId);
            data.put("status", "completed".equals(status) ? "completed" : "failed");
            data.put("summary", subagentSummary("completed".equals(status)
                    ? state.path("output").asText("")
                    : firstNonBlank(state.path("error").asText(""), "Subagent failed")));
            events.add(new SseEvent("subagent_completed", data));
        }
    }

    private static String subagentSummary(String output) {
        Matcher matcher = TASK_RESULT.matcher(output);
        String summary = matcher.find() ? matcher.group(1) : output;
        return summary.length() > MAX_SUMMARY_LENGTH ? summary.substring(0, MAX_SUMMARY_LENGTH) : summary;
    }

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
            case "question.asked" -> question(eventData);
            case "session.turn.completed", "session.idle" -> List.of(turnComplete(eventData));
            case "session.error" -> List.of(sessionError(eventData));
            // Retry notices may repeat: one is emitted per session.status retry event.
            case "session.status" -> sessionStatus(eventData);
            case "todo.updated" -> todos(eventData);
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
        if (partId.isEmpty()) {
            return List.of(new SseEvent("thinking", JsonNodeFactory.instance.objectNode()));
        }
        boolean firstSighting = reasoningPartsSeen.add(partId);
        String text = part.path("text").asText("");
        boolean finished = !part.path("time").path("end").isMissingNode() && !part.path("time").path("end").isNull();
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("id", partId);
        if (finished && !text.isBlank() && reasoningTextEmitted.add(partId)) {
            data.put("text", text);
            return List.of(new SseEvent("thinking", data));
        }
        return firstSighting ? List.of(new SseEvent("thinking", data)) : Collections.emptyList();
    }

    /** Maps {@code todo.updated} to a {@code todos} event carrying the full (replacement) todo list. */
    private List<SseEvent> todos(JsonNode eventData) {
        JsonNode todos = eventData.path("todos");
        if (!todos.isArray()) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.set("todos", AssistantEventParser.normalizeTodos(todos));
        return List.of(new SseEvent("todos", data));
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
            data.put("name", displayToolName(part.path("tool").asText("")));
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
            data.put("isError", "error".equals(status));
            events.add(new SseEvent("tool_result", data));
        }
        if ("task".equals(part.path("tool").asText(""))) {
            subagentLifecycle(callId, state, status, events);
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
        ObjectNode data = permissionData(payload, toolCalls);
        SseEvent request = new SseEvent("permission_request", data);
        String callId = data.path("toolUseId").asText("");
        if (callId.isEmpty()) {
            return List.of(request);
        }
        if (!toolUsesEmitted.add(callId)) {
            return List.of(request);
        }
        ToolCall call = toolCalls.get(callId);
        String callName = call != null && !call.name().isEmpty() ? call.name() : data.path("permission").asText("");
        ObjectNode toolUse = JsonNodeFactory.instance.objectNode();
        toolUse.put("id", callId);
        toolUse.put("name", callName);
        toolUse.set("input", data.path("toolInput"));
        return List.of(new SseEvent("tool_use", toolUse), request);
    }

    /** Maps OpenCode's question tool to the tool name the UI renders as a question form. */
    private static String displayToolName(String tool) {
        return QUESTION_TOOL.equals(tool) ? ASK_USER_QUESTION : tool;
    }

    /**
     * Maps {@code question.asked} to an {@code AskUserQuestion} permission request. A {@code tool_use} is emitted
     * first when the question tool part has not been shown yet, so the UI can attach the question form to it.
     */
    private List<SseEvent> question(JsonNode payload) {
        ObjectNode data = questionData(payload);
        SseEvent request = new SseEvent("permission_request", data);
        String callId = data.path("toolUseId").asText("");
        if (callId.isEmpty() || !toolUsesEmitted.add(callId)) {
            return List.of(request);
        }
        ObjectNode toolUse = JsonNodeFactory.instance.objectNode();
        toolUse.put("id", callId);
        toolUse.put("name", ASK_USER_QUESTION);
        toolUse.set("input", data.path("toolInput"));
        return List.of(new SseEvent("tool_use", toolUse), request);
    }

    /** Builds {@code permission_request} data for a question, in the UI's {@code AskUserQuestion} input shape. */
    private static ObjectNode questionData(JsonNode payload) {
        ArrayNode questions = JsonNodeFactory.instance.arrayNode();
        payload.path("questions").forEach(question -> {
            ObjectNode copy = question.isObject()
                    ? ((ObjectNode) question).deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            copy.put("multiSelect", question.path("multiple").asBoolean(false));
            questions.add(copy);
        });
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.set("questions", questions);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("requestId", payload.path("id").asText(""));
        // Same as toolName so the UI does not treat the request as a guard permission.
        data.put("permission", ASK_USER_QUESTION);
        data.put("toolName", ASK_USER_QUESTION);
        data.set("toolInput", input);
        data.set("patterns", JsonNodeFactory.instance.arrayNode());
        data.set("alwaysPatterns", JsonNodeFactory.instance.arrayNode());
        String callId = payload.path("tool").path("callID").asText("");
        if (!callId.isEmpty()) {
            data.put("toolUseId", callId);
        }
        return data;
    }

    /** Builds {@code permission_request} data, looking the tool call up in {@code calls}. */
    private ObjectNode permissionData(JsonNode payload, Map<String, ToolCall> calls) {
        String callId = firstNonBlank(payload.path("tool").path("callID").asText(""),
                payload.path("callID").asText(""));
        ToolCall call = callId.isEmpty() ? null : calls.get(callId);
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
        // Patterns OpenCode will approve for the rest of the session on an "always" reply.
        ArrayNode always = JsonNodeFactory.instance.arrayNode();
        if (payload.path("always").isArray()) {
            payload.path("always").forEach(always::add);
        }
        data.set("alwaysPatterns", always);
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
        if (!callId.isEmpty()) {
            data.put("toolUseId", callId);
        }
        return data;
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
        // Both session.idle and the session.turn.completed alias use the collected usage, not payload fields.
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

    /** Maps a provider retry status to a non-terminal notice; busy and idle are ignored. */
    private List<SseEvent> sessionStatus(JsonNode payload) {
        JsonNode status = payload.path("status");
        if (!"retry".equals(status.path("type").asText(""))) {
            return Collections.emptyList();
        }
        String attempt = status.path("attempt").isMissingNode() ? "?" : status.path("attempt").asText("?");
        String reason = firstNonBlank(status.path("message").asText(""), "provider error");
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", "ProviderRetry");
        data.put("message", "Retrying (attempt " + attempt + "): " + reason);
        return List.of(new SseEvent("session_error", data));
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
