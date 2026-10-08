# Workflow Workbench: Integrated AI Developer Experience — Design

Date: 2026-10-08
Status: Approved (brainstorming)
Related: Apitomy Flow spec `apitomy-flow/docs/superpowers/specs/2026-10-08-ai-assisted-editing-design.md`
(commit `c20d5af`), handoff notes in `agentdocs/handoff/`.

## Context

Axiom's Configuration Assistant is a chat that writes JSON files into a private directory and applies them
at the end. The **Workflow Workbench** is an integrated, AI-based experience for composing a workflow
together with the action types and tools that support it. The user and the AI edit the same live drafts,
and their edits interleave throughout the session.

## Decisions

| Topic | Decision |
|---|---|
| Audience | Goal-to-workflow creation first, then continued iteration in the same session ("A-first, then B"). |
| Concurrency | Interleaved turns. The user edits freely; AI changes land as discrete, highlighted, undoable revisions. While the AI works, only the action types and tools it touches in that turn are locked. |
| Draft state | Server-side composition drafts in the database, with a revision history. Publish is partial and the session continues. |
| AI runtime | One long-running interactive session (`AssistantSession` + `InteractiveSessionDriver`, claude-code and opencode). The agent edits only through MCP tools. It has no file-write tools. |
| Workflow edits by AI | Flow `ChangeSet`s, validated server-side with Flow's Java `ChangeSets.apply`, accumulated per turn into one combined change set, delivered to the editor. |
| Layout | A new three-pane workbench page: composition tree, tabbed editors, chat. Focus mode and break-out window. |
| Testing in the loop | v1: run draft action types and tools with sample inputs, and evaluate EL expressions against real events. Follow-up: whole-workflow dry run. |
| Event stream | The agent can list and read real stream events and evaluate EL against them. No redaction in v1. |
| Configuration Assistant | Kept. Both features share a refactored foundation built on template capabilities. |
| v1 item types | Workflow (exactly one per composition), action types, tools. Event sources, subscriptions, toolsets and session templates stay with the Configuration Assistant. |

## Dependencies on Apitomy Flow

The workbench needs the Flow release described in the Flow spec above:

- `contentRevision`: `"sha256:" + hex(SHA-256(JCS(stripLayout(workflow))))`, in TS and Java.
- An imperative `WorkflowEditor` handle: `propose`, `apply`, `replace`, `withdraw`, `clearHighlights`,
  `getSnapshot`.
- `onChange(workflow, { contentRevision, origin })` and `onSelectionChange({ nodeIds, edgeIds })`.
- `ChangeSet` / `ChangeOp`, including `unset`, and the error codes `target-missing`, `duplicate-id`,
  `edge-endpoint-missing`, `malformed`, `read-only`, `stale`.
- A proposal review bar, a lingering highlight after apply (`highlightApplied`), and
  `onProposalResolved`.
- Java `ChangeSets.apply` and `ContentRevision` in `apitomy-flow-engine`.
- `placeNewNodes` for positionless nodes on `apply` and `replace`.

**Axiom rule:** the server computes `contentRevision` over exactly the Flow `Workflow` document it sends to
the editor, with no Axiom-specific fields.

Until the release is published, Axiom develops against a local snapshot build of Flow.

## §1 Architecture

### Composition model (new database tables, added to `V1__baseline.sql`)

