# Workbench Phase 2b: AI Turn Loop and Agent Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Let an AI agent work on a composition in turns. It reads and edits drafts through a dedicated MCP
server, proposes workflow changes as Flow change sets, inspects real stream events, and evaluates EL
expressions. The UI receives each finished turn as one combined change set and can undo a turn.

**Architecture:**
- **Session binding:** a composition is bound to one interactive assistant session created from the new
  built-in template `axiom-workbench` (capability `composition`). That session runs the new
  `axiom-workbench` MCP server.
- **MCP-only routes:** the server calls REST routes under `/compositions/{id}/agent/...`. One shared
  `SessionTokenGuard` checks the session token on all of them.
- **Turns:** `CompositionTurns` keeps the in-memory state of a running turn: the base workflow, the
  workflow with the turn's changes so far, and the ops. When the turn completes, it stores a pending change
  set and announces it over SSE. The editor applies it, which is Phase 4.
- **Restarts:** `WorkbenchSessions` restores the chat history from `history.jsonl` and starts a new agent
  process. The next turn begins with a recap.
- **Expressions:** `ExpressionService` evaluates EL with Flow's `ConditionEvaluator`, using the same scope
  the engine provides at each point.

**Tech Stack:** Java 25, Quarkus 3.40.1, Panache, Jackson, Apitomy Flow engine 3.0.1
(`io.apitomy.flow.changeset.*`, `io.apitomy.flow.engine.ConditionEvaluator`), Node.js MCP server
(`@modelcontextprotocol/sdk`), JUnit 5, RestAssured, Mockito (`@InjectMock`), `node --test`.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`, especially §1 "MCP server",
"Template", "The turn loop", §2 "Right pane" (Undo AI turn), §3 and §4. Phase 2a is merged on
`feat/workflow-first`; its composition classes live in `app/src/main/java/io/apitomy/axiom/app/composition/`.

## Global Constraints

- REST is contract-first. Edit `common/api/src/main/resources/openapi.json` first, then implement the
  generated `CompositionsResource`. A new top-level path generates its own interface, for example
  `/expressions` generates `ExpressionsResource`. Never put `@Path` on implementation classes, and use the
  generated beans. Keep `openapi.json` valid (`python3 -m json.tool ... > /dev/null`).
- **Never run `mvn clean`, `./build.sh` or any `mvn install`.** Use
  `mvn -q -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- Java style: Javadoc on public types and methods, 4-space indent, explicit types, JUnit 5. Use `git grep`,
  not `rg`. Commit with conventional commit messages and no AI attribution. Do not push.
- Schema changes go into `V1__baseline.sql` in place. `MigrationSchemaValidationTest` must pass.
- Token header: `X-Axiom-Session-Token`. MCP server environment variables: `AXIOM_API_URL`,
  `AXIOM_COMPOSITION_ID`, `AXIOM_SESSION_TOKEN`.
- Built-in template id: `axiom-workbench`. MCP alias: `@axiom-workbench`. Registered server name:
  `workbench`, so its tools are named `mcp__workbench__<tool>`.
- Change-set author written by the server: `agent:axiom-workbench`. A combined change set has
  `id = turnId` and `baseRevision` = the workflow revision at the start of the turn.
- SSE event types:
  - `composition-turn`: `{compositionId, turnId, state}`, where `state` is `ai-working` or `idle`.
  - `composition-changeset`: `{compositionId, turnId, changeSet}`.
  - `composition-item-changed`: unchanged from 2a.
- Every MCP-only route requires a valid token for the composition's live session (401 otherwise). Agent
  writes also require `turnState = ai-working` (409 otherwise) and are tagged `author = ai` and
  `turnId = currentTurnId`.
- Stale handling: a stale resolution re-prompts the agent **once** per turn chain. A second stale
  resolution ends the chain with a chat notice.
- Expression scopes and their EL variables:

  | Scope | Kind | Variables |
  |---|---|---|
  | `edge-condition` | boolean | `context` |
  | `action-input` | value | `context` |
  | `human-task-title` | value | `context` |
  | `correlation-subscription-key` | value | `context` |
  | `correlation-event-key` | value | `event` only |
  | `receive-event-match` | boolean | `context`, `event` |
  | `receive-event-output` | value | `context`, `event` |

  `event` is `WorkflowEventMapper.toEventMap(streamEvent, mapper)`.

## File Map

| File | Change |
|---|---|
| `app/.../assistant/SessionTokenGuard.java` | Create: the shared token check |
| `app/.../assistant/HistoryJournal.java` | Asynchronous writer; normalize missing `data` |
| `app/.../assistant/AssistantSession.java` | `seedHistory(...)` |
| `app/.../assistant/AssistantSessionManager.java` | Composition-aware `createSession`, `@axiom-workbench`, generic MCP installer, keep composition session directories |
| `app/src/main/resources/templates/assistant-templates/axiom-workbench.json` | Create: the template |
| `app/src/main/resources/templates/axiom-workbench-mcp/{package.json,server.js}` | Create: the MCP server |
| `app/.../assistant/AssistantReferenceGenerator.java` | `generateChangeSetReference()` |
| `core/.../entities/CompositionEntity.java`, `V1__baseline.sql` | `last_turn_started_on` column |
| `core/.../events/SseEvent.java` | `compositionTurn`, `compositionChangeSet` |
| `app/.../composition/{CompositionTurns,WorkbenchSessions,TurnPreamble,WorkflowDiffs,TurnUndo}.java` | Create |
| `app/.../expressions/ExpressionService.java` | Create |
| `app/.../rest/CompositionsResourceImpl.java`, `app/.../rest/ExpressionsResourceImpl.java`, `openapi.json` | New endpoints |
| Tests under `app/src/test/java/io/apitomy/axiom/app/{assistant,composition,expressions}/` and `app/src/test/node/` | Create |

---

### Task 1: Foundation fixes (shared token guard, async journal, normalized history)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTokenGuard.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java` (`validateAssistantItem`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/HistoryJournal.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` (`destroySession`
  closes the journal)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/SessionTokenGuardTest.java`
- Test: extend `app/src/test/java/io/apitomy/axiom/app/assistant/HistoryJournalTest.java`

**Interfaces:**
- Produces:
  - `SessionTokenGuard` (`@ApplicationScoped`), with:
    - `static final String HEADER = "X-Axiom-Session-Token"`
    - `void require(AssistantSession session, String presented)`, which throws
      `WebApplicationException(401)` if `session == null` or the token does not verify.
  - `HistoryJournal`:
    - `accept` now only enqueues the event. A single daemon writer thread per journal appends events in
      order.
    - `void close()` flushes the remaining events and stops the thread. It is idempotent.
    - `void flush()` blocks until everything enqueued so far is written. It exists for tests and for
      restore.
    - `load` turns missing or null `data` into an empty object.

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app.assistant;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the shared MCP session-token check.
 */
class SessionTokenGuardTest {

    private final SessionTokenGuard guard = new SessionTokenGuard();

    private static AssistantSession session(String token) {
        AssistantSession session = new AssistantSession("s", "n", "t", null, null, java.util.List.of(),
                java.util.Map.of(), "claude-code", null, null, org.mockito.Mockito.mock(
                        io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver.class));
        session.setMcpToken(token);
        return session;
    }

    @Test
    void acceptsMatchingToken() {
        assertDoesNotThrow(() -> guard.require(session("secret"), "secret"));
    }

    @Test
    void rejectsMissingWrongTokenOrSession() {
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(session("secret"), null)).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(session("secret"), "nope")).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(null, "secret")).getResponse().getStatus());
    }
}
```

Append these to `HistoryJournalTest`:

```java
    @Test
    void asyncWritesArePreservedInOrderAfterFlush(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        HistoryJournal journal = new HistoryJournal(file, MAPPER);
        for (int i = 0; i < 200; i++) {
            journal.accept(event("assistant_text", "m" + i));
        }
        journal.flush();
        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertEquals(200, loaded.size());
        assertEquals("m199", loaded.get(199).data().path("text").asText());
        journal.close();
        journal.close();
    }

    @Test
    void missingDataLoadsAsEmptyObject(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(HistoryJournal.FILE_NAME);
        Files.writeString(file, "{\"type\":\"turn_complete\"}\n{\"type\":\"x\",\"data\":null}\n");
        List<SseEvent> loaded = HistoryJournal.load(file, MAPPER);
        assertTrue(loaded.get(0).data().isObject());
        assertTrue(loaded.get(1).data().isObject());
    }
```

The existing `writeFailureDoesNotThrow` test must keep passing. If `accept` is asynchronous, it trivially
does not throw, so also call `journal.flush()` and `journal.close()` in that test, and assert they do not
throw.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q -pl app -am test -Dtest='SessionTokenGuardTest,HistoryJournalTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `SessionTokenGuard`, `flush` and `close` do not exist.

- [ ] **Step 3: Implement `SessionTokenGuard`**

```java
package io.apitomy.axiom.app.assistant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

/**
 * The single check used by every REST route that only a session's own MCP server may call. The token keeps
 * sessions apart and stops casual local callers. It does not protect Axiom from the agent itself, which can
 * read its own token from its environment.
 */
@ApplicationScoped
public class SessionTokenGuard {

    /** Request header carrying the session's MCP token. */
    public static final String HEADER = "X-Axiom-Session-Token";

    /**
     * Requires the presented token to match the session's token.
     *
     * @param session the live session the route belongs to, or null if none is running
     * @param presented the header value, possibly null
     * @throws WebApplicationException with status 401 if the session is missing or the token does not match
     */
    public void require(AssistantSession session, String presented) {
        if (session == null || !session.verifyMcpToken(presented)) {
            throw new WebApplicationException("Missing or invalid " + HEADER, 401);
        }
    }
}
```

In `AssistantResourceImpl.validateAssistantItem`, replace the inline token check with
`tokenGuard.require(session, httpHeaders.getHeaderString(SessionTokenGuard.HEADER));`, injecting
`SessionTokenGuard tokenGuard`. Remove the now-unused `SESSION_TOKEN_HEADER` constant, or point it at
`SessionTokenGuard.HEADER` if tests reference it (check with `git grep SESSION_TOKEN_HEADER`).

- [ ] **Step 4: Make the journal asynchronous**

Rewrite `HistoryJournal`, keeping `FILE_NAME`, the constructor signature and `load`:
- **Fields:** a `LinkedBlockingQueue<Object>` queue, a daemon `Thread` named `history-journal-<file name>`
  that is started in the constructor, a `volatile boolean closed`, and a private static final `Object STOP`.
- **`accept(event)`:** if not closed, build the JSON line (`{"type":...,"data":...}`) on the calling thread
  and `offer` the string. Never block, never throw.
- **Writer loop:** `take()` items. On a `String`, append it with
  `Files.writeString(file, line + "\n", UTF_8, CREATE, APPEND)` and log I/O errors at WARN. On a
  `CountDownLatch`, count it down (this is the flush marker). On `STOP`, exit.
