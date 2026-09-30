# OpenCode Session Lifecycle Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make OpenCode interactive sessions robust across their whole lifecycle (GitHub issue #378, epic #387):
- **Server:** a per-session password on the local server, and a retry when the chosen port is taken.
- **Recoverable aborts:** a failed Stop doesn't kill the session.
- **Liveness:** it includes the event stream.
- **Recovery:** the event stream reconnects after a drop.
- **Cleanup:** destroy closes the stream and deletes the opencode session.
- **Raw log:** a `raw-events.jsonl` log like Claude's, so `GET /raw-events` works.

**Tech Stack:** Java 21, JDK HttpClient (HTTP/1.1), Jackson, JUnit 5, JDK `HttpServer` fakes, opencode 1.18.x.

**Spec:** GitHub issue #378.

## Background (verified facts)

**opencode 1.18.33, checked 2026-09-30:**
- **`--port 0` is not ephemeral.** `opencode serve --port 0` printed `opencode server listening on
  http://127.0.0.1:4096`, so it bound the default port. The issue's "let opencode pick the port" idea therefore
  **doesn't work**. We keep picking a free port and add a **retry on bind failure**.
- **Password protection works.** With `OPENCODE_SERVER_PASSWORD=s3cret`:
  - `GET /global/health` without auth returns **401**.
  - With HTTP Basic `opencode:s3cret` it returns **200**; with a wrong password, **401**.
  - `GET /event` with auth returns 200 `text/event-stream`.
  - The username is `opencode` (the default when `OPENCODE_SERVER_USERNAME` is unset).

**Code facts** (`app/src/main/java/io/apitomy/axiom/app/assistant/runtime/opencode/`):
- **`OpenCodeSessionServerProcess.start()`:** picks a port with `resolveEphemeralPort()` (open and close a
  `ServerSocket(0)`), starts the process, creates an `OpenCodeAssistantClient(baseUrl())` and runs
  `waitForHealthy()`. That throws "OpenCode server exited during startup with code N" if the process dies.
  `createProcessBuilder(int port)` adds the `environment` map. Stdout is drained and logged at debug.
- **`OpenCodeAssistantClient(String baseUrl)`:** builds its HTTP requests in several places (`health`, `postJson`,
  `getJson`, the SSE request in `streamEvents`, `deleteSession`). `connectEvents(onEvent, onError)` starts a
  virtual thread that reads the SSE stream until EOF or an error, then calls `onError`. There is no way to stop
  it.
- **`OpenCodeCapabilityProbe`:** uses its own `HttpClient` for `GET /doc` and the SSE check (`checkSseEndpoint`).
  Those requests need auth too.
- **`OpenCodeInteractiveSessionDriver`:**
  - `start()` creates `new OpenCodeAssistantClient(serverProcess.baseUrl())`.
  - `interrupt()` calls `client.abort`. On failure it **sets status ERROR** (the bug).
  - `isAlive()` returns `serverProcess.isAlive()`.
  - `handleStreamFailure` sets ERROR and emits `session_ended`.
  - `destroy()` clears state and stops the server.
  - `ServerProcessHandle` is an interface with `start`, `stop`, `isAlive` and `baseUrl`, implemented by
    `ServerProcessAdapter` and by the test's `FakeServerProcess`.
  - `SessionSettings(sessionTitle, model, tools, expectedMcpServers, systemPrompt, fallbackModel)`.
