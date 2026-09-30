# OpenCode Mid-Turn Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Let users and the Configuration Assistant's validation feedback send messages to an OpenCode session
while a turn is in progress. When a message genuinely cannot be delivered, say so clearly in the chat instead of
leaving an undelivered "user" message (GitHub issue #375, epic #387).

**Architecture:** opencode already queues prompts natively. A second `prompt_async` sent while the session is busy
is accepted, answered after the current turn, and followed by a single `session.idle`. The only thing blocking
mid-turn messages is our own `turnInFlight` guard in `OpenCodeInteractiveSessionDriver.sendUserMessage`. We remove
that guard, along with the `turnInFlight` state whose only purpose was the guard. Separately,
`AssistantSession.sendMessage` records the `user_message` before calling the driver. It keeps that order, so
history order and replay indexes don't change. If the driver throws, it now also records a `session_error`
(`MessageNotDelivered`) and then rethrows.

**Tech Stack:** Java 21, JUnit 5, JDK `HttpServer` fakes, opencode 1.18.x.

**Spec:** GitHub issue #375 ("OpenCode sessions: allow messages while a turn is in flight"). The issue was
reopened on 2026-09-30 after being auto-closed by a keyword in the PR #392 description.

## Background (verified facts)

Verified against opencode 1.18.33 on 2026-09-30 with `/tmp/opencode/busy.py`. It sent prompt 1 ("count slowly to
40, then FIRST-DONE") and, 1.5 s later while the session was busy, prompt 2 ("reply SECOND-DONE"):
- Both `POST /session/{id}/prompt_async` calls returned **204**.
- opencode answered them **in order**. The messages were user 1, then assistant `…FIRST-DONE` (parent = user 1),
  then user 2, then assistant `SECOND-DONE` (parent = user 2).
- Only **one** `session.idle` was emitted, after both answers.

Consequences:
- Removing the guard is enough to deliver queued messages. The driver emits one `turn_complete` for the
  combined work, which ends the UI's "processing" state at the right time.
- Every prompt still carries the system prompt: `sendUserMessage` already passes `systemPrompt`, as #370
  requires for every path that posts user messages.

Code facts:
- `OpenCodeInteractiveSessionDriver` has `private final AtomicBoolean turnInFlight`:
  - `sendUserMessage` (lines ~260-272) uses it as the guard.
  - It is reset in `sendUserMessage`'s catch, in `interrupt`, in `destroy`, in `handleRawEvent` (on
    `turn_complete`) and in the stream-failure handler (line ~432).
  - Nothing else reads it.
- Tests that use it, all in `OpenCodeInteractiveSessionDriverTest`:
  - `rejectsSecondPromptWhileTurnActive` (line ~71) asserts the old behavior.
  - `streamFailureTransitionsToErrorAndClearsInFlightPrompt` (line ~267) has `assertFalse(isTurnInFlight(driver))`.
  - `sendUserMessageIncludesSessionSystemPromptOnEveryPrompt` (line ~594) waits on `!isTurnInFlight(driver)`
    between sends.
  - The reflection helper `isTurnInFlight` (line ~425).
- `AssistantSession.sendMessage` (`AssistantSession.java:169-177`) calls
  `addEvent(new SseEvent("user_message", …))`, then `driver.sendUserMessage(message)`. Its callers:
  - `AssistantResourceImpl.sendAssistantMessage` (REST; maps `IOException` to 500)
  - `AssistantSessionManager` (initial message; the validation feedback listener, which catches `IOException`
    and whose outer handler catches `Exception`)
- History is an append-only `CopyOnWriteArrayList` replayed by index (`sinceId`), so events must never be removed.
- The UI (`AssistantChatPanel.tsx:452`) shows `session_error` as a system message
  (`data.message`, default "Session error") and clears the processing spinner.

**Rulings:**
- **Keep the user_message-before-send order.** Recording after a successful send would let the SSE thread emit
  the reply's first events before the user message in history, and removal is impossible with index-based
  replay. On failure we append a `session_error` explaining that the message was not delivered. This is the
  issue's "record failures clearly" option.
- **No Axiom-side queue.** opencode's native queue already delivers in order. A second queue would duplicate it
  and add ordering and shutdown complexity.
- **`noReply` for validation feedback is out of scope** (#385). Feedback is now delivered as a normal queued
  prompt, which is also what Claude Code does.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- Do not change the UI, the REST API/OpenAPI spec, or Claude driver behavior. The `AssistantSession` change
  applies to both engines: on a driver failure it adds one `session_error` event and still rethrows.
- The `session_error` for a failed delivery has `data.name = "MessageNotDelivered"`, and `data.message` equals
  `"Message was not delivered: " + <reason>`. `<reason>` is the exception message, or the exception's simple class
  name if the message is null or blank.
- Every prompt the driver posts keeps passing the session system prompt (#370).
- Out of scope: `noReply` feedback (#385), permission mapping (#373), cost (#374).
- Commit message style: conventional commits, e.g. `fix(assistant): ...`. **Do not put closing keywords (fix/
  fixes/close/resolve) directly before an issue number** in commit messages or PR text unless that issue is meant
  to close.
- Run tests with: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false` from the repo
  root (run `mvn -q install -DskipTests` once first if sibling modules aren't installed). A JBoss LogManager
  "accessed before property set" warning is pre-existing noise.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java` | Modify | Remove the turn-in-flight guard and state |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java` | Modify | Accept-while-busy test; drop `turnInFlight` reflection |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java` | Modify | Record `MessageNotDelivered` on driver failure |
| `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java` | Modify | Delivery-failure test |
| `docs/user-guide/ai-assistant.md` | Modify | Note that messages sent while the assistant is working are queued |

---

### Task 1: Remove the turn-in-flight guard from the OpenCode driver

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java`

**Interfaces:**
- Produces: `sendUserMessage(String)` never throws `IllegalStateException` for a busy session. It throws
  `IllegalStateException` only from `ensureRunning()` (session not running) and `IOException` when the HTTP post
  fails. The private field `turnInFlight` no longer exists.

- [ ] **Step 1: Replace the reject test with an accept test (failing first)**

Replace the whole `rejectsSecondPromptWhileTurnActive` test with:

```java
    @Test
    void acceptsSecondPromptWhileTurnActive() throws Exception {
        CountDownLatch promptSubmitted = new CountDownLatch(1);
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            promptSubmitted.await(3, TimeUnit.SECONDS);
            Thread.sleep(500);
            exchange.getResponseBody().close();
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder, promptSubmitted)) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    new OpenCodeInteractiveSessionDriver.SessionSettings(
                            "Axiom Session", "github-copilot/claude-sonnet-5", null, Set.of(),
                            "You are the Axiom Configuration Assistant."));

            driver.start();
            driver.sendUserMessage("first");

            assertDoesNotThrow(() -> driver.sendUserMessage("second while busy"));
            assertEquals(2, server.promptCallCount());
            JsonNode second = new ObjectMapper().readTree(server.promptBodies().get(1));
            assertEquals("second while busy", second.path("parts").get(0).path("text").asText());
            assertEquals("You are the Axiom Configuration Assistant.", second.path("system").asText());

            driver.destroy();
        }
    }
