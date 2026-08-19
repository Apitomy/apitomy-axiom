package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses Claude Code NDJSON stream-json output into normalised {@link SseEvent}
 * records suitable for forwarding to the browser via Server-Sent Events.
 *
 * <p>Raw Claude Code event types are transformed into semantic event types that
 * the frontend can handle consistently. This follows the same approach as the
 * claude-pilot POC's {@code ClaudeEventParser}.</p>
 *
 * <h3>Event type mapping</h3>
 * <ul>
 *   <li>{@code system} (subtype init) → {@code session_init}</li>
 *   <li>{@code system} (subtype task_started, task_type local_agent) → {@code subagent_started}</li>
 *   <li>{@code system} (subtype task_started, other task_type) → {@code background_task_started}</li>
 *   <li>{@code system} (subtype task_progress) → {@code subagent_progress}</li>
 *   <li>{@code system} (subtype task_updated) → {@code subagent_status}</li>
 *   <li>{@code system} (subtype task_notification) → {@code subagent_completed}</li>
 *   <li>{@code assistant} → {@code assistant_text}, {@code tool_use}, {@code thinking}</li>
 *   <li>{@code assistant} with non-null {@code parent_tool_use_id} → suppressed</li>
 *   <li>{@code user} (with tool_use_result) → {@code tool_result}</li>
 *   <li>{@code user} with non-null {@code parent_tool_use_id} → suppressed</li>
 *   <li>{@code result} → {@code turn_complete}</li>
 *   <li>{@code sdk_control_request} / {@code control_request} → {@code permission_request}</li>
 *   <li>{@code conversation_reset} → {@code conversation_reset}</li>
 *   <li>{@code tool_progress} → {@code tool_progress}</li>
 * </ul>
 */
public class AssistantEventParser {

