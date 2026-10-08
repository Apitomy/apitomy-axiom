# The AI Configuration Assistant: How It Works (2026-10-08)

The Configuration Assistant is a **chat session with a coding agent** (Claude Code CLI or OpenCode) running in
a private working directory. The agent learns Axiom's configuration format from a generated reference. It
discovers existing items through an Axiom MCP server, validates drafts through that server, and writes one
JSON file per item. Axiom re-validates the files and feeds problems back into the chat. When the user clicks
**Apply All**, the files are imported as one transactional configuration pack.

The new Developer Experience will do *similar* things, so this document also records which parts are generic
and which are hard-wired to the config assistant.

Paths below are relative to the repository root. `assistant/` means
`app/src/main/java/io/apitomy/axiom/app/assistant/`.

## 1. Architecture at a glance

```
UI (AssistantSessionPage)                     Axiom (Quarkus)                              Agent process
───────────────────────                       ────────────────                             ─────────────
POST /assistant/sessions ───────────────────► AssistantSessionManager.createSession
                                                ├─ template (SessionTemplateService)
                                                ├─ session dir + workDir/<7 item folders>
                                                ├─ system prompt = template + {{reference}}
                                                ├─ MCP config (@axiom-assistant → server.js)
                                                └─ InteractiveSessionDriver.start() ─────────► claude --print stream-json
                                                                                               or `opencode serve`
POST /messages ─────────────────────────────► AssistantSession.sendMessage ─────────────────► user turn
global SSE "assistant-session-event" ◄──────── normalized events ◄──────────────────────────── stream-json / OpenCode SSE
                                                                                               │ MCP tool calls
                                              REST /api/v1/... ◄──── axiom-assistant MCP ◄─────┘ (list/get/validate)
                                              validation listener (on every tool_result):
                                                re-validate workDir/**.json ─► feedback as a user message
GET /items, /items/{type}/{name} ───────────► list + validate files; preview modals
POST /apply ────────────────────────────────► AssistantPackBuilder ─► ImportExportService.importOrUpdatePack
                                              (transactional) ─► destroySession
```

## 2. Main classes

| Class | Responsibility |
|-------|----------------|
| `assistant/AssistantSessionManager` | Coordinator: create/destroy/list sessions, engine choice, prompt building, MCP resolution, validation listener, items, validate, apply, startup cleanup (10-minute grace), shutdown |
| `assistant/AssistantSession` | One session: in-memory event history, listeners, permission auto-approval (allow-all, per-subagent allow-all, regex rules), cost and token totals; hands runtime work to a driver |
| `assistant/AssistantContextBuilder` | Session directory, working directory (item subfolders only for the config assistant), MCP config file |
| `assistant/AssistantReferenceGenerator` | Builds the Markdown reference that replaces `{{reference}}`, cached |
| `assistant/AssistantItemValidator` | Wraps `ConfigItemValidationService`; validates a file or an item against a *working-directory* context |
| `assistant/ValidationFeedbackTracker` | Suppresses repeated feedback; detects the transition to "all valid" |
| `assistant/AssistantPackBuilder` | Turns `workDir/<type>/*.json` into a configuration pack; `itemCount` |
| `assistant/SessionTemplateService` | Built-in templates from the classpath (reloaded at every startup) plus user templates in the database |
| `assistant/AssistantEventParser` | Converts Claude stream-json into normalized events |
| `assistant/runtime/InteractiveSessionDriver` (+ factory) | Engine abstraction: `start`, `sendUserMessage`, `respondToPermission`, `interrupt`, `destroy` |
| `assistant/runtime/ClaudeInteractiveSessionDriver` | `claude` subprocess, NDJSON on stdin; interrupt is a `control_request` (SIGINT is ignored in stream-json mode) |
| `assistant/runtime/opencode/*` | One `opencode serve` per session (ephemeral port, random password), capability probe, HTTP client, event normalizer, `opencode.json` writer, permission mapping |
| `app/.../rest/AssistantResourceImpl` | REST endpoints, error mapping (409 limit, 422 compatibility or validation) |
| `app/.../ImportExportService.importOrUpdatePack` | Validates the pack, then upserts every item by name in one transaction |

