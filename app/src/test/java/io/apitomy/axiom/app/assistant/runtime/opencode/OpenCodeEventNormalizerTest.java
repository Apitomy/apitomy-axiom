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
    void mapsTurnCompletionEvent() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"sessionID":"s1","costUsd":0.02,"inputTokens":11,"outputTokens":7,"success":true}
                """);

        List<SseEvent> out = normalizer.normalize("session.turn.completed", payload);

        assertEquals(1, out.size());
        assertEquals("turn_complete", out.get(0).type());
        assertEquals("s1", out.get(0).data().path("sessionId").asText());
        assertEquals(0, out.get(0).data().path("costUsd").asDouble(), 0.0001);
        assertEquals(0, out.get(0).data().path("inputTokens").asLong());
        assertEquals(0, out.get(0).data().path("outputTokens").asLong());
        assertTrue(out.get(0).data().path("success").asBoolean());
    }

    @Test
    void childPermissionForUnknownChildHasNoSubagentToolUseId() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_1","sessionID":"child-1","permission":"bash",
                "patterns":["echo hi"],"metadata":{"command":"echo hi"},"tool":{"messageID":"m1","callID":"c1"}}}
                """);

        List<SseEvent> out = normalizer.normalizeChild("message", payload, "child-1");

        assertEquals(1, out.size());
        assertEquals("permission_request", out.get(0).type());
        assertEquals("per_1", out.get(0).data().path("requestId").asText());
        assertEquals("bash", out.get(0).data().path("toolName").asText());
        assertTrue(out.get(0).data().path("subagentToolUseId").isMissingNode());
    }

    @Test
    void grandchildPermissionCarriesTopLevelTaskCallId() throws Exception {
        normalizer.registerChildSession("child-1");
        normalizer.registerNestedSession("grandchild-1", "child-1");
        // The parent task call mapping becomes known after the grandchild was registered.
        normalizer.normalize("message", mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1","type":"tool",
                "tool":"task","callID":"task-call-1","state":{"status":"running","input":{"description":"d",
                "subagent_type":"general"},"metadata":{"sessionId":"child-1"}}}}}
                """));
        JsonNode permission = mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_9","sessionID":"grandchild-1",
                "permission":"bash","patterns":["ls"],"metadata":{},"tool":{"messageID":"m1","callID":"c9"}}}
                """);
        JsonNode tool = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"grandchild-1","part":{"id":"p9",
                "type":"tool","tool":"bash","callID":"c9","state":{"status":"running","input":{"command":"ls"}}}}}
                """);

        assertTrue(normalizer.childSessionIds().contains("grandchild-1"));
        List<SseEvent> progress = normalizer.normalizeChild("message", tool, "grandchild-1");
        assertEquals(1, progress.size());
        assertEquals("subagent_progress", progress.get(0).type());
        assertEquals("task-call-1", progress.get(0).data().path("toolUseId").asText());
        List<SseEvent> out = normalizer.normalizeChild("message", permission, "grandchild-1");
        assertEquals(1, out.size());
        assertEquals("permission_request", out.get(0).type());
        assertEquals("task-call-1", out.get(0).data().path("subagentToolUseId").asText());
    }

    @Test
    void childIdleAndTextAreIgnored() throws Exception {
        normalizer.registerChildSession("child-1");
        JsonNode idle = mapper.readTree("""
                {"type":"session.idle","properties":{"sessionID":"child-1"}}
                """);
        JsonNode text = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"child-1",
                "part":{"id":"p1","type":"text","text":"hello"}}}
                """);

        assertTrue(normalizer.normalizeChild("message", idle, "child-1").isEmpty());
        assertTrue(normalizer.normalizeChild("message", text, "child-1").isEmpty());
        assertTrue(normalizer.childSessionIds().contains("child-1"));
    }

    @Test
    void mapsRetryStatusToProviderRetryNotice() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"session.status","properties":{"sessionID":"s1","status":{"type":"retry","attempt":2,
                "message":"rate limited","next":1790000000000}}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("session_error", out.get(0).type());
        assertEquals("ProviderRetry", out.get(0).data().path("name").asText());
        String message = out.get(0).data().path("message").asText();
        assertTrue(message.contains("attempt 2"), message);
        assertTrue(message.contains("rate limited"), message);
    }

    @Test
    void ignoresBusyAndIdleStatus() throws Exception {
        for (String type : List.of("busy", "idle")) {
            JsonNode payload = mapper.readTree("{\"type\":\"session.status\",\"properties\":{\"sessionID\":\"s1\","
                    + "\"status\":{\"type\":\"" + type + "\"}}}");

            assertTrue(normalizer.normalize("message", payload).isEmpty(), type);
        }
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
    void mapsSessionIdleToTurnComplete() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"type":"session.idle","properties":{"sessionID":"s1"}}
                """);

        List<SseEvent> out = normalizer.normalize("message", payload);

        assertEquals(1, out.size());
        assertEquals("turn_complete", out.get(0).type());
        assertEquals("s1", out.get(0).data().path("sessionId").asText());
        assertEquals(0, out.get(0).data().path("costUsd").asDouble(), 0.0001);
        assertEquals(0, out.get(0).data().path("inputTokens").asLong());
        assertEquals(0, out.get(0).data().path("outputTokens").asLong());
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
        assertTrue(out.get(0).data().has("isError"));
        assertFalse(out.get(0).data().path("isError").asBoolean(true));
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
        assertTrue(out.get(0).data().path("isError").asBoolean(false));
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

    private static final java.util.function.Predicate<JsonNode> PERMISSION_ASKED =
            event -> "permission.asked".equals(event.path("type").asText());

    @Test
    void permissionAskedUsesIdAndToolFromPrecedingToolPart() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        normalize(first(events, toolPart("bash", "running")));

        List<SseEvent> out = normalize(first(events, PERMISSION_ASKED));

        assertEquals(1, out.size());
        SseEvent event = out.get(0);
        assertEquals("permission_request", event.type());
        assertEquals("per_0eeeec345001lu7adnxbwsnqB7", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("echo captured", event.data().path("toolInput").path("command").asText());
        assertEquals("toolu_01LG86GQEhToDrjJWeL12dQw", event.data().path("toolUseId").asText());
    }

    @Test
    void permissionAskedWithoutKnownToolPartFallsBackToPermissionAndMetadata() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        List<SseEvent> out = normalize(first(events, PERMISSION_ASKED));
        assertEquals(List.of("tool_use", "permission_request"), out.stream().map(SseEvent::type).toList());
        SseEvent event = out.get(1);

        assertEquals("per_0eeeec345001lu7adnxbwsnqB7", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("echo captured", event.data().path("toolInput").path("command").asText());
    }

    @Test
    void permissionForEditUsesToolPartNameNotPermissionKey() throws Exception {
        normalizer.normalize("message", mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1","sessionID":"s1",
                 "messageID":"m1","type":"tool","tool":"write","callID":"c1",
                 "state":{"status":"running","input":{"filePath":"/w/tools/x.json","content":"{}"}}}}}
                """));

        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_1","sessionID":"s1","permission":"edit",
                 "patterns":["tools/x.json"],"metadata":{"filepath":"/w/tools/x.json"},
                 "tool":{"messageID":"m1","callID":"c1"}}}
                """)).get(0);

        assertEquals("write", event.data().path("toolName").asText());
        assertEquals("/w/tools/x.json", event.data().path("toolInput").path("filePath").asText());
        assertEquals("c1", event.data().path("toolUseId").asText());
    }

    @Test
    void completedToolPartForgetsToolCallForLaterPermissions() throws Exception {
        normalizer.normalize("message", mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1","sessionID":"s1",
                 "messageID":"m1","type":"tool","tool":"write","callID":"c1",
                 "state":{"status":"completed","input":{"filePath":"/w/x.json"},"output":"ok"}}}}
                """));

        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_1","sessionID":"s1","permission":"edit",
                 "metadata":{"filepath":"/w/other.json"},"tool":{"messageID":"m1","callID":"c1"}}}
                """)).get(0);

        assertEquals("edit", event.data().path("toolName").asText());
        assertEquals("/w/other.json", event.data().path("toolInput").path("filepath").asText());
    }

    @Test
    void legacyPermissionUpdatedEventIsMappedLikeAsked() throws Exception {
        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.updated","properties":{"id":"per_2","sessionID":"s1","type":"bash",
                 "pattern":"ls","metadata":{"command":"ls"},"callID":"c9"}}
                """)).get(1);

        assertEquals("permission_request", event.type());
        assertEquals("per_2", event.data().path("requestId").asText());
        assertEquals("bash", event.data().path("toolName").asText());
        assertEquals("ls", event.data().path("toolInput").path("command").asText());
        assertEquals("c9", event.data().path("toolUseId").asText());
    }

    @Test
    void permissionRepliedEmitsNothing() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(normalize(first(events,
                event -> "permission.replied".equals(event.path("type").asText()))).isEmpty());
    }

    @Test
    void permissionAskedWhilePartPendingEmitsToolUseFirstAndOnlyOnce() throws Exception {
        List<SseEvent> pending = normalizer.normalize("message", globPart("pending", "{}", ""));
        assertTrue(pending.isEmpty());

        List<SseEvent> permission = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"per_Y","sessionID":"s1","permission":"glob",
                 "patterns":["*.txt"],"metadata":{"pattern":"*.txt"},"always":["*"],
                 "tool":{"messageID":"m1","callID":"toolu_X"}}}
                """));
        assertEquals(2, permission.size());
        assertEquals("tool_use", permission.get(0).type());
        assertEquals("toolu_X", permission.get(0).data().path("id").asText());
        assertEquals("glob", permission.get(0).data().path("name").asText());
        assertEquals("*.txt", permission.get(0).data().path("input").path("pattern").asText());
        assertEquals("permission_request", permission.get(1).type());
        assertEquals("toolu_X", permission.get(1).data().path("toolUseId").asText());
        assertEquals("glob", permission.get(1).data().path("toolName").asText());
        assertEquals("per_Y", permission.get(1).data().path("requestId").asText());

        List<SseEvent> running = normalizer.normalize("message",
                globPart("running", "{\"pattern\":\"*.txt\"}", ""));
        assertTrue(running.stream().noneMatch(event -> "tool_use".equals(event.type())));

        List<SseEvent> completed = normalizer.normalize("message",
                globPart("completed", "{\"pattern\":\"*.txt\"}", ",\"output\":\"a.txt\""));
        assertEquals(1, completed.size());
        assertEquals("tool_result", completed.get(0).type());
        assertEquals("toolu_X", completed.get(0).data().path("toolUseId").asText());
    }

    @Test
    void externalDirectoryPermissionAfterToolPermissionUsesGuardKeyAndSameToolUseId() throws Exception {
        assertTrue(normalizer.normalize("message", globPart("pending", "{}", "")).isEmpty());

        List<SseEvent> first = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"P1","sessionID":"s1","permission":"glob",
                 "patterns":["**/*.java"],"metadata":{"pattern":"**/*.java"},
                 "tool":{"messageID":"m1","callID":"toolu_X"}}}
                """));
        assertEquals(List.of("tool_use", "permission_request"), first.stream().map(SseEvent::type).toList());
        assertEquals("glob", first.get(0).data().path("name").asText());
        JsonNode p1 = first.get(1).data();
        assertEquals("glob", p1.path("toolName").asText());
        assertEquals("glob", p1.path("permission").asText());
        assertEquals("**/*.java", p1.path("patterns").path(0).asText());
        assertEquals(1, p1.path("patterns").size());
        assertEquals("toolu_X", p1.path("toolUseId").asText());

        assertTrue(normalizer.normalize("message",
                globPart("running", "{\"pattern\":\"**/*.java\",\"path\":\"/home/u/other\"}", "")).isEmpty());

        List<SseEvent> second = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"P2","sessionID":"s1",
                 "permission":"external_directory","patterns":["/home/u/other/*"],
                 "metadata":{"filepath":"/home/u/other","parentDir":"/home/u/other"},
                 "tool":{"messageID":"m1","callID":"toolu_X"}}}
                """));
        assertEquals(1, second.size());
        JsonNode p2 = second.get(0).data();
        assertEquals("permission_request", second.get(0).type());
        assertEquals("P2", p2.path("requestId").asText());
        assertEquals("external_directory", p2.path("toolName").asText());
        assertEquals("external_directory", p2.path("permission").asText());
        assertEquals("toolu_X", p2.path("toolUseId").asText());
        assertEquals("/home/u/other/*", p2.path("toolInput").path("patterns").path(0).asText());
        assertEquals("/home/u/other", p2.path("toolInput").path("filepath").asText());

        List<SseEvent> completed = normalizer.normalize("message",
                globPart("completed", "{\"pattern\":\"**/*.java\"}", ",\"output\":\"a.java\""));
        assertEquals(List.of("tool_result"), completed.stream().map(SseEvent::type).toList());
        assertEquals("toolu_X", completed.get(0).data().path("toolUseId").asText());
    }

    @Test
    void guardPermissionForUnseenCallSynthesizesToolUseWithPermissionKey() throws Exception {
        List<SseEvent> out = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.asked","properties":{"id":"P3","sessionID":"s1",
                 "permission":"external_directory","patterns":["/x/*"],"tool":{"messageID":"m1","callID":"c5"}}}
                """));
        assertEquals(List.of("tool_use", "permission_request"), out.stream().map(SseEvent::type).toList());
        assertEquals("external_directory", out.get(0).data().path("name").asText());
        assertEquals("c5", out.get(1).data().path("toolUseId").asText());
    }

    @Test
    void legacyPermissionPatternBecomesSingleElementPatterns() throws Exception {
        SseEvent event = normalizer.normalize("message", mapper.readTree("""
                {"type":"permission.updated","properties":{"id":"per_2","sessionID":"s1","type":"bash",
                 "pattern":"ls","metadata":{"command":"ls"},"callID":"c9"}}
                """)).get(1);

        assertEquals("bash", event.data().path("permission").asText());
        assertEquals(1, event.data().path("patterns").size());
        assertEquals("ls", event.data().path("patterns").path(0).asText());
    }

    private JsonNode globPart(String status, String input, String extra) throws Exception {
        return mapper.readTree("{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"s1\","
                + "\"part\":{\"id\":\"prt_1\",\"sessionID\":\"s1\",\"messageID\":\"m1\",\"type\":\"tool\","
                + "\"tool\":\"glob\",\"callID\":\"toolu_X\",\"state\":{\"status\":\"" + status
                + "\",\"input\":" + input + extra + "}}}}");
    }

    private SseEvent replayUntilTurnComplete(OpenCodeEventNormalizer target, List<JsonNode> events) {
        SseEvent turnComplete = null;
        for (JsonNode event : events) {
            for (SseEvent out : target.normalize(event.path("type").asText(), event)) {
                if ("turn_complete".equals(out.type())) {
                    turnComplete = out;
                }
            }
        }
        return turnComplete;
    }

    private JsonNode payload(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void turnCompleteCarriesCostTokensAndDurationFromAssistantMessages() {
        SseEvent turnComplete = replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_CALLS));

        assertEquals(0.0702413, turnComplete.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(50771, turnComplete.data().path("inputTokens").asLong());
        assertEquals(140, turnComplete.data().path("outputTokens").asLong());
        assertEquals(3895, turnComplete.data().path("durationMs").asLong());
    }

    @Test
    void costIsCumulativeAcrossTurnsWhileTokensArePerTurn() {
        replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_CALLS));

        SseEvent second = replayUntilTurnComplete(normalizer, OpenCodeEventFixtures.load(TOOL_ERROR));

        assertEquals(0.0702413 + 0.0146099, second.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(50611, second.data().path("inputTokens").asLong());
        assertEquals(72, second.data().path("outputTokens").asLong());
    }

    @Test
    void messageUpdatedEmitsNoUiEvents() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);

        assertTrue(events.stream()
                .filter(event -> "message.updated".equals(event.path("type").asText()))
                .allMatch(event -> normalize(event).isEmpty()));
    }

    @Test
    void lateAssistantUpdateAfterIdleIsNotCountedAgain() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TOOL_CALLS);
        replayUntilTurnComplete(normalizer, events);
        JsonNode lateUpdate = OpenCodeEventFixtures.first(events,
                event -> "message.updated".equals(event.path("type").asText())
                        && "assistant".equals(event.path("properties").path("info").path("role").asText())
                        && event.path("properties").path("info").path("cost").asDouble() > 0);
        normalize(lateUpdate);

        SseEvent next = normalizer.normalize("message",
                payload("{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}")).get(0);

        assertEquals(0.0702413, next.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(0, next.data().path("inputTokens").asLong());
        assertEquals(0, next.data().path("outputTokens").asLong());
        assertEquals(0, next.data().path("durationMs").asLong());
    }

    // ── Todos and reasoning text (#382) ─────────────────────────────

    private static final String TODOS = "1.18.33-todos.jsonl";

    private List<SseEvent> replay(List<JsonNode> events) {
        List<SseEvent> out = new java.util.ArrayList<>();
        events.forEach(event -> out.addAll(normalize(event)));
        return out;
    }

    @Test
    void todoUpdatedFixtureEmitsSingleTodosEvent() {
        List<SseEvent> out = replay(OpenCodeEventFixtures.load(TODOS));

        List<SseEvent> todos = out.stream().filter(e -> "todos".equals(e.type())).toList();
        assertEquals(1, todos.size());
        JsonNode items = todos.get(0).data().path("todos");
        assertEquals(3, items.size());
        assertEquals("plan", items.get(0).path("content").asText());
        assertEquals("completed", items.get(0).path("status").asText());
        assertEquals("high", items.get(0).path("priority").asText());
        assertEquals("build", items.get(1).path("content").asText());
        assertEquals("pending", items.get(1).path("status").asText());
    }

    @Test
    void todowriteToolPartDoesNotEmitTodos() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TODOS);
        List<JsonNode> toolParts = events.stream().filter(partOfType("tool")
                .and(event -> "todowrite".equals(event.path("properties").path("part").path("tool").asText())))
                .toList();
        assertFalse(toolParts.isEmpty());

        List<SseEvent> out = replay(toolParts);

        assertTrue(out.stream().noneMatch(e -> "todos".equals(e.type())));
    }

    @Test
    void todosFixtureHasNoUnhandledSessionEvents() {
        List<JsonNode> events = OpenCodeEventFixtures.load(TODOS).stream()
                .filter(OpenCodeEventFixtures::isSessionScoped).toList();

        List<SseEvent> out = replay(events);

        assertTrue(out.stream().noneMatch(e -> "unhandled_event".equals(e.type())),
                () -> out.stream().filter(e -> "unhandled_event".equals(e.type()))
                        .map(e -> e.data().path("rawType").asText()).toList().toString());
    }

    @Test
    void reasoningPartEmitsIdThenFinalTextOnce() throws Exception {
        JsonNode started = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1",\
                "sessionID":"s1","messageID":"m1","type":"reasoning","text":"","time":{"start":1}}}}
                """);
        JsonNode finished = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p1",\
                "sessionID":"s1","messageID":"m1","type":"reasoning","text":"Reasoning about X",\
                "time":{"start":1,"end":2}}}}
                """);

        List<SseEvent> first = normalize(started);
        List<SseEvent> second = normalize(finished);
        List<SseEvent> third = normalize(finished);

        assertEquals(1, first.size());
        assertEquals("thinking", first.get(0).type());
        assertEquals("p1", first.get(0).data().path("id").asText());
        assertFalse(first.get(0).data().has("text"));
        assertEquals(1, second.size());
        assertEquals("thinking", second.get(0).type());
        assertEquals("p1", second.get(0).data().path("id").asText());
        assertEquals("Reasoning about X", second.get(0).data().path("text").asText());
        assertTrue(third.isEmpty());
    }

    @Test
    void reasoningPartFinishedWithTextOnFirstSightingEmitsOnlyTextEvent() throws Exception {
        JsonNode finished = mapper.readTree("""
                {"type":"message.part.updated","properties":{"sessionID":"s1","part":{"id":"p2",\
                "sessionID":"s1","messageID":"m1","type":"reasoning","text":"Done thinking",\
                "time":{"start":1,"end":2}}}}
                """);

        List<SseEvent> out = normalize(finished);

        assertEquals(1, out.size());
        assertEquals("p2", out.get(0).data().path("id").asText());
        assertEquals("Done thinking", out.get(0).data().path("text").asText());
        assertTrue(normalize(finished).isEmpty());
    }

    @Test
    void reasoningPartEndingWithEmptyTextEmitsOnlyIdEvent() {
        List<JsonNode> reasoning = OpenCodeEventFixtures.load(TODOS).stream()
                .filter(partOfType("reasoning")).toList();
        assertFalse(reasoning.isEmpty());

        List<SseEvent> out = replay(reasoning);

        assertEquals(1, out.size());
        assertEquals("thinking", out.get(0).type());
        assertFalse(out.get(0).data().path("id").asText().isEmpty());
        assertFalse(out.get(0).data().has("text"));
    }
}
