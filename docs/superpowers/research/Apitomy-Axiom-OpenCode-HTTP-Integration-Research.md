# Apitomy Axiom: OpenCode HTTP Backend Integration — Research & Implementation Planning Brief

## Purpose

This document is intended to be provided to a coding agent working on **Apitomy Axiom**. Its purpose is to capture the current findings about OpenCode's programmatic HTTP interface and establish a starting point for analyzing and planning support for OpenCode as an alternative agent runtime to the existing Claude Code CLI integration.

The target architecture is:

- Axiom remains the application-facing web UI/backend.
- Axiom starts and owns **one long-lived OpenCode server process**.
- Axiom communicates with that server over HTTP.
- OpenCode sessions represent individual interactive coding conversations.
- Axiom translates OpenCode events/state into the existing Axiom session/event model.
- Axiom should not spawn one OpenCode process per conversation.

This document is a planning/research artifact, not an implementation specification. The coding agent should inspect the current Axiom codebase and the installed/current OpenCode version before making final architectural decisions.

## 1. Key conclusion

OpenCode has a first-class headless HTTP server intended for programmatic clients:

    opencode serve

By default it listens on `127.0.0.1:4096`. It exposes an OpenAPI 3.1 interface and is explicitly designed so that multiple clients can interact with the same OpenCode server. The official documentation states that the normal OpenCode TUI is itself a client of the server.

The server therefore maps well to Axiom's existing "custom UI over an agent runtime" architecture.

Relevant official documentation:

- Server: https://opencode.ai/docs/server/
- CLI: https://opencode.ai/docs/cli/

The server's OpenAPI documentation is exposed at:

    http://<host>:<port>/doc

The exact API should be treated as version-dependent. Axiom should verify the API against the specific OpenCode version it launches rather than hard-coding assumptions based only on this document.

## 2. Recommended Axiom architecture

Recommended high-level architecture:

    Axiom Web UI
          |
          | Axiom's existing protocol
          v
    Axiom backend
          |
          | HTTP
          v
    One OpenCode server process
          |
          +-- OpenCode session A
          +-- OpenCode session B
          +-- OpenCode session C
          |
          v
    LLM providers / tools / filesystem / git

The important distinction is:

- **OpenCode server lifecycle** = application/runtime lifecycle.
- **OpenCode session lifecycle** = individual interactive conversation lifecycle.

Axiom should maintain a mapping such as:

    Axiom conversation/session ID -> OpenCode session ID

Axiom should not create a new OpenCode server for every Axiom conversation unless future isolation requirements force that design.

## 3. OpenCode server lifecycle

The documented command is:

    opencode serve

Relevant options include:

    --port <number>
    --hostname <string>
    --cors <origin>

Defaults are:

    hostname = 127.0.0.1
    port     = 4096

The server can be protected with HTTP Basic Authentication by setting:

    OPENCODE_SERVER_PASSWORD

The username defaults to `opencode`, or can be overridden with:

    OPENCODE_SERVER_USERNAME

For a local Axiom-managed server, binding to loopback is preferable unless there is a deliberate reason to expose it on another interface.

Axiom should own the process lifecycle:

1. Start OpenCode server when the Axiom runtime needs the OpenCode backend.
2. Wait for `/global/health` to report healthy before accepting requests.
3. Keep the process alive across individual conversations.
4. Reuse the same server for multiple OpenCode sessions.
5. Detect process exit and make Axiom sessions fail/reconnect gracefully.
6. Shut the server down when the Axiom runtime/workspace is being disposed.
7. Consider graceful shutdown and cleanup of child processes.

The API also exposes:

    POST /instance/dispose

which disposes the current OpenCode instance. This should be investigated before deciding whether Axiom should use it during shutdown versus terminating the process directly.

Official source:
https://opencode.ai/docs/server/

## 4. Health and capability discovery

The documented health endpoint is:

    GET /global/health

It returns information equivalent to:

    {
      "healthy": true,
      "version": "..."
    }

Axiom should use this as the readiness check after launching OpenCode.

The server also exposes:

    GET /config
    PATCH /config
    GET /config/providers
    GET /provider
    GET /provider/auth