- **`flush()`:** enqueue a `CountDownLatch(1)` and await it for up to 10 seconds. Return quietly if the
  journal is closed.
- **`close()`:** if not already closed, set `closed`, enqueue `STOP`, and join the thread for up to 10
  seconds.
- **`load`:** where it builds `new SseEvent(type, line.path("data"))`, use
  `line.path("data").isObject() ? line.path("data") : mapper.createObjectNode()`.

Javadoc must state that ordering is preserved and that disk I/O never runs on the caller's thread. Writes
used to happen while `AssistantSession`'s event lock was held; now they don't.

`AssistantSessionManager.createSession` creates the journal inline. Keep a reference to it: add a field
`private final Map<String, HistoryJournal> journals = new ConcurrentHashMap<>();`, put the journal there by
session id, and in `destroySession` call `journals.remove(sessionId)` then `close()` **before** deleting the
session directory.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='SessionTokenGuardTest,HistoryJournalTest,SessionTokenTest,AssistantValidateEndpointTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If `SessionTokenTest.sessionEventsAreJournaled` now fails because the write is
asynchronous, change that test to read the journal through a short poll of up to 2 s, or to flush first.
Expose a package-private `AssistantSessionManager.journal(String sessionId)` accessor for this purpose.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/assistant
git commit -m "feat(assistant): shared session-token guard and asynchronous history journal"
```

---

### Task 2: Composition-aware assistant sessions (`@axiom-workbench`, seeded history, kept directories)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSession.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java` (`BUILT_IN_FILES`)
- Create: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json`. In this task it is a
  minimal placeholder; Task 11 completes it.
- Create: `app/src/main/resources/templates/axiom-workbench-mcp/package.json` and `server.js`. In this task
  they are minimal; Task 10 completes them.
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchSessionManagerTest.java`
- Test: extend `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantMcpServerAugmentationTest.java`

**Interfaces:**
- Produces:
  - `AssistantSession.seedHistory(List<SseEvent> events)`. It is package-private and only valid before any
    listener is attached and before `start()`. It appends to the history without dispatching.
  - `AssistantSessionManager.createSession(String name, String templateId, Long subjectId, Long compositionId, List<SseEvent> seedHistory)`.
    The existing three-argument method delegates to it with `null, List.of()`. `compositionId` is passed to
    the `@axiom-workbench` server as `AXIOM_COMPOSITION_ID`. `seedHistory` is applied before the journal and
    other listeners are attached, so seeded events are not journalled again. Write them to the new journal
    file directly instead, so the new directory holds the complete history: call `journal.accept` for each
    seeded event before `session.addListener(journal)`.
  - `AssistantSessionManager.destroySession(String sessionId, boolean keepDirectory)`. The one-argument
    form delegates with `false`.
  - `onShutdown` keeps the directories of sessions that have `SessionCapability.COMPOSITION`.
  - `onStartup` never deletes a session directory that contains a file named `composition`. The manager
    writes that marker, containing the composition id, when it creates a composition session.
  - `static Path sessionDirectory(String sessionId)`, which is `AxiomHome.resolve("assistant", "sessions", sessionId)`.
  - The `@axiom-workbench` alias resolves to the server name `workbench`: stdio `node <dir>/server.js`, with
    environment `AXIOM_API_URL`, `AXIOM_COMPOSITION_ID` (if set) and `AXIOM_SESSION_TOKEN`. It is installed
    to `${axiom.home}/workbench-mcp-server` from the classpath `templates/axiom-workbench-mcp/`.
  - `augmentServersFromAllowedTools` auto-adds `@axiom-workbench` for tools starting with
    `mcp__workbench__`, and excludes that prefix from the external-server loop.

- [ ] **Step 1: Write the failing tests**

Append this to `AssistantMcpServerAugmentationTest`, following its existing style of calling the static
method with mutable lists:

```java
    @Test
    void workbenchToolsImplyWorkbenchServerAndAreNotExternal() {
        List<String> servers = new ArrayList<>();
        AssistantSessionManager.augmentServersFromAllowedTools(servers,
                List.of("mcp__workbench__get_composition", "mcp__workbench__propose_workflow_changes"));
        assertEquals(List.of("@axiom-workbench"), servers);
    }
```

Create `WorkbenchSessionManagerTest`:

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Composition sessions: the workbench MCP server is configured, history can be seeded, and directories survive.
 */
@QuarkusTest
class WorkbenchSessionManagerTest {

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    AssistantSessionManager manager;

    @Inject
    ObjectMapper mapper;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(manager::destroySession);
    }

    private AssistantSession create(List<SseEvent> seed) throws IOException {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        AssistantSession session = manager.createSession("wb", "axiom-workbench", null, 42L, seed);
        created.add(session.getId());
        return session;
    }

    @Test
    void configuresWorkbenchServerWithCompositionAndToken() throws IOException {
        AssistantSession session = create(List.of());
        assertTrue(session.hasCapability(SessionCapability.COMPOSITION));
        JsonNode config = mapper.readTree(Files.readString(session.getSessionDirectory().resolve("mcp-config.json")));
        JsonNode env = config.path("mcpServers").path("workbench").path("env");
        assertEquals("42", env.path("AXIOM_COMPOSITION_ID").asText());
        assertEquals(session.getMcpToken(), env.path("AXIOM_SESSION_TOKEN").asText());
        assertEquals("42", Files.readString(session.getSessionDirectory().resolve("composition")).trim());
    }

    @Test
    void seededHistoryIsVisibleAndJournaledOnce() throws IOException {
        ObjectNode data = mapper.createObjectNode().put("content", "earlier question");
        AssistantSession session = create(List.of(new SseEvent("user_message", data)));
        assertEquals("earlier question", session.getEventHistory().get(0).data().path("content").asText());
        manager.journal(session.getId()).flush();
        List<SseEvent> journaled = HistoryJournal.load(
                session.getSessionDirectory().resolve(HistoryJournal.FILE_NAME), mapper);
        assertEquals(1, journaled.stream().filter(e -> "earlier question".equals(e.data().path("content").asText())).count());
    }

    @Test
    void destroyCanKeepDirectory() throws IOException {
        AssistantSession session = create(List.of());
        Path dir = session.getSessionDirectory();
        manager.destroySession(session.getId(), true);
        created.remove(session.getId());
        assertTrue(Files.isDirectory(dir));
        manager.destroySession(session.getId(), false);
    }
}
```

The last line runs after the session has already been removed, so it is a no-op. Delete the kept directory
in the test with the same recursive helper the context builder uses
(`contextBuilder.deleteSessionDirectory(dir)`, injected as `AssistantContextBuilder`), so the test leaves
nothing behind.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q -pl app -am test -Dtest='WorkbenchSessionManagerTest,AssistantMcpServerAugmentationTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because the new overloads do not exist.

- [ ] **Step 3: Add the placeholder template and MCP server**

`axiom-workbench.json` (Task 11 replaces the prompt):

```json
{
  "templateId": "axiom-workbench",
  "name": "Workflow Workbench",
  "description": "AI partner for composing a workflow together with its action types and tools in the workbench.",
  "capabilities": ["composition"],
  "systemPrompt": "You are the Axiom Workflow Workbench assistant.\n\n{{reference}}",
  "welcomeMessage": "Tell me what this workflow should do, or ask me to change something.",
  "mcpServers": ["@axiom-workbench"],
  "allowedTools": ["mcp__workbench__get_composition"]
}
```

`axiom-workbench-mcp/package.json`: copy `axiom-assistant-mcp/package.json` and change the name to
`axiom-workbench-mcp`. `server.js`: a minimal server built like `axiom-assistant-mcp/server.js`, with
`axiomApi` sending the token header and a single tool, `get_composition`, that calls
`GET /compositions/${AXIOM_COMPOSITION_ID}/agent/composition`. Task 10 completes it. Add
`"axiom-workbench.json"` to `SessionTemplateService.BUILT_IN_FILES`.

- [ ] **Step 4: Implement the manager changes**

1. **`AssistantSession.seedHistory`:**

```java
    /**
     * Restores earlier events into the history without notifying listeners. Must be called before any
     * listener is attached and before {@link #start()}.
     *
     * @param events the events, oldest first
     */
    void seedHistory(List<SseEvent> events) {
        synchronized (eventLock) {
            eventHistory.addAll(events);
        }
    }
```

2. **Generic installer:** refactor `ensureAssistantMcpServerInstalled()` into
   `private Path ensureMcpServerInstalled(String dirName, String resourceDir)`. Track installs in a
   `ConcurrentHashMap<String, Path>` keyed by `dirName`, and keep the same double-checked
   `node_modules` logic. `@axiom-assistant` calls it with `("assistant-mcp-server", "templates/axiom-assistant-mcp/")`
   and `@axiom-workbench` with `("workbench-mcp-server", "templates/axiom-workbench-mcp/")`. Both copy
   `package.json` and `server.js`.
3. **`resolveMcpServers`:** add a `Long compositionId` parameter and a branch:

```java
            } else if ("@axiom-workbench".equals(serverName)) {
                Path serverDir = ensureMcpServerInstalled("workbench-mcp-server", "templates/axiom-workbench-mcp/");
                Map<String, String> env = new LinkedHashMap<>();
                env.put("AXIOM_API_URL", apiUrl);
                if (compositionId != null) {
                    env.put("AXIOM_COMPOSITION_ID", String.valueOf(compositionId));
                }
                env.put("AXIOM_SESSION_TOKEN", mcpToken);
                configs.put("workbench", AssistantContextBuilder.McpServerConfig.stdio("node",
                        List.of(serverDir.resolve("server.js").toAbsolutePath().toString()), env));
```

4. **`augmentServersFromAllowedTools`:** add the `mcp__workbench__` prefix, which adds `@axiom-workbench`,
   and exclude it in the external loop.
5. **Five-argument `createSession`:**
   - Move the body of the existing `createSession` into it.
   - Pass `compositionId` to `resolveMcpServers`.
   - After the session directory exists and `compositionId != null`, write the marker
     (`Files.writeString(sessionDir.resolve("composition"), String.valueOf(compositionId))`).
   - Directly after `session.setMcpToken(...)`, call `session.seedHistory(seedHistory)`.
   - Create the journal, call `journal.accept` for each seeded event, then add it as a listener.
   - The three-argument form delegates.
6. **Destroy and cleanup:**
   - `destroySession(String, boolean keepDirectory)` skips `deleteSessionDirectory` when `keepDirectory`
     is true. It still closes the journal and records usage.
   - `onShutdown` calls `destroySession(id, session.hasCapability(SessionCapability.COMPOSITION))`.
   - `onStartup` skips any directory with a `composition` marker, logging at INFO instead of WARN.
7. Add the package-private `HistoryJournal journal(String sessionId)` accessor if Task 1 did not, and the
   static `sessionDirectory(String)` helper.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='WorkbenchSessionManagerTest,AssistantMcpServerAugmentationTest,SessionTokenTest,SessionCapability*Test,StaleSessionDirectoryTest,Assistant*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. The workbench server's `npm install` runs once during this test, so it needs network access
or the npm cache, the same as the assistant server.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant app/src/main/resources/templates \
  app/src/test/java/io/apitomy/axiom/app/assistant
git commit -m "feat(assistant): composition-aware sessions with the workbench MCP server"
```

---
### Task 3: Turn columns, SSE events and AI-tagged saves

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/CompositionEntity.java`,
  `app/src/main/resources/db/migration/V1__baseline.sql`
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`
- Modify: `common/api/src/main/resources/openapi.json`. Add these properties:
  - On `Composition`: `currentTurnId` (string), `pendingTurnId` (string) and `pendingChangeSet`
    (`$ref CompositionContent`, the combined change set JSON).
  - On `CompositionItemUpdate`: `turnId` (string), described as "Set when saving the editor's application
    of an AI change set; the revision is recorded with author ai".
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` (`toBean`,
  `updateCompositionItem`)
- Test: extend `app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java`

**Interfaces:**
- Produces:
  - `CompositionEntity.lastTurnStartedOn` (`Instant`, column `last_turn_started_on timestamp(6)`, nullable)
  - `SseEvent.compositionTurn(long compositionId, String turnId, String state)` (type `composition-turn`)
  - `SseEvent.compositionChangeSet(long compositionId, String turnId, String changeSetJson)` (type
    `composition-changeset`; `changeSet` is embedded as raw JSON)
  - A PUT that carries a `turnId` saves with `author = ai` and that turn id. Without one, the author is
    `user`, as before.

- [ ] **Step 1: Write the failing test**

Append to `CompositionsResourceTest`:

```java
    @Test
    void saveWithTurnIdIsRecordedAsAi() {
        long id = createComposition();
        JsonPath tool = addItem(id, "tool", "rest-ai-tool");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "applied from AI change set");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", tool.getString("contentRevision"), "turnId", "turn-9"))
                .put(BASE + "/{id}/items/{itemId}", id, tool.getLong("id")).then().statusCode(200)
                .body("lastAuthor", equalTo("ai")).body("lastTurnId", equalTo("turn-9"));
        given().get(BASE + "/{id}", id).then().statusCode(200)
                .body("pendingChangeSet", nullValue()).body("currentTurnId", nullValue());
    }
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. `lastAuthor` is `user`.

