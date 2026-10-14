# Workbench: Testing Workflows — Design

Status: proposed (not scheduled). Follows Phase 5 (test runs of action types and tools) and Phase 5b (the AI
only suggests tests). Tracking issue: see the GitHub issue linked from the commit that adds this file.

## Goal

Let the user test the composition's draft workflow from the workbench before publishing it, in two layers:

1. **Layer 1 — Simulate (routing check, in the browser).** Re-enable Apitomy Flow's **Simulate** feature in
   the workbench canvas and make it practical by pre-filling it from real data.
2. **Layer 2 — Dry run (the real engine, server-side).** Run the draft workflow through Axiom's real workflow
   engine with every action stubbed, and show the result in Apitomy Flow's `WorkflowViewer`.

Layer 1 ships first; layer 2 is a later phase.

## Decisions (agreed 2026-10-14)

| Topic | Decision |
|---|---|
| Starting data | Pre-fill from real data (a real stream event of the workflow's trigger type, and the outputs of the action types' last passing test runs). The user can always replace it with their own JSON. |
| Actions in a dry run | Always stubbed in v1. Nothing with side effects runs. No "run for real" option. |
| Showing a dry run | Apitomy Flow's `WorkflowViewer` (path taken, current node, per-visit inputs/outputs). |
| Order | Layer 1 first, layer 2 later. |
| Who runs tests | Only the user (Phase 5b rule). The AI may suggest a simulation/dry run, never start one. |

## Layer 1: Simulate with real data

### What Simulate is

Flow's Simulate (see `apitomy-flow/docs/user-guide/visual-editor.md`, "Simulation and Condition Testing") runs
the workflow's routing in the browser: the user enters a sample start context (JSON), then Steps/Runs; at
action, human-task and receive-event nodes it pauses for a mock output (JSON); waits pass through. It uses a
browser subset of Jakarta EL and is explicitly an authoring aid, not the engine.

### Changes

- **Re-enable Simulate** in `ui/src/pages/workbench/WorkflowTab.tsx` (remove `simulate: false` from
  `EDITOR_FEATURES`). The properties dialog mode stays; Flow brings the side panel back while simulating.
- **Pre-filled start context.** A **Start context** helper next to the Simulate panel (or a menu on the
  workbench canvas toolbar, depending on what Flow allows hosts to inject — see Open questions):
  - **From a real event:** pick from recent stream events of the workflow's trigger type (default: the most
    recent); the context is built the way the engine builds a run's start context from an event (same
    mapping as production — reuse the backend's event-to-context mapping through a small endpoint, do not
    reimplement it in the UI).
  - **My own JSON:** an editor with the last value the user used.
- **Mock outputs from test runs.** When the simulation pauses at an action node, offer the outputs of that
  action type's last *passing* test run (Phase 5 run store) as the mock output, editable before continuing.
  If Flow's simulation API does not let the host supply a mock-output default, this needs a Flow change.
- **No AI changes** in layer 1 (optional: a `suggest_simulation` tool is deferred to layer 2's suggestion).

### Needs from Apitomy Flow (to confirm)

- A host hook to set/replace the simulation's sample start context.
- A host hook to provide a default mock output when the simulation pauses at a node.
If missing, add them in a Flow 3.x release (Flow is ours).

## Layer 2: Dry run on the real engine

### Behaviour

- **Start:** a **Dry run…** dialog (workflow tab and the Test output strip) with the trigger data — a real
  stream event (default: most recent of the trigger type) or custom JSON — and one stub per action node:
  - default stub = the action type's last passing test run outputs; editable JSON per node;
  - nodes without a stub show a warning; the run fails at that node with a clear message if reached.
- **Engine:** the draft workflow (with draft action types resolved from the composition) runs in Axiom's real
  workflow engine in **dry-run mode**:
  - action nodes never execute; their output is the stub, then validated against the action type's declared
    outputs (exactly as a real task's output is);
  - input bindings into actions are evaluated and validated (required inputs, types) and recorded;
  - edge conditions, defaults, fork/join, EL — all real engine behaviour;
  - `wait` nodes complete immediately (recorded as skipped waits);
  - `human task` and `receive event` nodes use a stub too (provided up front in the dialog; default `{}`
    with a warning), so a dry run never blocks. (Interactive pausing is a possible follow-up.)
  - no side effects: no tasks are queued for agents, no events are emitted, no subject workspaces are
    touched, nothing is written to the regular runs tables (see Open questions for storage).
- **Result:** a dry-run entry in the Test output strip (status passed/failed/error, duration, the node that
  failed and why). Its details dialog shows Apitomy Flow's **`WorkflowViewer`** with the draft workflow and a
  `WorkflowInstance` built from the dry run's history (visited nodes, edges taken, per-visit inputs and
  outputs, the failing node highlighted red), plus the final context as highlighted JSON.
- **AI:** a `suggest_dry_run(trigger?, stubs?)` MCP tool, mirroring `suggest_test`: stores a *suggested*
  dry run; the user reviews and edits trigger/stubs in the Dry run dialog and runs it. The workbench prompt
  rule: suggest a dry run after significant workflow changes; never claim the workflow works until the user
  reports a passing dry run.

### Backend sketch

- `POST /compositions/{id}/workflow/dry-run` (UI route) and `POST /compositions/{id}/agent/dry-run-suggestions`
  (MCP-only, token guarded), contract-first in `openapi.json`.
- A `WorkflowDryRunner` that drives the engine with a stub task executor instead of `TaskExecutionService`,
  in memory, bounded (step limit, time limit), sharing the per-composition concurrency cap with test runs.
- Results stored in the existing in-memory run store (`CompositionTestRuns`) with `itemType: "workflow"` and
  a `history`/`instance` payload compatible with Flow's `WorkflowInstance`.

## Open questions

1. Can Axiom's workflow engine run fully in memory with a pluggable task executor, or does it require
   persisted run/task rows? If rows are required: a separate dry-run table, or a flag + cleanup.
2. Which Flow host hooks exist today for the simulation context and default mock outputs (layer 1)?
3. Should the trigger picker in both layers share one component (recommended)?
4. How is the "workflow's trigger type" determined for a draft (subscription vs. start node inputs)? Needed
   to filter real events.

## Out of scope

- Running real actions in a dry run (v1 always stubs).
- AI-initiated execution of any kind.
- Testing AI Agent action types.