These may be useful for future Axiom functionality such as model/provider discovery and configuration, but they should not all be required for the initial interactive-session implementation.

The `/doc` endpoint exposes OpenAPI documentation. The implementation should consider obtaining/inspecting the actual OpenAPI document from the running version during development rather than relying exclusively on manually reproduced request types.

## 5. Session model

OpenCode has first-class persistent sessions.

Documented endpoints include:

    GET    /session
    POST   /session
    GET    /session/status
    GET    /session/:id
    PATCH  /session/:id
    DELETE /session/:id

There are also:

    GET    /session/:id/children
    POST   /session/:id/fork
    POST   /session/:id/abort
    GET    /session/:id/diff
    GET    /session/:id/todo
    POST   /session/:id/summarize
    POST   /session/:id/revert
    POST   /session/:id/unrevert
    POST   /session/:id/permissions/:permissionID

This provides most of the primitives expected from an interactive coding-agent session.

Recommended initial Axiom mapping:

    Axiom Session
        |
        +-- openCodeSessionId
        +-- workspace/project information
        +-- Axiom-specific metadata
        +-- connection/event state

Axiom should preserve its own session abstraction rather than leaking OpenCode's IDs throughout the application.

## 6. Creating sessions

The documented endpoint is:

    POST /session

The request can include fields such as:

    {
      "parentID": "...",
      "title": "..."
    }

The response is an OpenCode Session object.

For a new Axiom interactive session, the initial implementation should:

1. Determine the correct Axiom workspace/repository.
2. Ensure the OpenCode server is associated with the intended project/path.
3. Create an OpenCode session.
4. Store the OpenCode session ID against the Axiom session.
5. Establish event handling before sending the first prompt, if possible.

The exact relationship between an OpenCode server instance, current project/path, and multiple workspaces must be verified against the current OpenCode implementation. This is an important area for the coding agent to investigate rather than assume.

## 7. Sending prompts

There are two important message APIs.

### Synchronous

    POST /session/:id/message

This sends a message and waits for the response.

### Asynchronous

    POST /session/:id/prompt_async

This sends a message and returns `204 No Content` without waiting for completion.

For Axiom's interactive web UI, the asynchronous API is the preferred conceptual model.

Recommended flow:

    Axiom UI
       |
       | user prompt
       v
    Axiom backend
       |
       | POST /session/{id}/prompt_async
       v
    OpenCode
       |
       | events
       v
    Axiom backend
       |
       | Axiom event stream
       v
    Axiom UI

The message body is documented as being equivalent to the synchronous message endpoint and can contain items such as model, agent, system, tools, and message parts.

## 8. Events / streaming

OpenCode documents SSE event streams.

The server documentation currently lists:

    GET /global/event

and also:

    GET /event

The `/event` endpoint is documented as an SSE stream whose first event is `server.connected`, followed by event-bus events.

This is potentially the most important integration mechanism for Axiom because Axiom needs to provide a live interactive UI while an agent is:

- generating text,
- invoking tools,
- modifying files,
- asking for permission,
- changing status,
- encountering errors,
- finishing a turn.

Events should therefore be treated as the primary mechanism for live UI updates, with session/message GET endpoints used for state reconciliation and recovery.

## 9. IMPORTANT: current SSE/event-stream reliability needs verification

This is a critical implementation-planning caveat.

Current OpenCode documentation describes persistent SSE event streams, but recent GitHub issue reports indicate regressions and inconsistencies in event delivery in some OpenCode versions.

For example, a September 2026 issue reports that on OpenCode 1.18.25:

- `/event` and `/global/event` accepted connections and delivered connection/heartbeat events,
- but expected message/session events were not delivered.

Another recent issue reports problems with `/api/event` connections closing during active turns.

These are GitHub issue reports rather than official API guarantees, but they are sufficiently recent that Axiom should explicitly test the exact OpenCode version it supports.

Therefore:

**Do not design the Axiom event bridge on the assumption that the documented SSE behavior is currently bug-free.**

The coding agent should build a small standalone integration test before committing to the final event architecture.

At minimum test:

1. Connect to the documented event endpoint.
2. Create a session.
3. Send an asynchronous prompt.
4. Verify receipt of session/message/tool/status events.
5. Run a multi-step tool-using prompt.
6. Verify that the stream remains usable throughout the entire turn.
7. Disconnect/reconnect during a turn.
8. Determine whether events can be recovered after reconnection.
9. Run two simultaneous sessions and verify events can be unambiguously associated with sessions.
10. Test the exact OpenCode version Axiom will launch.

Relevant reports:
- https://github.com/anomalyco/opencode/issues/46733
- https://github.com/anomalyco/opencode/issues/38458
- https://github.com/anomalyco/opencode/issues/36441

These reports should be treated as implementation-risk signals, not as proof that the behavior is universally broken in the target version.

## 10. Event routing and concurrency

The event stream is important because Axiom will likely have multiple active sessions sharing one OpenCode server.

Conceptually:

    OpenCode server
        |
        | event stream
        v
    Axiom OpenCode Event Dispatcher
        |
        +--> session A events
        |
        +--> session B events
        |
        +--> session C events

Axiom should inspect each event for its session identifier and route it to the corresponding Axiom session.

Do not assume that the event stream is already scoped to one session.

Recent OpenCode discussions/issues explicitly describe event streams as process-global and discuss future/ongoing work around scoping event subscriptions by client interest. This means the Axiom event bridge should be designed as a multiplexing layer.

Axiom should maintain:

    OpenCode session ID -> Axiom session/event sink

and discard or separately handle events that do not belong to an active Axiom session.

## 11. Same-session versus cross-session concurrency

The design should distinguish:

### Different sessions

Multiple OpenCode sessions should be considered independently addressable and should be designed to run concurrently.

Example:

    Session A -> repository/workspace A
    Session B -> repository/workspace B

Axiom should be able to receive events from both and route them independently.

### Same session

Do not assume that multiple prompts submitted concurrently to the same OpenCode session are safe or meaningful.

For the initial implementation, serialize user prompts per OpenCode session:

    Axiom Session Lock
          |
          +-- prompt 1
          |
          +-- prompt 2 (queued until prompt 1 completes)

If OpenCode's current API/source establishes stronger concurrency semantics, the implementation can later be relaxed.

This avoids race conditions such as two user messages attempting to drive one agent turn simultaneously.

## 12. Session status

The server provides:

    GET /session/status

which returns status information for all sessions.

There is also a per-session status concept exposed through events.

Axiom should not use polling as the primary live-update mechanism if reliable events are available.

However, status/message APIs should be used for reconciliation.

A robust design should assume:

    Events = notification / streaming mechanism
    REST state endpoints = authoritative state / recovery mechanism

This is particularly important because browser connections and SSE connections can disappear.

## 13. Message history

The documented APIs include:

    GET /session/:id/message
    GET /session/:id/message/:messageID

Axiom can therefore recover/reconstruct a conversation from OpenCode state rather than depending entirely on having received every event live.

This should be part of the design.

Recommended recovery strategy:

1. Maintain the OpenCode session ID.
2. Maintain the last successfully processed event/message information in Axiom.
3. If the event stream disconnects, reconnect.
4. Query session messages/state.
5. Reconcile any missing content/events.
6. Resume live event processing.

The exact event IDs/replay semantics must be investigated for the target OpenCode version. Do not assume a generic `Last-Event-ID` replay mechanism exists.

## 14. Permissions

OpenCode exposes:

    POST /session/:id/permissions/:permissionID

This is highly relevant to Axiom because an interactive web UI may need to present permission requests to the user.

Axiom should investigate:

- Which actions generate permission requests.
- What event identifies a permission request.
- What response values are accepted.
- Whether permission state can be persisted/remembered.
- Whether Axiom's existing Claude Code permission UI can be mapped cleanly.

This should be part of the first end-to-end prototype rather than deferred indefinitely.

## 15. Abort/cancellation

OpenCode exposes:

    POST /session/:id/abort

Axiom should map its existing "Stop/Cancel" UI action to this endpoint.

The implementation should define behavior for:

- user cancellation,
- browser disconnect,
- Axiom server shutdown,
- OpenCode process crash,
- duplicate abort requests.

Cancellation should be idempotent from Axiom's perspective.

## 16. Diffs and coding-agent UI

OpenCode exposes:

    GET /session/:id/diff

This is especially useful for Axiom.

The existing Axiom UI may be able to use OpenCode's diff data directly or translate it into its existing diff model.

Also investigate:

    GET /session/:id/todo

because coding-agent UIs commonly display the agent's current task plan/progress.

These are good candidates for the initial OpenCode adapter if Axiom already has corresponding Claude Code concepts.

## 17. Session forking

OpenCode supports:

    POST /session/:id/fork

with an optional message ID.

This is a potentially valuable future feature for Axiom:

    "Fork conversation from here"

It should not be required for the first implementation, but the Axiom session abstraction should avoid making future support impossible.

## 18. Workspace/project isolation

This is one of the most important architectural questions to resolve by inspecting the Axiom and OpenCode implementations.

OpenCode exposes project/path/VCS APIs:

    GET /project
    GET /project/current
    GET /path
    GET /vcs

The key question is:

**Can one long-lived OpenCode server safely service multiple independent Axiom workspaces/repositories, and how is the project/path associated with each session?**

Do not infer the answer solely from the presence of multiple sessions.

The coding agent should inspect the OpenCode source and run experiments with:

- two repositories,
- two sessions,
- simultaneous activity,
- separate working directories,
- tool execution,
- git operations.

If OpenCode's server instance is effectively tied to one project/path, the "single server for all Axiom sessions" recommendation may need to be refined to "one server per Axiom workspace" rather than globally one server.

This is a key validation item.

## 19. Provider/model configuration

The server exposes provider and configuration APIs, including:

    GET /provider
    GET /config/providers
    GET /config

The initial Axiom implementation should probably avoid reproducing all OpenCode configuration functionality.

Instead:

- let OpenCode handle provider/model configuration,
- allow Axiom to select a model/agent when submitting a prompt if required,
- expose provider/model information in Axiom only when needed by the existing UI.

The message API supports model and agent fields, so model/agent selection can potentially be controlled per prompt.

## 20. Authentication and network exposure

For an Axiom deployment where OpenCode is local to the Axiom backend:

- Prefer binding OpenCode to `127.0.0.1`.
- Keep OpenCode inaccessible directly from the public network.
- Let Axiom own authentication/authorization.
- Use `OPENCODE_SERVER_PASSWORD` as defense-in-depth if appropriate.

If Axiom ever needs to place OpenCode on another host, authentication, TLS, network isolation, and tenant/workspace isolation become much more important.

Axiom's browser should generally communicate with Axiom, not directly with the OpenCode server. This avoids exposing OpenCode's API and credentials to browsers and lets Axiom enforce its own authorization model.

## 21. Recommended adapter abstraction

Do not spread OpenCode-specific HTTP calls throughout Axiom.

Instead, introduce an agent-runtime abstraction along the lines of:

    AgentRuntime
       |
       +-- ClaudeCodeRuntime
       |
       +-- OpenCodeRuntime

The abstraction should cover only the capabilities Axiom actually needs.

Likely initial capability set:

    createSession()
    getSession()
    sendPrompt()
    abortSession()
    getMessages()
    getStatus()
    getDiff()
    respondToPermission()
    subscribeToEvents()
    deleteSession()

Potential future capabilities:

    forkSession()
    summarizeSession()
    revertSession()
    getTodo()
    listModels()
    listAgents()

OpenCode-specific request/response/event types should remain inside the OpenCode adapter.

## 22. Suggested event abstraction

Axiom should normalize OpenCode events into Axiom's internal event model.

Potential normalized events:

    session.created
    session.status
    assistant.message.delta
    assistant.message.completed
    tool.started
    tool.output
    tool.completed
    file.changed
    permission.requested
    permission.resolved
    session.error
    session.completed
    session.aborted

The exact OpenCode event types need to be derived from the target OpenCode version/source and experimentally verified.

Avoid making the Axiom UI depend directly on OpenCode event names.

