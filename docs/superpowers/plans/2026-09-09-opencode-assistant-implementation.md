# OpenCode Assistant Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Goal:** Add OpenCode-backed interactive AI Assistant sessions with feature parity, using one
`opencode serve` process per Axiom Assistant session and fail-closed compatibility checks.

**Architecture:** Keep the existing Assistant REST/SSE contract and frontend behavior unchanged.
Refactor the backend Assistant runtime boundary so session orchestration remains in
`AssistantSessionManager`, while runtime execution moves behind engine-specific interactive drivers
(`Claude` and `OpenCode`). OpenCode sessions own a
dedicated server process + HTTP/SSE client + capability probe, and emit normalized Assistant events.

**Tech Stack:** Java 25, Quarkus 3.x, JAX-RS, Jackson, JUnit 5, RestAssured,
React/TypeScript frontend (no schema breaking changes expected).

**Spec:** `docs/superpowers/specs/2026-09-09-opencode-assistant-design.md`

## Global Constraints

- Keep Assistant browser-visible event names stable (`assistant_text`, `tool_use`, `tool_result`,
  `permission_request`, `turn_complete`, etc.).
- OpenCode Assistant runtime model is **one OpenCode server process per Axiom interactive session**.
- Version support is best-effort latest OpenCode, validated at runtime per session via capability probe.
- Compatibility is fail-closed: if required capabilities fail, reject session startup with
  explicit diagnostics.
- Keep browser traffic routed through Axiom only; bind OpenCode servers to loopback.
- Preserve template-driven behavior in session startup (system prompt, model, working directory, init script,
  environment, MCP config).
- Java style: 4-space indentation, explicit types when ambiguous, Javadoc on public methods.
- Tests: JUnit 5 for unit/integration backend validation.

---

## File Structure

**Create (backend runtime abstraction):**
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriver.java` - runtime driver
  contract.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java`
  - engine-based driver selector.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/SessionCompatibilityException.java` - fail-closed
  startup exception with typed reason.

**Create (Claude driver extraction):**
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/ClaudeInteractiveSessionDriver.java`
  - current Claude subprocess behavior moved behind driver contract.

**Create (OpenCode assistant runtime):**
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java` -
  per-session OpenCode process lifecycle.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java` -
  assistant-specific OpenCode HTTP/SSE client.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java`
  - OpenCode raw event -> Assistant normalized event mapping.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbe.java` - startup
  capability validation.
- `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java` -
  OpenCode runtime driver implementation.

**Modify (assistant orchestration):**
- `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
  - retain session state/event history/auto-approval behavior, delegate runtime IO to driver.
- `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` - create sessions via driver
  factory, remove hard-coded Claude command bootstrapping.
- `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
  - update availability + error mapping messages for non-Claude interactive engines.

**Modify (configuration):**
- `app/src/main/resources/application.properties` - add assistant-opencode runtime properties.

**Create/modify tests:**
- `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java`
- `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbeTest.java`
- `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java`
- `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionManagerInteractiveEngineTest.java`
- `app/src/test/java/io/apitomy/axiom/app/OpenCodeAssistantResourceIntegrationTest.java`

**Modify docs:**
- `docs/user-guide/ai-assistant.md`
- `docs/getting-started/configuring-axiom.md`
- `docs/developer-guide/extending-axiom.md`

---

### Task 1: Introduce interactive runtime driver contracts

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriver.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/SessionCompatibilityException.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`

**Interfaces:**
- Produces: `InteractiveSessionDriver` with methods:
  - `void start() throws IOException`
  - `void sendUserMessage(String message) throws IOException`
  - `void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) throws IOException`
  - `void interrupt()`
  - `void destroy()`
  - `boolean isAlive()`
  - `AssistantSession.Status getStatus()`
  - `String getErrorMessage()`
- Produces: `SessionCompatibilityException` with `code` and `details` fields consumed by
  session manager/resource.

- [ ] **Step 1: Write failing contract test around session delegation**

Create `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java`:

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantSessionDriverDelegationTest {

    @Test
    void sessionDelegatesRuntimeCallsToDriver() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), null, null, driver);

        session.start();
        session.sendMessage("hello");
        session.respondToPermission("p1", true, JsonNodeFactory.instance.objectNode());
        session.interrupt();
        session.destroy();

        assertEquals(1, driver.startCalls);
        assertEquals("hello", driver.lastMessage);
        assertEquals("p1", driver.lastPermissionId);
        assertEquals(1, driver.interruptCalls);
        assertEquals(1, driver.destroyCalls);
    }

    static class RecordingDriver implements InteractiveSessionDriver {
        int startCalls;
        int interruptCalls;
        int destroyCalls;
        String lastMessage;
        String lastPermissionId;

        @Override public void start() { startCalls++; }
        @Override public void sendUserMessage(String message) { lastMessage = message; }
        @Override public void respondToPermission(String permissionId, boolean allow,
                                                   com.fasterxml.jackson.databind.JsonNode toolInput) {
            lastPermissionId = permissionId;
        }
        @Override public void interrupt() { interruptCalls++; }
        @Override public void destroy() { destroyCalls++; }
        @Override public boolean isAlive() { return true; }
        @Override public AssistantSession.Status getStatus() { return AssistantSession.Status.RUNNING; }
        @Override public String getErrorMessage() { return null; }
    }
}
```

