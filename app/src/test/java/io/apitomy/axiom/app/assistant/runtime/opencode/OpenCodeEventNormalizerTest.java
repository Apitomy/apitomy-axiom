package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeEventFixtures.first;
import static io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeEventFixtures.partOfType;
import static io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeEventFixtures.toolPart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeEventNormalizerTest {

    private static final String TOOL_CALLS = "1.18.33-tool-calls.jsonl";
    private static final String TOOL_ERROR = "1.18.33-tool-error.jsonl";

    private final ObjectMapper mapper = new ObjectMapper();
    private OpenCodeEventNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new OpenCodeEventNormalizer();
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
    void ignoresNullEventName() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"sessionID":"s1","part":{"type":"text","text":"hello"}}
                """);

        List<SseEvent> out = normalizer.normalize(null, payload);

        assertTrue(out.isEmpty());
    }

    @Test
    void mapsEnvelopePermissionAskedEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"permission.asked","properties":{"requestID":"per_1","toolName":"Bash","toolInput":{"command":"ls"}}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("permission_request", out.get(0).type());
        assertEquals("per_1", out.get(0).data().path("requestId").asText());
        assertEquals("Bash", out.get(0).data().path("toolName").asText());
    }

    @Test
    void mapsSessionIdleToTurnComplete() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"session.idle","properties":{"sessionID":"s1"}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("turn_complete", out.get(0).type());
        assertEquals("s1", out.get(0).data().path("sessionId").asText());
        assertTrue(out.get(0).data().path("success").asBoolean(false));
    }

    @Test
    void mapsSessionErrorToSessionErrorEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"session.error","properties":{"sessionID":"s1","error":{"name":"UnknownError","data":{"message":"Model not found"}}}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("session_error", out.get(0).type());
        assertEquals("Model not found", out.get(0).data().path("message").asText());
        assertEquals("UnknownError", out.get(0).data().path("name").asText());
    }

    private List<SseEvent> normalize(JsonNode event) {
        return normalizer.normalize(event.path("type").asText(), event);
    }

    @Test
    void pendingToolPartEmitsNothing() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(normalize(first(events, toolPart("read", "pending"))).isEmpty());
    }

    @Test
    void runningToolPartEmitsToolUseWithCallIdNameAndInput() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        List<SseEvent> out = normalize(first(events, toolPart("read", "running")));

        assertEquals(1, out.size());
        assertEquals("tool_use", out.get(0).type());
        assertEquals("toolu_01AaGr1CuwaqRudKSw25JKcY", out.get(0).data().path("id").asText());
        assertEquals("read", out.get(0).data().path("name").asText());
        assertEquals("/tmp/opencode/cap/hello.txt", out.get(0).data().path("input").path("filePath").asText());
    }

    @Test
    void repeatedRunningUpdatesEmitToolUseOnce() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        long toolUses = events.stream()
                .filter(toolPart("bash", "running"))
                .flatMap(event -> normalize(event).stream())
                .filter(event -> "tool_use".equals(event.type()))
                .count();

        assertEquals(1, toolUses);
    }

    @Test
    void completedToolPartEmitsToolResultWithOutputAsStdout() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        normalize(first(events, toolPart("bash", "running")));

        List<SseEvent> out = normalize(first(events, toolPart("bash", "completed")));

        assertEquals(1, out.size());
        assertEquals("tool_result", out.get(0).type());
        assertEquals("toolu_01LG86GQEhToDrjJWeL12dQw", out.get(0).data().path("toolUseId").asText());
        assertEquals("captured\n", out.get(0).data().path("stdout").asText());
        assertEquals("", out.get(0).data().path("stderr").asText());
        assertFalse(out.get(0).data().path("interrupted").asBoolean(true));
    }

    @Test
    void erroredToolPartEmitsToolResultWithErrorAsStderr() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_ERROR);
        normalize(first(events, toolPart("read", "running")));

        List<SseEvent> out = normalize(first(events, toolPart("read", "error")));

        assertEquals(1, out.size());
        assertEquals("tool_result", out.get(0).type());
        assertEquals("toolu_01KZ1mRba9RZWy5Q6b4QAEv2", out.get(0).data().path("toolUseId").asText());
        assertEquals("", out.get(0).data().path("stdout").asText());
        assertEquals("File not found: /tmp/opencode/cap/does-not-exist.txt",
                out.get(0).data().path("stderr").asText());
    }

    @Test
    void completedToolPartWithoutPriorRunningEmitsToolUseThenResult() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        List<SseEvent> out = normalize(first(events, toolPart("read", "completed")));

        assertEquals(List.of("tool_use", "tool_result"), out.stream().map(SseEvent::type).toList());
        assertEquals(out.get(0).data().path("id").asText(), out.get(1).data().path("toolUseId").asText());
    }

    @Test
    void lateToolPartAfterIdleIsNotReEmitted() throws Exception {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        JsonNode completed = first(events, toolPart("read", "completed"));
        normalize(first(events, toolPart("read", "running")));
        normalize(completed);
        normalize(idleEvent());

        assertTrue(normalize(completed).isEmpty());
    }

    @Test
    void lateTextPartAfterIdleIsNotReEmitted() throws Exception {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        JsonNode done = first(events, partOfType("text")
                .and(event -> "DONE".equals(event.path("properties").path("part").path("text").asText())));
        assertEquals(1, normalize(done).size());
        normalize(idleEvent());

        assertTrue(normalize(done).isEmpty());
    }

    @Test
    void toolPartWithoutCallIdFallsBackToPartId() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"prt_x",\
                "sessionID":"s1","messageID":"m1","type":"tool","tool":"read","state":{"status":"completed",\
                "input":{"filePath":"a.txt"},"output":"ok"}}}}
                """);

        List<SseEvent> out = normalize(payload);

        assertEquals(List.of("tool_use", "tool_result"), out.stream().map(SseEvent::type).toList());
        assertEquals("prt_x", out.get(0).data().path("id").asText());
        assertEquals("prt_x", out.get(1).data().path("toolUseId").asText());
        assertEquals("ok", out.get(1).data().path("stdout").asText());
    }

    private JsonNode idleEvent() throws Exception {
        return mapper.readTree("{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}");
    }

    @Test
    void reasoningPartEmitsThinkingOncePerPart() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        List<String> types = events.stream()
                .filter(partOfType("reasoning"))
                .flatMap(event -> normalize(event).stream())
                .map(SseEvent::type)
                .toList();

        assertEquals(List.of("thinking"), types);
    }

    @Test
    void textPartEmitsAssistantTextOnlyForNonBlankNewText() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        Predicate<JsonNode> assistantText = partOfType("text")
                .and(event -> !event.path("properties").path("part").path("text").asText().startsWith("Use the"));

        List<SseEvent> out = events.stream()
                .filter(assistantText)
                .flatMap(event -> normalize(event).stream())
                .toList();
        List<SseEvent> repeated = normalize(first(events, assistantText
                .and(event -> "DONE".equals(event.path("properties").path("part").path("text").asText()))));

        assertEquals(1, out.size());
        assertEquals("assistant_text", out.get(0).type());
        assertEquals("DONE", out.get(0).data().path("text").asText());
        assertTrue(repeated.isEmpty());
    }

    @Test
    void stepPartsAndDeltasEmitNothing() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(normalize(first(events, partOfType("step-start"))).isEmpty());
        assertTrue(normalize(first(events, partOfType("step-finish"))).isEmpty());
        assertTrue(normalize(first(events, event -> "message.part.delta".equals(event.path("type").asText())))
                .isEmpty());
    }

    @Test
    void noSessionScopedFixtureEventIsUnhandled() {
        for (String fixture : List.of(TOOL_CALLS, TOOL_ERROR)) {
            OpenCodeEventNormalizer fresh = new OpenCodeEventNormalizer();
            List<String> unhandled = OpenCodeEventFixtures.load(fixture).stream()
                    .filter(OpenCodeEventFixtures::isSessionScoped)
                    .flatMap(event -> fresh.normalize(event.path("type").asText(), event).stream())
                    .filter(event -> "unhandled_event".equals(event.type()))
                    .map(event -> event.data().path("rawType").asText())
                    .toList();

            assertEquals(List.of(), unhandled, fixture);
        }
    }

    @Test
    void unknownEventTypeEmitsUnhandledEventWithRawPayload() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"session.brand.new","properties":{"sessionID":"s1","x":"y"}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("unhandled_event", out.get(0).type());
        assertEquals("session.brand.new", out.get(0).data().path("rawType").asText());
        assertEquals(payload.toString(), out.get(0).data().path("raw").asText());
    }

    @Test
    void unknownPartTypeEmitsUnhandledEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1",
                 "part":{"id":"p1","sessionID":"s1","messageID":"m1","type":"hologram"}}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("unhandled_event", out.get(0).type());
        assertEquals("message.part.updated:hologram", out.get(0).data().path("rawType").asText());
    }
}