This will make the existing Claude Code implementation and new OpenCode implementation interchangeable from the UI's perspective.

## 23. Error handling

The OpenCode adapter should explicitly handle:

- HTTP connection failures
- server startup failure
- server health failure
- OpenCode process termination
- HTTP 4xx/5xx responses
- malformed/unexpected event payloads
- SSE disconnection
- permission failures
- model/provider failures
- session-not-found
- abort failures
- duplicate requests
- OpenCode version/API incompatibilities

The adapter should expose errors in Axiom's own error model.

Do not let raw OpenCode HTTP errors leak into the web UI.

## 24. Startup design

A possible Axiom startup sequence:

    1. Start Axiom backend.
    2. Start OpenCode server child process.
    3. Poll /global/health.
    4. Verify expected OpenCode version/capabilities.
    5. Establish event subscription.
    6. Mark OpenCode runtime READY.
    7. Accept Axiom interactive sessions.

The coding agent should determine whether OpenCode needs a project directory specified at process startup or whether the relevant project context is established through the API.

## 25. Shutdown design

On Axiom shutdown:

    1. Stop accepting new prompts.
    2. Notify/mark active Axiom sessions as shutting down.
    3. Optionally abort active OpenCode sessions.
    4. Close the event stream.
    5. Dispose/terminate OpenCode.
    6. Wait for child process exit.
    7. Force-kill only after a timeout.

The exact use of `/instance/dispose` should be verified against the target OpenCode version.

## 26. Testing plan

Before implementing the full UI integration, build a small OpenCode HTTP integration test.

Test cases:

### Server

- Start `opencode serve`.
- Verify `/global/health`.
- Verify `/doc`.
- Verify authentication if enabled.
- Verify graceful shutdown.

### Session

- Create session.
- Retrieve session.
- List sessions.
- Delete session.
- Verify persistence across prompts.

### Prompt

- Send synchronous prompt.
- Send asynchronous prompt.
- Verify final message.
- Verify message history.

### Events

- Connect to event stream.
- Verify connection event.
- Send prompt.
- Verify assistant events.
- Verify tool events.
- Verify status transitions.
- Verify completion.
- Verify errors.

### Concurrency

- Create two sessions.
- Run both simultaneously.
- Verify events remain distinguishable.
- Run different models if supported.
- Verify no cross-session transcript contamination.

### Same-session serialization

- Send two prompts rapidly.
- Determine actual OpenCode behavior.
- Implement Axiom-side serialization if needed.

### Permissions

- Trigger a permission request.
- Receive it.
- Display it through a test client.
- Respond.
- Verify agent continues.

### Cancellation

- Start a long-running turn.
- Abort it.
- Verify status transitions.
- Verify Axiom receives completion/abort state.

### Reconnection

- Disconnect event stream mid-turn.
- Reconnect.
- Determine what can be recovered.
- Reconcile with message/session state.

### Workspace isolation

- Run two repositories/projects.
- Create sessions for each.
- Perform file/tool operations.
- Verify operations cannot cross workspace boundaries.

## 27. Versioning strategy

OpenCode is evolving rapidly. Axiom should not treat the OpenCode API as an eternally stable external protocol.

Recommended strategy:

- Pin/test against a known OpenCode version.
- Record the supported version in Axiom.
- Perform startup capability/version validation.
- Keep OpenCode API types isolated in one module/package.
- Have integration tests exercise a real OpenCode server.
- Avoid depending on undocumented internal APIs unless absolutely necessary.
- Re-run the integration suite when upgrading OpenCode.

The OpenAPI document exposed by the running server should be considered a useful source of truth for the installed version.

## 28. Important current-state caveat

As of September 2026, the official server documentation says the HTTP server exposes OpenAPI and SSE APIs and is intended for programmatic clients.

However, current GitHub issues indicate active development and recent event-stream regressions/issues. Therefore the most important unknown is not whether OpenCode has an HTTP API—it clearly does—but whether the event semantics required by Axiom are sufficiently reliable in the exact version Axiom will use.

The implementation plan should therefore make **event-stream validation the first technical spike**, not an assumption.