- [ ] **Step 3: Implement**

- **Entity:** add `@Column(name = "last_turn_started_on") public Instant lastTurnStartedOn;` with Javadoc
  "When the most recent AI turn started; user edits after it are summarized for the next turn." Add
  `last_turn_started_on timestamp(6),` to the `composition` table in the baseline, next to the other
  timestamps.
- **SSE factories:** add both next to `compositionItemChanged`, built the same way. `escapeJson` the string
  fields, and embed `changeSetJson` raw.
- **OpenAPI:** add the properties above, then regenerate with `mvn -q -pl app -am compile`.
- **`CompositionsResourceImpl.toBean(CompositionEntity)`:** set `currentTurnId` and `pendingTurnId`. If
  `pendingChangeSet != null`, set `pendingChangeSet` by converting the parsed JSON to `CompositionContent`,
  as `toBean(item, true)` does for content.
- **`updateCompositionItem`:**
  `String turnId = data.getTurnId(); String author = turnId == null ? CompositionService.AUTHOR_USER : CompositionService.AUTHOR_AI;`
  and pass both to `service.save`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='CompositionsResource*Test,MigrationSchemaValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core common/api/src/main/resources/openapi.json app/src/main/resources/db/migration/V1__baseline.sql \
  app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java
git commit -m "feat(workbench): turn bookkeeping columns, turn SSE events and AI-tagged saves"
```

---

### Task 4: `WorkbenchSessions` (bind, restore, send, notify)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkbenchSessionEvent.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkbenchSessions.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionService.java` (`delete` discards
  the session)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/WorkbenchSessionsTest.java`

**Interfaces:**
- Consumes: `AssistantSessionManager.createSession(name, templateId, subjectId, compositionId, seed)`,
  `getSession`, `destroySession(id, keepDirectory)`, `sessionDirectory(id)` and `HistoryJournal.load`
  (Tasks 1 and 2).
- Produces:
  - `record WorkbenchSessionEvent(long compositionId, Kind kind, String detail)`, with
    `enum Kind { TURN_COMPLETE, FAILED }`. It is fired as a CDI event when the composition's session emits
    `turn_complete` (`TURN_COMPLETE`), `session_ended`, or a `session_error` whose `name` is not
    `ProviderRetry` (`FAILED`, with `detail` = the message).
  - `WorkbenchSessions` (`@ApplicationScoped`):
    - `AssistantSession live(long compositionId)`: the running session, or null.
    - `AssistantSession ensure(long compositionId)`: returns the live session, or starts one from
      `axiom-workbench` and stores its id in `CompositionEntity.assistantSessionId` (in its own
      transaction). If the composition previously had a session whose directory still exists, that
      directory's `history.jsonl` is loaded and seeded, the old directory is deleted, and a recap is armed.
    - `String takeRecap(long compositionId)`: the armed recap text, or null. Taking it disarms it.
    - `void send(long compositionId, String text) throws IOException`: `ensure(...)`, then
      `session.sendMessage(text)`.
    - `void notice(long compositionId, String text)`: adds an `assistant_text` event with `notice: true` to
      the live session, if any. It does not reach the agent.
    - `void interrupt(long compositionId)`
    - `void discard(long compositionId)`: destroys the live session and deletes any kept directory.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.HistoryJournal;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Binding compositions to workbench sessions, restoring history and translating session events.
 */
@QuarkusTest
class WorkbenchSessionsTest {

    /** Collects events fired during the test. */
    @Singleton
    static class Recorder {
        final List<WorkbenchSessionEvent> events = new CopyOnWriteArrayList<>();

        void on(@Observes WorkbenchSessionEvent event) {
            events.add(event);
        }
    }

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    WorkbenchSessions sessions;

    @Inject
    CompositionService compositions;

    @Inject
    AssistantSessionManager manager;

    @Inject
    Recorder recorder;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>listAll()).forEach(c -> sessions.discard(c.id));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
        recorder.events.clear();
    }

    private void mockDriver() {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
    }

    @Test
    void ensureBindsOneSessionPerComposition() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", "goal", null, null, null);
        AssistantSession first = sessions.ensure(composition.id);
        assertSame(first, sessions.ensure(composition.id));
        assertEquals(first.getId(), compositions.get(composition.id).assistantSessionId);
        assertNull(sessions.takeRecap(composition.id));
    }

    @Test
    void turnCompleteAndErrorsAreTranslated() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", null, null, null, null);
        AssistantSession session = sessions.ensure(composition.id);
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("turn_complete", mapper.createObjectNode()));
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("session_error",
                mapper.createObjectNode().put("name", "ProviderRetry").put("message", "retrying")));
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("session_error",
                mapper.createObjectNode().put("name", "Boom").put("message", "broken")));
        assertEquals(List.of(WorkbenchSessionEvent.Kind.TURN_COMPLETE, WorkbenchSessionEvent.Kind.FAILED),
                recorder.events.stream().map(WorkbenchSessionEvent::kind).toList());
        assertEquals("broken", recorder.events.get(1).detail());
    }

    @Test
    void restartRestoresHistoryAndArmsRecap() throws IOException {
        mockDriver();
        CompositionEntity composition = compositions.create("c", "triage issues", null, null, null);
        AssistantSession first = sessions.ensure(composition.id);
        first.sendMessage("build me a triage workflow");
        manager.journal(first.getId()).flush();
        // Simulate a server restart: the process goes away but the directory is kept.
        manager.destroySession(first.getId(), true);
        assertTrue(Files.isDirectory(AssistantSessionManager.sessionDirectory(first.getId())));

        AssistantSession second = sessions.ensure(composition.id);
        assertNotEquals(first.getId(), second.getId());
        assertTrue(second.getEventHistory().stream()
                .anyMatch(e -> "build me a triage workflow".equals(e.data().path("content").asText())));
        assertFalse(Files.exists(AssistantSessionManager.sessionDirectory(first.getId())));
        String recap = sessions.takeRecap(composition.id);
        assertNotNull(recap);
        assertTrue(recap.contains("triage issues"), recap);
        assertTrue(recap.contains("build me a triage workflow"), recap);
        assertNull(sessions.takeRecap(composition.id));
    }

    @Test
    void noticeIsVisibleButNotSentToAgent() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", null, null, null, null);
        AssistantSession session = sessions.ensure(composition.id);
        sessions.notice(composition.id, "The workflow changed while I worked.");
        assertTrue(session.getEventHistory().stream().anyMatch(e -> e.data().path("notice").asBoolean()));
    }
}
```

`AssistantSessionManager.journal(...)` and `sessionDirectory(...)` must be visible from the `composition`
package. If Task 2 made them package-private, make them `public` and add Javadoc.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=WorkbenchSessionsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `WorkbenchSessions` does not exist.

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app.composition;

/**
 * Something happened in a composition's agent session that ends the current turn.
 *
 * @param compositionId the composition
 * @param kind what happened
 * @param detail the error message for {@link Kind#FAILED}, otherwise null
 */
public record WorkbenchSessionEvent(long compositionId, Kind kind, String detail) {

    /** Event kinds. */
    public enum Kind {
        /** The agent finished its turn normally. */
        TURN_COMPLETE,
        /** The agent's turn ended because of an error, or the session ended. */
        FAILED
    }
}
```

`WorkbenchSessions` implementation requirements:
- **Injection:** `AssistantSessionManager manager`, `CompositionService compositions`,
  `Event<WorkbenchSessionEvent> events` and `ObjectMapper mapper`. Keep
  `ConcurrentHashMap<Long, String> recaps`.
- **`ensure` is `synchronized`**, so a composition never gets two sessions. In order:
  1. Read the composition with `compositions.get(id)`.
  2. If `assistantSessionId` points at a live session (`manager.getSession(...) != null`), return it.
  3. If a previous id exists and its directory exists, load `history.jsonl` from it into `seed`.
  4. Call `manager.createSession(composition.name, "axiom-workbench", null, id, seed)`.
  5. Attach the listener described below.
  6. Store the new session id with `QuarkusTransaction.requiringNew().run(...)`, setting
     `assistantSessionId` and `updatedOn`.
  7. Delete the old directory with `AssistantContextBuilder.deleteSessionDirectory`.
  8. If `seed` was not empty, put the recap.
  9. Wrap `IOException` in `IllegalStateException("Could not start the workbench agent: " + message)`.
- **Recap text:**

  ```
  This conversation is being resumed after Axiom restarted. Your previous process ended, so here is a recap.
  Goal: <goal or "(none given)">
  Recent conversation (oldest first):
  - user: <content>
  - assistant: <text>
  ...
  Call get_composition before making changes.
  ```

  Use the last 20 `user_message` (`data.content`) and `assistant_text` (`data.text`) events, skipping
  notices. Truncate each entry to 500 characters with "…".
- **Listener**, attached once per session:
  - `turn_complete` fires `TURN_COMPLETE`.
  - `session_ended` fires `FAILED` with `data.message`, or "The agent session ended".
  - `session_error` whose `name` is not `ProviderRetry` fires `FAILED` with `data.message`.
  - Fire with `events.fire(...)` inside a try/catch that logs at WARN. Never throw from the listener.
- **`notice`:** `session.addEvent(new SseEvent("assistant_text", mapper.createObjectNode().put("text", text).put("notice", true)))`.
- **`discard(id)`:** if the composition still exists and has an `assistantSessionId`, call
  `manager.destroySession(sessionId, false)`, then delete any directory left at `sessionDirectory(sessionId)`.
  This method must work when the composition row is gone, so look it up with `CompositionEntity.findById`
  and treat null as "nothing to do".
- **`CompositionService.delete(id)`:** call `workbenchSessions.discard(id)` before deleting rows. Injecting
  `WorkbenchSessions` into `CompositionService` creates a cycle (`WorkbenchSessions` injects
  `CompositionService`). Break it by injecting `jakarta.enterprise.inject.Instance<WorkbenchSessions>` and
  calling `.get()` lazily.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='WorkbenchSessionsTest,CompositionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/composition app/src/main/java/io/apitomy/axiom/app/assistant \
  app/src/test/java/io/apitomy/axiom/app/composition/WorkbenchSessionsTest.java
git commit -m "feat(workbench): bind compositions to agent sessions with history restore"
```

