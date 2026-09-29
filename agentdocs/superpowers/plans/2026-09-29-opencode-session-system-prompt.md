# OpenCode Session System Prompt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Send the session template's system prompt, including injected project context, to OpenCode on every
prompt, so assistant templates behave the same on OpenCode as on Claude Code (GitHub issue #370, epic #387).

**Architecture:** `AssistantSessionManager.buildSystemPrompt` already builds the final prompt (template prompt
plus the `{{projectContext}}`/`{{projectName}}` substitutions). Today only Claude receives it, via
`--append-system-prompt`. We carry it in `DriverRequest`, pass it to `OpenCodeInteractiveSessionDriver`, and send
it as the `system` field of every `POST /session/{id}/prompt_async` call. The driver's positional constructor
list is already long, so the per-session prompt settings are grouped into a new `SessionSettings` record. The
existing constructors keep working and delegate to it.

**Tech Stack:** Java 21, Quarkus, Jackson, JUnit 5, JDK `HttpServer` fakes, opencode 1.18.x.

**Spec:** GitHub issue #370 ("OpenCode sessions: send the template system prompt and project context").

## Background (verified facts)

Verified against opencode 1.18.33 on 2026-09-29:
- The OpenAPI spec (`GET /doc`) for `POST /session/{sessionID}/prompt_async` has a body property `system` of
  type `string`.
- `POST /session/{id}/message` with `{"noReply":true,"system":"AXIOM-SYSTEM-MARKER","parts":[...]}` returns a
  user message whose `info.system` is `"AXIOM-SYSTEM-MARKER"`. `GET /session/{id}/message` shows it stored **on
  that user message**. The system prompt is therefore per message, and **must be sent on every prompt**, not
  once per session.
- In opencode's prompt assembly, `system` is added alongside the agent/provider base prompt and project
  instructions. It is not a replacement, which matches Claude's `--append-system-prompt`. This comes from
  reading the opencode source, not an automated test. The manual check in Task 3 Step 7 confirms it end to end.

**Design ruling:** use the per-prompt `system` field. The alternative, a custom agent in `opencode.json`
(`agent.<name>.prompt` plus `agent` on each prompt), was rejected. A custom agent `prompt` replaces opencode's
provider base prompt, which carries tool-use guidance. `system` appends, like Claude.

Code facts:
- `OpenCodeAssistantClient.sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools)` builds
  the body (`parts`, optional `model`, optional `tools`). It is called from the driver
  (`OpenCodeInteractiveSessionDriver.java:232`), `OpenCodeCapabilityProbe.java:83`, and two tests.
- `OpenCodeInteractiveSessionDriver` constructors:
  - public 8-arg `(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink, sessionTitle, model,
    tools)`
  - public 9-arg (the same plus `Set<String> expectedMcpServers`)
  - package-private 9-arg (with `EventStreamConnector eventStreamConnector` after `autoApprovalSink`, no set)
  - package-private 10-arg canonical constructor, with the connector and the set
  - Fields `sessionTitle`, `model`, `tools`, `expectedMcpServers`. `InteractiveSessionDriverFactoryTest` reads
    `expectedMcpServers` via reflection.
- `InteractiveSessionDriverFactory.DriverRequest` has 14 components ending in
  `Map<String, AssistantContextBuilder.McpServerConfig> mcpServers`, with a compact constructor normalizing null
  to `Map.of()`. It is constructed in `AssistantSessionManager.java:~246-260` (last arg `mcpConfigs`) and in
  6 places in `InteractiveSessionDriverFactoryTest`.
- `AssistantSessionManager` computes `String systemPrompt = buildSystemPrompt(template, project);` at
  `~line 217`, before the `DriverRequest` is built.

## Global Constraints

- 4-space indentation; Javadoc on all public types and methods; explicit types (no `var`); JUnit 5.
- Do not change Claude Code behavior or the REST API / OpenAPI spec.
- Out of scope: allowed tools/permissions (#371), event normalizer (#372), capability probe changes (#376).
  The probe keeps calling the 4-arg `sendPromptAsync` with no system prompt.
- The `system` field is omitted from the request body when the system prompt is null or blank.
- The system prompt is sent on **every** `prompt_async` call made by the driver.
- Commit message style: conventional commits, e.g. `fix(assistant): ...`. No AI attribution.
- Run tests with: `mvn -q -pl app test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false` from the
  repo root (run `mvn -q install -DskipTests` once first if sibling modules aren't installed). Any test that talks
  HTTP to a real opencode server must use an `HttpClient` forced to HTTP/1.1. Java's default HTTP/2 upgrade hangs
  against opencode.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java` | Modify | `system` field on prompts |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientPermissionContractTest.java` | Modify | Body contract tests for `system` |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java` | Modify | `SessionSettings` record; send system prompt with each prompt |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java` | Modify | Driver sends `system` |
| `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java` | Modify | `DriverRequest.systemPrompt`; wire into driver |
| `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` | Modify | Pass `systemPrompt` in `DriverRequest` |
| `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java` | Modify | New arg; wiring test |
| `docs/user-guide/ai-assistant.md` | Modify | Note that the system prompt applies to OpenCode |

---

### Task 1: `system` field in the OpenCode client

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientPermissionContractTest.java`

**Interfaces:**
- Produces: `public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools, String
  system)`. The existing 4-arg overload delegates with `system = null`.

- [ ] **Step 1: Write the failing tests** (add to `OpenCodeAssistantClientPermissionContractTest`)

```java
    @Test
    void sendPromptAsyncIncludesSystemPromptWhenProvided() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.sendPromptAsync(SESSION_ID, "hello", null, null, "You are the Axiom Configuration Assistant.");

            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(recordingServer.rawBody());
            assertEquals("You are the Axiom Configuration Assistant.", body.path("system").asText());
            assertEquals("hello", body.path("parts").get(0).path("text").asText());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void sendPromptAsyncOmitsBlankSystemPrompt() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.sendPromptAsync(SESSION_ID, "hello", null, null, "   ");

            assertEquals("{\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}]}", recordingServer.body());
        } finally {
            recordingServer.close();
        }
    }