- [ ] **Step 2: Run the new test and confirm failure**

Run: `mvn -pl app -Dtest=AssistantSessionDriverDelegationTest test`
Expected: FAIL because `AssistantSession` constructor and driver contract do not exist yet.

- [ ] **Step 3: Add runtime contracts and adapt AssistantSession constructor**

Create `InteractiveSessionDriver.java`:

```java
package io.apitomy.axiom.app.assistant.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.app.assistant.AssistantSession;

import java.io.IOException;

/**
 * Runtime driver abstraction for interactive Assistant sessions.
 */
public interface InteractiveSessionDriver {

    void start() throws IOException;

    void sendUserMessage(String message) throws IOException;

    void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) throws IOException;

    void interrupt();

    void destroy();

    boolean isAlive();

    AssistantSession.Status getStatus();

    String getErrorMessage();
}
```

Update `AssistantSession` constructor signature to accept `InteractiveSessionDriver driver` and make
`start/sendMessage/respondToPermission/interrupt/destroy/isAlive/getStatus/getErrorMessage` delegate to driver
while preserving event history, auto-approval, and usage accounting behavior.

- [ ] **Step 4: Re-run delegation test**

Run: `mvn -pl app -Dtest=AssistantSessionDriverDelegationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriver.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/SessionCompatibilityException.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java
git commit -m "refactor(assistant): introduce interactive runtime driver boundary"
```

---

### Task 2: Extract existing Claude runtime into Claude driver

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/ClaudeInteractiveSessionDriver.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java`

**Interfaces:**
- Consumes: `InteractiveSessionDriver` from Task 1.
- Produces: `ClaudeInteractiveSessionDriver` constructor:
  `ClaudeInteractiveSessionDriver(Path workingDirectory, Path sessionDirectory, List<String> command,
  Map<String, String> environment, AssistantEventParser parser, Consumer<SseEvent> eventSink,
  Consumer<SseEvent> autoApprovalSink)`.

- [ ] **Step 1: Add failing parser/driver integration test for Claude behavior parity**

Create `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/ClaudeInteractiveSessionDriverTest.java`
with a test that feeds representative NDJSON lines to parser-driven driver path and asserts normalized events
(`assistant_text`, `tool_use`, `permission_request`, `turn_complete`) are emitted unchanged.

```java
@Test
void parsesAssistantAndPermissionEventsWithoutRenaming() {
    AssistantEventParser parser = new AssistantEventParser();
    List<SseEvent> events = parser.parse("""
        {"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}
        """);
    assertEquals("assistant_text", events.get(0).type());
}
```

- [ ] **Step 2: Run test and verify failure**

Run: `mvn -pl app -Dtest=ClaudeInteractiveSessionDriverTest test`
Expected: FAIL because driver class does not exist.

- [ ] **Step 3: Move subprocess runtime logic from AssistantSession into Claude driver**

Implement `ClaudeInteractiveSessionDriver` using the current `AssistantSession` internals:

```java
public final class ClaudeInteractiveSessionDriver implements InteractiveSessionDriver {
    // process, stdin, parser, stdout/stderr readers, monitor thread
    // writes NDJSON user/control lines and emits parsed events via eventSink
}
```

Keep current event mapping and auto-approval semantics identical by preserving
`AssistantEventParser` outputs and permission response payload structure.

- [ ] **Step 4: Re-run focused assistant parser and Claude driver tests**

Run:
- `mvn -pl app -Dtest=AssistantEventParserTest test`
- `mvn -pl app -Dtest=ClaudeInteractiveSessionDriverTest test`

Expected: PASS; no behavior drift in normalized Claude event handling.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/ClaudeInteractiveSessionDriver.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/ClaudeInteractiveSessionDriverTest.java
git commit -m "refactor(assistant): move Claude interactive subprocess runtime behind driver"
```

