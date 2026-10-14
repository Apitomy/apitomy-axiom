# Workbench Runs Tab and Run Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The workbench shows the runs of the composition's (published) workflow in a new **Runs** tab of the
bottom panel (next to **Tests**), lets the user open a run in a dialog with Flow's `WorkflowViewer` and the
failing task's output and log, and lets the user ask the AI to analyze a failed run. The AI gets read-only MCP
tools to read the composition's runs, a run's details (history, failure, task output and log) and its trace.

**Architecture:** The composition's workflow item's `baseItemId` is the published WorkflowDefinition id (null
when never published). UI: the bottom strip becomes a tabbed panel (Tests | Runs). Runs are fetched with the
existing `GET /workflow/runs?definitionId=` and refreshed (debounced) on the `workflow-updated` SSE event. The
run dialog reuses the instance mapping from `WorkflowRunDetailPage` (extracted into a shared helper). MCP: new
read-only tools in `axiom-workbench-mcp/server.js` call the existing public REST routes; the composition's
definition id comes from the agent composition route.

**Tech Stack:** Java/Quarkus, Node MCP server, React/PatternFly/Vitest, `@apitomy/flow-ui` `WorkflowViewer`.

**Spec:** user decisions (2026-10-14): runs of the composition's workflow only (all versions); run details in a
dialog with WorkflowViewer; "Ask AI to analyze" prefills the chat (the user sends).

## Global Constraints

- Contract-first REST for any schema change (`openapi.json`, programmatic edits, additions only).
- Never run `./build.sh`, `mvn clean`, `mvn install`. Backend tests:
  `mvn -q -pl app -am test -Dtest=<Classes> -Dsurefire.failIfNoSpecifiedTests=false`; Node MCP tests:
  `node --test app/src/test/node/workbench-mcp.test.js`; UI (in `ui/`): `timeout 250 npx tsc -b --noEmit`,
  `timeout 280 npx vitest run [paths]`.
- Commit only after the relevant tests pass (`<tests> && git commit`, never `;`).
- Conventional commits on `feat/workflow-first`, no AI attribution, never push.
- The AI tools are read-only: they never start, cancel or change runs.
- Exact UI texts: bottom tabs `Tests` and `Runs` (each with a count badge); Runs empty states:
  `Publish the workflow to see its runs here.` (no published definition) and `No runs of this workflow yet.`;
  run row action / dialog button `Ask AI to analyze`; dialog title `Run #<id>: <title>`; link
  `Open run page`.
- Chat prefill for Ask AI (exact shape): ``Analyze run #<id> of workflow `<name>` (status: <status>``
  `` at node `<currentNodeName>`): <failureReason or first failed task output line>. Find the cause and fix the``
  `` workflow or its components.`` — omit the ` at node …` part when unknown and the `: <reason>` part when
  there is no reason.

---

### Task 1: Read-only MCP tools for runs

**Files:** `app/src/main/resources/templates/axiom-workbench-mcp/server.js`, `app/src/test/node/workbench-mcp.test.js`,
`app/src/main/resources/templates/assistant-templates/axiom-workbench.json`,
`app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java`; if the agent composition
response (`GET /compositions/{id}/agent/composition`, schema `AgentComposition`) does not already expose the
workflow's published definition id, add `workflowDefinitionId` (int64, nullable: "The published workflow
definition of this composition, or null when never published") to the schema and fill it from the workflow
item's `baseItemId` in `CompositionsResourceImpl.getAgentComposition` (+ a REST test).

Tools (each returns compact text; use `truncateDeep` for JSON):
- `list_workflow_runs(status?, limit?)` — runs of the composition's workflow, newest first (`GET
  /workflow/runs?definitionId=<id>&limit=<min(limit||10,20)>[&status=]`). Output: one line per run:
  `#<runId> <status> v<definitionVersion> <title> started <startedOn>[ at <currentNodeName>]`. When the workflow
  was never published: `The workflow has not been published yet, so it has no runs.`
- `get_workflow_run(runId)` — `GET /workflow/runs/{runId}`; refuse (`Run <id> is not a run of this
  composition's workflow.`) when its `definitionId` differs from the composition's. Output: header (status,
  version, title, current node, `failureReason`, traceId), then the history (one line per entry: node name/id,
  task status, compact input/output), then for each failed task: its output (`GET /subjects/{s}/tasks/{t}`) and
  the last 4000 chars of its log (`GET .../log`, text/plain — override Accept; a 404 means no log).
- `get_trace(traceId)` — `GET /traces/{traceId}`: trace status/summary and the nodes (one line each:
  type, status, summary, duration), failed nodes first.
- Export pure formatters (`formatRunList`, `formatRun`, `formatTrace`) and unit-test them in
  `workbench-mcp.test.js`.