## 29. Proposed implementation phases

### Phase 1 — Research/spike

Build a tiny OpenCode HTTP client outside the main Axiom implementation.

Goals:

- start server,
- health check,
- create session,
- send prompt,
- receive events,
- retrieve messages,
- abort,
- permissions,
- diff,
- concurrent sessions,
- workspace isolation.

Deliverable: a verified list of endpoints/events and observed behavior for the selected OpenCode version.

### Phase 2 — Runtime abstraction

Refactor/extend Axiom's agent-runtime architecture so Claude Code and OpenCode share an internal interface.

Do not change the existing Claude Code behavior unnecessarily.

### Phase 3 — OpenCode server manager

Implement:

- process startup,
- health/readiness,
- version checking,
- shutdown,
- failure detection,
- HTTP client,
- event subscription,
- reconnect/reconciliation.

### Phase 4 — OpenCode session adapter

Implement:

- session creation,
- prompt,
- message retrieval,
- abort,
- status,
- permission response,
- diff,
- session deletion.

### Phase 5 — Event normalization

Translate OpenCode events to Axiom's internal interactive-session event model.

### Phase 6 — UI integration

Make the existing Axiom UI work against the normalized runtime interface.

The UI should ideally not know whether the backend is Claude Code or OpenCode.

### Phase 7 — Reliability hardening

Add:

- SSE reconnect/reconciliation,
- process restart handling,
- concurrent-session tests,
- workspace isolation tests,
- permission tests,
- cancellation tests,
- upgrade compatibility tests.

## 30. Questions the coding agent must answer before finalizing the implementation plan

1. What exact OpenCode version should Axiom support?
2. Does one OpenCode server instance safely support multiple projects/workspaces, or does the server/project model require one server per workspace?
3. Which event endpoint is reliable in that exact version?
4. What exact event types are emitted for:
   - assistant streaming,
   - tool calls,
   - tool output,
   - file changes,
   - permissions,
   - status,
   - errors,
   - completion?
5. Does every relevant event contain a session ID?
6. Can events be replayed/recovered after SSE disconnection?
7. Is there a reliable event sequence number or cursor?
8. What is the exact behavior of concurrent prompts to one session?
9. What is the exact behavior of simultaneous prompts in different sessions?
10. How is the OpenCode server's project/current-directory context established?
11. What state survives server restart?
12. Which OpenCode state should Axiom persist independently?
13. Can Axiom safely restart OpenCode without losing existing sessions?
14. How are provider/model credentials configured?
15. How are permission requests represented and resolved?
16. What OpenCode features have no equivalent in the current Axiom/Claude Code abstraction?
17. Which Claude Code features currently exposed by Axiom cannot be implemented through OpenCode?
18. Which OpenCode capabilities are worth adding to Axiom beyond the existing Claude Code feature set?

## 31. Initial recommendation

Proceed with the OpenCode HTTP integration, but make the first implementation milestone a **real-server protocol spike** rather than immediately implementing the production adapter.

The intended production architecture should be:

    Axiom
      |
      +-- AgentRuntime abstraction
              |
              +-- ClaudeCodeRuntime
              |
              +-- OpenCodeRuntime
                        |
                        | HTTP
                        v
                  One long-lived
                  OpenCode server
                        |
             +----------+----------+
             |          |          |
          Session A  Session B  Session C

Use asynchronous prompts plus event streaming for interactive operation. Use session/message/status APIs as authoritative state and recovery mechanisms. Keep OpenCode-specific protocol details isolated behind the OpenCode runtime adapter.

Most importantly, validate event streaming, concurrent-session behavior, and workspace isolation against the exact OpenCode version before treating this architecture as production-ready.

## Sources

Official OpenCode server documentation:
https://opencode.ai/docs/server/

Official OpenCode CLI documentation:
https://opencode.ai/docs/cli/

Recent OpenCode event-stream issue reports:
https://github.com/anomalyco/opencode/issues/46733
https://github.com/anomalyco/opencode/issues/38458
https://github.com/anomalyco/opencode/issues/36441
https://github.com/anomalyco/opencode/issues/36443
