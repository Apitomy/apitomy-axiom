# OpenCode Event Normalizer (Real Events) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Map the events opencode actually emits into Axiom's assistant events: tool calls and results,
reasoning, and text. Surface unknown events instead of dropping them, and replace the invented test payloads with
payloads captured from a real `opencode serve` (GitHub issue #372, epic #387).

**Architecture:** `OpenCodeEventNormalizer` (one instance per driver/session) becomes lightly stateful:
- it tracks which tool calls already produced a `tool_use`/`tool_result`;
- it tracks the last text emitted per text part;
- it tracks which reasoning parts already produced `thinking`.

opencode reports tool calls as `message.part.updated` with `part.type == "tool"` and
`state.status` `pending → running → completed | error`. They map to `tool_use` (first non-pending update) and
`tool_result` (`completed` → `stdout`, `error` → `stderr`). Known session events that Axiom doesn't use yet are
ignored explicitly. Anything else becomes `unhandled_event` (the UI already renders it as a warning). A
driver-level replay test feeds the captured streams through the real driver.

**Tech Stack:** Java 21, Jackson, JUnit 5, JDK `HttpServer` fakes; fixtures captured from opencode 1.18.33 with
`github-copilot/claude-sonnet-5`.

**Spec:** GitHub issue #372 ("OpenCode sessions: rewrite event normalizer against real opencode events").

## Background (verified facts)

Two real event streams were captured on 2026-09-29 with `/tmp/opencode/capture.py`. It starts `opencode serve`
with `permission.bash = "ask"`, subscribes to `GET /event`, sends one prompt, auto-replies `once` to permissions,
and stops at `session.idle`. The outputs are:
- `/tmp/opencode/cap-events.jsonl` (96 events): the prompt was "read hello.txt, run `echo captured`, reply DONE".
- `/tmp/opencode/cap-events-error.jsonl` (85 events): the prompt was "read does-not-exist.txt, reply DONE".

What they contain (every SSE `data:` line is `{"id","type","properties"}`):
- **Session-scoped event types seen:** `message.updated`, `message.part.updated`, `message.part.delta`,
  `session.status`, `session.updated`, `session.created`, `session.diff`, `session.idle`, `permission.asked`,
  `permission.replied`.
- **Global events** with no session id (`plugin.added`, `catalog.updated`, `reference.updated`,
  `integration.updated`, `server.connected`) are already dropped by the driver's `isCurrentSessionEvent`.
- **Text part:** `message.part.updated` with `text: ""` (start), then `message.part.delta`
  `{partID, field:"text", delta}` events, then a final `message.part.updated` with the full `text` and
  `time.end`.
- **Reasoning part:** `{"type":"reasoning","text":"","time":{"start":...}}`, then deltas, then an update with
  `time.end`.
- **Tool part:**
  - `{"type":"tool","tool":"read","callID":"toolu_...","state":{"status":"pending","input":{},"raw":""}}`
  - then `running` (`input` filled in; `bash` sends several `running` updates as `metadata.output` grows)
  - then `completed` (`state.output` string) or `error` (`state.error`, e.g. `"File not found: ..."`)
- **Other part types:** `step-start`, and `step-finish` (with `tokens` and `cost`; used later by #374).
- **Permission:** `permission.asked` with `properties.id`, `permission`, `patterns`, `metadata`, and
  `tool.callID`. Its mapping belongs to #373 and is **not** changed here.
- **Parallel tool calls happen.** In the tool-calls capture, both `tool_use`s come before either `tool_result`.

Expected Axiom events after the driver's filtering (the user-prompt echo is removed by the driver):
- **Tool-calls capture:**
  1. `thinking`
  2. `tool_use` read `toolu_01AaGr1CuwaqRudKSw25JKcY`
  3. `tool_use` bash `toolu_01LG86GQEhToDrjJWeL12dQw`
  4. `tool_result` read (stdout starts `<path>/tmp/opencode/cap/hello.txt</path>`)
  5. `tool_result` bash (stdout `captured\n`)
  6. `assistant_text` `DONE`
  7. `turn_complete`

  plus one `permission_request` to the auto-approval sink.
- **Error capture:**
  1. `tool_use` read `toolu_01KZ1mRba9RZWy5Q6b4QAEv2`
  2. `tool_result` (stderr `File not found: /tmp/opencode/cap/does-not-exist.txt`, stdout `""`)
  3. `assistant_text` `DONE`
  4. `turn_complete`

UI contract (`ui/src/components/assistant/AssistantChatPanel.tsx:76-128, 444-450`):
- `assistant_text {text}` replaces the content of the last assistant message, or appends a new one.
- `thinking {}` only updates the spinner.
- `tool_use {id, name, input}` and `tool_result {toolUseId, stdout, stderr, interrupted}` are matched by id. An
  error shows when `stderr` is set and `stdout` is empty.
- `unhandled_event {rawType, raw}` shows a warning with the raw payload. The same shapes come from
  `AssistantEventParser` for Claude.

**Rulings:**
- **Text is emitted per completed/updated text part, not per delta.** This matches Claude's parser, which emits
  whole text blocks. It also avoids storing one event per token in `AssistantSession`'s unbounded event history.
  `message.part.delta` is explicitly ignored. Token streaming is a possible follow-up.
- **Tool ids are opencode's `callID`,** which `permission.asked` also references through `tool.callID`. That keeps
  #373 simple.
- **Invented names are removed.** `session.message.part`, `session.tool.started` and `session.tool.completed` are
  gone. The permission aliases and `session.turn.completed` are left unchanged: permissions belong to #373, and
  `session.turn.completed` is used by many driver test fakes.
- **Tool names are passed through unchanged** (`read`, `bash`, `axiom_axiom_list_tools`, ...). The UI tool preview
  mapping belongs to #380.

**Known interaction (not fixed here, #375):** `AssistantSessionManager.createValidationListener` runs on every
`tool_result`. On a validation failure it calls `session.sendMessage(feedback)` mid-turn.
`AssistantSession.sendMessage` records a `user_message` event **before** calling the driver, and the OpenCode
driver then throws `IllegalStateException("A turn is already in flight")` (logged by the listener). Once this plan
makes `tool_result` flow on OpenCode, an invalid Configuration Assistant file will show an undelivered "user"
message in the chat. #375 fixes this and should land right after this plan.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- Do not change Claude Code behavior, the UI, or the REST API / OpenAPI spec.
- Out of scope:
  - permission field mapping (#373)
  - cost/tokens (#374)
  - mid-turn messages (#375)
  - `session_init` / todos / subagents (#379)
  - UI tool previews (#380)
- The emitted event shapes must match the UI contract above exactly (field names `id`, `name`, `input`,
  `toolUseId`, `stdout`, `stderr`, `interrupted`, `text`, `rawType`, `raw`).
- No event in either captured fixture may produce `unhandled_event`.
- Commit message style: conventional commits, e.g. `fix(assistant): ...`. No AI attribution.
- Run tests with: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false` from the
  repo root (run `mvn -q install -DskipTests` once first if sibling modules aren't installed). A JBoss
  LogManager "accessed before property set" warning in test output is pre-existing noise.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/test/resources/opencode/events/1.18.33-tool-calls.jsonl` | Create (copy) | Real captured stream: read + bash + permission |
| `app/src/test/resources/opencode/events/1.18.33-tool-error.jsonl` | Create (copy) | Real captured stream: failing read |
| `app/src/test/resources/opencode/events/capture.py` | Create (copy) | Script to regenerate fixtures for new opencode versions |
| `app/src/test/resources/opencode/events/README.md` | Create | How fixtures were captured and how to regenerate them |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventFixtures.java` | Create | Test helper to load fixture lines |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java` | Modify | Real event mapping |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java` | Modify | Fixture-based unit tests |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java` | Modify | Replay tests through the driver |

---

### Task 1: Fixtures and normalizer rewrite

**Files:** the fixture files, `OpenCodeEventFixtures.java`, `OpenCodeEventNormalizer.java`,
`OpenCodeEventNormalizerTest.java` (see the table above).

**Interfaces:**
- Produces:
  - Test helper
    `final class OpenCodeEventFixtures { static List<JsonNode> load(String fileName); static JsonNode first(List<JsonNode> events, Predicate<JsonNode> predicate); static boolean isSessionScoped(JsonNode event); }`
    (package `io.apitomy.axiom.app.assistant.runtime.opencode`, test sources). `load("1.18.33-tool-calls.jsonl")`
    reads `/opencode/events/<fileName>` from the classpath, one JSON object per non-blank line.
  - `OpenCodeEventNormalizer.normalize(String eventName, JsonNode payload)` keeps its signature. It is no longer
    stateless: callers must use one instance per session (the factory already creates one per driver).

- [ ] **Step 1: Add the fixtures**

```bash
mkdir -p app/src/test/resources/opencode/events
cp /tmp/opencode/cap-events.jsonl app/src/test/resources/opencode/events/1.18.33-tool-calls.jsonl
cp /tmp/opencode/cap-events-error.jsonl app/src/test/resources/opencode/events/1.18.33-tool-error.jsonl
cp /tmp/opencode/capture.py app/src/test/resources/opencode/events/capture.py
wc -l app/src/test/resources/opencode/events/*.jsonl   # expect 96 and 85
```

Create `app/src/test/resources/opencode/events/README.md`:

```markdown
# Captured OpenCode event streams

Real `GET /event` streams from `opencode serve` 1.18.33 (model `github-copilot/claude-sonnet-5`), one SSE `data:`
JSON object per line. They are used by `OpenCodeEventNormalizerTest` and `OpenCodeInteractiveSessionDriverTest`.

| File | Prompt |
|---|---|
| `1.18.33-tool-calls.jsonl` | Read `hello.txt`, run `echo captured` (bash set to `ask`, answered `once`), reply `DONE` |
| `1.18.33-tool-error.jsonl` | Read `does-not-exist.txt` (fails), reply `DONE` |

To capture streams for a new opencode version, run `python3 capture.py`. It needs `opencode` on the `PATH` and a
working `github-copilot` provider, and it makes one short model call. Edit `OUT` and the prompt in the script as
needed, then save the result under a new version-prefixed name.
```

- [ ] **Step 2: Add the fixture helper**

`app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventFixtures.java`:

```java
package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Loads OpenCode SSE event streams captured from a real {@code opencode serve}.
 */
final class OpenCodeEventFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenCodeEventFixtures() {
    }

    static List<JsonNode> load(String fileName) {
        String resource = "/opencode/events/" + fileName;
        try (InputStream in = OpenCodeEventFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing fixture " + resource);
            }
            List<JsonNode> events = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(MAPPER.readTree(line));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode first(List<JsonNode> events, Predicate<JsonNode> predicate) {
        return events.stream().filter(predicate).findFirst()
                .orElseThrow(() -> new AssertionError("No fixture event matches predicate"));
    }

    static boolean isSessionScoped(JsonNode event) {
        JsonNode properties = event.path("properties");
        return !properties.path("sessionID").asText("").isEmpty()
                || !properties.path("part").path("sessionID").asText("").isEmpty()
                || !properties.path("info").path("sessionID").asText("").isEmpty();
    }

    static Predicate<JsonNode> toolPart(String tool, String status) {
        return event -> "message.part.updated".equals(event.path("type").asText())
                && "tool".equals(event.path("properties").path("part").path("type").asText())
                && tool.equals(event.path("properties").path("part").path("tool").asText())
                && status.equals(event.path("properties").path("part").path("state").path("status").asText());
    }

    static Predicate<JsonNode> partOfType(String partType) {
        return event -> "message.part.updated".equals(event.path("type").asText())
                && partType.equals(event.path("properties").path("part").path("type").asText());
    }
}
```

- [ ] **Step 3: Rewrite the normalizer tests (failing first)**

Replace `mapsAssistantTextEvent`, `mapsToolInvocationStartEvent`, `mapsToolOutputResultEvent`,
`ignoresIrrelevantEvents` and `mapsEnvelopeEventUsingPayloadTypeForAssistantText` with the tests below. Keep
`mapsPermissionRequestEvent`, `mapsTurnCompletionEvent`, `ignoresNullEventName`,
`mapsEnvelopePermissionAskedEvent`, `mapsSessionIdleToTurnComplete` and `mapsSessionErrorToSessionErrorEvent`
unchanged. Add imports: `java.util.function.Predicate`, `static org.junit.jupiter.api.Assertions.assertFalse`,
`static ...OpenCodeEventFixtures.first`, `static ...OpenCodeEventFixtures.toolPart`,
`static ...OpenCodeEventFixtures.partOfType`.

```java
    private static final String TOOL_CALLS = "1.18.33-tool-calls.jsonl";
    private static final String TOOL_ERROR = "1.18.33-tool-error.jsonl";

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
```

- [ ] **Step 4: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeEventNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: several failures. For example, `runningToolPartEmitsToolUseWithCallIdNameAndInput` gets 0 events,
`unknownEventTypeEmitsUnhandledEventWithRawPayload` gets 0 events, and `reasoningPartEmitsThinkingOncePerPart`
gets `[]`.

- [ ] **Step 5: Rewrite the normalizer**

Replace the body of `OpenCodeEventNormalizer` (keep the package, and keep the `permission`, `turnComplete`,
`sessionError`, `eventData`, `resolvedEventType` and `firstNonBlank` helpers exactly as they are) with:

```java
/**
 * Normalizes OpenCode runtime events into assistant SSE events consumed by the UI.
 *
 * <p>Instances are stateful (they de-duplicate tool calls, text and reasoning parts across the repeated
 * {@code message.part.updated} events OpenCode sends), so use one instance per session.
 */
public class OpenCodeEventNormalizer {

    /** Session events that are understood but not (yet) surfaced to the UI. */
    private static final Set<String> IGNORED_EVENT_TYPES = Set.of(
            "message.updated",
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
    private static final Set<String> IGNORED_PART_TYPES = Set.of(
            "step-start", "step-finish", "snapshot", "patch", "file", "agent", "retry", "compaction", "subtask");

    private final Set<String> toolUsesEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> toolResultsEmitted = ConcurrentHashMap.newKeySet();
    private final Set<String> reasoningPartsSeen = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastTextByPart = new ConcurrentHashMap<>();

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
            case "session.permission.requested", "permission.asked", "permission.v2.asked" ->
                    List.of(permission(eventData));
            case "session.turn.completed", "session.idle" -> {
                clearTurnState();
                yield List.of(turnComplete(eventData));
            }
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
        return events;
    }

    private SseEvent unhandled(String rawType, JsonNode rawPayload) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("rawType", rawType);
        data.put("raw", rawPayload.toString());
        return new SseEvent("unhandled_event", data);
    }

    private void clearTurnState() {
        toolUsesEmitted.clear();
        toolResultsEmitted.clear();
        reasoningPartsSeen.clear();
        lastTextByPart.clear();
    }

    // eventData, resolvedEventType, permission, turnComplete, sessionError, firstNonBlank: unchanged
}
```

Delete the old `mapMessagePart(JsonNode)`, `toolUse(JsonNode)` and `toolResult(JsonNode)` methods. Imports
needed: `java.util.ArrayList`, `java.util.Collections`, `java.util.List`, `java.util.Map`, `java.util.Set`,
`java.util.concurrent.ConcurrentHashMap`.

- [ ] **Step 6: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

If a pre-existing `OpenCodeInteractiveSessionDriverTest` test now fails, it is because its fake event stream
sends an event type that previously was silently dropped and is now `unhandled_event`. Fix the **test fake** to
send a real event type (e.g. `session.idle` instead of an invented name). Do not add invented names to the
normalizer. Record every such change in the report.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/resources/opencode/events \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventFixtures.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java
git commit -m "fix(assistant): map real OpenCode tool, reasoning and text events (#372)"
```

(Include `OpenCodeInteractiveSessionDriverTest.java` in the commit if Step 6 required fake changes.)

---

### Task 2: Replay the captured streams through the driver

**Files:**
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java`
- Modify (only if Step 4 shows it's needed): `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`

**Interfaces:**
- Consumes: `OpenCodeEventFixtures.load(String)` (Task 1); `FakeOpenCodeServer.start(EventResponder)` and
  `FakeServerProcess` (existing in the test class; `FakeOpenCodeServer.SESSION_ID` is `"session-1"`).

- [ ] **Step 1: Write the replay tests**

Add to `OpenCodeInteractiveSessionDriverTest`:

```java
    private static EventResponder replayFixture(String fixture) {
        return exchange -> {
            List<com.fasterxml.jackson.databind.JsonNode> events = OpenCodeEventFixtures.load(fixture);
            String capturedSessionId = OpenCodeEventFixtures.first(events,
                            event -> "session.created".equals(event.path("type").asText()))
                    .path("properties").path("info").path("id").asText();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                for (com.fasterxml.jackson.databind.JsonNode event : events) {
                    String line = event.toString().replace(capturedSessionId, "session-1");
                    outputStream.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                outputStream.flush();
            }
        };
    }

    private static List<SseEvent> replayThroughDriver(String fixture, List<SseEvent> permissionEvents)
            throws Exception {
        List<SseEvent> events = new CopyOnWriteArrayList<>();
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(replayFixture(fixture))) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    permissionEvents::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null);
            driver.start();
            waitUntil(() -> events.stream().anyMatch(event -> "turn_complete".equals(event.type())),
                    Duration.ofSeconds(5));
            driver.destroy();
        }
        return events;
    }

    @Test
    void replaysRealToolCallStreamIntoAssistantEvents() throws Exception {
        List<SseEvent> permissionEvents = new CopyOnWriteArrayList<>();

        List<SseEvent> events = replayThroughDriver("1.18.33-tool-calls.jsonl", permissionEvents);

        List<String> summary = events.stream()
                .filter(event -> !"session_error".equals(event.type()))
                .map(event -> switch (event.type()) {
                    case "tool_use" -> "tool_use:" + event.data().path("name").asText() + ":"
                            + event.data().path("id").asText();
                    case "tool_result" -> "tool_result:" + event.data().path("toolUseId").asText();
                    case "assistant_text" -> "assistant_text:" + event.data().path("text").asText();
                    default -> event.type();
                })
                .toList();
        assertEquals(List.of(
                "thinking",
                "tool_use:read:toolu_01AaGr1CuwaqRudKSw25JKcY",
                "tool_use:bash:toolu_01LG86GQEhToDrjJWeL12dQw",
                "tool_result:toolu_01AaGr1CuwaqRudKSw25JKcY",
                "tool_result:toolu_01LG86GQEhToDrjJWeL12dQw",
                "assistant_text:DONE",
                "turn_complete"), summary);
        assertEquals(1, permissionEvents.size());
        assertEquals("permission_request", permissionEvents.get(0).type());
    }

    @Test
    void replaysRealToolErrorStreamIntoAssistantEvents() throws Exception {
        List<SseEvent> events = replayThroughDriver("1.18.33-tool-error.jsonl", new CopyOnWriteArrayList<>());

        List<SseEvent> results = events.stream().filter(event -> "tool_result".equals(event.type())).toList();
        assertEquals(1, results.size());
        assertEquals("", results.get(0).data().path("stdout").asText());
        assertEquals("File not found: /tmp/opencode/cap/does-not-exist.txt",
                results.get(0).data().path("stderr").asText());
        assertTrue(events.stream().noneMatch(event -> "unhandled_event".equals(event.type())));
        assertEquals("turn_complete", events.get(events.size() - 1).type());
    }
```

(`OutputStream`, `StandardCharsets`, `CopyOnWriteArrayList`, `Duration` and `SseEvent` are already imported in
this class.)

The `session_error` filter in the first test is there because a stream closing after replay may make the driver
report a stream failure. If that produces no `session_error`, the filter is harmless.

- [ ] **Step 2: Run**

Run: `mvn -q -pl app test -Dtest=OpenCodeInteractiveSessionDriverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both new tests PASS (Task 1 made the mapping correct; this test proves the driver's filtering, including
removal of the user-prompt echo, composes with it).

- [ ] **Step 3: If a replay test fails**

Diagnose before changing code. Print `events` in the assertion message. Likely causes:
- **User-prompt text appears as `assistant_text`:** the driver's echo filter
  (`rememberUserMessageId`/`isEchoedUserMessagePart`) missed it. Fix the driver and describe the fix in the
  report.
- **`turn_complete` never arrives:** the fixture's `session.idle` session id wasn't replaced. Check
  `capturedSessionId`.

Do not weaken the expected sequence. It comes from the real capture (see the plan's Background).

- [ ] **Step 4: Full module test run**

Run: `mvn -q -pl app test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java
git commit -m "test(assistant): replay captured OpenCode event streams through the driver (#372)"
```

(Also add `OpenCodeInteractiveSessionDriver.java` if Step 3 required a driver fix.)

- [ ] **Step 6: Manual end-to-end verification (human)**

1. `./dev.sh` from this branch's worktree. Start a Configuration Assistant session on `opencode`.
2. Ask it to list the tools configured in Axiom. Tool blocks for `axiom_axiom_list_tools` should appear in the
   chat with their results.
3. Ask it to create a simple tool. The `write` tool block appears. After it completes, the generated-items panel
   refreshes on its own (driven by `tool_result`), so no page reload is needed.
4. The thinking spinner text changes while the model reasons. No "Unhandled event type" warnings appear during
   a normal session.
5. Known until #375: if the created file fails validation, a validation-feedback "user" message may appear that
   was never delivered to the model.

---

## Self-Review Notes

- Issue #372 requirements → tasks:
  - Tool parts → `tool_use`/`tool_result`, de-duplicated → Task 1.
  - Reasoning → `thinking` → Task 1.
  - Text de-duplication → Task 1. Deltas are deliberately not streamed; see the Rulings.
  - `unhandled_event` for unknown events → Task 1.
  - Real fixtures → Task 1 (files) and Task 2 (driver replay).
  - Acceptance (tool calls render; validation fires) → Task 2 Step 6.
- Names are consistent across tasks: `OpenCodeEventFixtures.load/first/isSessionScoped/toolPart/partOfType`, the
  fixture file names, and the event field names from the UI contract.
- Permission mapping is intentionally untouched (#373). The replay test only asserts that a `permission_request`
  reaches the auto-approval sink, not its fields.