- Template: allow `mcp__workbench__list_workflow_runs`, `mcp__workbench__get_workflow_run`,
  `mcp__workbench__get_trace`; add rule: `- When asked about a workflow run, read it with get_workflow_run (and
  get_trace when the cause is unclear) before changing anything; fix the cause in the workflow or its
  components, then summarise what failed and what you changed.` `WorkbenchReferenceTest` checks the rule and
  the three tools.
- [ ] RED → GREEN; commit `feat(workbench): read-only MCP tools for the workflow's runs`.

### Task 2: Bottom panel with Tests and Runs tabs; Runs list

**Files:** `ui/src/pages/workbench/TestOutputStrip.tsx` (rename to `BottomPanel.tsx` or wrap it — keep the Tests
behaviour identical), new `ui/src/pages/workbench/RunsList.tsx`, `ui/src/pages/workbench/WorkbenchPage.tsx`,
`ui/src/pages/workbench/workbenchPrefs.ts` (+ tests), `ui/src/pages/workbench/useWorkflowRuns.ts` (+ test).

- Panel header: collapse toggle, PatternFly `Tabs` (`Tests` | `Runs`, each title with a compact count label),
  and the tab's actions on the right (Tests: the existing `Test…` button; Runs: a Refresh button). Pref
  `bottomPanelTab: "tests" | "runs"` (default `tests`) alongside `testStripCollapsed` (keep that name).
- Existing auto-expand for new live test runs/suggestions also switches to the Tests tab.
- `useWorkflowRuns(definitionId | null)`: loads `fetchWorkflowRuns(1, 50, { definitionId })`, refetches
  (debounced 500 ms) on `workflow-updated` SSE events, exposes `{ runs, loading, error, refresh }`.
  `definitionId` = the composition's workflow item's `baseItemId`.
- Runs list rows (same row style as the Tests list): status label (STATUS_COLORS as on the Runs page), `#id`,
  title, `v<version>`, started time, current node for failed/waiting runs; failed rows show a small
  `Ask AI to analyze` link button; clicking a row calls `onOpenRun(runId)` (dialog comes in Task 3 — for now
  `onOpenRun` may be a no-op prop). Empty states and an inline error state (exact texts above).
- When a new run (id not seen before, after the initial load) arrives with status `failed` while the panel is
  collapsed, expand the panel on the Runs tab.
- Tests for the hook (load, SSE refetch), the list (rows, empty states, Ask AI link), the panel (tab switch,
  per-tab actions, pref persisted), and the page (Runs tab uses the workflow's `baseItemId`).
- [ ] RED → GREEN (tsc + full vitest); commit `feat(workbench): Tests and Runs tabs in the bottom panel`.

### Task 3: Run dialog and Ask AI

**Files:** extract `toFlowInstance(run)` from `ui/src/pages/WorkflowRunDetailPage.tsx` into
`ui/src/components/workflowRun.ts` (+ test) and use it in the page; new
`ui/src/pages/workbench/RunDetailDialog.tsx` (+ test); `WorkbenchPage.tsx` wiring; a pure
`runAnalysisPrefill(run)` in `ui/src/pages/workbench/runAnalysis.ts` (+ test).

- Dialog (large modal, fixed body height like the test run dialog): title `Run #<id>: <title>`; compact
  metadata line (status label, workflow version, started, duration, current node); `failureReason` as a danger
  alert when present; tabs **Graph** (`WorkflowViewer` with `run.workflowContent` and `toFlowInstance(run)`,
  theme from `useEffectiveTheme`, height filling the tab), **Failure** (only when failed: each failed task's
  output and its execution log text, highlighted as JSON when JSON), **Context** (run context as highlighted
  JSON). Footer: `Ask AI to analyze` (primary, failed runs only) and `Open run page` (link to `/runs/<id>`,
  new tab), `Close`.
- `Ask AI to analyze` (row link and dialog button) calls the page's existing `askAi(text)` with
  `runAnalysisPrefill(run)` (exact shape above; the row uses the summary fields, the dialog the full run) and
  closes the dialog.
- Live: the dialog refetches the run on `workflow-updated` for its run id.
- Tests: prefill shapes (with/without node and reason); dialog renders metadata, alert, tabs, Failure tab with
  task output/log (mock api), Ask AI calls back with the prefill; `toFlowInstance` mapping unchanged
  (snapshot of the page's previous behaviour).
- [ ] RED → GREEN (tsc + full vitest); commit `feat(workbench): run details dialog and Ask AI to analyze`.

### Task 4: Live verification

With `dev.sh` restarted: Runs tab lists the published workflow's runs (e.g. *Apitomy Renovate PR Auto-Merge*
run #1, failed); the dialog shows the graph with `check-status` failed and the task output; Ask AI prefills the
chat; sending it makes the AI call `get_workflow_run` and explain the missing `previousAttempts` input.