## 3. Session lifecycle

### Create (`AssistantSessionManager.createSession`)
1. **Template.** Unknown templates return 404.
2. **Engine.** Template `engine`, then `SystemConfigEntity.defaultEngine`, then `axiom.agent.default-type`.
   Only engines with `supportsInteractiveSessions()` work: **claude-code and opencode, not copilot**.
3. **Slot.** An atomic counter is checked against `axiom.assistant.max-sessions` (12 in
   `application.properties`, 3 in code). Exceeding it returns 409.
4. **Subject (optional).** Adds `AXIOM_SUBJECT_ID`, `_NAME`, `AXIOM_REF` and `AXIOM_REPOSITORY` to the
   environment, together with the template `environment` (secrets resolved).
5. **Directories.**
   - Session directory: `${axiom.home}/assistant/sessions/<uuid>/`.
   - Working directory: the template `workingDirectory`, else the subject workspace, else
     `<sessionDir>/workDir/`. The last gets `tools/ action-types/ toolsets/ session-templates/ event-sources/
     subscriptions/ workflows/`.
6. **Tools and MCP.** `allowedTools` are resolved (expanding `@Toolset`). MCP aliases are resolved and written
   to `mcp-config.json`, or to `opencode.json` (mode `rw-------`) for OpenCode.
7. **Init script (optional).** `init.sh` or `init.js`, 60-second timeout.
8. **System prompt.** The template `systemPrompt`, with `{{reference}}`, `{{subjectContext}}` and
   `{{subjectName}}` replaced. Claude receives it through `--append-system-prompt`; OpenCode as the `system`
   field of each prompt. **No CLAUDE.md or AGENTS.md is written, and no example files are copied**: the
   examples are embedded in the reference text.
9. **Driver start.** The welcome message is added as a synthetic `assistant_text` event. A listener forwards
   every event to the global SSE channel. For the config assistant, the validation listener is attached. The
   template's `initialMessage` is sent, if any.

### Directory layout
```
${axiom.home}/assistant/sessions/<uuid>/
  mcp-config.json | opencode.json     tools.json (if @axiom-tools)     raw-events.jsonl (every raw runtime line)
  workDir/tools/ action-types/ toolsets/ session-templates/ event-sources/ subscriptions/ workflows/
${axiom.home}/assistant-mcp-server/   (server.js + npm install, redone on the first session after each restart)
${axiom.home}/mcp-server/             (tools-server.js, sdk-server.js; McpConfigGenerator)
```

### Messages, permissions, interrupt
- `POST /messages` records a `user_message` event, then calls the driver.
- Permission requests become `permission_request` events. The session auto-approves first; anything left is
  answered from the UI with `POST /permissions {permissionId, allow, always, updatedInput}`.
  `AskUserQuestion` is never auto-approved.
- `POST /interrupt`: Claude gets a control request; OpenCode gets an abort, which also drops queued prompts.

### Normalized event types (identical for both engines)
`session_init`, `assistant_text`, `thinking`, `tool_use`, `tool_result`, `tool_progress`, `todos`,
`turn_complete` (cost, tokens, duration, `sessionCostUsd`), `permission_request`, `permission_resolved`,
`user_message`, `subagent_started|progress|status|completed`, `background_task_started`, `conversation_reset`
(`/clear`), `session_error`, `session_ended`, `card_dismissed`, `allow_all_changed`,
`subagent_allow_all_changed`, `unhandled_event`.

### Transport and history
- **What the UI uses:** the global SSE channel (`/api/v1/sse`), event `assistant-session-event`, with payload
  `{sessionId, eventType, eventData, eventIndex}`. On load and on reconnect, the UI calls `GET /history` and
  removes duplicates by `eventIndex`.
- A per-session SSE endpoint (`GET /assistant/sessions/{id}/events`, with `Last-Event-Id` replay and
  heartbeats) exists but the UI does not use it.
- **History is in memory only.** It is lost on destroy or server restart. Only cost and usage are persisted
  (`AiUsageEntity`). `GET /raw-events` returns `raw-events.jsonl` for debugging.

