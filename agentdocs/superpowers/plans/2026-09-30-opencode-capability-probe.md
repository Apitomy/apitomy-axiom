# OpenCode Capability Probe Without Side Effects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make the OpenCode compatibility check cheap and free of side effects. It must not call a model, must not
leave a stray "Axiom capability probe" session, and must not send a fake permission reply. Results for the same
opencode version are cached (GitHub issue #376, epic #387).

**Architecture:** `OpenCodeCapabilityProbe.probe(client)` keeps its signature and its `Result`/failure codes. New
flow:
1. `GET /global/health`. Must be healthy. Gives the `version`.
2. If a passing result is cached for that version, return it.
3. `GET /doc` (opencode's OpenAPI spec). Check that it declares every endpoint and method the driver uses. Map
   missing ones to the existing failure codes.
4. Check the SSE endpoint as today. It is a `GET`, and the response body is closed straight away, so it is free.
5. If `/doc` isn't available (non-200 or not JSON), fall back to the old endpoint checks. The fallback **does
   not** send a prompt or a fake permission reply, and it deletes the session it created.
6. Cache passing results by version.

**Tech Stack:** Java 21, Jackson, JDK HttpClient (HTTP/1.1), JUnit 5, JDK `HttpServer` fakes.

**Spec:** GitHub issue #376.

## Background (verified facts)

- **Current probe** (`app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbe.java`):
  1. health
  2. `createSession("Axiom capability probe")`
  3. SSE check on `/event`, falling back to `/global/event`
  4. `sendPromptAsync(sessionId, "capability-probe", …)`: a **real model call**
  5. `POST /session/{id}/permissions/per_probe_permission_id`: any 200 counts as success
  6. `abort`

  The session is never deleted. It runs before **every** session, inside `OpenCodeInteractiveSessionDriver.start()`
  (`capabilityProbe.probe(client)`), against the new session's own `opencode serve`.
- **Real opencode 1.18.33** (checked 2026-09-30):
  - `GET /global/health` → `{"healthy":true,"version":"1.18.33"}`.
  - `GET /doc` → 200, OpenAPI 3.1.0, 162 paths, about 480 KB. `paths` has these entries and methods:
    - `/session`: get, post
    - `/session/{sessionID}`: get, patch, delete
    - `/session/{sessionID}/prompt_async`: post
    - `/session/{sessionID}/permissions/{permissionID}`: post
    - `/session/{sessionID}/abort`: post
    - `/event`: get
    - `/global/event`: get
    - `/mcp`: get, post
- **Existing failure codes** (`SessionCompatibilityException`): `RUNTIME_UNHEALTHY`, `SESSION_PROTOCOL_UNSUPPORTED`,
  `EVENT_STREAM_UNRELIABLE`, `PROMPT_PROTOCOL_UNSUPPORTED`, `PERMISSION_PROTOCOL_UNSUPPORTED`,
  `INTERRUPT_PROTOCOL_UNSUPPORTED`.
- **Existing tests** (`OpenCodeCapabilityProbeTest`) use a fake `HttpServer` and cover pass/fail for SSE and the
  permission endpoint. Keep their intent, and adapt the fake to serve `/doc` where needed.
- `OpenCodeAssistantClient` has `health()`, `createSession`, `sendPromptAsync`, `abort`, `mcpStatus`, a private
  `getJson`, and a private `postJson`. It has **no** `deleteSession` and no raw-GET helper.

**Required operations** (method + path in `/doc` → failure code if missing):

| Operation | Failure code |
|---|---|
| `post /session` | `SESSION_PROTOCOL_UNSUPPORTED` |
| `post /session/{sessionID}/prompt_async` | `PROMPT_PROTOCOL_UNSUPPORTED` |
| `post /session/{sessionID}/permissions/{permissionID}` | `PERMISSION_PROTOCOL_UNSUPPORTED` |
| `post /session/{sessionID}/abort` | `INTERRUPT_PROTOCOL_UNSUPPORTED` |
| `get /event` **or** `get /global/event` | `EVENT_STREAM_UNRELIABLE` |

`get /mcp` is not required, because MCP status is only reported as a warning.

**Rulings:**
- Keep the live SSE check. `/doc` can't prove the stream really returns `text/event-stream`, and the check has no
  side effects.
- The cache is a static `ConcurrentHashMap<String, Result>` keyed by the health `version`.
  It caches **passes only** and
  skips caching when the version is blank. That is safe because each session starts its own `opencode serve` from
  the same executable. A package-private `clearCache()` is provided for tests.
- The fallback (no `/doc`, for older opencode) creates a session, checks SSE, then `DELETE /session/{id}` (best
  effort). It does not send a prompt, a permission reply or an abort. It trusts that the prompt, permission and
  abort endpoints exist once session creation works.
- The success path creates **no** opencode session at all.

## Global Constraints

- 4-space indentation; Javadoc on public members; explicit types (no `var`); JUnit 5.
- `probe(OpenCodeAssistantClient)`, `Result` and the failure codes are unchanged. No UI or REST changes.
- The probe must never call `sendPromptAsync` or post to a permissions endpoint.
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. A JBoss LogManager warning
  is pre-existing noise. Real-opencode HTTP must use HTTP/1.1.

---

### Task 1: Spec-based probe with cache

**Files:**
- Modify: `OpenCodeCapabilityProbe.java`
- Modify: `OpenCodeCapabilityProbeTest.java`
- Modify: `OpenCodeAssistantClient.java` (add `deleteSession`)

**Interfaces:**
- `public void OpenCodeAssistantClient.deleteSession(String sessionId)`: `DELETE /session/{id}`, accepting 200 or
  204. Throws `IllegalStateException` on failure. Add a private `sendDelete`, or generalise the existing request
  helper minimally.
- `static void OpenCodeCapabilityProbe.clearCache()` (package-private).

- [ ] **Step 1: Write the failing tests**

Rework the test fake so each test can configure the following (keep the existing handler classes where
possible):
- **Health:** `{"healthy":true,"version":"<v>"}`.
- **`/doc`:** status and body.
- **SSE:** `/event` and `/global/event` behaviour, as today.
- **Counters:** the number of `POST /session` calls, `POST …/prompt_async` calls, `POST …/permissions/…` calls
  and `DELETE /session/…` calls.

Build a helper `static String doc(String... operations)` that produces
`{"openapi":"3.1.0","paths":{ "<path>": {"<method>": {}} … }}` from strings like `"post /session"`. Define a
constant `ALL_OPS` with the six operations from the table (`get /event` and `get /global/event` both included).

Call `OpenCodeCapabilityProbe.clearCache()` in `@BeforeEach`. Tests:

```java
    @Test
    void passesUsingSpecWithoutCreatingSessionsOrPrompting() {
        // doc = ALL_OPS, /event SSE ok
        // assert result.compatible(); sessionPosts == 0; promptPosts == 0; permissionPosts == 0
    }

    @Test
    void failsWithPromptCodeWhenPromptAsyncMissingFromSpec() {
        // doc = ALL_OPS minus "post /session/{sessionID}/prompt_async"
        // assert !compatible, code == PROMPT_PROTOCOL_UNSUPPORTED
    }

    @Test
    void failsWithPermissionCodeWhenPermissionEndpointMissingFromSpec() { /* PERMISSION_PROTOCOL_UNSUPPORTED */ }

    @Test
    void failsWithInterruptCodeWhenAbortMissingFromSpec() { /* INTERRUPT_PROTOCOL_UNSUPPORTED */ }

    @Test
    void failsWithSessionCodeWhenSessionCreateMissingFromSpec() { /* SESSION_PROTOCOL_UNSUPPORTED */ }

    @Test
    void failsWhenSpecDeclaresNoEventStream() { /* doc without get /event and get /global/event → EVENT_STREAM_UNRELIABLE */ }

    @Test
    void cachesPassingResultPerVersion() {
        // same version twice → second probe does not request /doc (count /doc hits == 1)
        // different version → /doc requested again
    }

    @Test
    void doesNotCacheFailures() { /* failing doc twice → /doc hit twice */ }

    @Test
    void fallsBackWithoutPromptingAndDeletesProbeSessionWhenSpecUnavailable() {
        // /doc returns 404; POST /session returns {"id":"ses_probe"}; DELETE /session/ses_probe → 200
        // assert compatible; promptPosts == 0; permissionPosts == 0; deletes == 1
    }
```

Write each test fully, with real assertions, following the comments. Keep the existing SSE tests (non-SSE content
type, primary 500 with global healthy, primary unmapped with global SSE) with `/doc` = `ALL_OPS`. Replace the two
permission-endpoint tests, which relied on the fake permission POST, with the spec-based permission test above.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -pl app test -Dtest=OpenCodeCapabilityProbeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: new tests FAIL (the current probe prompts and posts permissions), or the test fails to compile because
`clearCache` is missing.

- [ ] **Step 3: Implement**

- `probe(client)`:
  1. Health.
  2. Cache lookup by version.
  3. `fetchSpec(baseUrl)`, using `httpClient` `GET /doc` with a 10 s timeout. It returns the parsed `JsonNode`, or
     null if the status isn't 200 or the JSON is unparseable.
  4. If the spec is non-null, run `checkSpec(spec)`. That returns the first failing `Result` in table order
     (session, prompt, permission, abort, events), or null.
  5. Run the existing SSE check (`/event`, falling back to `/global/event`).
  6. If the spec is null, run `fallbackProbe(client)`: `createSession("Axiom capability probe")` (failure →
     `SESSION_PROTOCOL_UNSUPPORTED` as today), then SSE, then `deleteSession` in a `finally`, with failures only
     logged at debug.
  7. On pass, cache it if the version isn't blank.
- `hasOperation(spec, method, path)` = `spec.path("paths").path(path).has(method)`.
- Delete `PROBE_PERMISSION_ID`, `checkPermissionEndpoint`, `sendJsonPost`, `sendOptions` and
  `PermissionEndpointStatus`, since they are no longer used. Remove any imports that become unused.
- Update the class Javadoc to describe the new flow and to say that it has no side effects.

- [ ] **Step 4: Run to verify pass**

Run `-Dtest='OpenCode*Test'`. Expected: all PASS. If `OpenCodeInteractiveSessionDriverTest` fakes rely on the old
probe, they're unaffected: they inject a lambda probe.

- [ ] **Step 5: Commit** `fix(assistant): check OpenCode compatibility from its API spec without prompting (#376)`

---

### Task 2: Real-opencode verification

**Files:** `app/src/test/java/io/apitomy/axiom/app/assistant/runtime/opencode/OpenCodeCapabilityProbeTest.java`
(or `OpenCodeSessionServerProcessTest.java`, which already has real-opencode tests. Put it wherever the helper
imports are simplest).

- [ ] **Step 1: Add the test** (skipped when opencode isn't installed)

```java
    @Test
    void realOpenCodePassesProbeWithoutCreatingSessions(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeCapabilityProbe.clearCache();
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(process.baseUrl());
            int before = sessionCount(process.baseUrl());

            OpenCodeCapabilityProbe.Result result = new OpenCodeCapabilityProbe().probe(client);

            assertTrue(result.compatible(), String.valueOf(result));
            assertEquals(before, sessionCount(process.baseUrl()));
        } finally {
            process.stop();
        }
    }
```

`sessionCount` is `GET /session` with an HTTP/1.1 client, returning the size of the JSON array.

Note: opencode's `GET /session` may list sessions from the user's global opencode state, not only this server's.
The test only compares before and after, so that's fine.

- [ ] **Step 2: Run it**, plus the full `mvn -q -pl app test`.

- [ ] **Step 3: Commit** `test(assistant): verify OpenCode probe against a real server creates no sessions (#376)`

- [ ] **Step 4: Manual (human)**

Start an OpenCode session:
- The session appears quickly.
- `curl http://127.0.0.1:<port>/session` shows only the assistant session, with no "Axiom capability probe".
- There is no extra model call in opencode's usage.