---

### Task 3: Add OpenCode event normalization and unit tests

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java`
- Create: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java`

**Interfaces:**
- Produces: `List<SseEvent> normalize(String eventName, JsonNode payload)`.
- Produces: mapping methods for OpenCode event fragments to Assistant events.

- [ ] **Step 1: Write failing event mapping tests for required parity events**

Create `OpenCodeEventNormalizerTest.java` with explicit coverage for:
- assistant text chunk -> `assistant_text`
- tool invocation start -> `tool_use`
- tool output/result -> `tool_result`
- permission request -> `permission_request`
- turn completion -> `turn_complete`

```java
@Test
void mapsAssistantTextEvent() {
    JsonNode payload = mapper.readTree("""
        {"sessionID":"s1","part":{"type":"text","text":"hello"}}
        """);
    List<SseEvent> out = normalizer.normalize("session.message.part", payload);
    assertEquals("assistant_text", out.get(0).type());
    assertEquals("hello", out.get(0).data().path("text").asText());
}
```

- [ ] **Step 2: Run test and verify failure**

Run: `mvn -pl app -Dtest=OpenCodeEventNormalizerTest test`
Expected: FAIL because normalizer class does not exist.

- [ ] **Step 3: Implement OpenCode -> Assistant event normalization**

Create `OpenCodeEventNormalizer` with deterministic mappings and ignored-event behavior:

```java
public List<SseEvent> normalize(String eventName, JsonNode payload) {
    return switch (eventName) {
        case "session.message.part" -> mapMessagePart(payload);
        case "session.tool.started" -> List.of(toolUse(payload));
        case "session.tool.completed" -> List.of(toolResult(payload));
        case "session.permission.requested" -> List.of(permission(payload));
        case "session.turn.completed" -> List.of(turnComplete(payload));
        default -> List.of();
    };
}
```

Ensure outputs use existing field names consumed in `AssistantChatPanel.tsx`.

- [ ] **Step 4: Re-run normalizer tests**

Run: `mvn -pl app -Dtest=OpenCodeEventNormalizerTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizer.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeEventNormalizerTest.java
git commit -m "feat(assistant-opencode): add OpenCode event normalization for assistant parity"
```

---

### Task 4: Build OpenCode per-session process and HTTP/SSE client

**Files:**
- Create:
  `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java`
- Create:
  `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcessTest.java`

**Interfaces:**
- Produces: `OpenCodeSessionServerProcess.start()/stop()/baseUrl()/isAlive()`.
- Produces: `OpenCodeAssistantClient` methods:
  - `HealthStatus health()`
  - `String createSession(String title)`
  - `void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools)`
  - `void abort(String sessionId)`
  - `void respondPermission(String sessionId, String permissionId, boolean allow)`
  - `void connectEvents(Consumer<OpenCodeRawEvent> onEvent)`

- [ ] **Step 1: Write failing process lifecycle test with OpenCode availability guard**

Create `OpenCodeSessionServerProcessTest.java`:

```java
@Test
void startsAndStopsSessionScopedOpenCodeServer() {
    Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
    OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);
    process.start();
    assertTrue(process.isAlive());
    assertTrue(process.baseUrl().startsWith("http://127.0.0.1:"));
    process.stop();
    assertFalse(process.isAlive());
}
```

- [ ] **Step 2: Run test and verify failure**

Run: `mvn -pl app -Dtest=OpenCodeSessionServerProcessTest test`
Expected: FAIL because class does not exist.

- [ ] **Step 3: Implement process manager and assistant HTTP client**

Implement ephemeral-port capable process manager and HTTP 1.1 client:

```java
ProcessBuilder pb = new ProcessBuilder(
        executable,
        "serve",
        "--hostname", hostname,
        "--port", String.valueOf(resolvedPort)
);
```

Implement health polling against `/global/health` and bounded startup timeout.

- [ ] **Step 4: Re-run process test**