- **`CompositionEntity`**:
  - `id`, `name`, `goal` (the user's first request, kept for recaps), `engine`, `model`;
  - `assistantSessionId`;
  - `turnState` (`idle` | `ai-working`) and `currentTurnId`;
  - `applyMode` (`live` | `review`), defaulting to `live`;
  - `createdAt`, `updatedAt`.
- **`CompositionItemEntity`**:
  - `id`, `compositionId`, `type` (`workflow` | `action-type` | `tool`), `name`;
  - `baseItemId` and `baseUpdatedAt`, set when the draft edits an existing item;
  - `content` (JSON) and `contentRevision`;
  - `lastAuthor`, `lastTurnId`, `publishedAt`, `publishedRevision`.
- **`CompositionRevisionEntity`**:
  - `id`, `itemId`, `content`, `contentRevision`;
  - `author` (`user` | `ai`), `turnId` (null for user edits), `createdAt`.

For workflows, `content` is the Flow `Workflow` document and `contentRevision` is Flow's hash. For action
types and tools, `content` is the item JSON and `contentRevision` is the same hash scheme applied to the item
JSON (SHA-256 of the JCS form).

### Shared assistant foundation (refactor)

- Template **capabilities** replace every check of the template ID `axiom-config-assistant`:
  - `config-items`: the current file-based items, validate and apply endpoints, and the working-directory
    subfolders.
  - `validation-feedback`: the current listener that re-validates files after each tool result.
  - `composition`: binds the session to a composition and enables the workbench MCP server.
- The Configuration Assistant template declares `config-items` and `validation-feedback`, and its behaviour
  does not change. The UI's `isConfigAssistant` becomes a capability check.
- **Persisted conversation history**: every normalized event is appended to
  `${axiom.home}/assistant/sessions/<id>/history.jsonl`. After a restart the chat is rebuilt from that file.
  Composition sessions are not deleted at startup.
- **MCP authentication**: each session gets a random token, passed to its MCP server process in an
  environment variable and sent as an `X-Axiom-Session-Token` header. Composition and workbench endpoints
  require it when the call comes from MCP. Calls from the UI are unaffected.

### MCP server `axiom-workbench` (new)

Location: `app/src/main/resources/templates/axiom-workbench-mcp/server.js`. It is installed like
`axiom-assistant` and receives `AXIOM_COMPOSITION_ID` and the token. Its tools:

| Tool | Purpose |
|---|---|
| `get_composition` | Items with names, types, status and problems, plus the workflow document and its `contentRevision`. |
| `get_item(type, name)` | Full draft content. |
| `propose_workflow_changes(changeSet)` | Validate the change set and add it to this turn's accumulated change set. Returns the resulting `contentRevision` and any problems, or a `ChangeSetError`. |
| `upsert_action_type(item)`, `upsert_tool(item)` | Save a whole-item draft revision (author `ai`, current turn). Returns the problems. |
| `remove_item(type, name)` | Remove a draft from the composition. It does not delete the published item. |
| `validate_composition` | Validate all drafts against the drafts plus the database. |
| `test_action_type(name, inputs, subjectId?)`, `test_tool(name, params, subjectId?)` | Run the draft and return the outputs, stdout, stderr, exit code, duration and output-validation problems. |
| `list_stream_events(type?, eventSourceId?, ref?, limit<=20)` | Compact summaries: id, type, source, ref, actor, time and top-level payload keys. |
| `get_stream_event(eventId, path?)` | The normalized event, optionally narrowed to `path`. Large values are truncated with a marker. |
| `evaluate_expression(expression, scope, eventId?, context?)` | Evaluate EL with the engine's evaluator. Returns the value or the error. |
| Read-only discovery | The list and get tools of `axiom-assistant`, for database items and secret names. |

Within a turn, `propose_workflow_changes` builds on the turn's accumulated workflow: the first change set's
`baseRevision` is the revision at the start of the turn, and each later one uses the revision returned by the
previous call. The server stores the combined change set as the concatenation of the ops, with the base
revision from the start of the turn.

### Template `axiom-workbench` (built in)

- **Capabilities**: `composition`.
- **MCP**: `@axiom-workbench`.
- **Allowed tools**: all `mcp__workbench__*` tools, read-only shell (`ls`, `cat`, `jq`), and no Write or Edit.
- **System prompt**: the working loop, then the generated reference (reused from `AssistantReferenceGenerator`
  and extended with the `ChangeSet` op reference), then the prompt rules. The rules include:
  - Edit workflows only with change sets, and read `get_composition` first.
  - Before writing an EL expression that reads event data, inspect a real event of that type with
    `get_stream_event` and check the expression with `evaluate_expression`. If no event of that type exists,
    say so and use the documented event schema.
  - Test action types and tools you create with `test_action_type` or `test_tool` before finishing a turn.
  - The existing rules: prefer `node`, declare inputs, correlate receive-event nodes, do not describe the
    output format in prompts.

`WorkbenchReferenceTest` asserts that each rule is present.

### The turn loop

1. The user sends a message. The client flushes its pending workflow `onChange` save, then posts the message.
2. The server sets `turnState = ai-working` and creates a `turnId`. It builds a preamble that includes:
   - the workflow's `contentRevision`;
   - a summary of user edits since the AI's last turn, built from diffs of user-authored revisions;
   - the current selection, if the client sent one.
3. The agent works through MCP:
   - Action type and tool upserts are saved as revisions immediately and pushed to the UI over SSE.
   - Workflow change sets accumulate on the server.
4. On `turn_complete`, if a combined change set exists, the server publishes a `composition-changeset` SSE
   event. The client then calls `handle.apply(cs)` (live mode) or `handle.propose(cs)` (review mode):
   - On success, `onChange` with an `agent:` origin saves the revision with the turn's `turnId`.
   - If the result is `stale`, the server re-prompts the agent once with the diff and the new revision. A
     second stale result ends the turn with a chat notice.
5. The server sets `turnState = idle` and releases the locks.

### Publish

- `POST /compositions/{id}/publish {items[]}` builds a configuration pack from the selected drafts and runs
  `ImportExportService.importOrUpdatePack` in a single transaction. Workflows are published as part of it.
- Checks before publishing:
  - Every selected draft is valid. Warnings are allowed.
  - A selected workflow's new action-type dependencies are also selected or already exist.
  - The draft's `baseUpdatedAt` still matches the published item. If it does not, the server returns a
    conflict that the user confirms with `force`.
- On success, the server sets `publishedAt` and `publishedRevision` on the items. The session continues.

## §2 UI

### Routes and entry points

- `/workbench` lists compositions and has a **New composition** button.
- `/workbench/:id` is the workbench. With `?chrome=none`, it is used for break-out windows.
- **Open in workbench…** on `WorkflowDefinitionDetailPage` creates a composition seeded with the workflow,
  its action types, and the tools those action types reference in `allowedTools`.
- A **Workbench** entry under Configuration in `AppSidebar`.

### Left pane: composition tree

- The workflow, then action types, then tools. Each item shows:
  - a status icon (valid, warnings or errors);
  - an unpublished-changes dot;
  - an AI badge if the last turn changed it.
- **+ Action type** and **+ Tool** create a blank draft or add an existing item.
- A revision list for the selected item, with author and turn. Selecting a revision shows a diff and offers
  **Restore**, which creates a new user revision.
- A **Publish…** dialog with per-item checkboxes. Changed, valid items are preselected, and required
  dependencies are checked automatically.

### Centre pane: tabbed editors

- **Workflow tab**: the Flow `WorkflowEditor` with a ref handle.
  - `onChange` saves a revision, debounced by 500 ms and flushed before each AI turn.
  - `onSelectionChange` records the selection as context for the AI. Double-clicking an action node opens
    its action type's tab.
  - `spi.validate` calls draft validation on the server.
  - `spi.actionTypes` merges database action types with draft ones.
- **Action type tab**: the new `ActionTypeEditor` component, extracted from `ActionTypeDetailPage`. It takes
  `value` and `onChange`. `ActionTypeDetailPage` is refactored to use it.
- **Tool tab**: the new `ToolEditor` component, extracted from the tool detail page in the same way.
- **During an AI turn**: items the AI has touched in the current turn are read-only, with a banner, and
  refresh live. All other items stay editable.
- **Bottom strip** (collapsible):
  - **Problems** for the active item. Clicking one jumps to the node or field.
  - **Test output**: test runs and expression evaluations by the user or the AI.
  - A **Test…** button on action type and tool tabs opens a sample-input form.

### Right pane: chat

- It reuses `AssistantChatPanel`.
- Compact cards for workbench tool calls, for example "Updated action type `add-comment`" or "Proposed 4
  workflow changes". Each card links to its tab.
- A turn header with **Undo AI turn**:
  - It restores the previous revision of every action type and tool the turn changed.
  - For the workflow, it calls the editor's undo only if the turn's change set is the latest undo step.
    Otherwise it leaves the workflow alone and says so in chat.
- A mode toggle, **Apply live** (the default) or **Review changes**.

### Maximizing space

- **Focus mode**: a toolbar toggle (`Ctrl+Shift+F`) hides the app masthead and sidebar. It is persisted in
  `localStorage`.
- **Break out**: opens `/workbench/:id?chrome=none` in a new window. The original tab shows a "Bring back
  here" notice.
- **Resizable, collapsible panes**: drag splitters between the panes. The side panes collapse to rails, and
  the chat rail shows an AI-working or unread indicator. Sizes are persisted. A **Maximize canvas** action
  collapses both side panes.

### Session lifecycle

Compositions persist. After a restart, the chat is restored from `history.jsonl`. On the next message, the
agent process starts again with a recap: the goal, a composition summary and the last 20 messages. Deleting a
composition discards its unpublished drafts, after a confirmation.

## §3 REST API (openapi-first, tag `Compositions`)

- `GET/POST /compositions` and `GET/PATCH/DELETE /compositions/{id}`.
  - `POST` accepts `{name, goal?, engine?, seedWorkflowId?}`.
  - `PATCH` accepts `name` and `applyMode`.
- `GET /compositions/{id}/items` and `POST /compositions/{id}/items`. `POST` creates a blank draft or adds an
  existing item.
- `GET/PUT/DELETE /compositions/{id}/items/{itemId}`. `PUT` takes `{content, baseRevision}` and returns 409
  if `baseRevision` is not current.
- `GET /compositions/{id}/items/{itemId}/revisions` and `POST .../revisions/{revId}/restore`.
- `POST /compositions/{id}/workflow/changesets`, for MCP. The body is a `ChangeSet`. It returns
  `{contentRevision, problems[]}` or a `ChangeSetError`.
- `POST /compositions/{id}/validate`.
- `POST /compositions/{id}/test/action-type` and `POST /compositions/{id}/test/tool`.
- `POST /compositions/{id}/turns/{turnId}/undo`.
- `POST /compositions/{id}/publish`.
- Conversation: reuse the assistant session endpoints via `assistantSessionId`.
- `POST /expressions/evaluate`, with body `{expression, scope, eventId?, context?}`. `scope` is one of:
  - `edge-condition`;
  - `action-input`;
  - `receive-event-match`;
  - `correlation-key`;
  - `human-task-title`.

  Each scope builds the same EL variables the engine provides at that point.
- New SSE event types:
  - `composition-item-changed`, with `{compositionId, itemId, contentRevision, author, turnId}`;
  - `composition-turn`, with `{compositionId, turnId, state}`;
  - `composition-changeset`, with `{compositionId, turnId, changeSet}`.

## §4 Error handling

- **Change set rejected during a turn**: the MCP tool returns the `ChangeSetError` and the validation
  problems, and the agent fixes them within the turn. Nothing is sent to the client.
- **Stale at the end of a turn**: the server re-prompts the agent once. A second stale result ends the turn
  with a notice.
- **Client `apply` fails for another reason**: the client shows an error card with **Reload workflow from
  server**, which calls `handle.replace`.
- **Two windows on one composition**: saves carry `baseRevision`, and a mismatch returns 409 with the notice
  "Changed elsewhere — reload". Only the window holding the workflow editor lease (a heartbeat, 30 seconds)
  can edit the workflow. Other windows show it read-only.
- **Agent error or process exit**: the turn ends, `turnState` resets to `idle`, and the locks are released.
  A **Restart agent** button resumes the session with a recap.
- **Server restart during a turn**: at startup, compositions in `ai-working` reset to `idle`, and any
  accumulated change set is discarded. Action type and tool revisions saved during the turn keep their
  `turnId`, so **Undo AI turn** still works.
- **Publish**: it is all-or-nothing. Failures return 422 with problems per item, and the session is kept.
  Missing dependencies are blocked in the dialog and on the server. A base conflict needs confirmation.
- **Test runs**:
  - They use the existing timeouts and truncate output.
  - They run in a scratch workspace, unless the user chooses a subject.
  - Tests of `agent`-mode action types cost money and are recorded in `AiUsageEntity`.
- **Event tools**: an unknown event or path returns a clear message. Payloads are truncated.

## §5 Testing

All Java tests use JUnit 5 and the isolated `axiom.home`.

- **Composition service**:
  - revisions and turn tagging;
  - undo of a turn;
  - 409 on a stale `baseRevision`;
  - restart recovery from `ai-working`.
- **Change-set endpoint**:
  - delegation to Flow `ChangeSets.apply`;
  - validation against drafts plus the database;
  - accumulation and the base revision used for the combined set;
  - a single stale re-prompt.
- **`contentRevision` parity**: Axiom's stored hash equals Flow's for the documents it sends. The test reuses
  Flow's conformance fixtures.
- **Publish**:
  - partial selection;
  - dependency blocking;
  - base conflict;
  - a single transaction.
- **`/expressions/evaluate`**: every scope, against `StreamEventEntity` fixtures.
- **Capabilities refactor**: the existing Configuration Assistant tests pass unchanged.
- **`WorkbenchReferenceTest`**: the prompt rules are present, and the change-set examples are valid
  according to Flow.
- **MCP server**: a Node test of the mapping from tools to REST calls and of the token header.
- **UI (vitest, pure logic)**:
  - tree status;
  - publish dependency selection;
  - the turn and lock reducer;
  - the undo-AI-turn decision;
  - persistence of pane sizes and focus mode.
- **Manual (Chrome DevTools MCP)**: go from a goal to a workflow with an action type and a tool, interleave
  manual edits, trigger the stale path, publish, and use a break-out window.

## §6 Phasing

1. **Foundation**: template capabilities, persisted history, MCP token. The Configuration Assistant is
   unchanged.
2. **Composition backend**:
   - entities, REST, revisions, draft validation and publish;
   - `/expressions/evaluate` and the stream-event tools;
   - the `axiom-workbench` MCP server, template and reference.
3. **Flow upgrade**: consume the new Flow release, using a local snapshot until it is published.
4. **Workbench UI**:
   - the page, tree and tabs;
   - the extracted `ActionTypeEditor` and `ToolEditor`;
   - chat integration, apply and review modes, and undo of an AI turn;
   - focus mode, break-out and resizable panes.
5. **Testing in the loop**: test runs from the UI and from MCP, and the test output strip.

## Out of scope for v1 (planned follow-ups)

- Whole-workflow dry run using flow-ui's simulator.
- Inline one-shot AI helpers in the editors, including a helper for node `codeTemplate`.
- Flow context hooks and a live action type catalog (Flow feature set items 4 and 5).
- Event sources, subscriptions and toolsets in the workbench.
- The copilot engine. It needs interactive session support.
- Simultaneous (non-turn-based) editing by the user and the AI.
