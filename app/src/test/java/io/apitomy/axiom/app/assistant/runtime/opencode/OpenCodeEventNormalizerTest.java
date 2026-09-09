package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeEventNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OpenCodeEventNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new OpenCodeEventNormalizer();
    }

    @Test
    void mapsAssistantTextEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"sessionID":"s1","part":{"type":"text","text":"hello"}}
                """);

        List<SseEvent> out = normalizer.normalize("session.message.part", payload);

        assertEquals(1, out.size());
        assertEquals("assistant_text", out.get(0).type());
        assertEquals("hello", out.get(0).data().path("text").asText());
    }

    @Test
    void mapsToolInvocationStartEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"toolUseId":"tu-1","toolName":"Write","toolInput":{"filePath":"notes.txt"}}
                """);

        List<SseEvent> out = normalizer.normalize("session.tool.started", payload);

        assertEquals(1, out.size());
        assertEquals("tool_use", out.get(0).type());
        assertEquals("tu-1", out.get(0).data().path("id").asText());
        assertEquals("Write", out.get(0).data().path("name").asText());
        assertEquals("notes.txt", out.get(0).data().path("input").path("filePath").asText());
    }

    @Test
    void mapsToolOutputResultEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"toolUseId":"tu-1","stdout":"done","stderr":"","interrupted":false}
                """);

        List<SseEvent> out = normalizer.normalize("session.tool.completed", payload);

        assertEquals(1, out.size());
        assertEquals("tool_result", out.get(0).type());
        assertEquals("tu-1", out.get(0).data().path("toolUseId").asText());
        assertEquals("done", out.get(0).data().path("stdout").asText());
        assertEquals("", out.get(0).data().path("stderr").asText());
        assertEquals(false, out.get(0).data().path("interrupted").asBoolean(true));
    }

    @Test
    void mapsPermissionRequestEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"requestId":"perm-1","toolName":"Bash","toolInput":{"command":"ls"}}
                """);

        List<SseEvent> out = normalizer.normalize("session.permission.requested", payload);

        assertEquals(1, out.size());
        assertEquals("permission_request", out.get(0).type());
        assertEquals("perm-1", out.get(0).data().path("requestId").asText());
        assertEquals("Bash", out.get(0).data().path("toolName").asText());
        assertEquals("ls", out.get(0).data().path("toolInput").path("command").asText());
    }

    @Test
    void mapsTurnCompletionEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"sessionID":"s1","costUsd":0.02,"inputTokens":11,"outputTokens":7,"success":true}
                """);

        List<SseEvent> out = normalizer.normalize("session.turn.completed", payload);

        assertEquals(1, out.size());
        assertEquals("turn_complete", out.get(0).type());
        assertEquals("s1", out.get(0).data().path("sessionId").asText());
        assertEquals(0.02, out.get(0).data().path("costUsd").asDouble(), 0.0001);
        assertEquals(11, out.get(0).data().path("inputTokens").asLong());
        assertEquals(7, out.get(0).data().path("outputTokens").asLong());
        assertTrue(out.get(0).data().path("success").asBoolean());
    }

    @Test
    void ignoresIrrelevantEvents() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"x":"y"}
                """);

        List<SseEvent> out = normalizer.normalize("session.unknown.event", payload);

        assertTrue(out.isEmpty());
    }

    @Test
    void ignoresNullEventName() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"sessionID":"s1","part":{"type":"text","text":"hello"}}
                """);

        List<SseEvent> out = normalizer.normalize(null, payload);

        assertTrue(out.isEmpty());
    }
}