```

The existing recorder strips all whitespace from `body` (`replaceAll("\\s+", "")`), which would mangle a system
prompt containing spaces. Add a `rawBody` alongside it. In `RecordingPermissionServer` add
`private volatile String rawBody;` and an accessor `String rawBody() { return rawBody; }`. In `PromptHandler.handle`
replace the body line with:

```java
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recordingServer.rawBody = requestBody;
            recordingServer.body = requestBody.replaceAll("\\s+", "");
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeAssistantClientPermissionContractTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (no 5-arg `sendPromptAsync`).

- [ ] **Step 3: Implement**

Replace the existing `sendPromptAsync` with:

```java
    /**
     * Sends an async prompt to an existing OpenCode session without a system prompt.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param tools optional tools object
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools) {
        sendPromptAsync(sessionId, prompt, model, tools, null);
    }

    /**
     * Sends an async prompt to an existing OpenCode session.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param tools optional tools object
     * @param system system prompt added to OpenCode's own system prompt for this message; omitted when null or
     *               blank. OpenCode stores it per message, so callers must pass it on every prompt.
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools, String system) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode parts = body.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        if (model != null && model.contains("/")) {
            String[] split = model.split("/", 2);
            ObjectNode modelNode = body.putObject("model");
            modelNode.put("providerID", split[0]);
            modelNode.put("modelID", split[1]);
        }
        if (tools != null && !tools.isNull()) {
            body.set("tools", normalizePromptTools(tools));
        }
        if (system != null && !system.isBlank()) {
            body.put("system", system);
        }

        postJson("/session/" + sessionId + "/prompt_async", body, 204);
    }
```

(The body-building lines above are the existing ones, unchanged except for the added `system` block.)

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCodeAssistantClient*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. The existing `sendPromptAsyncConvertsAllowedToolsArrayToBooleanMap` still passes unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClient.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeAssistantClientPermissionContractTest.java
git commit -m "feat(assistant): support system prompt on OpenCode prompts (#370)"
```

---

### Task 2: Driver sends the system prompt on every prompt

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java`

**Interfaces:**
- Consumes: 5-arg `OpenCodeAssistantClient.sendPromptAsync(..., String system)` (Task 1).
- Produces:
  - Nested `public record SessionSettings(String sessionTitle, String model, JsonNode tools, Set<String>
    expectedMcpServers, String systemPrompt)`. Its compact constructor normalizes `expectedMcpServers` (null →
    `Set.of()`, otherwise `Set.copyOf`).
  - New public constructor `OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess, CapabilityProbe
    capabilityProbe, OpenCodeEventNormalizer normalizer, Consumer<SseEvent> eventSink, Consumer<SseEvent>
    autoApprovalSink, SessionSettings settings)`.
  - New package-private canonical constructor `(serverProcess, capabilityProbe, normalizer, eventSink,
    autoApprovalSink, EventStreamConnector eventStreamConnector, SessionSettings settings)`.
  - All four existing constructors keep their signatures and delegate by building a `SessionSettings` with
    `systemPrompt = null`.
  - Fields stay named `sessionTitle`, `model`, `tools`, `expectedMcpServers`, plus a new
    `private final String systemPrompt;`.

- [ ] **Step 1: Write the failing tests**

