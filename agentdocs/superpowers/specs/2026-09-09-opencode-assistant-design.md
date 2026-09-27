# OpenCode Interactive Assistant Integration Design

## 1. Overview

This document defines the architecture for adding OpenCode support to Apitomy Axiom's interactive
AI Assistant.
The goal is to preserve the existing Assistant user experience and browser API contract while
enabling OpenCode as an interchangeable runtime behind the same Assistant endpoints.

The design choices in this document reflect explicit product decisions:

- Delivery target: feature parity with the current Claude Code Assistant UX.
- Server lifecycle model: one long-lived OpenCode server process per Axiom interactive session.
- Version policy: best-effort support for latest OpenCode releases.
- Compatibility policy: fail closed when required interactive capabilities are unavailable.

## 2. Goals and Non-Goals

### 2.1 Goals

- Keep the current Assistant frontend event contract stable.
- Add OpenCode as a runtime for interactive Assistant sessions.
- Preserve template-driven behavior (system prompt, tools, MCP, env, working directory, init scripts).
- Isolate OpenCode protocol details behind dedicated adapter boundaries.
- Guarantee deterministic session behavior and strong per-session isolation.
- Fail at session startup when compatibility checks do not pass.

### 2.2 Non-Goals

- Rewriting the Assistant frontend to OpenCode-native event names.
- Introducing degraded runtime mode as default behavior.
- Building a cross-engine refactor of all task execution paths in the same change.
- Supporting direct browser access to OpenCode HTTP endpoints.

## 3. Current State Summary

The current Assistant implementation is centered around `AssistantSessionManager` and `AssistantSession`, with
Claude-oriented stream parsing in `AssistantEventParser`. Session creation includes template resolution,
working
directory setup, optional init scripts, MCP config generation, and event replay support.

Relevant code references:

- `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java`
- `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantEventParser.java`
- `docs/user-guide/ai-assistant.md`

There is already an OpenCode agent implementation for non-interactive executions under:

- `agents/opencode/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeAgent.java`
- `agents/opencode/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeServerManager.java`
- `agents/opencode/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeClient.java`

This design intentionally introduces Assistant-specific OpenCode integration components rather than coupling
tightly
to non-interactive execution assumptions.

## 4. High-Level Architecture

### 4.1 Core Direction

Retain `AssistantSessionManager` as the orchestration entry point and introduce a driver abstraction for
interactive
runtime behavior. The manager continues to own template/workdir/MCP/init-script concerns and delegates runtime
execution details to engine-specific drivers.

### 4.2 Driver Boundary

Introduce an `InteractiveSessionDriver` abstraction with at least two implementations:

- `ClaudeInteractiveSessionDriver`: wraps current Claude subprocess interactive behavior.
- `OpenCodeInteractiveSessionDriver`: implements OpenCode HTTP + SSE behavior.

Add an `InteractiveSessionDriverFactory` that resolves the concrete driver based on active engine selection.

### 4.3 OpenCode Session Isolation

For OpenCode-backed assistant sessions:

- Start one dedicated `opencode serve` process per Axiom assistant session.
- Create one OpenCode session ID tied to that process.
- Route all prompts, permissions, and control operations through that dedicated pair.
- Tear down both OpenCode session state and process when the Axiom session ends.

This yields strong isolation and minimizes cross-session event multiplexing complexity.

## 5. Component Design

### 5.1 AssistantSessionManager (existing, adapted)

Responsibilities retained:

- Session registry and max-session limit enforcement.
- Template resolution and project context injection.
- Session directory and working directory lifecycle.
- Init script execution.
- MCP config generation and auto-inclusion behavior.
- Generated-item validation and apply workflow.
- SSE bridge emission to browser subscribers.

New responsibility:

- Runtime driver orchestration through `InteractiveSessionDriverFactory`.

### 5.2 InteractiveSessionDriver (new)

Proposed responsibilities and methods:

- `start()`: start runtime resources and enter ready state.
- `sendUserMessage(String message)`: submit a new user turn.
- `respondToPermission(...)`: map UI permission decisions to runtime protocol.
- `cancelActiveTurn()`: perform idempotent stop behavior.
- `stop()`: close resources and terminate runtime.
- `getStatus()`: expose session runtime state.
- `setEventSink(...)`: register normalized event callback into Assistant session history.

The interface must emit only normalized event payloads expected by current frontend consumers.

### 5.3 OpenCodeSessionServerProcess (new)

Assistant-scoped process manager for OpenCode that differs from global-server assumptions:

- Spawns `opencode serve` with loopback binding.
- Allocates and tracks a dedicated port.
- Waits for health readiness.
- Captures startup and runtime diagnostics (stdout/stderr ring buffer).
- Performs graceful shutdown, then force-kill timeout fallback.

### 5.4 OpenCodeAssistantClient (new)

Assistant-focused HTTP/SSE client wrapper supporting:

- Health checks.
- Session create/delete.
- Prompt submission (prefer async prompt APIs when available).
- Message/status/diff retrieval for reconciliation.
- Permission response API operations.
- Abort operations.
- SSE stream connect/reconnect handling.

This component should remain isolated from non-interactive OpenCode task-execution codepaths.

### 5.5 OpenCodeCapabilityProbe (new)

Per-session startup probe that validates required capabilities before session enters running state.

Checks include:

- Required endpoint availability and expected status codes.
- Prompt submission viability.
- Event stream viability for required turn semantics.
- Permission request/response protocol viability.
- Abort viability.

Probe output is a structured compatibility report used for fail-closed gate decisions.

### 5.6 Event Normalization Layer (new)

Translate OpenCode runtime events into existing Assistant semantic event types, including:

- `assistant_text`
- `tool_use`
- `tool_result`
- `permission_request`
- `turn_complete`
- `thinking`
- other currently supported session-level events as needed for parity

Frontend-facing event names remain unchanged.

## 6. Data Flow and Lifecycle

### 6.1 Session Creation

1. User requests new Assistant session.
2. Manager resolves template, directories, env, and MCP config.
3. Manager creates `AssistantSession` and selects driver through factory.
4. OpenCode driver starts dedicated OpenCode process.
5. Driver waits for `/global/health` readiness.
6. Driver runs capability probe.
7. Driver creates OpenCode session and establishes event subscription.
8. Manager marks session running and emits initial session events.

If steps 4-7 fail, creation fails and cleanup runs; session never enters running state.

### 6.2 Prompt Turn

1. Browser sends user message.
2. Manager delegates to driver.
3. Driver enforces single in-flight turn semantics.
4. Driver submits prompt (async preferred, compatibility-dependent).
5. Driver receives OpenCode events.
6. Driver normalizes events and forwards to Assistant session history/listeners.
7. Browser receives existing Axiom SSE event types unchanged.

### 6.3 Permission Turn

1. OpenCode emits permission-related event.
2. Driver normalizes and emits `permission_request`.
3. UI decision (allow/deny/auto-rule) routes back through manager to driver.
4. Driver posts protocol-correct response to OpenCode.
5. Turn continues and completion event resolves UI state.

### 6.4 Stop/Cancel

1. User triggers stop.
2. Manager calls driver cancel.
3. Driver calls OpenCode abort endpoint idempotently.
4. Driver emits deterministic terminal-state event sequence for UI consistency.

### 6.5 Destroy Session

1. Manager requests driver stop.
2. Driver closes SSE, aborts active turn if needed, disposes OpenCode session, and stops process.
3. Manager records usage and deletes session directory as currently implemented.

## 7. Compatibility and Reliability Policy

### 7.1 Fail-Closed Contract

Assistant sessions backed by OpenCode start only when required capabilities are validated.
If required behavior is not present for the running OpenCode build, session creation is rejected
with explicit compatibility diagnostics.

### 7.2 Best-Effort Latest Strategy

The system does not pin a single OpenCode version for assistant use. Instead, each session startup validates
actual
runtime behavior through probes and rejects incompatible runtimes.