Run: `mvn -pl app -Dtest=OpenCodeSessionServerProcessTest test`
Expected: PASS when OpenCode CLI is installed; SKIPPED when unavailable.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeSessionServerProcess.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/
OpenCodeSessionServerProcessTest.java
git commit -m "feat(assistant-opencode): add per-session OpenCode process and assistant HTTP client"
```

---

### Task 5: Implement fail-closed capability probe

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbe.java`
- Create: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbeTest.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/SessionCompatibilityException.java`

**Interfaces:**
- Produces: `OpenCodeCapabilityProbe.Result probe(OpenCodeAssistantClient client)`.
- Produces: failure codes aligned to spec taxonomy.

- [ ] **Step 1: Write failing probe tests with mock HTTP endpoints**

In `OpenCodeCapabilityProbeTest`, use JDK `com.sun.net.httpserver.HttpServer` to simulate:
- healthy + required endpoints present -> probe passes
- missing event endpoint -> `EVENT_STREAM_UNRELIABLE`
- missing permission endpoint -> `PERMISSION_PROTOCOL_UNSUPPORTED`

```java
@Test
void failsWhenEventStreamEndpointMissing() {
    // setup fake server without /event
    Result result = probe.probe(client);
    assertFalse(result.compatible());
    assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
}
```

- [ ] **Step 2: Run probe tests and verify failure**

Run: `mvn -pl app -Dtest=OpenCodeCapabilityProbeTest test`
Expected: FAIL because probe class does not exist.

- [ ] **Step 3: Implement capability probe with strict required checks**

Implement probe sequence:
- GET `/global/health`
- POST `/session` + capture session ID
- check event stream endpoint availability (`/event` preferred, `/global/event` fallback only when mapped)
- verify prompt path exists (`/session/{id}/prompt_async` preferred)
- verify permission/abort paths needed by Assistant contract

```java
if (!eventSupported) {
    return Result.fail("EVENT_STREAM_UNRELIABLE", "No supported SSE endpoint for assistant runtime");
}
```

- [ ] **Step 4: Re-run probe tests**

Run: `mvn -pl app -Dtest=OpenCodeCapabilityProbeTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbe.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/SessionCompatibilityException.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbeTest.java
git commit -m "feat(assistant-opencode): add fail-closed capability probe for interactive sessions"
```

---

### Task 6: Implement OpenCode interactive session driver

**Files:**
- Create:
  `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java`
- Modify: `app/src/main/resources/application.properties`

**Interfaces:**
- Consumes: Task 3 normalizer, Task 4 process/client, Task 5 probe.
- Produces: concrete `InteractiveSessionDriver` implementation for OpenCode.

- [ ] **Step 1: Write failing driver tests for lifecycle and prompt serialization**

Create
`app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java`
covering:
- startup runs process + probe and transitions to RUNNING
- second prompt while first in-flight is queued or rejected deterministically
- `interrupt()` triggers `/abort`

```java
@Test
void rejectsSecondPromptWhileTurnActive() throws Exception {
    driver.start();
    driver.sendUserMessage("first");
    assertThrows(IllegalStateException.class, () -> driver.sendUserMessage("second"));
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -pl app -Dtest=OpenCodeInteractiveSessionDriverTest test`
Expected: FAIL because driver class does not exist.

- [ ] **Step 3: Implement OpenCode driver lifecycle, SSE handling, and permission bridge**

Implement startup path:

```java
serverProcess.start();
OpenCodeAssistantClient client = new OpenCodeAssistantClient(serverProcess.baseUrl(), timeoutSeconds);
OpenCodeCapabilityProbe.Result result = capabilityProbe.probe(client);
if (!result.compatible()) {
    throw new SessionCompatibilityException(result.code(), result.message(), result.details());
}
this.openCodeSessionId = client.createSession(sessionTitle);
client.connectEvents(raw -> eventSink.acceptAll(normalizer.normalize(raw.name(), raw.payload())));
```

Implement:
- strict single in-flight prompt guard
- async prompt submit
- permission response mapping
- abort-on-interrupt
- deterministic driver status/error tracking

- [ ] **Step 4: Re-run OpenCode driver tests**

Run: `mvn -pl app -Dtest=OpenCodeInteractiveSessionDriverTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/ \
OpenCodeInteractiveSessionDriver.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java \
        app/src/main/resources/application.properties \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/ \
OpenCodeInteractiveSessionDriverTest.java
git commit -m "feat(assistant-opencode): implement interactive OpenCode session driver"
```

---

### Task 7: Wire session manager and REST layer to multi-engine interactive runtime

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
- Modify: `app/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeAgent.java`

**Interfaces:**
- Consumes: `InteractiveSessionDriverFactory` and `SessionCompatibilityException`.
- Produces: Assistant session creation path that supports both Claude and OpenCode interactive engines.

- [ ] **Step 1: Write failing manager/resource integration test**

Create `app/src/test/java/io/apitomy/axiom/app/OpenCodeAssistantResourceIntegrationTest.java`:

```java
@QuarkusTest
class OpenCodeAssistantResourceIntegrationTest {

    @Test
    void createSessionReturnsCompatibilityErrorWhenProbeFails() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"templateId":"general-assistant","name":"opencode-test"}
                """)
            .when()
                .post("/api/v1/assistant/sessions")
            .then()
                .statusCode(anyOf(is(201), is(400), is(422), is(500)));
    }
}
```

Then refine expected status after implementation to exact fail-closed mapping (`422` recommended).

- [ ] **Step 2: Run test and verify failure**

Run: `mvn -pl app -Dtest=OpenCodeAssistantResourceIntegrationTest test`
Expected: FAIL or nondeterministic status before explicit compatibility mapping exists.

- [ ] **Step 3: Update session manager + resource for driver-based startup and error mapping**

In `AssistantSessionManager.createSession(...)`:
- remove Claude-only command assembly from direct session startup path
- construct driver from factory using active engine type and resolved template context
- catch `SessionCompatibilityException` and propagate typed failure

In `AssistantResourceImpl.createAssistantSession(...)`:
- change availability text from Claude-only to generic interactive-engine support
- map compatibility failures to deterministic response (422 with message + code)

In `OpenCodeAgent`, add:

```java
@Override
public boolean supportsInteractiveSessions() {
    return true;
}
```

- [ ] **Step 4: Re-run integration and assistant tests**

Run:
- `mvn -pl app -Dtest=OpenCodeAssistantResourceIntegrationTest test`
- `mvn -pl app -Dtest=AssistantEventParserTest,AssistantItemValidatorTest test`

Expected: PASS with deterministic assistant session creation behavior for
supported/unsupported OpenCode runtime.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java \
        app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
        agents/opencode/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeAgent.java \
        app/src/test/java/io/apitomy/axiom/app/OpenCodeAssistantResourceIntegrationTest.java
git commit -m "feat(assistant): enable OpenCode interactive engine via driver-based session startup"
```

---

### Task 8: Add protocol harness for real OpenCode runtime verification

**Files:**
- Create:
  `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/
  OpenCodeAssistantProtocolHarnessTest.java`

**Interfaces:**
- Produces: executable harness assertions for startup, prompt, events, permission, abort, and teardown.

- [ ] **Step 1: Write failing harness test class with explicit scenario methods**

Create test skeleton:

```java
@QuarkusTest
class OpenCodeAssistantProtocolHarnessTest {

    @Test
    void verifiesSessionPromptEventAndAbortFlow() {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        fail("Implement protocol harness");
    }
}
```

- [ ] **Step 2: Run harness class to verify failure**

Run: `mvn -pl app -Dtest=OpenCodeAssistantProtocolHarnessTest test`
Expected: FAIL from `fail("Implement protocol harness")`.

- [ ] **Step 3: Implement real harness assertions**

Implement test flow:
1. start per-session process
2. verify `/global/health`
3. create OpenCode session
4. connect SSE and capture events
5. send async prompt
6. verify assistant + tool/status/turn completion events (minimum required subset)
7. issue abort on long-running turn
8. verify deterministic terminal behavior

Use JUnit assumptions to skip when OpenCode CLI is not installed.

- [ ] **Step 4: Re-run harness**

Run: `mvn -pl app -Dtest=OpenCodeAssistantProtocolHarnessTest test`
Expected: PASS when OpenCode runtime is installed and healthy; SKIPPED otherwise.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/ \
OpenCodeAssistantProtocolHarnessTest.java
git commit -m "test(assistant-opencode): add real-runtime protocol harness for interactive compatibility"
```

---

### Task 9: Update docs and configuration guidance for OpenCode interactive sessions

**Files:**
- Modify: `docs/user-guide/ai-assistant.md`
- Modify: `docs/getting-started/configuring-axiom.md`
- Modify: `docs/developer-guide/extending-axiom.md`
- Modify: `app/src/main/resources/application.properties`

**Interfaces:**
- Produces: user/developer docs that reflect multi-engine Assistant support and fail-closed behavior.

- [ ] **Step 1: Update AI Assistant user guide engine statement and behavior notes**

Replace Claude-only requirement text with engine-agnostic interactive requirement and add OpenCode notes:

```md
The AI Assistant requires an active AI engine that supports interactive sessions
(`claude-code` or `opencode`). If OpenCode capability checks fail at startup,
session creation is rejected with a compatibility error.
```

- [ ] **Step 2: Update configuration docs with assistant-opencode runtime properties**

Add properties to `configuring-axiom.md` table:

```md
| `axiom.assistant.opencode.executable` | `opencode` | OpenCode CLI executable |
| `axiom.assistant.opencode.startup-timeout-seconds` | `30` | Per-session server startup timeout |
| `axiom.assistant.opencode.probe-timeout-seconds` | `20` | Capability probe timeout |
| `axiom.assistant.opencode.port-range` | *(empty)* | Optional dedicated port range for per-session servers |
```

- [ ] **Step 3: Update extending guide with interactive driver architecture**

Document `InteractiveSessionDriver` and engine implementations with one paragraph and class references.

- [ ] **Step 4: Validate markdown wrap and links**

Run:

```bash
python -m scripts.check_markdown_wrap docs/user-guide/ai-assistant.md \
    docs/getting-started/configuring-axiom.md \
    docs/developer-guide/extending-axiom.md
```
Expected: PASS (or no lines over wrap threshold per repo policy).

- [ ] **Step 5: Commit**

```bash
git add docs/user-guide/ai-assistant.md \
        docs/getting-started/configuring-axiom.md \
        docs/developer-guide/extending-axiom.md \
        app/src/main/resources/application.properties
git commit -m "docs(assistant): document OpenCode interactive runtime and fail-closed compatibility"
```

---

## Final Verification Checklist

- [ ] Run backend focused tests:

```bash
mvn -pl app \
  -Dtest=AssistantEventParserTest,AssistantItemValidatorTest,AssistantSessionDriverDelegationTest,\
ClaudeInteractiveSessionDriverTest,OpenCodeEventNormalizerTest,OpenCodeCapabilityProbeTest,\
OpenCodeSessionServerProcessTest,OpenCodeInteractiveSessionDriverTest,\
OpenCodeAssistantResourceIntegrationTest \
  test
```

Expected: PASS (with OpenCode runtime-dependent tests skipped only when OpenCode CLI is unavailable).

- [ ] Run OpenCode protocol harness:

```bash
mvn -pl app -Dtest=OpenCodeAssistantProtocolHarnessTest test
```

Expected: PASS on installed OpenCode runtime; SKIPPED otherwise.

- [ ] Manual smoke test in UI:

```text
1) Set axiom.agent.default-type=opencode
2) Start Axiom
3) Create Assistant session from General template
4) Send prompt, approve permission, verify tool activity and completion
5) Click Stop and End Session, verify deterministic termination
```

Expected: behavior matches existing Assistant UX contract and session teardown removes the dedicated OpenCode
process.

---

## Self-Review

**Spec coverage:**
- Architecture boundary + manager ownership: Tasks 1, 2, 7.
- One OpenCode process per Axiom session: Tasks 4, 6, 8.
- Fail-closed capability validation: Tasks 5, 6, 7.
- Event normalization and unchanged frontend contract: Tasks 3, 6.
- Prompt/permission/abort/session lifecycle parity: Tasks 6, 7, 8.
- Reliability verification and protocol spike first: Task 8 + final verification.
- Docs/config updates: Task 9.

**Placeholder scan:**
- No TBD/TODO placeholders remain.
- Each code step includes concrete classes/methods and snippets.
- Every task includes a concrete test cycle and commit step.

**Type/interface consistency:**
- `InteractiveSessionDriver` signatures are defined in Task 1 and consumed consistently in Tasks 2, 6, 7.
- OpenCode normalizer output event names match existing Assistant frontend consumers.
- Compatibility failure types flow from probe -> `SessionCompatibilityException` -> REST response mapping.