- **REST** `AssistantResourceImpl`:
  - The SSE drainer loops while `session.isAlive() || !eventQueue.isEmpty()` (~line 209).
  - `GET …/raw-events` reads `session.getRawEventsFile()` (the session dir's `raw-events.jsonl`) and returns 404
    if it's missing.
- **Claude raw log format** (`ClaudeInteractiveSessionDriver.writeRawEvent`): one line per event,
  `{"ts":"<Instant>","raw":<original JSON>}`. It is flushed per line and disabled with a WARN on an IO error.

**Rulings:**
- **Port:** keep choosing a free port, and retry up to **3 attempts** with a new port when the process exits
  during startup. Only a startup exit is retried; a health timeout is not.
- **Password:**
  - A random 32-byte URL-safe base64 password per session server, from `SecureRandom`.
  - Passed as `OPENCODE_SERVER_PASSWORD`. It **overrides** any template value, because Axiom manages it.
  - Every Axiom HTTP request to that server sends `Authorization: Basic base64("opencode:" + password)`.
  - The password is never logged.
- **Abort failure:** keep the status as is. Emit `session_error` `{name:"InterruptFailed", message:"Could not stop
  the current reply: <reason>"}` and log a WARN.
- **Liveness:** `isAlive()` = server process alive **and** the event stream is not permanently failed.
- **Stream reconnect:**
  - When the stream ends or fails and the driver is not destroyed and the server process is alive, reconnect up
    to **3** times, waiting 250 ms, 1 s and 2 s.
  - After a successful reconnect, emit `session_error` `{name:"EventStreamReconnected", message:"Reconnected to
    OpenCode; some updates during the interruption may be missing."}`.
  - After the attempts are exhausted, or if the server process is dead, keep today's behaviour: ERROR, then
    `session_ended`.
  - The attempt counter resets after an event is received on a reconnected stream.
- **`session.status` tracking:** not added (YAGNI). Retry and busy indicators belong to #379.
- **Destroy:** first close the event stream so its thread stops and no reconnect starts. Then
  `DELETE /session/{id}` (best effort, 3 s timeout, failures logged at debug). Then stop the server.
- **Raw log:**
  - Location: `SessionSettings.rawEventsFile`. The factory passes `request.sessionDirectory().resolve(
    "raw-events.jsonl")`; tests may pass null, which disables it.
  - Content: every raw SSE event the driver receives, **before** session filtering, is written as
    `{"ts":"…","raw":<payload JSON>}`. Same format as Claude, and useful for debugging protocol mismatches.
  - The writer is closed on destroy and after a permanent stream failure.

## Global Constraints

- 4-space indentation; Javadoc on public members; explicit types (no `var`); JUnit 5.
- No UI or REST/OpenAPI changes. The Claude driver is unchanged.
- The password must never appear in logs, exception messages or events.
- All real-opencode tests are skipped when opencode isn't installed, and use HTTP/1.1.
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers.
  Don't commit `ui/package-lock.json`.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`. A JBoss LogManager warning
  is pre-existing noise.

---

### Task 1: Password-protected server with bind retry

**Files:**
- `OpenCodeSessionServerProcess.java`, `OpenCodeAssistantClient.java`, `OpenCodeCapabilityProbe.java`
- `OpenCodeInteractiveSessionDriver.java` (the `ServerProcessHandle` interface and adapter, and client
  construction)
- Tests: `OpenCodeSessionServerProcessTest`, new `OpenCodeAssistantClientAuthTest`, `OpenCodeCapabilityProbeTest`

**Interfaces:**
- **`OpenCodeAssistantClient`:** add a constructor `OpenCodeAssistantClient(String baseUrl, String password)`. A
  null or blank password means no auth, and the existing 1-arg constructor delegates with null.
  - Every request built by the client adds `Authorization` when a password is set.
  - Add `public Optional<String> authorizationHeader()` for the probe.
- **`OpenCodeSessionServerProcess`:**
  - Generates the password in its constructor.
  - `createProcessBuilder` puts `OPENCODE_SERVER_PASSWORD` **after** the extra environment, so it wins.
  - `public String password()` returns it. Its Javadoc says it is for the driver's client only.
  - Its internal health client uses the password.
- **`ServerProcessHandle`:** add `default String password() { return null; }`. `ServerProcessAdapter` delegates it.
  The driver creates `new OpenCodeAssistantClient(serverProcess.baseUrl(), serverProcess.password())`.
- **`OpenCodeCapabilityProbe`:** its own requests (`/doc`, SSE check) add `client.authorizationHeader()` when it
  is present.
- **Bind retry:** in `start()`, loop up to `MAX_START_ATTEMPTS = 3`. Retry only if the failure is the "exited
  during startup" case and `configuredPort == 0`, choosing a new ephemeral port each time. On the final failure,
  throw the last exception.

**Steps:**
- [ ] **1. Write the failing tests:**
  - **(a)** `OpenCodeAssistantClientAuthTest`, using a fake `HttpServer` that records the `Authorization` header:
    - With a password, `health()`, `createSession`, `mcpStatus()` and the SSE connection
      (`connectEvents(...)`, read one event) each send `Basic base64("opencode:pw")`.
    - Without a password, none send the header.
  - **(b)** `OpenCodeSessionServerProcessTest`: `createProcessBuilder(4321).environment()` contains
    `OPENCODE_SERVER_PASSWORD` equal to `process.password()`, even when the extra environment sets it to something
    else. The password is at least 32 characters, and two instances get different passwords.
  - **(c)** Real opencode:
    - Start the process.
    - A raw HTTP/1.1 `GET /global/health` **without** auth returns 401.
    - `new OpenCodeAssistantClient(process.baseUrl(), process.password()).health().healthy()` is true.
  - **(d)** Bind retry, using a fake executable script:
    - Create an executable shell script in `@TempDir` that exits 1 immediately on its first run (use a marker
      file to count runs), then `exec opencode "$@"` on later runs. Use it as the executable, with an assumption
      that opencode is available and the OS is POSIX.
    - Expect `start()` to succeed and the marker to show 2 runs.
    - If a script-based test isn't feasible, extract the retry decision into a package-private
      `static boolean shouldRetry(RuntimeException e, int attempt, int configuredPort)` and unit-test it instead.
      Record which option you chose in the report.
  - **(e)** Probe: `OpenCodeCapabilityProbeTest`'s fake records auth. A probe run with a password-bearing client
    sends the header on `/doc` and SSE.
- [ ] **2. Run to verify failure:**
  `-Dtest='OpenCodeAssistantClientAuthTest,OpenCodeSessionServerProcessTest,OpenCodeCapabilityProbeTest'`.
- [ ] **3. Implement as described.** In the client, centralise the request building in a private
  `HttpRequest.Builder request(String path)` that adds the header, and use it everywhere, including the SSE
  request.
- [ ] **4. Run to verify pass:** `-Dtest='OpenCode*Test,InteractiveSessionDriverFactoryTest'`.
- [ ] **5. Commit:** `feat(assistant): protect OpenCode session servers with a per-session password (#378)`

---

### Task 2: Recoverable abort, stream liveness, reconnect and cleanup

**Files:**
- `OpenCodeAssistantClient.java` (a stoppable event stream)
- `OpenCodeInteractiveSessionDriver.java`
- Test: `OpenCodeInteractiveSessionDriverTest`

**Interfaces:**
- **`OpenCodeAssistantClient.connectEvents(onEvent, onError)`** returns an `AutoCloseable`
  (`public interface EventStream extends AutoCloseable { void close(); }`, nested).
  - `close()` sets a closed flag and closes the response `InputStream`, which unblocks the reader.
  - After `close()`, `onError` is **not** called.
  - The 1-arg overload's behaviour is unchanged (it may ignore the handle).
- **The driver's `EventStreamConnector.connect(...)`** returns `OpenCodeAssistantClient.EventStream`. Update the
  default connector reference and every test lambda that implements it: return a no-op `() -> { }`, or whatever
  the lambda previously returned.

**Driver behaviour:** see the Rulings: InterruptFailed, `isAlive` (server alive && !streamFailed), reconnect with
backoff (250 ms, 1 s, 2 s, max 3), EventStreamReconnected, and destroy (close stream, delete session, stop
server). Add private state: `volatile EventStream eventStream`, `volatile boolean destroyed`,
`volatile boolean streamFailed`, `AtomicInteger reconnectAttempts`. Reset `reconnectAttempts` to 0 in
`handleRawEvent` when a reconnected stream delivers an event. Make the backoff delays injectable for tests
(package-private constructor argument or a setter, e.g. `List<Duration>`) so tests don't sleep for seconds.

**Steps:**
- [ ] **1. Write the failing tests:**
  1. **Interrupt failure:** the fake `/abort` returns 500. `driver.interrupt()` leaves the status `RUNNING` and
     emits exactly one `session_error` named `InterruptFailed`.
  2. **Reconnect success:** the fake `/event` closes the stream after the first connection (count connections),
     then serves a normal stream. Expect the status to stay `RUNNING`, exactly one `EventStreamReconnected`, 2
     `/event` connections, and `isAlive()` true. Use tiny injected backoffs.
  3. **Reconnect exhausted:** `/event` always closes immediately. After 1 + 3 connections, expect status `ERROR`,
     one `session_ended`, and `isAlive()` false.
  4. **Server dead:** `FakeServerProcess` reports not alive, and the stream closes. Expect no reconnect attempts
     (1 connection), then `ERROR` and `session_ended`.
  5. **Destroy:** the fake records `DELETE /session/session-1`. After `destroy()`, expect exactly one DELETE, no
     reconnect attempts afterwards (connection count unchanged after waiting 200 ms), and `onError` not
     triggering `session_ended` after destroy.
  6. **Existing test** `streamFailureTransitionsToError`: update it to inject zero backoffs and to make the fake
     report the server as dead or keep closing the stream, so it still ends in `ERROR`. Keep its assertions.
- [ ] **2. Run to verify failure.**
- [ ] **3. Implement.**
- [ ] **4. Run** `-Dtest='OpenCode*Test'` 3 times; all must pass each time.
- [ ] **5. Commit:** `fix(assistant): recover OpenCode sessions from aborts and event stream drops (#378)`

---

### Task 3: Raw event log

**Files:**
- `OpenCodeInteractiveSessionDriver.java` (`SessionSettings.rawEventsFile`, and the writer)
- `InteractiveSessionDriverFactory.java`
- Tests: `OpenCodeInteractiveSessionDriverTest`, `InteractiveSessionDriverFactoryTest`
- `docs/user-guide/ai-assistant.md`, if raw events are mentioned there

**Interfaces:**
- `SessionSettings` gains a trailing `Path rawEventsFile`. It may be null, which disables the log. All
  construction sites must be updated; tests pass null.
- The factory passes `request.sessionDirectory().resolve("raw-events.jsonl")`.

**Steps:**
- [ ] **1. Write the failing tests:**
  - **Driver:** with `rawEventsFile` set to a `@TempDir` file, replay the `1.18.33-tool-calls.jsonl` fixture
    through the driver, using the existing `replayFixture` helper pattern with `SessionSettings`. The file must
    have one line per received SSE event (≥ the fixture's session-scoped events). Each line parses as
    `{"ts":…, "raw":{…}}`, with `raw.type` present. Also: after `destroy()`, the file is closed (it can be read
    fully, and later events aren't appended).
  - **Factory:** a driver created for opencode has field `rawEventsFile` equal to
    `sessionDir.resolve("raw-events.jsonl")`.
- [ ] **2. Implement.**
  - Open a `BufferedWriter` (UTF-8, append = false) in `start()` before connecting the stream. On an IO error, log
    a WARN and disable the log.
  - Write in the raw-event callback **before** `handleRawEvent` filtering. Guard with `synchronized` on the
    writer, since reconnects may briefly overlap.
  - Close it on destroy and after a permanent stream failure.
  - Write the same format as Claude: `{"ts":"<Instant>","raw":<payload.toString()>}`.
- [ ] **3. Run** `-Dtest='OpenCode*Test,InteractiveSessionDriverFactoryTest'`, then the full `mvn -q -pl app
  test`.
- [ ] **4. Commit:** `feat(assistant): write raw event log for OpenCode sessions (#378)`
- [ ] **5. Manual (human):**
  1. Start an OpenCode session. `ps e` / `/proc/<pid>/environ` shows `OPENCODE_SERVER_PASSWORD` is set, and
     `curl http://127.0.0.1:<port>/global/health` without auth returns 401.
  2. `GET /api/v1/assistant/sessions/<id>/raw-events` returns the log.
  3. Kill the opencode process. The session ends with an error instead of hanging.
  4. Click Stop while nothing is running. No session error state.
  5. End the session. `ls ~/.local/share/opencode/storage/session*` (or `GET /session` before exit) shows the
     session was deleted.
