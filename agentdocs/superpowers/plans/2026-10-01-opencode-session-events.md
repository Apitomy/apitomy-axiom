# OpenCode Session Info, Slash Commands, /clear, Retry Status and Subagents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Close the remaining event gaps between OpenCode and Claude Code sessions (GitHub issue #379, epic #387):
- **`session_init`:** include slash commands and tools, so the chat input can autocomplete them.
- **Slash commands:** `/name args` runs the opencode command.
- **`/clear`:** starts a fresh opencode session and emits `conversation_reset`.
- **Retries:** provider retry status is shown as a notice.
- **Subagents:** opencode `task` subagents appear as subagent cards, with progress and permissions, as Claude
  subagents already do.

Todos were already done in #382.

**Tech Stack:** Java 21, Jackson, JUnit 5, JDK `HttpServer` fakes; small TypeScript change; opencode 1.18.33.

**Spec:** GitHub issue #379.

## Background (verified facts, opencode 1.18.33, 2026-10-01)

- **`GET /command`:** a JSON array of `{name, description, template, source, hints, subtask?}`. It has 17 entries
  here: `init`, `review`, and the user's skills.
- **`GET /experimental/tool/ids`:** `["invalid","question","bash","read","glob","grep","edit","write","task",
  "webfetch","todowrite","websearch","skill","apply_patch"]`. MCP tools are not included.
- **`POST /session/{id}/command`:**
  - Body schema: `{command (required), arguments (required, string), model? (string "provider/model"), agent?,
    messageID?, variant?, parts?}`.
  - It has no `system` field, so command turns run without Axiom's template system prompt. This is acceptable:
    commands bring their own template.
  - It is **synchronous**: it returns when the command's turn finishes. Run it off the caller's thread.
- **Subagent capture** (`/tmp/opencode/subagent-events.jsonl`; the prompt used the task tool with the explore
  subagent). Event order:
  1. Parent `tool task pending` (callID C).
  2. Child `session.created`, with `info.parentID` = the parent session and title
     `"Find txt files (@explore subagent)"`.
  3. Parent `tool task running`, with `input {description, prompt, subagent_type}` and
     `metadata {parentSessionId, sessionId: <child>, model}`.
  4. Child: `session.status busy`, then the child's tool parts (`bash` running and completed), then `session.idle`
     (child).
  5. Parent `tool task completed`, with `output` starting `<task id="<child>" state="completed">
     <task_result>…`.
  6. Parent `session.idle`.
- **Subagent permission capture** (`/tmp/opencode/subperm-events.jsonl`): `permission.asked` carries the **child**
  `sessionID`. Replying through `POST /session/{PARENT}/permissions/{id}` **worked**: `permission.replied`
  followed and the child's bash completed. No session mapping is needed for replies.
- **Today the driver drops all child-session events** (`isCurrentSessionEvent`), so subagents are invisible.
- **`session.status`:** `{sessionID, status:{type:"busy"|"idle"|"retry", …}}`. Per the opencode docs, a retry has
  `{type:"retry", attempt, message, next}`. A retry could not be triggered for capture, so handle it
  defensively.
- **UI contract for subagent events** (from the Claude parser and `AssistantChatPanel.tsx`):
  - `subagent_started {toolUseId, taskId, description, subagentType}`
  - `subagent_progress {toolUseId, taskId, description, lastToolName, toolCount, durationMs}`
  - `subagent_completed {toolUseId, taskId, status, summary}`
  - `permission_request` with `subagentToolUseId` is routed to the subagent card.
  - `toolNames.ts` maps `task` → agent, so clicking the `task` block opens the card.
- **`/clear`:** the UI sends the text `/clear` as a message and clears its own view. Claude's CLI emits
  `conversation_reset`; `AssistantSession` clears its history on it and keeps a cost baseline.