### Destroy and cleanup
- `destroySession` stops the driver, records usage, then deletes the session directory. It runs after a
  successful Apply, on delete, and at shutdown.
- At startup, leftover session directories are deleted only if nothing in them changed in the last 10 minutes,
  and a WARN is logged.

## 4. MCP: the `axiom-assistant` server

- **Code:** `app/src/main/resources/templates/axiom-assistant-mcp/server.js`, a stdio MCP server using
  `@modelcontextprotocol/sdk`. It calls Axiom REST at `AXIOM_API_URL` with no authentication.
  `AXIOM_ASSISTANT_SESSION_ID` is passed in so that validation runs against this session's working directory.
- **Discovery tools:** list/get for tools, action types, toolsets, MCP servers, session templates, event
  sources, subscriptions and workflow definitions; `axiom_list_secrets` (names only); subjects; actors.
  `get_workflow_definition` and `get_subscription` take numeric IDs, but updates must reuse the *name*.
- **`axiom_validate_item(type, item)`:** calls `POST /assistant/sessions/{id}/validate` and returns `VALID`,
  `VALID with warnings` or `INVALID` with `- [SEVERITY] path code: message` lines.
- **Subject tools** (only when a subject is attached): details, tasks, thread, events, traces.
- **Aliases in templates:**
  - `@axiom-assistant` → this server, as config key `axiom`, so tools are named `mcp__axiom__axiom_*`.
  - `@axiom-tools` → the user's script tools.
  - `@axiom-sdk` → SDK functions.
  - Any other name → an `McpServerEntity` (stdio or HTTP).

  Servers are also added automatically when `allowedTools` names them (`mcp__axiom__*` and so on).

## 5. Session templates (`app/src/main/resources/templates/assistant-templates/`)

| templateId | MCP | allowedTools |
|------------|-----|--------------|
| `axiom-config-assistant` | `@axiom-assistant` | `Read/Write/Edit(*)`, `Bash(ls/cat/find/jq *)`, all `mcp__axiom__axiom_*` list/get tools and `axiom_validate_item` |
| `general-assistant` | none | Read, Write, Edit, Bash, WebSearch, WebFetch |
| `subject-assistant` | `@axiom-assistant` | Read-only shell, git and gh; web tools; `mcp__axiom__axiom_subject_*` |

- **Template fields:** `templateId`, `name`, `description`, `systemPrompt`, `welcomeMessage`,
  `initialMessage`, `workingDirectory`, `mcpServers`, `allowedTools`, plus optional `model`, `engine`,
  `initScript(Type)` and `environment`.
- **The config-assistant system prompt** contains:
  1. The working loop: Discover, Draft, Validate (`axiom_validate_item`), Fix, Write `<dir>/<name>.json`, read
     Axiom's feedback.
  2. Concepts: choosing the execution mode (prefer **node** for non-AI steps); action types run only from
     workflow nodes; receive-event nodes must correlate (`correlationKey`, `match`, deliberate `lookback`,
     `timeout` with an `isTimeout` edge); human-task `title`; event sources and subscriptions are created
     disabled; `${secret:NAME}`; declare inputs for everything an action uses; never describe the output
     format in prompts.
  3. Updating existing items: write the complete item under the same name.
  4. File layout.
  5. `{{reference}}`.

## 6. The generated reference (`AssistantReferenceGenerator`)

The reference is generated from code wherever possible, so it stays correct as the code changes:

1. **Workflows.**
   - Node types with their config documentation (`NODE_CONFIG_DOCS`; a test fails if a `NodeType` is missing).
   - Edge fields, read by reflection from Flow's `WorkflowEdge` (so `isTimeout` appeared automatically).
   - Graph rules and EL rules.
   - Start-input naming and the built-in inputs (`WorkflowContentRules`).
   - The `sdk:` functions (`SdkFunctionRegistry`).
2. **Action types.**
   - Execution modes and when to choose each.
   - The Node.js contract and its pitfalls.
   - The script output rule.
   - Removed-placeholder hints from `ActionTypeValidator`.
   - Input mapping, field types and `allowedTools` formats.
