# Workbench Chat Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** In the workbench chat, show the AI's workbench tool calls as compact, readable cards ("Updated
action type `x` · VALID", "Proposed 4 workflow changes", "Suggested a test for `y`") that link to what they
affected, fold read-only calls into one quiet line, and keep the raw call one click away.

**Architecture:** `AssistantMessageList`/`AssistantChatPanel` gain an optional `renderToolUse` hook; when it
returns content, it replaces the generic `AssistantToolUseBlock` (the Configuration Assistant is
unchanged). The workbench supplies a `WorkbenchToolCard` renderer built from a pure `describeToolCall`
function that parses the tool name, input and result text. Consecutive read-only workbench calls are grouped
by a pure `groupToolCalls` pass. One small backend change makes `upsert_item` report created vs updated.

**Tech Stack:** React 18 + PatternFly 6 + Vitest (ui/), Java/Quarkus (one field), Node MCP server.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md` §2 "Right pane: chat" ("Compact
cards for workbench tool calls, for example 'Updated action type `add-comment`' or 'Proposed 4 workflow
changes'. Each card links to its tab.").

## Global Constraints

- Contract-first REST for the one schema change (`AgentItemResult.created`); never run `./build.sh`,
  `mvn clean`, `mvn install`. Backend tests: `mvn -q -pl app -am test -Dtest=<Classes>
  -Dsurefire.failIfNoSpecifiedTests=false`. UI (in `ui/`): `timeout 250 npx tsc -b --noEmit`,
  `timeout 280 npx vitest run [paths]`.
- Code style: 4-space indent, doc comments on exported functions/public Java methods, explicit types, JUnit 5.
- Conventional commits on `feat/workflow-first`, no AI attribution, never push.
- Tool names: workbench MCP tools arrive as `mcp__workbench__<tool>` (Claude Code) or `workbench_<tool>`
  (OpenCode). Both must be recognised; anything else falls back to the generic block.
- The Configuration Assistant and every non-workbench chat keep the generic `AssistantToolUseBlock`.
- Every card expands to the raw input and result (reuse `AssistantToolUseBlock` inside the expansion) —
  nothing is hidden.
- Permission prompts, errors and running (no result yet) calls must still be visible: a call that is waiting
  for permission is never replaced by a card (render the generic block).
- Card texts (exact):

| Tool | Card text | Link |
|---|---|---|
| `upsert_item`, created | `Created <kind> <name>` + status label | open item tab |
| `upsert_item`, updated | `Updated <kind> <name>` + status label | open item tab |
| `remove_item` | `Removed <kind> <name>` | — |
| `propose_workflow_changes`, applied | `Proposed <n> workflow change(s)` + first 3 op summaries joined by `, ` (+ ` and <k> more`) | open workflow tab, select first touched node |
| `propose_workflow_changes`, rejected | `Workflow changes rejected: <reason>` (danger) | open workflow tab |
| `suggest_test` | `Suggested a test for <kind> <name>` | open the suggestion's Run… (by item) |
| `evaluate_expression` | `Evaluated <expression> → <value or error>` | — |
| `list_stream_events` | `Looked at <n> stream event(s)` | — |
| `get_stream_event` | `Read stream event <id>` | — |
| read-only group (`get_composition`, `get_item`, `validate_composition`, `axiom_*`) | `Read the composition`, `Read <kind> <name>`, `Checked the composition`, `Looked up existing items`; a group of several: `Read <n> things` with the list in the expansion | — |

  `<kind>` is `action type` / `tool` / `workflow`. Status label: `VALID` (green), `VALID with warnings`
  (orange), `INVALID` (red). Op summaries: `added <node name or id>`, `removed <id>`, `connected <a> →
  <b>`, `disconnected <edge id>`, `updated <id>` (by op type; unknown op types → `changed <id>`).

---

### Task 1: Report created vs updated from `upsert_item`

**Files:** `common/api/src/main/resources/openapi.json` (add `created: boolean` to `AgentItemResult`,
description "True when the call created the item"), `app/src/main/java/io/apitomy/axiom/app/composition/
CompositionTurns.java` (`AgentWrite` gains `boolean created`, true when `existing == null`),
`app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` (`setCreated`),
`app/src/main/resources/templates/axiom-workbench-mcp/server.js` (`upsert_item` result becomes
`${statusOf(r)}: ${r.type}/${r.name} (${r.created ? "created" : "updated"}, revision ${r.contentRevision})`),
tests: `CompositionTurnsResourceTest` (or the existing agent upsert test: first upsert → `created: true`,
second → `false`).

- [ ] Failing test (RED) → implement → `mvn -q -pl app -am test -Dtest='CompositionTurnsResourceTest,CompositionTurnsTest' -Dsurefire.failIfNoSpecifiedTests=false` (GREEN).
- [ ] Commit: `feat(workbench): upsert_item reports whether it created the item`

### Task 2: `describeToolCall` and `groupToolCalls` (pure logic)

**Files:** Create `ui/src/pages/workbench/toolCards.ts` + `toolCards.test.ts`.

**Interfaces (produced):**

```ts
export type WorkbenchTool = "get_composition" | "get_item" | "upsert_item" | "remove_item"
    | "propose_workflow_changes" | "validate_composition" | "suggest_test" | "list_stream_events"
    | "get_stream_event" | "evaluate_expression" | "axiom_list_action_types" | "axiom_get_action_type"
    | "axiom_list_tools" | "axiom_get_tool" | "axiom_list_secrets";