```

(`assertDoesNotThrow`, `JsonNode`, `ObjectMapper`, `Set`, `CountDownLatch` and `TimeUnit` are already imported,
and `FakeOpenCodeServer.promptBodies()` already exists. If any is missing, add it.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeInteractiveSessionDriverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `acceptsSecondPromptWhileTurnActive` FAILS with `IllegalStateException: A turn is already in flight`.

- [ ] **Step 3: Implement**

In `OpenCodeInteractiveSessionDriver`:
1. Replace `sendUserMessage` with the code below and give it Javadoc (the interface method may already have
   Javadoc, in which case keep `@Override` and add this note as an implementation comment inside):

```java
    @Override
    public void sendUserMessage(String message) throws IOException {
        // OpenCode queues prompts natively: a prompt posted while the session is busy is answered after the
        // current turn (verified on opencode 1.18.33), so no client-side turn guard is needed.
        ensureRunning();
        try {
            client.sendPromptAsync(openCodeSessionId, message, model, tools, systemPrompt);
        } catch (RuntimeException e) {
            throw new IOException("Failed to submit OpenCode prompt", e);
        }
    }
```

2. Delete the field `private final AtomicBoolean turnInFlight = new AtomicBoolean(false);` and every
   `turnInFlight.set(false);` line (in `interrupt`, `destroy`, `handleRawEvent` and the stream-failure handler).
   In `handleRawEvent`, remove the `if ("turn_complete".equals(normalizedEvent.type())) { ... }` block entirely,
   because its only statement was the reset. Remove the `AtomicBoolean` import if nothing else uses it.

- [ ] **Step 4: Update the tests that read `turnInFlight`**

- In `streamFailureTransitionsToErrorAndClearsInFlightPrompt`: delete the line
  `assertFalse(isTurnInFlight(driver));` and rename the test to `streamFailureTransitionsToError`. Keep all other
  assertions.
- In `sendUserMessageIncludesSessionSystemPromptOnEveryPrompt`: delete the `waitUntil(() -> { ...
  !isTurnInFlight(driver) ... }, Duration.ofSeconds(3));` block and the following
  `assertFalse(isTurnInFlight(driver));`. The second `sendUserMessage("second")` is now allowed immediately.
  Keep the event responder and all body assertions as they are.
- Delete the `isTurnInFlight(OpenCodeInteractiveSessionDriver)` helper.
- Run `grep -n "turnInFlight\|isTurnInFlight" app/src/test app/src/main -r` and confirm there are no results.

- [ ] **Step 5: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS. Then run `-Dtest=OpenCodeInteractiveSessionDriverTest` 3 more times. All 3 must pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java
git commit -m "feat(assistant): let OpenCode queue messages sent during a turn (#375)"
```