### 7.3 Error Taxonomy

Use structured error categories for diagnostics and UI mapping:

- `OPENCODE_NOT_INSTALLED`
- `SERVER_START_TIMEOUT`
- `CAPABILITY_PROBE_FAILED`
- `EVENT_STREAM_UNRELIABLE`
- `PERMISSION_PROTOCOL_UNSUPPORTED`
- `SESSION_CREATE_FAILED`
- `PROMPT_SUBMIT_FAILED`
- `ABORT_FAILED`
- `SESSION_RUNTIME_CRASH`

These categories should be emitted in backend logs and mapped to concise user-facing errors.

### 7.4 SSE Reliability Rules

- Support bounded reconnect attempts on transient disconnects.
- Detect stale streams (connected but missing required turn events during active execution).
- On reliability threshold breach, fail session rather than silently degrading.
- Reconcile state via message/status endpoints when reconnect succeeds.

### 7.5 Concurrency Rules

- Cross-session concurrency: allowed and isolated by process-per-session model.
- Intra-session concurrency: one prompt at a time; subsequent prompts must queue or reject deterministically.

## 8. Security and Operational Controls

- Bind all OpenCode session servers to `127.0.0.1`.
- Keep browser traffic routed through Axiom backend only.
- Pass only required environment variables to OpenCode processes.
- Redact sensitive values in diagnostics.
- Treat max assistant sessions as max concurrent OpenCode processes.
- Add assistant-opencode config for startup timeout, probe strictness, optional port range, and diagnostic
  verbosity.

## 9. Testing Strategy

### 9.1 Protocol Verification Harness (Phase 1)

Before full integration, run a real-server protocol harness that validates:

- process startup and health
- session create/delete
- prompt submission
- event stream semantics needed for parity
- permission roundtrip
- abort behavior
- stale-stream failure detection

This gate must pass before production adapter rollout.

### 9.2 Driver Unit Tests (Phase 2)

- Event normalization correctness.
- Permission correlation and response mapping.
- Single in-flight prompt enforcement.
- Capability probe decision matrix behavior.
- Error taxonomy mapping.

### 9.3 Backend Integration Tests (Phase 3)

- Session create/send/receive/stop/destroy through existing Assistant APIs.
- SSE payload contract compatibility from browser perspective.
- Usage recording and filesystem cleanup.
- Session limit enforcement with process-per-session behavior.

### 9.4 End-to-End Parity Validation (Phase 4)

- Template-driven behavior parity.
- Permission UX parity.
- Config Assistant workflow parity.
- Deterministic failure behavior when compatibility checks fail.

### 9.5 Operational Hardening (Phase 5)

- Concurrent session stress under configured max.
- Mid-turn process crash behavior and terminal state guarantees.
- Reconnect and reconciliation reliability checks.
- Diagnostics and metrics verification.

## 10. Migration and Rollout

- Keep Claude interactive path fully available as fallback.
- Enable OpenCode interactive path via engine selection and optional assistant feature guard.
- Start with internal dogfooding, then wider availability after parity and reliability thresholds pass.
- Preserve frontend contract to avoid immediate UI migration risk.

## 11. Open Questions to Resolve During Planning

- Exact OpenCode endpoints and payload shapes for permission decisions in target builds.
- Preferred event endpoint selection logic when multiple event endpoints exist.
- Optional use of `/instance/dispose` vs process termination for shutdown.
- Port allocation policy and collision strategy for multiple concurrent assistant sessions.
- Minimum diagnostic payload required for user-facing compatibility failures.

## 12. Acceptance Criteria

The design is considered successfully implemented when:

- OpenCode-backed Assistant sessions provide feature parity with current Claude-based UX for required flows.
- One OpenCode process is launched per Assistant session and is terminated when that session ends.
- Compatibility probe gate blocks incompatible runtimes before session run state.
- Browser-visible SSE event schema remains compatible with existing frontend consumption.
- Stop/permission/reconnect/error behavior is deterministic and covered by automated tests.