---

### Task 5: Turn preamble (`WorkflowDiffs`, `TurnPreamble`)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkflowDiffs.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/TurnPreamble.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/WorkflowDiffsTest.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/TurnPreambleTest.java`

**Interfaces:**
- Produces:
  - `WorkflowDiffs.summarize(JsonNode before, JsonNode after): String`. This is pure. It returns lines of
    the form `added nodes: a, b`, `removed nodes: c`, `changed nodes: d` (any node JSON other than
    `position` differs), and `edges changed` (if the edge arrays differ as sets). It returns an empty string
    when nothing differs. Node `position` is ignored.
  - `TurnPreamble` (`@ApplicationScoped`): `String build(long compositionId, List<String> selectedNodeIds, List<String> selectedEdgeIds)`.
    It returns the text that is put before the user's message. In order:
    1. `[Workbench context]`
    2. `Workflow content revision: <rev>`, or `No workflow yet.`
    3. The user-edit summary since `lastTurnStartedOn`. For every item with a `user` revision created after
       that instant: `- <type> '<name>': edited by the user`, and for the workflow, the indented
       `WorkflowDiffs.summarize(<content at the latest revision created at or before lastTurnStartedOn>, <current content>)`.
       If `lastTurnStartedOn` is null, the summary is skipped. If there is nothing to report, it prints
       `User edits since your last turn: none.`
    4. `The user is looking at: nodes [..] edges [..]` when the selection is not empty.
    5. `[End of workbench context]`

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the node-level workflow summary used in turn preambles.
 */
class WorkflowDiffsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void reportsAddedRemovedChangedAndEdges() throws Exception {
        JsonNode before = json("""
                {"nodes":[{"id":"a","name":"A"},{"id":"b","name":"B"},{"id":"c","name":"C","position":{"x":1,"y":1}}],
                 "edges":[{"id":"e1","source":"a","target":"b"}]}""");
        JsonNode after = json("""
                {"nodes":[{"id":"a","name":"A2"},{"id":"c","name":"C","position":{"x":9,"y":9}},{"id":"d","name":"D"}],
                 "edges":[{"id":"e2","source":"a","target":"d"}]}""");
        String summary = WorkflowDiffs.summarize(before, after);
        assertTrue(summary.contains("added nodes: d"), summary);
        assertTrue(summary.contains("removed nodes: b"), summary);
        assertTrue(summary.contains("changed nodes: a"), summary);
        assertTrue(summary.contains("edges changed"), summary);
    }

    @Test
    void layoutOnlyIsNoChange() throws Exception {
        JsonNode before = json("{\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":1,\"y\":1}}],\"edges\":[]}");
        JsonNode after = json("{\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":5,\"y\":1}}],\"edges\":[]}");
        assertEquals("", WorkflowDiffs.summarize(before, after));
    }
}
```

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preamble tells the agent the workflow revision, what the user changed since its last turn, and what the
 * user is looking at.
 */
@QuarkusTest
class TurnPreambleTest {

    @Inject
    CompositionService service;

    @Inject
    TurnPreamble preamble;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void summarizesUserEditsSinceLastTurn() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "tp-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "tp-tool");
        Thread.sleep(5);
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionEntity c = CompositionEntity.findById(composition.id);
            c.lastTurnStartedOn = Instant.now();
        });
        Thread.sleep(5);
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ((ObjectNode) content.path("nodes").get(0)).put("name", "Begin");
        service.save(composition.id, workflow.id, content, workflow.contentRevision, CompositionService.AUTHOR_USER, null);

        String text = preamble.build(composition.id, List.of("start-1"), List.of());
        assertTrue(text.startsWith("[Workbench context]"), text);
        assertTrue(text.contains("Workflow content revision: sha256:"), text);
        assertTrue(text.contains("workflow 'tp-wf': edited by the user"), text);
        assertTrue(text.contains("changed nodes: start-1"), text);
        assertTrue(!text.contains("tp-tool"), text);
        assertTrue(text.contains("The user is looking at: nodes [start-1]"), text);
    }

    @Test
    void firstTurnHasNoEditSummary() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        String text = preamble.build(composition.id, List.of(), List.of());
        assertTrue(text.contains("No workflow yet."), text);
        assertTrue(!text.contains("User edits since your last turn"), text);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q -pl app -am test -Dtest='WorkflowDiffsTest,TurnPreambleTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure.

- [ ] **Step 3: Implement**

- **`WorkflowDiffs`** (final class, private constructor):
  - Index nodes by `id` into a `LinkedHashMap<String, JsonNode>`, with `position` removed from a deep copy.
  - `added` = ids only in `after`, `removed` = ids only in `before`, `changed` = ids in both whose
    stripped JSON differs.
  - Edges differ if `new HashSet<>(beforeEdges)` does not equal `new HashSet<>(afterEdges)`, comparing each
    edge's `toString()`.
  - Join the non-empty parts with `\n` in the order added, removed, changed, `edges changed`.
- **`TurnPreamble`:**
  - Inject `CompositionService service`.
  - Find the workflow item with `service.items(id)` (type `workflow`).
  - For the edit summary, query `CompositionRevisionEntity.list("itemId = ?1 and author = 'user' and createdOn > ?2", item.id, since)`
    for each item.
  - For the workflow base, use
    `CompositionRevisionEntity.find("itemId = ?1 and createdOn <= ?2 order by createdOn desc, id desc", item.id, since).firstResult()`.
    If none exists, skip the diff lines.
  - Lines are joined with `\n`. Javadoc must explain that this context is sent to the agent ahead of the
    user's message on every turn.

- [ ] **Step 4: Run the tests and confirm they pass**

Run the same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/composition/WorkflowDiffs.java \
  app/src/main/java/io/apitomy/axiom/app/composition/TurnPreamble.java \
  app/src/test/java/io/apitomy/axiom/app/composition/WorkflowDiffsTest.java \
  app/src/test/java/io/apitomy/axiom/app/composition/TurnPreambleTest.java
git commit -m "feat(workbench): build the per-turn context preamble"
```

---
### Task 6: `CompositionTurns` (turn lifecycle, change sets, agent writes)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionTurns.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsTest.java`

**Interfaces:**
- Consumes:
  - `WorkbenchSessions.send/notice/interrupt/takeRecap` and `WorkbenchSessionEvent` (Task 4)
  - `TurnPreamble.build` (Task 5)
  - `CompositionService` and `CompositionValidator` (2a)
  - Flow's `ChangeSets.applyChecked` and `ContentRevision.of`
  - `SseEvent.compositionTurn` / `compositionChangeSet` (Task 3)
- Produces `CompositionTurns` (`@ApplicationScoped`, with `synchronized` public methods):
  - `String start(long compositionId, String message, List<String> nodeIds, List<String> edgeIds)`.
    Returns the new `turnId` (a UUID). Throws `CompositionConflictException` if a turn is already running,
    and `IllegalArgumentException` for a blank message.
  - `record ChangeSetOutcome(boolean applied, String contentRevision, List<ConfigProblem> problems, ChangeSetError error)`
  - `ChangeSetOutcome applyChangeSet(long compositionId, JsonNode changeSet)`
  - `record AgentWrite(CompositionItemEntity item, List<ConfigProblem> problems)`
  - `AgentWrite upsert(long compositionId, String type, JsonNode content)`
  - `void remove(long compositionId, String type, String name)`
  - `JsonNode currentWorkflow(long compositionId)`: the turn's working workflow while a turn runs,
    otherwise the saved workflow content. Null if there is no workflow.
  - `String currentTurnId(long compositionId)`: the running turn id, or null.
  - `void resolve(long compositionId, String turnId, String outcome)`, where `outcome` is one of
    `applied`, `accepted`, `rejected`, `stale`.
  - `void interrupt(long compositionId)`
  - `void onSessionEvent(@Observes WorkbenchSessionEvent event)`, which finishes the turn.
  - `void onStartup(@Observes StartupEvent event)`, which resets `ai-working` compositions to `idle`.
  - Constants: `AUTHOR = "agent:axiom-workbench"`, plus `STALE_RETRY_PROMPT`, `FAILED_NOTICE_PREFIX` and
    `SECOND_STALE_NOTICE` (texts below).

**Behaviour:**
- **`start`:**
  1. Lock the composition row with `CompositionEntity.findById(id, PESSIMISTIC_WRITE)` in a transaction.
  2. Build the preamble with the **previous** `lastTurnStartedOn`.
  3. Set `turnState = ai-working`, `currentTurnId`, `lastTurnStartedOn = now` and `updatedOn`.
  4. Record an in-memory `Turn(turnId, workflowItemId or null, baseRevision, baseContent, working, ops = [], staleRetry flag)`,
     where `baseContent` and `working` are the saved workflow content.
  5. After commit, fire `compositionTurn(..., "ai-working")`.
  6. Send `(recap == null ? "" : recap + "\n\n") + preamble + "\n\n" + message`.
  7. If sending throws, finish the turn as failed (back to `idle`, with a notice) and throw
     `IllegalStateException`.
- **`applyChangeSet`:**
  - Require a running turn (`CompositionConflictException("No AI turn is running")`) and a workflow item
    (`IllegalArgumentException("The composition has no workflow yet; create one with upsert_item type workflow")`).
  - If `changeSet.baseRevision` is not the working revision, return `applied = false` with error
    `stale`: "Base revision is not the current working revision <rev>; call get_composition".
  - Otherwise call `ChangeSets.applyChecked(working, changeSet)`. A `Rejected` result returns
    `applied = false` with the error.
  - On `Applied`, set `working` to the result, append every element of `changeSet.ops` to the turn's ops,
    and return `applied = true` with the new revision and
    `validator.validateContent(id, WORKFLOW, item.name, working)`.
  - Nothing is persisted.