- **`session_init`** is emitted by the OpenCode driver after the model is resolved (#377), as `{model, engine}`.
  The UI reads `slashCommands` when it is an array.

**Rulings:**
- **`session_init`:** add `slashCommands` (the command names, without the `/`) and `tools` (the tool ids minus
  `invalid`). Fetching either is best effort: if it fails, leave that field out.
- **Slash commands:**
  - A user message whose first token is `/<name>`, where `<name>` is in the command list fetched at start, runs
    `POST /session/{id}/command` with `{command:name, arguments:<rest, trimmed>}`, plus `model` when an effective
    model is set.
  - It runs on a virtual thread with a 10-minute timeout. A failure emits `session_error`
    `{name:"CommandFailed", message}`.
  - Other `/x` messages are sent as normal prompts.
  - Session events still arrive through SSE as usual.
- **`/clear`** (exact text after trimming):
  1. `POST /session` to create a new opencode session with the same title.
  2. Switch `openCodeSessionId` to it.
  3. Clear the per-session normalizer state by creating a new normalizer instance.
  4. Emit `conversation_reset {}` through `eventSink`.
  5. Delete the old opencode session (best effort).

  No prompt is sent.
- **Retries:** `session.status` with `type:"retry"` (current session only) → `session_error`
  `{name:"ProviderRetry", message:"Retrying (attempt N): <message>"}`. `busy` and `idle` stay ignored.
  `ProviderRetry` is added to the UI's non-terminal notice set.
- **Subagents:**
  - The driver accepts events from child sessions: sessions whose `session.created` has `info.parentID` equal to
    the current session, or whose id appears as `metadata.sessionId` on a parent `task` part.
  - Child events go to a new normalizer method, `normalizeChild(eventName, payload, childSessionId)`.
  - **Parent `task` part** with `metadata.sessionId` (first time seen) → `subagent_started {toolUseId=callID,
    taskId=childSessionId, description=input.description, subagentType=input.subagent_type}`. This is in addition
    to the existing `tool_use`.
  - **Parent `task` part completed or error** → `subagent_completed {toolUseId, taskId, status:
    "completed"|"failed", summary}`, in addition to the existing `tool_result`. `summary` is the `<task_result>`
    inner text if present, else the output (truncated to 2000 characters).
  - **Child tool parts:** the first non-pending update per child callID → `subagent_progress {toolUseId=parent
    callID, taskId=child, description=state.title or input.description or tool name, lastToolName=tool,
    toolCount=count of distinct child tool calls so far, durationMs=now − subagent start}`.
  - **Child `permission.asked` / `permission.updated`** → a `permission_request` mapped as today, plus
    `subagentToolUseId=<parent callID>`, when the child is known. If it isn't known, send it without that field
    (inline).
  - **All other child events** are ignored: text, reasoning, `session.idle`, `session.status`, `todo.updated`,
    `session.error` and `message.updated` (no cost double-count). A child `session.idle` must **not** produce
    `turn_complete`.
  - Raw events from children still go to the raw log; that already happens before filtering.

## Global Constraints

- Java: 4-space indentation; Javadoc on public members; explicit types; JUnit 5. TypeScript: surrounding style.
- No REST/OpenAPI changes. Claude behaviour is unchanged.
- Every prompt the driver posts keeps the system prompt (#370). The command endpoint can't carry it (ruled).
- Commit style: conventional commits. No AI attribution. No closing keywords directly before issue numbers. Don't
  commit `ui/package-lock.json` or `dist`.
- Tests: `mvn -q -pl app test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; `cd ui && npm run build`.
  A JBoss LogManager warning is pre-existing noise.

---

### Task 1: session_init, slash commands, /clear, retry notice

**Files:**
- `OpenCodeAssistantClient.java`:
  - `listCommands()` → `List<String>`
  - `toolIds()` → `List<String>`
  - `runCommand(sessionId, command, arguments, model)`, with a 10-minute timeout
  - `createSession` already exists; `deleteSession` already exists
- `OpenCodeInteractiveSessionDriver.java`
- `OpenCodeEventNormalizer.java` (retry mapping)
- `ui/src/components/assistant/AssistantChatPanel.tsx`: add `"ProviderRetry"` to `NON_TERMINAL_SESSION_ERRORS`
- Tests: client tests with a fake server, `OpenCodeInteractiveSessionDriverTest`, `OpenCodeEventNormalizerTest`

**Steps:**
- [ ] **1. Failing tests (write fully):**
  - **Client:** `listCommands` parses names from an array of objects; `toolIds` parses a string array; both throw
    `IllegalStateException` on HTTP 500. `runCommand` posts `{command, arguments, model}` to
    `/session/s/command`, and leaves out `model` when it is null.
  - **Driver**, with the fake server extended to serve `/command` and `/experimental/tool/ids` and to record
    `/session/session-1/command` bodies and extra `POST /session` calls:
    1. `session_init` contains `slashCommands == ["init","review"]` and `tools` excluding `invalid`.
    2. `/command` returns 500, so `session_init` has no `slashCommands` and the session still starts.
    3. `sendUserMessage("/review main")` posts a command body `{command:"review", arguments:"main"}`, and **no**
       `prompt_async`.
    4. `sendUserMessage("/unknown x")` goes to `prompt_async` with the original text.
    5. `sendUserMessage("/clear")`:
       - a second `POST /session` happens;
       - a `DELETE` of the old session happens;
       - `conversation_reset` is emitted;
       - no prompt is sent;
       - a following `sendUserMessage("hi")` posts `prompt_async` to the **new** session id. Make the fake return
         `session-2` for the second create, and register its `prompt_async` path.
    6. The command endpoint returns 500, so exactly one `session_error` `CommandFailed` is emitted.
  - **Normalizer:** `session.status` with `{"type":"retry","attempt":2,"message":"rate limited","next":…}` →
    `session_error` `{name:"ProviderRetry", message containing "attempt 2" and "rate limited"}`. `busy`/`idle` →
    empty.
- [ ] **2. Implement per the Rulings.**
  - Fetch the command names and tool ids in `start()`, next to `resolveModel` (best effort, inside the same
    never-fail wrapper). Store the command names in a `volatile Set<String>`.
  - For `/clear`, replace the normalizer field (make it non-final and volatile) with
    `normalizerFactory.get()`. The existing constructors pass `OpenCodeEventNormalizer::new`.
  - Remove `"session.status"` from `IGNORED_EVENT_TYPES` and map only the `retry` case.
- [ ] **3. UI:** add `"ProviderRetry"` to the non-terminal set.
- [ ] **4. Run:** `-Dtest='OpenCode*Test'` 3 times; `cd ui && npm run build`.
- [ ] **5. Commit:** `feat(assistant): OpenCode slash commands, /clear and session info (#379)`

---

### Task 2: OpenCode subagents

**Files:**
- `OpenCodeInteractiveSessionDriver.java` (accept and route child-session events)
- `OpenCodeEventNormalizer.java` (`normalizeChild`, plus the parent `task` part extras)
- Fixtures: copy `/tmp/opencode/subagent-events.jsonl` → `1.18.33-subagent.jsonl` and
  `/tmp/opencode/subperm-events.jsonl` → `1.18.33-subagent-permission.jsonl` in
  `app/src/test/resources/opencode/events/`, and add README rows.
- Tests: `OpenCodeEventNormalizerTest`, `OpenCodeInteractiveSessionDriverTest` (replay)

**Interfaces:**
- `public List<SseEvent> normalizeChild(String eventName, JsonNode payload, String childSessionId)`
- `public void registerChildSession(String childSessionId)` (when the driver sees a child via `session.created`)
- `public Set<String> childSessionIds()` (read-only view)

The normalizer learns children from parent `task` metadata. The driver asks the normalizer, or keeps its own set
fed by both sources; pick one owner and document it.

**Steps:**
- [ ] **1. Failing tests:**
  - **Driver replay of `1.18.33-subagent.jsonl`** (rewrite the parent session id to `session-1`; leave the child
    id as is). The `eventSink` sequence of subagent events must be exactly:
    1. `subagent_started`: toolUseId = the task callID, taskId = the child id, description `Find txt files`,
       subagentType `explore`.
    2. One or more `subagent_progress` with `lastToolName:"bash"`, then `toolCount:1`.
    3. `subagent_completed`: status `completed`, with a non-empty summary.

    The parent's `tool_use`/`tool_result` for `task` are still present. Exactly **one** `turn_complete` (the
    parent's). No `unhandled_event`. No `assistant_text` from the child.
  - **Driver replay of `1.18.33-subagent-permission.jsonl`:** the auto-approval sink receives exactly one
    `permission_request`, with `subagentToolUseId` = the parent task callID and `toolName:"bash"`.
  - **Normalizer unit test:** a child permission for an unknown child gives a `permission_request` without
    `subagentToolUseId`.
- [ ] **2. Implement per the Rulings.**
  - In `handleRawEvent`, when the event's session is a known child, call `normalizeChild` and route the result
    like the other events (`permission_request` → autoApprovalSink, else → eventSink).
  - Register a child on `session.created` whose `properties.info.parentID` equals the current session id.
  - `respondToPermission` keeps using the parent session id (verified to work).
- [ ] **3. Run:** `-Dtest='OpenCode*Test'` 3 times, then the full `mvn -q -pl app test`.
- [ ] **4. Commit:** `feat(assistant): show OpenCode subagents as subagent cards (#379)`
- [ ] **5. Docs:** in the OpenCode part of the `!!! note` block in `docs/user-guide/ai-assistant.md` (4-space
  indent, at most 110 columns), add that slash commands, `/clear` and subagents work for OpenCode, and that
  commands run without the template system prompt. Commit `docs(assistant): document OpenCode commands, /clear
  and subagents (#379)`.
- [ ] **6. Manual (human):**
  1. Type `/` in an OpenCode session. The command list appears. Run `/init` or another cheap command.
  2. Ask it to use the task tool with the explore subagent. A subagent card appears with progress and completes.
  3. Run `/clear`. The conversation resets, and the next message starts fresh.