    private static final Logger LOG = Logger.getLogger(AssistantEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses a single NDJSON line from Claude Code's stream-json output into
     * zero or more normalised SSE events.
     *
     * @param line one line of NDJSON output
     * @return list of normalised events (may be empty, never null)
     */
    public List<SseEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode node = MAPPER.readTree(line);
            String type = node.path("type").asText("");

            return switch (type) {
                case "system" -> parseSystem(node);
                case "assistant" -> parseAssistant(node);
                case "user" -> parseUser(node);
                case "result" -> parseResult(node);
                case "sdk_control_request" -> parseSdkControlRequest(node);
                case "control_request" -> parseControlRequest(node);
                case "conversation_reset" -> parseConversationReset(node);
                case "tool_progress" -> parseToolProgress(node);
                default -> {
                    ObjectNode data = JsonNodeFactory.instance.objectNode();
                    data.put("rawType", type);
                    data.put("raw", node.toString());
                    yield List.of(new SseEvent("unhandled_event", data));
                }
            };
        } catch (Exception e) {
            LOG.tracef("Failed to parse NDJSON line: %s",
                    line.substring(0, Math.min(line.length(), 200)));
            return Collections.emptyList();
        }
    }

    /**
     * Parses system events: session init and subagent lifecycle.
     *
     * @param root the raw NDJSON node
     * @return normalised SSE events for the frontend
     */
    private List<SseEvent> parseSystem(JsonNode root) {
        String subtype = root.path("subtype").asText("");
        return switch (subtype) {
            case "init" -> {
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                data.put("sessionId", root.path("session_id").asText());
                data.put("cwd", root.path("cwd").asText());
                data.put("model", root.path("model").asText());
                JsonNode slashCommands = root.path("slash_commands");
                if (slashCommands.isArray()) {
                    data.set("slashCommands", slashCommands);
                }
                JsonNode tools = root.path("tools");
                if (tools.isArray()) {
                    data.set("tools", tools);
                }
                yield List.of(new SseEvent("session_init", data));
            }
            case "task_started" -> {
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                data.put("toolUseId", root.path("tool_use_id").asText());
                data.put("taskId", root.path("task_id").asText());
                data.put("description", root.path("description").asText());
                String taskType = root.path("task_type").asText("");
                if ("local_agent".equals(taskType)) {
                    data.put("subagentType", root.path("subagent_type").asText());
                    yield List.of(new SseEvent("subagent_started", data));
                } else {
                    yield List.of(new SseEvent("background_task_started", data));
                }
            }
            case "task_progress" -> {
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                data.put("toolUseId", root.path("tool_use_id").asText());
                data.put("taskId", root.path("task_id").asText());
                data.put("description", root.path("description").asText());
                data.put("lastToolName", root.path("last_tool_name").asText());
                JsonNode usage = root.path("usage");
                data.put("toolCount", usage.path("tool_uses").asInt(0));
                data.put("durationMs", usage.path("duration_ms").asLong(0));
                yield List.of(new SseEvent("subagent_progress", data));
            }
            case "task_updated" -> {
                String status = root.path("patch").path("status").asText("");
                if (status.isEmpty()) {
                    yield Collections.emptyList();
                }
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                data.put("taskId", root.path("task_id").asText());
                data.put("status", status);
                yield List.of(new SseEvent("subagent_status", data));
            }
            case "task_notification" -> {
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                data.put("toolUseId", root.path("tool_use_id").asText());
                data.put("taskId", root.path("task_id").asText());
                data.put("status", root.path("status").asText());
                data.put("summary", root.path("summary").asText());
                yield List.of(new SseEvent("subagent_completed", data));
            }
            default -> Collections.emptyList();
        };
    }

    private List<SseEvent> parseAssistant(JsonNode root) {
        String parentToolUseId = root.path("parent_tool_use_id").asText("");
        if (!parentToolUseId.isEmpty()) {
            return Collections.emptyList();
        }
        JsonNode content = root.path("message").path("content");
        if (!content.isArray()) {
            return Collections.emptyList();
        }
        List<SseEvent> events = new ArrayList<>();
        for (JsonNode block : content) {
            String blockType = block.path("type").asText("");
            switch (blockType) {
                case "text" -> {
                    ObjectNode data = JsonNodeFactory.instance.objectNode();
                    data.put("text", block.path("text").asText());
                    events.add(new SseEvent("assistant_text", data));
                }
                case "tool_use" -> {
                    ObjectNode data = JsonNodeFactory.instance.objectNode();
                    data.put("id", block.path("id").asText());
                    data.put("name", block.path("name").asText());
                    data.set("input", block.path("input"));
                    events.add(new SseEvent("tool_use", data));
                }
                case "thinking" -> {
                    ObjectNode data = JsonNodeFactory.instance.objectNode();
                    events.add(new SseEvent("thinking", data));
                }
            }
        }
        return events;
    }

    private List<SseEvent> parseUser(JsonNode root) {
        String parentToolUseId = root.path("parent_tool_use_id").asText("");
        if (!parentToolUseId.isEmpty()) {
            return Collections.emptyList();
        }
        JsonNode toolResult = root.path("tool_use_result");
        if (toolResult.isMissingNode()) {
            return Collections.emptyList();
        }
        JsonNode content = root.path("message").path("content");
        String toolUseId = "";
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("tool_result".equals(block.path("type").asText())) {
                    toolUseId = block.path("tool_use_id").asText();
                    break;
                }
            }
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("toolUseId", toolUseId);
        data.put("stdout", toolResult.path("stdout").asText(""));
        data.put("stderr", toolResult.path("stderr").asText(""));
        data.put("interrupted", toolResult.path("interrupted").asBoolean(false));
        return List.of(new SseEvent("tool_result", data));
    }

    private List<SseEvent> parseResult(JsonNode root) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("sessionId", root.path("session_id").asText());
        data.put("costUsd", root.path("total_cost_usd").asDouble(0));
        data.put("durationMs", root.path("duration_ms").asLong(0));
        JsonNode usage = root.path("usage");
        data.put("inputTokens", usage.path("input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0));
        data.put("outputTokens", usage.path("output_tokens").asLong(0));
        data.put("success", "success".equals(root.path("subtype").asText()));
        return List.of(new SseEvent("turn_complete", data));
    }

    private List<SseEvent> parseSdkControlRequest(JsonNode root) {
        JsonNode request = root.path("request");
        if (!"permission".equals(request.path("subtype").asText())) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("requestId", request.path("request_id").asText());
        data.put("toolName", request.path("tool_name").asText());
        data.set("toolInput", request.path("tool_input"));
        String parentToolUseId = root.path("parent_tool_use_id").asText("");
        if (!parentToolUseId.isEmpty()) {
            data.put("subagentToolUseId", parentToolUseId);
        }
        return List.of(new SseEvent("permission_request", data));
    }

    private List<SseEvent> parseControlRequest(JsonNode root) {
        JsonNode request = root.path("request");
        String subtype = request.path("subtype").asText("");
        if (!"can_use_tool".equals(subtype)) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("requestId", root.path("request_id").asText());
        data.put("toolName", request.path("tool_name").asText());
        data.put("description", request.path("description").asText());
        data.set("toolInput", request.path("input"));
        String parentToolUseId = root.path("parent_tool_use_id").asText("");
        if (!parentToolUseId.isEmpty()) {
            data.put("subagentToolUseId", parentToolUseId);
        }
        return List.of(new SseEvent("permission_request", data));
    }

    /**
     * Parses a conversation_reset event emitted when the user sends the
     * {@code /clear} slash command.
     *
     * @param root the raw NDJSON node
     * @return a single conversation_reset SSE event
     */
    private List<SseEvent> parseConversationReset(JsonNode root) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        return List.of(new SseEvent("conversation_reset", data));
    }

    /**
     * Parses a tool_progress event that reports elapsed time while a tool
     * is executing.
     *
     * @param root the raw NDJSON node
     * @return a single tool_progress SSE event, or empty if required fields are missing
     */
    private List<SseEvent> parseToolProgress(JsonNode root) {
        JsonNode toolUseId = root.path("tool_use_id");
        JsonNode toolName = root.path("tool_name");
        JsonNode elapsed = root.path("elapsed_time_seconds");
        if (toolUseId.isMissingNode() || toolName.isMissingNode() || elapsed.isMissingNode()) {
            return Collections.emptyList();
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("toolUseId", toolUseId.asText());
        data.put("toolName", toolName.asText());
        data.put("elapsedSeconds", elapsed.asInt());
        return List.of(new SseEvent("tool_progress", data));
    }

    /**
     * A normalised SSE event parsed from Claude Code's NDJSON output.
     *
     * @param type the normalised event type (session_init, assistant_text,
     *             tool_use, tool_result, tool_progress, turn_complete,
     *             permission_request, thinking, conversation_reset,
     *             subagent_started, subagent_progress, subagent_status,
     *             subagent_completed, background_task_started)
     * @param data the extracted/transformed JSON data
     */
    public record SseEvent(String type, JsonNode data) {

        /**
         * Serialises this event to a JSON string for transmission over SSE.
         *
         * @return JSON string representation
         */
        public String toJson() {
            return data.toString();
        }
    }
}