/** Strips `mcp__workbench__` / `workbench_`; null when the name is not a workbench tool. */
export function workbenchTool(toolName: string): WorkbenchTool | null;

export type CardTarget =
    | { kind: "item"; itemType: "action-type" | "tool" | "workflow"; name: string }
    | { kind: "workflow"; nodeId?: string }
    | { kind: "suggestion"; itemType: "action-type" | "tool"; name: string };

export interface ToolCard {
    readOnly: boolean;
    /** Plain text of the card (exact texts in the plan's Global Constraints); used by tests and tooltips. */
    text: string;
    /** The leading verb phrase, e.g. "Updated action type"; the component renders it before `name`. */
    lead: string;
    /** Item, node or expression shown in monospace after `lead`, if any. */
    name?: string;
    /** Text after the name, e.g. op summaries or "→ value". */
    detail?: string;
    status?: "VALID" | "VALID with warnings" | "INVALID";
    variant: "default" | "danger";
    target?: CardTarget;
}

/** Describes a finished workbench tool call; null for unknown tools or calls without a result yet. */
export function describeToolCall(toolName: string, input: Record<string, unknown> | undefined,
                                 result: string | undefined, isError: boolean | undefined): ToolCard | null;

export interface ToolCallRef { id: string; toolName: string; card: ToolCard | null }
/** Groups consecutive read-only cards; non-read-only cards and null cards stay single. */
export function groupToolCalls(calls: ToolCallRef[]): ToolCallRef[][];
```

`text` always equals `[lead, name, detail].filter(Boolean).join(" ")`.

Parsing rules (from `server.js` result formats):
- `upsert_item`: first line `^(VALID with warnings|VALID|INVALID): (\S+?)/(.+?) \((created|updated), ` →
  status, type, name, created. Older results without `(created|updated,` → treat as `Updated`.
- `propose_workflow_changes`: result starts with `APPLIED` → count `input.changeSet.ops.length`
  (input may be a JSON string — parse it), summaries from the ops; starts with `REJECTED` → danger card with
  the text after the first `: `. `target.nodeId` = first op's node id (`op.node?.id ?? op.nodeId ?? op.id`).
- `remove_item`, `suggest_test`, `get_item`: from `input.type`/`input.name`.
- `evaluate_expression`: result `OK (<type>): <json>` → `→ <json>`; `ERROR: <msg>` → `→ error: <msg>` (danger).
- `list_stream_events`: count result lines (`No stream events found.` → 0).
- `isError === true` → danger card with the first result line.

- [ ] Write table-driven tests covering every row of the Global Constraints table, both tool-name prefixes,
  unknown tools → null, no result → null, JSON-string `changeSet`, grouping (`[r, r, w, r]` → `[[r, r], [w],
  [r]]`). RED → implement → GREEN (`timeout 280 npx vitest run src/pages/workbench/toolCards.test.ts`).
- [ ] Commit: `feat(workbench): describe workbench tool calls for chat cards`

### Task 3: Renderer hook in the shared chat components

**Files:** `ui/src/components/assistant/AssistantMessageList.tsx`, `AssistantChatPanel.tsx` (+ tests).

**Interfaces (produced):**

```ts
/** A rendered tool call: the message plus the generic block, so a custom renderer can embed it. */
export interface ToolUseRenderContext { message: ChatMessage; genericBlock: ReactNode }
/** Optional replacement renderer for runs of consecutive tool_use messages. Return null to use the default. */
export type ToolUseGroupRenderer = (group: ToolUseRenderContext[]) => ReactNode | null;
```

- `AssistantMessageList` accepts `renderToolUseGroup?: ToolUseGroupRenderer`. When set, consecutive
  `tool_use` messages (no other message type between them) are passed as one group; the renderer returns
  the content for the whole group or `null` (→ each message renders as today). Messages with an unresolved
  permission request are never grouped (render the default).
- `AssistantChatPanel` passes a `renderToolUseGroup` prop through.
- Tests: default rendering unchanged without the prop; with a renderer the group content replaces the
  blocks; `null` falls back; a pending-permission message is rendered by default even with the renderer.

- [ ] RED → implement → GREEN (`timeout 280 npx vitest run src/components/assistant`), full UI suite once.
- [ ] Commit: `feat(assistant): optional custom rendering of tool call groups`

### Task 4: `WorkbenchToolCard` and wiring in the workbench

**Files:** Create `ui/src/pages/workbench/WorkbenchToolCards.tsx` (+ test); modify `ChatPane.tsx`,
`WorkbenchPage.tsx` (+ tests), `WorkflowTab.tsx` (`WorkflowTabHandle.selectNode(nodeId: string): void`,
implemented with `handle.dispatch({ type: "select", nodeId })`), `WorkbenchPage.css`.

Behaviour:
- `renderWorkbenchToolGroup(group, actions)` → for each `groupToolCalls` group: a read-only group renders one
  quiet line (subtle text, small icon) expandable to the generic blocks; any other card renders a compact
  row: icon by tool, the text with the item name in `<code>`, the status `Label` (compact; green/orange/red),
  the link (a `Button variant="link" isInline`), and an expand toggle that shows the generic block. Danger
  cards use the danger text colour and an error icon. Calls `describeToolCall` returns null for (not a
  workbench tool, still running, waiting for permission) render the generic block.
- `actions` = `{ openItem(type, name), openWorkflow(nodeId?), openSuggestion(type, name) }`:
  `openItem` finds the item in `wb.items` by type+name and opens its tab (no-op if gone);
  `openWorkflow` activates the workflow tab and, after it renders, calls `workflowTabRef.current?.selectNode`;
  `openSuggestion` finds the newest `suggested` run for that item in `wb.testRuns` and calls the existing
  `runSuggestion` (falls back to `openItem` when there is none).
- `ChatPane` takes `toolActions` and passes `renderToolUseGroup` to `AssistantChatPanel`.
- CSS: cards are single-line rows with 4px vertical padding, same left inset as tool blocks today.

Tests: rendering of each card kind (text, status label, link calls the right action), read-only grouping
line + expansion shows the generic blocks, fallback to the generic block for an unknown tool; page test that
clicking an "Updated tool `my-tool`" card opens the tool's tab.

- [ ] RED → implement → GREEN (`timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run`).
- [ ] Commit: `feat(workbench): compact chat cards for workbench tool calls`

### Task 5: Live verification

- [ ] With `dev.sh` running, open `/workbench/<id>`; ask the AI to create a small node action type, wire it
  into the workflow and suggest a test. Check: the reads fold into one line; "Created action type `…` ·
  VALID" opens the tab; "Proposed N workflow changes" opens the workflow and selects the node; "Suggested a
  test for …" opens the pre-filled Test dialog; each card expands to the raw call; history from before the
  change (OpenCode `workbench_` names and Claude `mcp__workbench__` names) still renders. Screenshot and report.
