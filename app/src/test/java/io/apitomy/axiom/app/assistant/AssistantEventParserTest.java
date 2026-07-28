package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AssistantEventParser}. Verifies that Claude Code
 * NDJSON stream events are correctly parsed and normalised into typed
 * {@link SseEvent} records matching the claude-pilot POC conventions.
 */
class AssistantEventParserTest {

    private AssistantEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new AssistantEventParser();
    }

    // ── System events ───────────────────────────────────────────────

    @Test
    void parseSystemInitEvent() {
        String line = """
                {"type":"system","subtype":"init","session_id":"sess-123","cwd":"/tmp","model":"sonnet"}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        SseEvent event = events.get(0);
        assertEquals("session_init", event.type());
        assertEquals("sess-123", event.data().path("sessionId").asText());
        assertEquals("sonnet", event.data().path("model").asText());
    }

    @Test
    void parseSystemNonInitIsIgnored() {
        String line = """
                {"type":"system","subtype":"other"}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── Assistant events ────────────────────────────────────────────

    @Test
    void parseAssistantTextMessage() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello, I can help."}]}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("assistant_text", events.get(0).type());
        assertEquals("Hello, I can help.", events.get(0).data().path("text").asText());
    }

    @Test
    void parseAssistantToolUseContent() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu-1","name":"Write","input":{"file_path":"tools/test.json"}}]}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("tool_use", events.get(0).type());
        assertEquals("Write", events.get(0).data().path("name").asText());
        assertEquals("tu-1", events.get(0).data().path("id").asText());
    }

    @Test
    void parseAssistantMultipleContentBlocks() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Let me write that."},{"type":"tool_use","id":"tu-2","name":"Write","input":{}}]}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(2, events.size());
        assertEquals("assistant_text", events.get(0).type());
        assertEquals("tool_use", events.get(1).type());
    }

    @Test
    void parseAssistantThinkingBlock() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"reasoning..."}]}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("thinking", events.get(0).type());
    }

    @Test
    void parseAssistantNoContentArray() {
        String line = """
                {"type":"assistant","message":{}}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── User/tool result events ─────────────────────────────────────

    @Test
    void parseUserToolResult() {
        String line = """
                {"type":"user","tool_use_result":{"stdout":"file written","stderr":"","interrupted":false},"message":{"content":[{"type":"tool_result","tool_use_id":"tu-1"}]}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("tool_result", events.get(0).type());
        assertEquals("tu-1", events.get(0).data().path("toolUseId").asText());
        assertEquals("file written", events.get(0).data().path("stdout").asText());
    }

    @Test
    void parseUserWithoutToolResultIsIgnored() {
        String line = """
                {"type":"user","message":{"content":[{"type":"text","text":"hello"}]}}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── Result events ───────────────────────────────────────────────

    @Test
    void parseResultEvent() {
        String line = """
                {"type":"result","subtype":"success","session_id":"s-1","total_cost_usd":0.05,"duration_ms":12000}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("turn_complete", events.get(0).type());
        assertTrue(events.get(0).data().path("success").asBoolean());
        assertEquals(0.05, events.get(0).data().path("costUsd").asDouble(), 0.001);
    }

    // ── Permission request events (sdk_control_request) ─────────────

    @Test
    void parseSdkControlRequest() {
        String line = """
                {"type":"sdk_control_request","request":{"subtype":"permission","request_id":"perm-1","tool_name":"Bash","tool_input":{"command":"ls"}}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("permission_request", events.get(0).type());
        assertEquals("perm-1", events.get(0).data().path("requestId").asText());
        assertEquals("Bash", events.get(0).data().path("toolName").asText());
    }

    @Test
    void parseSdkControlRequestNonPermissionIsIgnored() {
        String line = """
                {"type":"sdk_control_request","request":{"subtype":"other"}}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── Permission request events (control_request) ─────────────────

    @Test
    void parseControlRequest() {
        String line = """
                {"type":"control_request","request_id":"perm-2","request":{"subtype":"can_use_tool","tool_name":"Write","description":"Write a file","input":{"file_path":"test.json"}}}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        assertEquals("permission_request", events.get(0).type());
        assertEquals("perm-2", events.get(0).data().path("requestId").asText());
        assertEquals("Write", events.get(0).data().path("toolName").asText());
    }

    @Test
    void parseControlRequestNonCanUseToolIsIgnored() {
        String line = """
                {"type":"control_request","request":{"subtype":"other"}}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── Conversation reset ────────────────────────────────────────

    @Test
    void parseConversationResetEvent() {
        String line = """
                {"type":"conversation_reset"}""";

        List<SseEvent> events = parser.parse(line);
        assertEquals(1, events.size());
        assertEquals("conversation_reset", events.get(0).type());
    }

    // ── Tool progress events ──────────────────────────────────────

    @Test
    void parseToolProgressEvent() {
        String line = """
                {"type":"tool_progress","tool_use_id":"tu-42","tool_name":"Bash","elapsed_time_seconds":5}""";

        List<SseEvent> events = parser.parse(line);

        assertEquals(1, events.size());
        SseEvent event = events.get(0);
        assertEquals("tool_progress", event.type());
        assertEquals("tu-42", event.data().path("toolUseId").asText());
        assertEquals("Bash", event.data().path("toolName").asText());
        assertEquals(5, event.data().path("elapsedSeconds").asInt());
    }

    @Test
    void parseToolProgressMissingFieldsIsIgnored() {
        String line = """
                {"type":"tool_progress","tool_use_id":"tu-42"}""";

        List<SseEvent> events = parser.parse(line);
        assertTrue(events.isEmpty());
    }

    // ── Unknown types ───────────────────────────────────────────────

    @Test
    void parseUnknownTypeReturnsUnhandledEvent() {
        String line = """
                {"type":"something_else","data":"ignored"}""";

        List<SseEvent> events = parser.parse(line);
        assertEquals(1, events.size());
        assertEquals("unhandled_event", events.get(0).type());
        assertEquals("something_else", events.get(0).data().path("rawType").asText());
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void parseNullReturnsEmpty() {
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void parseEmptyStringReturnsEmpty() {
        assertTrue(parser.parse("").isEmpty());
    }

    @Test
    void parseBlankStringReturnsEmpty() {
        assertTrue(parser.parse("   ").isEmpty());
    }

    @Test
    void parseMalformedJsonReturnsEmpty() {
        assertTrue(parser.parse("this is not json").isEmpty());
    }

    @Test
    void parsePartialJsonReturnsEmpty() {
        assertTrue(parser.parse("{\"type\":").isEmpty());
    }

    // ── toJson serialisation ────────────────────────────────────────

    @Test
    void toJsonProducesValidString() {
        String line = """
                {"type":"system","subtype":"init","session_id":"s-1","cwd":"/tmp","model":"m"}""";

        List<SseEvent> events = parser.parse(line);
        assertEquals(1, events.size());

        String json = events.get(0).toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"sessionId\""));
    }
}