In `FakeOpenCodeServer` add `private final AtomicReference<String> lastPromptBody = new AtomicReference<>();` and
an accessor `String lastPromptBody() { return lastPromptBody.get(); }`. In the `prompt_async` handler, right after
the `String body = ...` line, add `fakeOpenCodeServer.lastPromptBody.set(body);`. (`AtomicReference` is already
imported.)

Add tests:

```java
    @Test
    void sendUserMessageIncludesSessionSystemPrompt() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
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

            driver.sendUserMessage("hello");

            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(server.lastPromptBody());
            assertEquals("You are the Axiom Configuration Assistant.", body.path("system").asText());
            assertEquals("hello", body.path("parts").get(0).path("text").asText());
            driver.destroy();
        }
    }

    @Test
    void sendUserMessageOmitsSystemWhenNoSystemPrompt() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null);
            driver.start();

            driver.sendUserMessage("hello");

            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(server.lastPromptBody());
            assertFalse(body.has("system"));
            driver.destroy();
        }
    }

    @Test
    void sessionSettingsNormalizesExpectedMcpServers() {
        OpenCodeInteractiveSessionDriver.SessionSettings settings =
                new OpenCodeInteractiveSessionDriver.SessionSettings("t", null, null, null, null);

        assertEquals(Set.of(), settings.expectedMcpServers());
    }
```

(`Set`, `assertFalse`, `assertEquals` are already imported in this test class. If `Set` isn't, add
`import java.util.Set;`.)

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeInteractiveSessionDriverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`SessionSettings` not found).

- [ ] **Step 3: Implement**

Add the field `private final String systemPrompt;`.

Add the record inside the class, next to the other nested types:

```java
    /**
     * Per-session settings applied to the OpenCode session and its prompts.
     *
     * @param sessionTitle title used when creating the OpenCode session
     * @param model model in provider/model format, or null for OpenCode's default
     * @param tools optional tools payload for prompt submissions
     * @param expectedMcpServers names of MCP servers configured for the session; a warning is emitted for each one
     *                           that OpenCode does not report as connected
     * @param systemPrompt system prompt sent with every prompt, or null/blank for none
     */
    public record SessionSettings(String sessionTitle,
                                  String model,
                                  JsonNode tools,
                                  Set<String> expectedMcpServers,
                                  String systemPrompt) {

        /**
         * Normalizes a null MCP server set to an empty set and copies non-null sets.
         */
        public SessionSettings {
            expectedMcpServers = expectedMcpServers != null ? Set.copyOf(expectedMcpServers) : Set.of();
        }
    }
```

Add the new public constructor:

```java
    /**
     * Creates an OpenCode interactive session driver.
     *
     * @param serverProcess OpenCode session server handle
     * @param capabilityProbe OpenCode capability probe
     * @param normalizer event normalizer
     * @param eventSink sink for non-permission events
     * @param autoApprovalSink sink for permission_request events
     * @param settings per-session settings
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            SessionSettings settings) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                OpenCodeAssistantClient::connectEvents, settings);
    }
```

Change the existing public 9-arg constructor body to:

```java
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                new SessionSettings(sessionTitle, model, tools, expectedMcpServers, null));
```

Change the existing package-private 10-arg constructor (with connector and set) so it is no longer canonical.
Its body becomes:

```java
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink, eventStreamConnector,
                new SessionSettings(sessionTitle, model, tools, expectedMcpServers, null));
```

Add the new canonical package-private constructor, holding the assignments moved from the old one:

```java
    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     SessionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.serverProcess = Objects.requireNonNull(serverProcess, "serverProcess");
        this.capabilityProbe = Objects.requireNonNull(capabilityProbe, "capabilityProbe");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.autoApprovalSink = Objects.requireNonNull(autoApprovalSink, "autoApprovalSink");
        this.eventStreamConnector = Objects.requireNonNull(eventStreamConnector, "eventStreamConnector");
        this.sessionTitle = settings.sessionTitle();
        this.model = settings.model();
        this.tools = settings.tools();
        this.expectedMcpServers = settings.expectedMcpServers();
        this.systemPrompt = settings.systemPrompt();
        this.status = new AtomicReference<>(AssistantSession.Status.STARTING);
    }
```

The public 8-arg and package-private 9-arg constructors already delegate with `Set.of()` and keep doing so.

In `sendUserMessage`, change the call to:

```java
            client.sendPromptAsync(openCodeSessionId, message, model, tools, systemPrompt);
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='OpenCode*Test,InteractiveSessionDriverFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS (existing constructors still compile and behave the same).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriver.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeInteractiveSessionDriverTest.java
git commit -m "feat(assistant): send session system prompt with every OpenCode prompt (#370)"
```