3. **Events.** `EventType` values, filter variables, event-source fields, GitHub and Jira configuration.
4. **Subscriptions.** Fields and routing types (`create-workflow`, `workflow-dispatch`).
5. **Examples.** Every file listed in `templates/assistant-reference/examples/index.txt`, embedded as fenced
   JSON:
   - One tool and one toolset.
   - Four action types (`triage-issue` agent, `request-more-info` script, `fetch-repo-labels` and
     `add-issue-comment` node).
   - One workflow, `issue-triage`, which shows a conditional branch, a correlated receive-event with look-back
     and a 7-day timeout, and a titled human task.
   - One event source and one subscription.

`AssistantReferenceTest` validates every example with the real validators and checks that key guidance is
present.

## 7. Validation

- **Item types** (`app/.../validation/ConfigItemType`): tool, action-type, toolset, session-template,
  workflow, event-source, subscription. Each type has a JSON name and a directory name.
- **`ConfigItemValidationService.validate(type, item, context)`** returns `ConfigProblem(severity, path, code,
  message)`.
  - Workflows go through Flow's validator plus Axiom's `WorkflowContentRules`: start inputs, input mappings,
    known context keys, event references, and checks that action types exist.
  - Action types go through `ActionTypeValidator`, which also runs `node --check`.
- **`ValidationContextFactory`** provides three contexts:
  - `databaseOnly()`.
  - `forPack(pack)`, used on Apply.
  - `forDirectory(workDir)`: database names plus items in the working directory, including the declared inputs
    of action types that are not yet applied. This is what lets a session write an action type and a workflow
    that uses it before either exists.
- **Three entry points:**
  1. **Before writing:** the agent calls `axiom_validate_item`.
  2. **After every `tool_result`:** the validation listener re-validates all files and sends the problems back
     *as a user message* (`Validation issues in <file>: ... Please fix the errors above.`). When everything is
     clean it sends "All configuration items in this session are valid." The tracker suppresses repeats.
  3. **Item list:** `GET /items` validates every file on each call. Only errors are exposed, not warnings.

## 8. Apply (`AssistantSessionManager.applySession`)

1. If any item has errors, return 422 `{message, errors[]}`. Warnings do not block.
2. If the working directory is missing, or the pack would be empty, return 422 starting "Nothing to apply…"
   and **keep the session**. Apply used to report success with 0 items.
3. Build the pack with `AssistantPackBuilder.fromDirectory`.
4. `ImportExportService.importOrUpdatePack` validates the whole pack first (`forPack`), then upserts by name
   in dependency order: tools, toolsets, action types, session templates, workflows (published in the same
   transaction), event sources, subscriptions. It is a single transaction.
5. Return `AssistantApplyResult` with created and updated counts per type. Then destroy the session; the UI
   shows a summary (`ui/src/pages/assistantApplySummary.ts`) and returns to `/assistant`.

## 9. REST API (`openapi.json`, tag Assistant)

- **Sessions:** `GET/POST /assistant/sessions`, `GET/PATCH/DELETE /assistant/sessions/{id}`.
- **Conversation:** `/messages`, `/interrupt`, `/permissions`, `/auto-approvals[/{ruleId}]`, `/allow-all`,
  `/subagent-allow-all`, `/dismiss-card`, `/history`, `/raw-events`, `/events` (SSE).
- **Items:**
  - `/items`
  - `/items/{itemType}/{itemName}`
  - `/validate` (`{type, item}` → `{valid, problems[]}`)
  - `/apply`

  These are allowed only for the `axiom-config-assistant` template; other templates get 400.
- **Templates:** `/assistant/templates[/{id}]` CRUD. Built-ins return 403.

## 10. UI

- **`ui/src/pages/AssistantSessionPage.tsx`:**
  - Chat panel, generated-items panel, Apply All (disabled when there are no items).
  - Apply error alert and success summary modal.
  - Rename, end session, allow-all and auto-approval rules.
  - Breakout mode.