- **`upsert`:**
  - Require a running turn.
  - `type` is `action-type` or `tool`, or `workflow` only when the composition has no workflow yet.
  - The name is `CompositionItems.nameOf(content)`, which is required. For a workflow, it also becomes the
    item name.
  - If the item exists, call `service.save(id, itemId, content, null, AUTHOR_AI, turnId)`. Otherwise call
    `addBlank` then `save` with the same arguments.
  - Return the problems from `validator.validateContent`.
  - A workflow created this way also becomes the turn's `baseContent` and `working` (so later change sets
    apply to it), with `baseRevision` = its revision.
- **`remove`:** require a running turn. Only `action-type` and `tool` can be removed (workflows cannot).
  Unknown items give `CompositionNotFoundException`.
- **Finishing** (from `onSessionEvent`, for this composition only):
  - Remove the in-memory turn.
  - In a transaction with a locked row: set `turnState = idle` and `currentTurnId = null`.
  - On `TURN_COMPLETE` with non-empty ops, build the combined change set
    `{"id": turnId, "baseRevision": baseRevision, "author": AUTHOR, "summary": "AI turn: <n> workflow change(s)", "ops": [...]}`,
    then set `pendingChangeSet` (as a string) and `pendingTurnId`. A newer pending change set replaces an
    older one.
  - After commit, fire `compositionChangeSet` (if one was created), then `compositionTurn(..., "idle")`.
  - On `FAILED`, discard the ops and post the notice
    `FAILED_NOTICE_PREFIX + detail + " Workflow changes from this turn were discarded; action type and tool changes were kept (use Undo AI turn to revert them)."`
  - An event for a composition with no running turn only makes sure the state is `idle`.
- **`resolve`:**
  - If `pendingTurnId` does not equal `turnId`, do nothing.
  - Otherwise clear `pendingChangeSet` and `pendingTurnId`.
  - If the outcome is `stale`:
    - If the stale turn was itself a stale retry, or a turn is now running, post `SECOND_STALE_NOTICE`.
    - Otherwise call `start(id, STALE_RETRY_PROMPT, [], [])` and mark the new turn as a stale retry.
- **`interrupt`:** `sessions.interrupt(id)`. The turn ends when the session reports it.
- **Startup:** for every composition in `ai-working`, set `idle` and `currentTurnId = null`. The in-memory
  turns were lost with the restart, so their workflow ops are dropped.
- **Texts:**
  - `STALE_RETRY_PROMPT`: "Your workflow changes from the last turn could not be applied because the user
    changed the workflow while you were working. Call get_composition to see the current workflow, then
    resubmit the workflow changes that still make sense."
  - `SECOND_STALE_NOTICE`: "The workflow changed again before my changes could be applied, so I stopped.
    Ask me to try again when you are ready."
  - `FAILED_NOTICE_PREFIX`: "The AI turn ended with an error: "

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.flow.changeset.ContentRevision;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The AI turn lifecycle: starting, accumulating change sets, agent writes, finishing and stale retries.
 */
@QuarkusTest
class CompositionTurnsTest {

    @InjectMock
    WorkbenchSessions sessions;

    @Inject
    CompositionTurns turns;

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>listAll())
                .forEach(c -> turns.onSessionEvent(new WorkbenchSessionEvent(c.id, WorkbenchSessionEvent.Kind.FAILED, "cleanup")));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private CompositionEntity withWorkflow() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "ct-wf");
        return composition;
    }

    private JsonNode renameStart(String baseRevision, String name) throws Exception {
        return mapper.readTree("""
                {"id":"cs-%s","baseRevision":"%s","author":"agent:axiom-workbench","summary":"rename",
                 "ops":[{"op":"updateNode","id":"start-1","patch":{"name":"%s"}}]}""".formatted(name, baseRevision, name));
    }

    private CompositionEntity reload(long id) {
        return QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>findById(id));
    }

    @Test
    void startSendsPreambleAndMarksWorking() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = turns.start(composition.id, "Add a triage step", List.of("start-1"), List.of());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sessions).send(eq(composition.id), text.capture());
        assertTrue(text.getValue().contains("[Workbench context]"), text.getValue());
        assertTrue(text.getValue().endsWith("Add a triage step"), text.getValue());
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_AI_WORKING, saved.turnState);
        assertEquals(turnId, saved.currentTurnId);
        assertNotNull(saved.lastTurnStartedOn);
        assertThrows(CompositionConflictException.class, () -> turns.start(composition.id, "again", List.of(), List.of()));
    }

    @Test
    void changeSetsAccumulateAgainstTheWorkingRevision() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());

        CompositionTurns.ChangeSetOutcome first = turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        assertTrue(first.applied());
        CompositionTurns.ChangeSetOutcome stale = turns.applyChangeSet(composition.id, renameStart(base, "Again"));
        assertFalse(stale.applied());
        assertEquals("stale", stale.error().code());
        CompositionTurns.ChangeSetOutcome second = turns.applyChangeSet(composition.id, renameStart(first.contentRevision(), "Go"));
        assertTrue(second.applied());
        assertEquals("Go", turns.currentWorkflow(composition.id).path("nodes").get(0).path("name").asText());

        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_IDLE, saved.turnState);
        assertEquals(turnId, saved.pendingTurnId);
        JsonNode pending = mapper.readTree(saved.pendingChangeSet);
        assertEquals(base, pending.path("baseRevision").asText());
        assertEquals(turnId, pending.path("id").asText());
        assertEquals("agent:axiom-workbench", pending.path("author").asText());
        assertEquals(2, pending.path("ops").size());
        // Only the editor commits workflow change sets: the saved draft is unchanged by the turn.
        assertEquals("Start", service.contentOf(service.items(composition.id).get(0))
                .path("nodes").get(0).path("name").asText());
    }

    @Test
    void agentUpsertIsTaggedWithTheTurn() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = turns.start(composition.id, "make a tool", List.of(), List.of());
        ObjectNode tool = CompositionItems.blank(ConfigItemType.TOOL, "ct-tool", mapper);
        CompositionTurns.AgentWrite write = turns.upsert(composition.id, "tool", tool);
        assertEquals("ai", write.item().lastAuthor);
        assertEquals(turnId, write.item().lastTurnId);
        tool.put("description", "v2");
        assertEquals(turnId, turns.upsert(composition.id, "tool", tool).item().lastTurnId);
        assertEquals(1, service.items(composition.id).stream().filter(i -> "tool".equals(i.itemType)).count());
        assertThrows(IllegalArgumentException.class,
                () -> turns.upsert(composition.id, "workflow", CompositionItems.blank(ConfigItemType.WORKFLOW, "x", mapper)));
    }

    @Test
    void agentWritesRequireARunningTurn() {
        CompositionEntity composition = withWorkflow();
        assertThrows(CompositionConflictException.class, () -> turns.upsert(composition.id, "tool",
                CompositionItems.blank(ConfigItemType.TOOL, "t", mapper)));
    }

    @Test
    void failedTurnDiscardsWorkflowOpsAndNotifies() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.FAILED, "boom"));
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_IDLE, saved.turnState);
        assertNull(saved.pendingChangeSet);
        verify(sessions).notice(eq(composition.id), org.mockito.ArgumentMatchers.startsWith(CompositionTurns.FAILED_NOTICE_PREFIX + "boom"));
    }

    @Test
    void staleResolutionRetriesOnceThenStops() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));

        turns.resolve(composition.id, turnId, "stale");
        assertNull(reload(composition.id).pendingChangeSet);
        verify(sessions).send(eq(composition.id), org.mockito.ArgumentMatchers.endsWith(CompositionTurns.STALE_RETRY_PROMPT));
        String retryTurn = reload(composition.id).currentTurnId;
        String retryBase = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.applyChangeSet(composition.id, renameStart(retryBase, "Again"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));

        turns.resolve(composition.id, retryTurn, "stale");
        verify(sessions).notice(composition.id, CompositionTurns.SECOND_STALE_NOTICE);
        verify(sessions, times(2)).send(anyLong(), anyString());
    }

    @Test
    void acceptedResolutionClearsPendingAndIgnoresOtherTurns() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, "other-turn", "applied");
        assertNotNull(reload(composition.id).pendingChangeSet);
        turns.resolve(composition.id, turnId, "applied");
        assertNull(reload(composition.id).pendingChangeSet);
        verify(sessions, never()).notice(eq(composition.id), anyString());
        verify(sessions, atLeastOnce()).send(anyLong(), anyString());
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionTurnsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure.

- [ ] **Step 3: Implement `CompositionTurns`**, following the Behaviour section above.

Implementation notes:
- Keep `ConcurrentHashMap<Long, Turn> turns` and a `Set<String> staleRetryTurnIds`. `Turn` is a private
  static final class with mutable `working` and `ops` (an `ArrayNode`).
- Use `QuarkusTransaction.requiringNew().run/call` for each database step. That way the SSE events can be
  fired right after `call` returns (the transaction is committed by then), and the agent is never called
  while a transaction is open.
- The Flow result is a sealed type: `switch` over `ChangeSetResult` with `instanceof ChangeSetResult.Applied applied`
  and `ChangeSetResult.Rejected rejected`.
- `ChangeSetError` for the stale case is `new ChangeSetError("stale", null, "...")`.
- Inject `Event<io.apitomy.axiom.core.events.SseEvent> sseEvents`.
- Javadoc each public method, stating which fields it locks and which events it fires.

- [ ] **Step 4: Run the test and confirm it passes**