---

### Task 2: Record undelivered messages clearly

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java:162-177`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java`
- Modify: `docs/user-guide/ai-assistant.md`

**Interfaces:**
- Produces: `AssistantSession.sendMessage(String)` keeps its signature and `throws IOException`. On a driver
  exception (`IOException` or `RuntimeException`) it appends
  `SseEvent("session_error", {"name":"MessageNotDelivered","message":"Message was not delivered: <reason>"})`
  after the already-recorded `user_message`, then rethrows the original exception unchanged.

- [ ] **Step 1: Write the failing tests**

Add to `AssistantSessionDriverDelegationTest` (imports: `java.io.IOException`,
`io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent`,
`static org.junit.jupiter.api.Assertions.assertThrows`):

```java
    @Test
    void failedDeliveryRecordsMessageNotDeliveredAfterUserMessage() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        driver.sendFailure = new IOException("connection refused");
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "opencode", null, null, driver);
        session.start();

        IOException thrown = assertThrows(IOException.class, () -> session.sendMessage("hello"));

        assertSame(driver.sendFailure, thrown);
        List<SseEvent> history = session.getEventHistory();
        SseEvent userMessage = history.get(history.size() - 2);
        SseEvent error = history.get(history.size() - 1);
        assertEquals("user_message", userMessage.type());
        assertEquals("hello", userMessage.data().path("content").asText());
        assertEquals("session_error", error.type());
        assertEquals("MessageNotDelivered", error.data().path("name").asText());
        assertEquals("Message was not delivered: connection refused", error.data().path("message").asText());
    }

    @Test
    void failedDeliveryWithoutMessageUsesExceptionType() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        driver.sendFailure = new IllegalStateException();
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "opencode", null, null, driver);
        session.start();

        assertThrows(IllegalStateException.class, () -> session.sendMessage("hello"));

        List<SseEvent> history = session.getEventHistory();
        assertEquals("Message was not delivered: IllegalStateException",
                history.get(history.size() - 1).data().path("message").asText());
    }
```