- **`components/assistant/AssistantChatPanel.tsx`:**
  - A `switch` over the event types.
  - Live SSE events are buffered until history has loaded.
  - Each `tool_result` refreshes the item list.
  - Renders thinking, tool use, todos, subagent and background-task cards, permission prompts,
    `AskUserQuestion` and cost.
- **`components/assistant/AssistantGeneratedItems.tsx`:** the item list with valid/error icons. Each item
  opens a preview modal for its type:
  - `WorkflowDetailModal`: tabs Diagram (the Flow `WorkflowEditor` with **`readOnly`** and the shared
    `useWorkflowEditorSpi()`), Details, Inputs, Source, Problems.
  - `ActionTypeDetailModal`: tabs Details, Prompt/Script/Code, Inputs, Outputs, Problems.
  - Tool, Toolset, SessionTemplate, EventSource and Subscription modals.
  - Shared parts: `FieldsTable`, `ValidationProblemsTab`.
- **`config/api.ts`:** `createAssistantSession`, `sendAssistantMessage`, `fetchAssistantSessionHistory`,
  `fetchAssistantItems`, `fetchAssistantItemContent`, `applyAssistantSession`, permission, approval and
  template functions. **`config/sse.ts`:** `sseClient.subscribeSession(sessionId, cb)`.

## 11. Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `axiom.agent.default-type` | `claude-code` | Engine fallback for sessions |
| `axiom.assistant.max-sessions` | 12 (properties) / 3 (code) | Concurrent sessions |
| `axiom.agent.claude-code.executable` | `claude` | |
| `axiom.assistant.opencode.executable` | `opencode` | Falls back to `axiom.agent.opencode.executable` |
| `axiom.assistant.opencode.server.port` | 0 (ephemeral) | One server per session |
| `axiom.assistant.opencode.startup-timeout-seconds` | 30 | |
| `axiom.agent.opencode.model` | `github-copilot/claude-sonnet-5` | Fallback model for OpenCode sessions |
| `axiom.home` | `~/.axiom` | Session directories and MCP installs live here |

Model: the template `model`. Claude receives `--model`. OpenCode checks it against its provider catalog, then
falls back to `axiom.agent.opencode.model`, then to OpenCode's own default.

## 12. Hard-wiring, pitfalls and lessons

1. **The config assistant's behaviour is keyed to the template ID `"axiom-config-assistant"`** in four places:
   the validation listener, the working-directory subfolders, `requireConfigAssistant` (items, validate,
   apply) and the UI's `isConfigAssistant`. A new AI experience that needs items, validation or apply must
   generalize this, for example as template capabilities.
2. **Validation feedback is injected as user messages.** It appears in the chat as if the user typed it and
   starts a new agent turn. It runs after *every* tool call, including Read and Bash, and re-validates every
   file with database queries each time.
3. **Files in a working directory are the unit of state.** They are simple and inspectable, but external
   deletion loses the work. A real incident: a Quarkus test startup deleted the live session directories, and
   Apply then reported success with 0 items. Both causes are now fixed: tests use their own `axiom.home`, and
   Apply refuses an empty pack.
4. **History is in memory only.** A server restart (including dev-mode restarts) ends every session.
5. **Apply destroys the session.** There is no "apply and keep iterating".
6. **The MCP server has no authentication** and is reinstalled with `npm install` on the first session after
   each restart.
7. **The two engines differ subtly:**
   - Interrupt semantics (Claude control request versus OpenCode abort).
   - OpenCode permission keys (`server_*` wildcards).
   - Claude cannot natively "always allow" a permission.
   - The OpenCode capability probe is cached per version.
8. **Teaching the agent works best from generated reference, validated examples and a short prompt rule
   list.** Examples are copied heavily, so a careless example becomes a careless output. The issue-triage
   example used to wait for *any* comment; it was fixed together with a new prompt rule.
9. **The UI previews** use the same Flow components as the real editor, so the preview equals the result.
10. **Small known issues:**
    - `getItemContent` builds paths from URL parameters; whether path traversal is possible is unverified.
    - `mcp-config.json` is not permission-restricted, unlike `opencode.json`.
    - The item list hides warnings.

User documentation: `docs/user-guide/ai-assistant.md`. It has not been re-checked against the code in this
session.