Run the same command. Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/composition/CompositionTurns.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsTest.java
git commit -m "feat(workbench): AI turn lifecycle with accumulated change sets"
```

---

### Task 7: Undo an AI turn (`TurnUndo`)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/TurnUndo.java`
- Modify: `CompositionService.java` (the `addBlank` overload) and `CompositionTurns.java` (`upsert` uses it)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/TurnUndoTest.java`

**Interfaces:**
- Produces:
  - `record TurnUndo.Result(List<String> restored, List<String> removed, List<String> skipped, JsonNode workflowBefore, boolean workflowRestorable)`.
    The list entries are `"<type>/<name>"`.
  - `TurnUndo.undo(long compositionId, String turnId): Result`. It throws `CompositionConflictException`
    while a turn is running, and `CompositionNotFoundException` if no revision carries `turnId`.

**Behaviour, for each composition item with at least one revision whose `turnId` equals `turnId`:**
- Let `first` be the earliest of those revisions, and `before` the latest revision ordered strictly before
  `first` by `(createdOn, id)`.
- If the item's latest revision is not one of the turn's revisions (the user or a later turn changed it),
  add the item to `skipped`.
- **Workflow items:** never change them on the server. Set `workflowBefore` to the `before` content (null
  if there is none) and `workflowRestorable` to `true` unless the item was skipped. The client applies the
  undo with `handle.replace(workflowBefore, 'host')`, as the spec describes.
- **Other items:** if `before` exists, call `service.save(id, itemId, before.content, null, AUTHOR_USER, null)`
  and add the item to `restored`. Otherwise (the turn created the item) call `service.removeItem` and add it
  to `removed`.
- All of this runs in one transaction.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undoing an AI turn restores, removes or skips the items it touched.
 */
@QuarkusTest
class TurnUndoTest {

    @Inject
    CompositionService service;

    @Inject
    TurnUndo undo;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private CompositionItemEntity aiSave(long compositionId, CompositionItemEntity item, String description, String turnId) {
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("description", description);
        return service.save(compositionId, item.id, content, null, CompositionService.AUTHOR_AI, turnId);
    }

    @Test
    void restoresChangedRemovesCreatedSkipsLaterEdited() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "tu-wf");
        CompositionItemEntity changed = service.addBlank(composition.id, ConfigItemType.TOOL, "tu-changed");
        CompositionItemEntity edited = service.addBlank(composition.id, ConfigItemType.TOOL, "tu-edited");
        String originalChanged = changed.contentRevision;

        aiSave(composition.id, changed, "by ai", "t1");
        CompositionItemEntity editedAi = aiSave(composition.id, edited, "by ai", "t1");
        service.addBlank(composition.id, ConfigItemType.TOOL, "tu-created", CompositionService.AUTHOR_AI, "t1");
        aiSave(composition.id, workflow, "wf by ai", "t1");
        aiSave(composition.id, editedAi, "by user later", null);

        TurnUndo.Result result = undo.undo(composition.id, "t1");
        assertEquals(List.of("tool/tu-changed"), result.restored());
        assertEquals(List.of("tool/tu-edited"), result.skipped());
        assertEquals(List.of("tool/tu-created"), result.removed());
        assertTrue(result.workflowRestorable());
        assertEquals("", result.workflowBefore().path("description").asText(""));
        CompositionItemEntity restored = service.items(composition.id).stream()
                .filter(i -> "tu-changed".equals(i.name)).findFirst().orElseThrow();
        assertEquals(originalChanged, restored.contentRevision);
    }

    @Test
    void unknownTurnIsNotFound() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        assertThrows(CompositionNotFoundException.class, () -> undo.undo(composition.id, "nope"));
    }
}
```

This test uses a new overload, `CompositionService.addBlank(long compositionId, ConfigItemType type, String name, String author, String turnId)`,
which records the creation revision with the given author and turn. The existing three-argument form
delegates to it with `AUTHOR_USER, null`. `CompositionTurns.upsert` must use the overload when it creates
an item, so that "created in the turn" means the item has no revision from before the turn.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=TurnUndoTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure.

- [ ] **Step 3: Implement** `TurnUndo` (`@ApplicationScoped`, `@Transactional undo`), the `addBlank`
  overload, and the switch of `CompositionTurns.upsert` to it. Check `CompositionEntity.turnState` and throw
  `CompositionConflictException("Wait for the AI turn to finish before undoing")` while it is `ai-working`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='TurnUndoTest,CompositionTurnsTest,CompositionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/composition app/src/test/java/io/apitomy/axiom/app/composition/TurnUndoTest.java
git commit -m "feat(workbench): undo an AI turn"
```

---

### Task 8: REST for turns and the agent

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Modify: `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`, §3. Rename the change-set route
  to `/compositions/{id}/agent/workflow/changesets`, list the agent routes, add the
  `changeset-resolution` route, and use the expression scope names from the Global Constraints.
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsResourceTest.java`

**Interfaces:**
- **UI routes** (no token):

  | Operation | Request | Response |
  |---|---|---|
  | `POST /compositions/{compositionId}/messages` (`sendCompositionMessage`) | `CompositionMessage {message (required), nodeIds?: string[], edgeIds?: string[]}` | 200 `Composition`; 409 if a turn is running (`CompositionConflictError`) |
  | `POST /compositions/{compositionId}/interrupt` (`interruptComposition`) | none | 204 |
  | `POST /compositions/{compositionId}/changeset-resolution` (`resolveCompositionChangeSet`) | `CompositionChangeSetResolution {turnId, outcome: enum [applied, accepted, rejected, stale]}` | 204 |
  | `POST /compositions/{compositionId}/turns/{turnId}/undo` (`undoCompositionTurn`) | none | 200 `CompositionUndoResult {restored, removed, skipped: string[], workflowBefore?: CompositionContent, workflowRestorable: boolean}`; 409; 404 |

- **Agent routes** (header `X-Axiom-Session-Token` required, 401 otherwise; documented in each operation's
  description and with a `401` response):

  | Operation | Request | Response |
  |---|---|---|
  | `GET /compositions/{compositionId}/agent/composition` (`getAgentComposition`) | none | `AgentComposition {compositionId (int64), turnId?, goal?, workflowName?, workflowRevision?, workflow?: CompositionContent, items: AgentItemSummary[]}`. `AgentItemSummary {type, name, contentRevision, valid, problems: ConfigProblem[]}` |
  | `GET /compositions/{compositionId}/agent/items/{itemType}/{itemName}` (`getAgentItem`) | none | `CompositionContent`; 404 |
  | `PUT /compositions/{compositionId}/agent/items` (`upsertAgentItem`) | `AgentItemUpsert {type: enum [workflow, action-type, tool], content: CompositionContent}` | 200 `AgentItemResult {type, name, contentRevision, valid, problems}`; 400; 409 |
  | `DELETE /compositions/{compositionId}/agent/items/{itemType}/{itemName}` (`removeAgentItem`) | none | 204; 404; 409 |
  | `POST /compositions/{compositionId}/agent/workflow/changesets` (`proposeAgentWorkflowChanges`) | `CompositionContent` (a Flow change set) | 200 `AgentChangeSetResult {applied, contentRevision?, problems: ConfigProblem[], error?: {code, opIndex?, reason}}`; 400 (no workflow); 409 (no turn) |

  `itemType` and `itemName` are string path parameters.
- `getAgentComposition` returns `turns.currentWorkflow(id)` as the workflow and its `ContentRevision` as
  `workflowRevision`, together with `validator.validateAll` per item.
- All agent routes call
  `tokenGuard.require(workbenchSessions.live(id), httpHeaders.getHeaderString(SessionTokenGuard.HEADER))`
  first. Inject `@Context HttpHeaders` as in `AssistantResourceImpl`.

- [ ] **Step 1: Add the contract** to `openapi.json`, as in the tables above:
  - All new schemas: `CompositionMessage`, `CompositionChangeSetResolution`, `CompositionUndoResult`,
    `AgentComposition`, `AgentItemSummary`, `AgentItemUpsert`, `AgentItemResult`, `AgentChangeSetResult`
    and `ChangeSetError`.
  - Full path parameters, including the string `itemType`, `itemName` and `turnId`.
  - Every operation tagged `Compositions`.
  - Responses referencing `CompositionConflictError`, `StaleRevisionError` and `NotFound` where relevant.

  Regenerate with `mvn -q -pl app -am compile` and read the new interface methods.

- [ ] **Step 2: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST tests for sending messages, agent routes (token-guarded), change sets, resolution and undo.
 */
@QuarkusTest
class CompositionTurnsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @InjectMock
    WorkbenchSessions sessions;

    @Inject
    CompositionService service;

    @Inject
    CompositionTurns turns;

    private long compositionId;

    @BeforeEach
    void setUp() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "ctr-wf");
        compositionId = composition.id;
        AssistantSession session = mock(AssistantSession.class);
        when(session.verifyMcpToken("good")).thenReturn(true);
        when(sessions.live(anyLong())).thenReturn(session);
    }

    @AfterEach
    void cleanUp() {
        turns.onSessionEvent(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.FAILED, "cleanup"));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void agentRoutesRequireToken() {
        given().get(BASE + "/{id}/agent/composition", compositionId).then().statusCode(401);
        given().header("X-Axiom-Session-Token", "bad").get(BASE + "/{id}/agent/composition", compositionId)
                .then().statusCode(401);
        given().header("X-Axiom-Session-Token", "good").get(BASE + "/{id}/agent/composition", compositionId)
                .then().statusCode(200).body("workflowRevision", notNullValue()).body("items", hasSize(1));
    }

    @Test
    void fullTurnThroughRest() {
        given().contentType(ContentType.JSON).body(Map.of("message", "rename start", "nodeIds", List.of("start-1")))
                .post(BASE + "/{id}/messages", compositionId).then().statusCode(200)
                .body("turnState", equalTo("ai-working"));
        given().contentType(ContentType.JSON).body(Map.of("message", "again"))
                .post(BASE + "/{id}/messages", compositionId).then().statusCode(409);

        String revision = given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/composition", compositionId).then().extract().path("workflowRevision");
        Map<String, Object> changeSet = Map.of("id", "cs1", "baseRevision", revision,
                "author", "agent:axiom-workbench", "summary", "rename",
                "ops", List.of(Map.of("op", "updateNode", "id", "start-1", "patch", Map.of("name", "Begin"))));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(changeSet)
                .post(BASE + "/{id}/agent/workflow/changesets", compositionId).then().statusCode(200)
                .body("applied", equalTo(true));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(changeSet)
                .post(BASE + "/{id}/agent/workflow/changesets", compositionId).then().statusCode(200)
                .body("applied", equalTo(false)).body("error.code", equalTo("stale"));

        Map<String, Object> tool = Map.of("type", "tool", "content",
                Map.of("name", "ctr-tool", "description", "d", "parameters", List.of(), "scriptTemplate", "echo hi"));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(tool)
                .put(BASE + "/{id}/agent/items", compositionId).then().statusCode(200).body("name", equalTo("ctr-tool"));
        String turnId = given().get(BASE + "/{id}", compositionId).then().extract().path("currentTurnId");

        turns.onSessionEvent(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        given().get(BASE + "/{id}", compositionId).then().statusCode(200)
                .body("turnState", equalTo("idle")).body("pendingTurnId", equalTo(turnId))
                .body("pendingChangeSet.ops", hasSize(1));

        given().contentType(ContentType.JSON).body(Map.of("turnId", turnId, "outcome", "applied"))
                .post(BASE + "/{id}/changeset-resolution", compositionId).then().statusCode(204);
        given().post(BASE + "/{id}/turns/{turnId}/undo", compositionId, turnId).then().statusCode(200)
                .body("removed", equalTo(List.of("tool/ctr-tool")));
    }
}
```

`sessions.send` is a mock, so no agent runs. Because `WorkbenchSessions` is mocked, `send` does nothing.

- [ ] **Step 3: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionTurnsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation fails, because the interface methods are not implemented.

- [ ] **Step 4: Implement the operations** in `CompositionsResourceImpl`:
  - Delegate to `CompositionTurns`, `TurnUndo`, `CompositionValidator`, `CompositionService`,
    `WorkbenchSessions` and `SessionTokenGuard`.
  - Convert `CompositionContent` to and from `JsonNode` with the existing helpers.
  - Map `ChangeSetError` to the bean.
  - Use the existing problem-bean helper.
  - Add a `@ServerExceptionMapper` for `IllegalStateException`, returning 503 `{message}`. It covers "Could
    not start the workbench agent".
  - Update spec §3 as listed under **Files**.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='CompositionTurnsResourceTest,CompositionsResource*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionTurnsResourceTest.java \
  docs/superpowers/specs/2026-10-08-workflow-workbench-design.md
git commit -m "feat(workbench): REST for AI turns and token-guarded agent routes"
```

