package io.apitomy.axiom.app.assistant.runtime;

import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeInteractiveSessionDriverTest {

    @Test
    void parsesAssistantAndPermissionEventsWithoutRenaming() {
        AssistantEventParser parser = new AssistantEventParser();
        List<SseEvent> emittedEvents = new ArrayList<>();
        List<SseEvent> permissionEvents = new ArrayList<>();
        ClaudeInteractiveSessionDriver driver = new ClaudeInteractiveSessionDriver(
                Path.of("/tmp"),
                Path.of("/tmp"),
                List.of("claude"),
                Map.of(),
                parser,
                emittedEvents::add,
                permissionEvents::add
        );

        driver.handleStdoutLine("""
                {"type":"assistant","message":{"content":[{"type":"text","text":"hello"},{"type":"tool_use","id":"tu-1","name":"Write","input":{"file_path":"notes.txt"}}]}}
                """);
        driver.handleStdoutLine("""
                {"type":"control_request","request_id":"perm-1","request":{"subtype":"can_use_tool","tool_name":"Write","description":"Write a file","input":{"file_path":"notes.txt"}}}
                """);
        driver.handleStdoutLine("""
                {"type":"result","subtype":"success","session_id":"s-1","total_cost_usd":0.01,"duration_ms":12,"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4}}
                """);

        assertEquals(List.of("assistant_text", "tool_use", "turn_complete"),
                emittedEvents.stream().map(SseEvent::type).toList());
        assertEquals("hello", emittedEvents.get(0).data().path("text").asText());
        assertEquals("Write", emittedEvents.get(1).data().path("name").asText());
        assertEquals(0.01, emittedEvents.get(2).data().path("costUsd").asDouble(), 0.0001);

        assertEquals(List.of("permission_request"), permissionEvents.stream().map(SseEvent::type).toList());
        assertEquals("perm-1", permissionEvents.get(0).data().path("requestId").asText());
        assertEquals("Write", permissionEvents.get(0).data().path("toolName").asText());
    }
}