---

### Task 3: Carry the system prompt from the session manager to the driver

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java:~246-260`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java`
- Modify: `docs/user-guide/ai-assistant.md` (`!!! note` block)

**Interfaces:**
- Consumes: `OpenCodeInteractiveSessionDriver.SessionSettings` and the 6-arg public constructor (Task 2).
- Produces: `DriverRequest` gains a trailing component `String systemPrompt` (15 components total).

- [ ] **Step 1: Write the failing test**

Update all 6 existing `new InteractiveSessionDriverFactory.DriverRequest(...)` calls in
`InteractiveSessionDriverFactoryTest` to pass `null` as a new last argument, after the MCP servers map. Then add:

```java
    @Test
    void createDriverPassesSystemPromptToOpenCodeDriver(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "axiom-config-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(),
                        "You are the Axiom Configuration Assistant."));

        assertEquals("You are the Axiom Configuration Assistant.", getField(driver, "systemPrompt"));
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=InteractiveSessionDriverFactoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`DriverRequest` has 14 components, not 15).

- [ ] **Step 3: Implement**

In `InteractiveSessionDriverFactory.DriverRequest`, add a trailing component `String systemPrompt`, and a
Javadoc line `@param systemPrompt final system prompt for the session (template prompt plus project context);
applied by the OpenCode driver. The Claude driver receives it through {@code command}.`

In the OpenCode branch of `createDriver`, replace the `return new OpenCodeInteractiveSessionDriver(...)` call
with:

```java
                return new OpenCodeInteractiveSessionDriver(
                        new OpenCodeInteractiveSessionDriver.ServerProcessAdapter(openCodeSessionServerProcess),
                        capabilityProbe::probe,
                        normalizer,
                        request.eventSink(),
                        request.autoApprovalSink(),
                        new OpenCodeInteractiveSessionDriver.SessionSettings(
                                sessionTitle,
                                request.model(),
                                request.tools(),
                                request.mcpServers().keySet(),
                                request.systemPrompt()));
```

In `AssistantSessionManager`, add `systemPrompt` as the last `DriverRequest` argument:

```java
                            sessionName,
                            mcpConfigs,
                            systemPrompt));
```

Search the whole repo (`grep -rn "DriverRequest(" --include=*.java`) to make sure no other construction site
exists.

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -pl app test -Dtest='InteractiveSessionDriverFactoryTest,OpenCode*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all PASS.

- [ ] **Step 5: Update the user guide**

In `docs/user-guide/ai-assistant.md`, inside the `!!! note` block, append a paragraph (4-space indented, wrapped
at 110 columns):

```markdown
    The template's system prompt, including project context for project sessions, is applied to both engines.
    Claude Code receives it with `--append-system-prompt`; OpenCode receives it as the system prompt of every
    message, added to OpenCode's own system prompt.
```

- [ ] **Step 6: Full module test run**

Run: `mvn -q -pl app test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactory.java \
        app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java \
        app/src/test/java/io/apitomy/axiom/app/assistant/runtime/InteractiveSessionDriverFactoryTest.java \
        docs/user-guide/ai-assistant.md
git commit -m "fix(assistant): send template system prompt to OpenCode sessions (#370)"
```

- [ ] **Step 8: Manual end-to-end verification (human)**

1. `./dev.sh`, set the Configuration Assistant template's agent type to `opencode` with a valid
   `provider/model`, start a session.
2. Ask: "What is your role, and which Axiom item types can you help create?" The answer should reflect the
   Configuration Assistant's system prompt (tools, action types, report definitions, ...), not a generic coding
   assistant.
3. Send a second message and confirm the persona persists. The prompt is sent on every message.
4. Optional: `curl -s http://127.0.0.1:<opencode port>/session/<id>/message | jq '.[].info.system' | head`
   shows the system prompt on each user message.
5. For a Project Assistant session, ask "Which project are you scoped to?" The answer should name the project
   (from the injected project context).

---

## Self-Review Notes

- Issue #370 requirements → tasks:
  - Carry the system prompt in `DriverRequest` → Task 3.
  - Send it as `system` on every `prompt_async` → Tasks 1 and 2.
  - Acceptance (templates behave the same on both engines) → Task 3 Step 8.
  - The issue's alternative, a per-session agent, was rejected; see the Design ruling.
- Names are consistent across tasks: `sendPromptAsync(..., String system)`, `SessionSettings(sessionTitle,
  model, tools, expectedMcpServers, systemPrompt)`, driver field `systemPrompt` (read reflectively in Task 3),
  and `DriverRequest.systemPrompt`.
- The probe (`OpenCodeCapabilityProbe`) is intentionally unchanged (#376).