---
### Task 9: Expression evaluation (`ExpressionService`, `POST /expressions/evaluate`)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/expressions/ExpressionService.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/ExpressionsResourceImpl.java`
- Modify: `common/api/src/main/resources/openapi.json`
- Test: `app/src/test/java/io/apitomy/axiom/app/expressions/ExpressionServiceTest.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/expressions/ExpressionsResourceTest.java`

**Interfaces:**
- Consumes: `io.apitomy.flow.engine.ConditionEvaluator` (public no-argument constructor, with
  `evaluate(expr, context[, event])` and `resolve(expr, context[, event])`; failures throw
  `ConditionEvaluationException`), `WorkflowEventMapper.toEventMap(StreamEventEntity, ObjectMapper)` and
  `StreamEventEntity` (id `UUID`).
- Produces:
  - `ExpressionService.Scope`, an enum whose JSON names are those in the Global Constraints table, with
    `boolean usesContext()`, `boolean usesEvent()`, `boolean isBoolean()` and `static Scope fromName(String)`.
    `fromName` throws `IllegalArgumentException` listing the valid names.
  - `record ExpressionService.Result(boolean ok, JsonNode value, String error)`
  - `ExpressionService.evaluate(String expression, String scope, String eventId, JsonNode context): Result`.
    It returns `ok = false` with a message for an EL error, a missing required `eventId`, an unknown or
    invalid `eventId`, or a non-object `context`. It throws `IllegalArgumentException` only for a blank
    expression or an unknown scope.
  - Context scopes that are given an `eventId` also see `context.event` = the event map, unless the given
    context already has `event`. This mirrors runs started by a subscription and receive-event outputs.
  - REST: `POST /expressions/evaluate` (operationId `evaluateExpression`, tag `Expressions`).
    - Request `ExpressionEvaluateRequest {expression (required), scope (required, enum of the seven names), eventId?, context?: CompositionContent}`.
    - Response 200 `ExpressionEvaluateResult {ok, valueJson? (the value as a JSON string), valueType? (enum null|boolean|number|string|object|array), error?}`.
    - 400 for a blank expression or an unknown scope.

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EL evaluation per scope, against literal contexts and stored stream events.
 */
@QuarkusTest
class ExpressionServiceTest {

    @Inject
    ExpressionService expressions;

    @Inject
    ObjectMapper mapper;

    private UUID eventId;

    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.deleteById(eventId));
        }
    }

    private String storeEvent() {
        eventId = QuarkusTransaction.requiringNew().call(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.type = "issue.comment.created";
            event.source = "github";
            event.eventSourceId = "github-com";
            event.ref = "https://github.com/o/r/issues/1";
            event.timestamp = Instant.now();
            event.payload = "{\"comment\":{\"body\":\"Here are the logs\"}}";
            event.persist();
            return event.id;
        });
        return eventId.toString();
    }

    @Test
    void edgeConditionIsBooleanOverContext() throws Exception {
        ExpressionService.Result result = expressions.evaluate("context.needsInfo == true", "edge-condition",
                null, mapper.readTree("{\"needsInfo\":true}"));
        assertTrue(result.ok(), result.error());
        assertTrue(result.value().asBoolean());
    }

    @Test
    void humanTaskTitleResolvesAValue() throws Exception {
        ExpressionService.Result result = expressions.evaluate("'No reply on ' += context.ref", "human-task-title",
                null, mapper.readTree("{\"ref\":\"r-1\"}"));
        assertEquals("No reply on r-1", result.value().asText());
    }

    @Test
    void eventKeySeesOnlyTheEvent() {
        String id = storeEvent();
        ExpressionService.Result result = expressions.evaluate("event.ref", "correlation-event-key", id, null);
        assertEquals("https://github.com/o/r/issues/1", result.value().asText());
        ExpressionService.Result payload = expressions.evaluate("event.payload.comment.body", "receive-event-output", id, null);
        assertEquals("Here are the logs", payload.value().asText());
    }

    @Test
    void contextScopesSeeTheEventAsContextEvent() {
        String id = storeEvent();
        ExpressionService.Result result = expressions.evaluate("context.event.type", "action-input", id, null);
        assertEquals("issue.comment.created", result.value().asText());
    }

    @Test
    void reportsErrorsAsResults() {
        assertFalse(expressions.evaluate("event.ref", "receive-event-match", null, null).ok());
        assertFalse(expressions.evaluate("event.ref", "correlation-event-key", "not-a-uuid", null).ok());
        assertFalse(expressions.evaluate("context.(", "action-input", null, null).ok());
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate(" ", "action-input", null, null));
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate("1", "nope", null, null));
    }
}
```

If `StreamEventEntity` has other non-null columns, set them in `storeEvent` (check the entity's
`nullable = false` columns).

```java
package io.apitomy.axiom.app.expressions;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * REST test for POST /expressions/evaluate.
 */
@QuarkusTest
class ExpressionsResourceTest {

