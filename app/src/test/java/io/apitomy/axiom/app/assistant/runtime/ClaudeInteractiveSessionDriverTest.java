package io.apitomy.axiom.app.assistant.runtime;

import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void interruptSendsControlRequestOnStdinAndKeepsProcessRunning(@TempDir Path dir) throws Exception {
        Path captured = dir.resolve("stdin.jsonl");
        ClaudeInteractiveSessionDriver driver = new ClaudeInteractiveSessionDriver(
                dir, dir, List.of("sh", "-c", "cat > " + captured), Map.of(),
                new AssistantEventParser(), event -> { }, event -> { });
        driver.start();
        try {
            driver.interrupt();

            String content = waitForContent(captured, Duration.ofSeconds(5));
            JsonNode request = new ObjectMapper().readTree(content.lines().findFirst().orElseThrow());
            assertEquals("control_request", request.path("type").asText());
            assertEquals("interrupt", request.path("request").path("subtype").asText());
            assertFalse(request.path("request_id").asText().isBlank(), content);
            assertTrue(driver.isAlive(), "interrupt must not kill the Claude process");
        } finally {
            driver.destroy();
        }
    }

    @Test
    void interruptFailureEmitsInterruptFailedWarning(@TempDir Path dir) throws Exception {
        List<SseEvent> events = new CopyOnWriteArrayList<>();
        ClaudeInteractiveSessionDriver driver = new ClaudeInteractiveSessionDriver(
                dir, dir, List.of("sh", "-c", "exec 0<&-; sleep 5"), Map.of(),
                new AssistantEventParser(), events::add, event -> { });
        driver.start();
        try {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(4));
            while (Instant.now().isBefore(deadline) && events.stream().noneMatch(
                    e -> "InterruptFailed".equals(e.data().path("name").asText()))) {
                driver.interrupt();
                Thread.sleep(100);
            }
            assertTrue(events.stream().anyMatch(e -> "session_error".equals(e.type())
                    && "InterruptFailed".equals(e.data().path("name").asText())), events.toString());
        } finally {
            driver.destroy();
        }
    }

    private static String waitForContent(Path file, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(file) && Files.size(file) > 0) {
                return Files.readString(file);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No stdin content captured in " + file);
    }
}