Extend `RecordingDriver`: add a field `Exception sendFailure;` and change `sendUserMessage`:

```java
        @Override
        public void sendUserMessage(String message) throws IOException {
            lastMessage = message;
            if (sendFailure instanceof IOException ioException) {
                throw ioException;
            }
            if (sendFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
        }
```

(If `InteractiveSessionDriver.sendUserMessage` does not declare `throws IOException`, check the interface. It
does in the OpenCode driver. Keep the interface's declared exceptions.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=AssistantSessionDriverDelegationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: both new tests FAIL (the last history event is `user_message`, not `session_error`).

- [ ] **Step 3: Implement**

Replace `AssistantSession.sendMessage` with:

```java
    /**
     * Sends a user message to the runtime. The message is recorded in event history (so it can be replayed on
     * reconnect) before it is handed to the driver. If the driver cannot deliver it, a {@code session_error}
     * event named {@code MessageNotDelivered} is recorded after it and the driver's exception is rethrown.
     *
     * @param message the user's message text
     * @throws IOException if the message cannot be written
     */
    public void sendMessage(String message) throws IOException {
        ObjectNode userData = MAPPER.createObjectNode();
        userData.put("content", message);
        addEvent(new SseEvent("user_message", userData));

        try {
            driver.sendUserMessage(message);
        } catch (IOException | RuntimeException e) {
            recordUndeliveredMessage(e);
            throw e;
        }
        lastActivityAt = Instant.now();
    }

    private void recordUndeliveredMessage(Exception failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        ObjectNode errorData = MAPPER.createObjectNode();
        errorData.put("name", "MessageNotDelivered");
        errorData.put("message", "Message was not delivered: " + reason);
        addEvent(new SseEvent("session_error", errorData));
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='AssistantSession*Test,OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS, including the existing `sessionDelegatesRuntimeCallsToDriver`.

- [ ] **Step 5: Update the user guide**

In `docs/user-guide/ai-assistant.md`, find the section that describes chatting with or sending messages in a
session (search for "Send" or the chat input description). Add one sentence there, wrapped at 110 columns and
matching the surrounding style:

```markdown
You can send another message while the assistant is still working; it is queued and answered after the current
reply. If a message cannot be delivered, the chat shows a "Message was not delivered" notice.
```

If no such section exists, add it to the end of the `!!! note` block about agent types instead (4-space
indented).

- [ ] **Step 6: Full module test run**

Run: `mvn -q -pl app test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/AssistantSessionDriverDelegationTest.java \
        docs/user-guide/ai-assistant.md
git commit -m "feat(assistant): show when an assistant message could not be delivered (#375)"
```

- [ ] **Step 8: Manual end-to-end verification (human)**

1. `./dev.sh` from this branch's worktree. Start a Configuration Assistant session on `opencode`.
2. Ask a long-running question (e.g. "list every tool configured in Axiom and describe each"). While it is
   working, send "Also, how many action types are there?". There should be no error, and both are answered in
   order.
3. Ask it to create a tool with a deliberately invalid field (e.g. "create a tool with an empty name"). The
   validation feedback message appears as a user message, and the assistant then responds to it and fixes the
   file.
4. No "Message was not delivered" notice appears in normal use.

---

## Self-Review Notes

- Issue #375 → tasks:
  - Queue while busy → Task 1. opencode's native queue was verified, so the guard is simply removed.
  - Record `user_message` only after success / record failures clearly → Task 2. The "record failures clearly"
    option was chosen; see the Rulings.
  - Acceptance (sending while busy doesn't error and the message is delivered) → Task 1 test and Task 2 Step 8.
  - `noReply` is deferred to #385.
- The system prompt on every prompt (#370) is asserted in the Task 1 accept test.
- Names are consistent: `MessageNotDelivered`, `"Message was not delivered: "`, `sendFailure`,
  `acceptsSecondPromptWhileTurnActive`, `streamFailureTransitionsToError`.