    @Test
    void evaluatesAndReportsErrors() {
        given().contentType(ContentType.JSON)
                .body(Map.of("expression", "context.n + 1", "scope", "action-input", "context", Map.of("n", 41)))
                .post("/api/v1/expressions/evaluate").then().statusCode(200)
                .body("ok", equalTo(true)).body("valueJson", equalTo("42")).body("valueType", equalTo("number"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expression", "event.ref", "scope", "receive-event-match"))
                .post("/api/v1/expressions/evaluate").then().statusCode(200).body("ok", equalTo(false));
        given().contentType(ContentType.JSON).body(Map.of("expression", "1", "scope", "bogus"))
                .post("/api/v1/expressions/evaluate").then().statusCode(400);
    }
}
```

`"bogus"` is not in the request enum, so deserialization may reject it with 400 before the service runs.
Either way the test expects 400.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q -pl app -am test -Dtest='ExpressionServiceTest,ExpressionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure.

- [ ] **Step 3: Implement**

- **`ExpressionService`** (`@ApplicationScoped`, with an injected `ObjectMapper`):
  - Keep one `ConditionEvaluator` instance.
  - Convert `context` to `Map<String, Object>` with `mapper.convertValue(context, Map.class)`.
  - Load the event in a read (`StreamEventEntity.findById(UUID)`). Catch `IllegalArgumentException` for an
    invalid UUID.
  - For boolean scopes, call `evaluate(...)` and wrap the result as a `BooleanNode`. For value scopes, call
    `resolve(...)` and convert with `mapper.valueToTree(result)`; null becomes `NullNode`.
  - Use the three-argument overloads when `usesEvent()`. For `correlation-event-key`, pass `Map.of()` as
    the context.
  - Catch `ConditionEvaluationException` and any `RuntimeException` from EL, and return
    `ok = false, error = message`.
  - Add Javadoc on the class listing each scope and its variables (copy the Global Constraints table).
- **OpenAPI:** add the `/expressions/evaluate` path, the two schemas, and a top-level `Expressions` tag.
  Regenerate.
- **`ExpressionsResourceImpl`** (`@ApplicationScoped @RunOnVirtualThread`, implements the generated
  interface):
  - Map the request to the service.
  - Map `Result` to the bean: `valueJson = mapper.writeValueAsString(value)`, and `valueType` from the node
    type.
  - Add a `@ServerExceptionMapper` that turns `IllegalArgumentException` into 400 `{message}`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run the same command. Expected: PASS. If `+=` string concatenation is not supported by the EL version in
use, look at how `WorkflowEngine.taskTitle` evaluates titles. Use the same evaluator and adjust only the
test expression to one the engine accepts in titles; note it in the report.

- [ ] **Step 5: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src/main/java/io/apitomy/axiom/app/expressions \
  app/src/main/java/io/apitomy/axiom/app/rest/ExpressionsResourceImpl.java app/src/test/java/io/apitomy/axiom/app/expressions
git commit -m "feat(workbench): evaluate EL expressions per workflow scope"
```

---

### Task 10: The `axiom-workbench` MCP server

**Files:**
- Modify: `app/src/main/resources/templates/axiom-workbench-mcp/server.js` (complete it)
- Create: `app/src/test/node/workbench-mcp.test.js`
- Create: `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchMcpServerScriptTest.java`

**Interfaces:**
- Consumes: the agent routes (Task 8), `GET /stream/events` and `GET /stream/events/{id}` (existing),
  and `POST /expressions/evaluate` (Task 9).
- Produces the MCP tools below (server name `workbench`, so the agent sees `mcp__workbench__<name>`). Every
  handler returns a string. Errors are thrown as `Error(message)`, and the bootstrap turns them into
  `isError` results, as in `axiom-assistant-mcp/server.js`.

  | Tool | Parameters | Calls / behaviour |
  |---|---|---|
  | `get_composition` | — | `GET /compositions/{C}/agent/composition`, pretty JSON |
  | `get_item` | `type`, `name` | `GET .../agent/items/{type}/{name}` |
  | `upsert_item` | `type`, `item` (object) | `PUT .../agent/items {type, content: item}`. Returns `VALID`, `VALID with warnings` or `INVALID` plus `- [SEVERITY] path code: message` lines, like `axiom_validate_item` |
  | `remove_item` | `type`, `name` | `DELETE .../agent/items/{type}/{name}` |
  | `propose_workflow_changes` | `changeSet` (object) | `POST .../agent/workflow/changesets`. Applied: `APPLIED. New revision: <rev>` plus the problem lines. Not applied: `REJECTED (<code> at op <opIndex>): <reason>` |
  | `validate_composition` | — | `GET .../agent/composition`, then one status line per item with its problem lines |
  | `list_stream_events` | `type?`, `eventSourceId?`, `ref?`, `limit?` (default 10, maximum 20) | `GET /stream/events?filterType=&filterEventSourceId=&filterRef=&limit=`. One `summarizeEvent` line per event |
  | `get_stream_event` | `eventId`, `path?` | `GET /stream/events/{id}`. Build the normalized event `{id, type, source, eventSourceId, ref, timestamp, actor, payload}`, narrow it with `pickPath`, and pretty-print it through `truncateDeep` |
  | `evaluate_expression` | `expression`, `scope`, `eventId?`, `context?` (object) | `POST /expressions/evaluate`. Returns `OK (<valueType>): <valueJson>` or `ERROR: <error>` |
  | Read-only discovery | — | Copy `axiom_list_action_types`, `axiom_get_action_type`, `axiom_list_tools`, `axiom_get_tool` and `axiom_list_secrets` from the assistant server unchanged, keeping their names |

- **Parameter types:** the bootstrap in `axiom-assistant-mcp/server.js` maps parameters to
  `{type: p.type || "string"}`. Support `type: "object"` and `"number"` the same way, so `changeSet`,
  `item` and `context` are objects.
- **Pure helpers**, exported with `module.exports = { summarizeEvent, pickPath, truncateDeep, formatProblems }`
  when the file is `require`d, without starting the server (`if (require.main === module) { main(); }`):
  - `summarizeEvent(e)` returns
    `"<id> <type> <eventSourceId> <ref> actor=<actor.login||actor.name||-> at <timestamp> keys=[<top-level payload keys>]"`.
  - `pickPath(obj, path)` supports dot segments and `[n]` indexes, for example `payload.issue.labels[0].name`.
    It returns `undefined` when the path is missing.
  - `truncateDeep(value, maxString = 2000, maxArray = 50)` copies the value. Strings longer than the limit
    become their first `maxString` characters plus `"…[truncated N chars]"`. Arrays longer than the limit
    keep `maxArray` items plus a final string `"…[N more items]"`.
  - `formatProblems(problems)` returns `- [SEVERITY] path code: message` lines.
- If `AXIOM_COMPOSITION_ID` or `AXIOM_SESSION_TOKEN` is empty, `main` logs a one-line warning to stderr.

- [ ] **Step 1: Write the failing tests**

`app/src/test/node/workbench-mcp.test.js`:

```js
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const helpers = require(path.resolve(__dirname, "../../main/resources/templates/axiom-workbench-mcp/server.js"));

test("summarizeEvent lists id, type, ref, actor and payload keys", () => {
    const line = helpers.summarizeEvent({ id: "e1", type: "issue.comment.created", eventSourceId: "gh",
        ref: "r", timestamp: "t", actor: { login: "octo" }, payload: { comment: {}, issue: {} } });
    assert.match(line, /^e1 issue\.comment\.created gh r actor=octo at t keys=\[comment, issue\]$/);
});

test("pickPath handles dots and indexes", () => {
    const obj = { payload: { issue: { labels: [{ name: "bug" }] } } };
    assert.strictEqual(helpers.pickPath(obj, "payload.issue.labels[0].name"), "bug");
    assert.strictEqual(helpers.pickPath(obj, "payload.nope.x"), undefined);
});

test("truncateDeep limits long strings and arrays", () => {
    const out = helpers.truncateDeep({ s: "x".repeat(10), a: [1, 2, 3, 4] }, 4, 2);
    assert.strictEqual(out.s, "xxxx…[truncated 6 chars]");
    assert.deepStrictEqual(out.a, [1, 2, "…[2 more items]"]);
});

test("formatProblems renders one line per problem", () => {
    assert.strictEqual(helpers.formatProblems([{ severity: "error", path: "content", code: "c", message: "m" }]),
        "- [ERROR] content c: m");
});
```

`WorkbenchMcpServerScriptTest.java`: run the Node tests, skipping when `node` is not installed.

```java
package io.apitomy.axiom.app.assistant;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the workbench MCP server's Node unit tests ({@code node --test}).
 */
class WorkbenchMcpServerScriptTest {

    @Test
    void nodeHelperTestsPass() throws IOException, InterruptedException {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        Path test = Path.of("src/test/node/workbench-mcp.test.js").toAbsolutePath();
        Process process = new ProcessBuilder("node", "--test", test.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(60, TimeUnit.SECONDS);
        assertEquals(0, process.exitValue(), output);
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
```

The server file `require`s `@modelcontextprotocol/sdk` at the top, and that package is not installed under
`src/main/resources`. Move those `require` calls inside `main()` (or into a lazy `loadSdk()`), so that
requiring the file for its helpers works without `node_modules`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q -pl app -am test -Dtest=WorkbenchMcpServerScriptTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. The helpers are not exported.

- [ ] **Step 3: Implement `server.js`** as specified. Base the bootstrap on `axiom-assistant-mcp/server.js`:
  `axiomApi` with the token header, the `TOOLS` array, the `ListTools` / `CallTool` handlers and the stdio
  transport. Check the syntax with `node --check`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run the same command. Expected: PASS (or SKIPPED if `node` is missing; report which).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/resources/templates/axiom-workbench-mcp app/src/test/node \
  app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchMcpServerScriptTest.java
git commit -m "feat(workbench): axiom-workbench MCP server"
```

---

### Task 11: Workbench template and reference

**Files:**
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantReferenceGenerator.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` (`buildSystemPrompt`)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java`

**Interfaces:**
- Produces:
  - `AssistantReferenceGenerator.generateChangeSetReference(): String`, cached. It contains:
    - the change set shape;
    - the op table (copy the op names and fields from Flow's `ai-assisted-editing.md` "Change sets" table:
      `addNode`, `updateNode`, `renameNode`, `removeNode`, `addEdge`, `updateEdge`, `removeEdge`,
      `metadata`);
    - the patch semantics (shallow config merge, nested values replaced whole, `unset`, `null` is a value);
    - the error codes;
    - one complete example change set. It adds a `wait` node between Start and End of the blank workflow
      (`start-1`, `end-1`, `edge-1`) by removing `edge-1` and adding the node and two edges.
  - `buildSystemPrompt` replaces `{{changesetReference}}` with it.
- **The template** (complete file):
  - `templateId` `axiom-workbench` and `capabilities` `["composition"]`.
  - `mcpServers` `["@axiom-workbench"]`.
  - `allowedTools`: every `mcp__workbench__<tool>` from Task 10, plus `Bash(jq *)`. No Read, Write or Edit.
  - The `systemPrompt`, covering the following.

  **Role:** you build workflows together with the user in a shared workbench. The user can edit the same
  drafts between your turns, so always work from `get_composition`.

  **Working loop:**
  1. `get_composition`.
  2. Plan the change.
  3. Create or update action types and tools with `upsert_item` and fix every `INVALID`.
  4. Change the workflow only with `propose_workflow_changes`, using the latest `workflowRevision` (or the
     revision returned by your previous change set) as `baseRevision`. Fix any rejection or problem.
  5. Finish with a short summary of what you changed.

  **Rules (each asserted by the test):**
  - "Edit workflows only with propose_workflow_changes"
  - "Use the workflowRevision from get_composition, or the revision returned by your last change set, as baseRevision"
  - "Before writing an EL expression that reads event data, inspect a real event of that type with get_stream_event and check the expression with evaluate_expression"
  - "If no events of that type exist, say so and use the documented event schema"
  - "Prefer node action types for non-AI steps"
  - "Declare an input for everything an action uses"
  - "Receive-event nodes must correlate"
  - "Never describe the output format in an agent prompt"
  - "Your workflow changes are applied by the user's editor when your turn ends"

  **Reference:** `{{reference}}` then `{{changesetReference}}`.

  **Welcome message:** "Tell me what this workflow should do, or select something on the canvas and ask me
  to change it."

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbench template carries its rules, and the change-set reference example is valid for Flow.
 */
class WorkbenchReferenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode template() throws Exception {
        try (InputStream is = WorkbenchReferenceTest.class.getResourceAsStream(
                "/templates/assistant-templates/axiom-workbench.json")) {
            return MAPPER.readTree(is);
        }
    }

    @Test
    void promptCarriesEveryRule() throws Exception {
        String prompt = template().path("systemPrompt").asText();
        for (String rule : List.of(
                "Edit workflows only with propose_workflow_changes",
                "as baseRevision",
                "inspect a real event of that type with get_stream_event and check the expression with evaluate_expression",
                "If no events of that type exist, say so and use the documented event schema",
                "Prefer node action types for non-AI steps",
                "Declare an input for everything an action uses",
                "Receive-event nodes must correlate",
                "Never describe the output format in an agent prompt",
                "applied by the user's editor when your turn ends",
                "{{reference}}",
                "{{changesetReference}}")) {
            assertTrue(prompt.contains(rule), "prompt must contain: " + rule);
        }
    }

    @Test
    void templateAllowsOnlyWorkbenchToolsAndJq() throws Exception {
        for (JsonNode tool : template().path("allowedTools")) {
            String name = tool.asText();
            assertTrue(name.startsWith("mcp__workbench__") || name.equals("Bash(jq *)"), name);
        }
        assertFalse(template().path("allowedTools").toString().contains("Write"));
    }

    @Test
    void changeSetExampleAppliesToTheBlankWorkflow() throws Exception {
        String reference = new AssistantReferenceGenerator().generateChangeSetReference();
        Matcher json = Pattern.compile("```json\\n(\\{.*?\\})\\n```", Pattern.DOTALL).matcher(reference);
        assertTrue(json.find(), "reference must contain a json example");
        JsonNode workflow = WorkflowTemplates.emptyWorkflow("wf", MAPPER);
        com.fasterxml.jackson.databind.node.ObjectNode changeSet = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(json.group(1));
        changeSet.put("baseRevision", ContentRevision.of(workflow));
        assertInstanceOf(ChangeSetResult.Applied.class, ChangeSets.applyChecked(workflow, changeSet));
        for (String code : List.of("target-missing", "duplicate-id", "edge-endpoint-missing", "malformed", "stale")) {
            assertTrue(reference.contains(code), code);
        }
    }
}
```

If `AssistantReferenceGenerator` cannot be constructed with `new` (for example, because it needs injected
beans), make `generateChangeSetReference` delegate to a `static String changeSetReference()` and call that
static method in the test instead.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=WorkbenchReferenceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. The rules and the reference method are missing.

- [ ] **Step 3: Implement** the template, the reference method and the placeholder replacement.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='WorkbenchReferenceTest,AssistantReferenceTest,WorkbenchSessionManagerTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/resources/templates/assistant-templates/axiom-workbench.json \
  app/src/main/java/io/apitomy/axiom/app/assistant app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java
git commit -m "feat(workbench): workbench template with change-set reference"
```

---

### Task 12: Full verification

**Files:** none. This task only verifies.

- [ ] **Step 1:** Run `mvn -q package`. Expected: exit 0. Run
  `grep -l "<failure\|<error" */target/surefire-reports/*.xml */*/target/surefire-reports/*.xml` and expect
  no output. Report the total test count from all Surefire XML files.
- [ ] **Step 2:** Run `node --check app/src/main/resources/templates/axiom-workbench-mcp/server.js`.
  Expected: exit 0.
- [ ] **Step 3:** Run
  `git grep -n "@Path" app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java app/src/main/java/io/apitomy/axiom/app/rest/ExpressionsResourceImpl.java`.
  Expected: no output.
- [ ] **Step 4:** Report the commands and their results. State clearly that no real agent (Claude Code or
  OpenCode) was run end to end. All agent interaction was mocked. A live end-to-end check is part of
  Phase 4's manual verification.
