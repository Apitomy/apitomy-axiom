# Workflow Draft Test Runs — Design

## Goal

Allow a workflow that is being edited in the workbench (an unpublished composition draft) to be executed as a
test run before publishing. The trigger is either an existing stream event or a fabricated event, and the run
uses a transient test subject. The workbench AI assistant can launch test runs, monitor them to completion, and
use the results to refine the workflow design.

## Decisions

| Topic | Decision |
|---|---|
| Side effects | Steps execute for real (scripts, agents, LLMs, external APIs). The UI and tools show a warning. |
| Subject | Always a fresh, ephemeral test subject, hidden from normal views and auto-deleted after a retention period. |
| Assistant monitoring | Polling with a long-poll `wait` parameter. No push into the session. |
| Waiting steps | The run waits normally and is resumed only by fabricated events sent to it. It is cancelled after a wait timeout. Test runs never consume real stream events. |
| Execution approach | Real runs flagged `isTest` that carry a snapshot of the draft definition, executed by the real engine. |

Alternatives that were rejected:

- An in-memory engine extending `DraftTestRunner`, because its behavior would drift from real runs.
- Publishing the draft as a hidden "shadow" definition, because it pollutes the catalog and races with publishing.

## Section 1: Data model and API

### `WorkflowRunEntity`

- `isTest` (boolean, default false).
- `compositionId` and `compositionRevision` (nullable): which draft and revision was tested.
- `definitionSnapshot` (TEXT, nullable): the draft workflow definition at launch time. When it is set, the engine
  uses it instead of loading `definitionId`/`definitionVersion`.
  - If the draft edits an existing workflow, `definitionId` is that workflow's ID and `definitionVersion` is
    null.
  - For a brand-new workflow, both are null, so these two columns become nullable.
- `actionOverlaySnapshot` (TEXT, nullable): a JSON map of the draft action types and tools at launch time,
  keyed by kind and name.

### `SubjectEntity`

- `isTest` (boolean, default false) and `expiresAt` (timestamp, nullable).
- Test subjects use a reserved `ref` prefix `test:<uuid>`, which cannot collide with real event refs.

### Migration

Add the columns above with defaults. Make `WorkflowRunEntity.definitionId` and `definitionVersion` nullable.

### REST API (contract-first, in `common/api/src/main/resources/openapi.json`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/compositions/{id}/workflow-test-runs` | Start a test run. Body: `{ trigger: { eventId } \| { event: {type, ref, data, ...} }, subjectOverrides? }`. Returns the test run. |
| GET | `/compositions/{id}/workflow-test-runs` | List test runs for the composition. |
| GET | `/compositions/{id}/workflow-test-runs/{runId}?wait=N` | Get status, steps (status, outputs, errors), recent logs, and the wait condition. `wait` (0–30s) blocks until the run changes state or the timeout passes. |
| POST | `/compositions/{id}/workflow-test-runs/{runId}/events` | Send a fabricated event to a waiting test run. |
| POST | `/compositions/{id}/workflow-test-runs/{runId}/cancel` | Cancel a test run. |

Test run IDs are ordinary workflow run IDs, so the existing trace and task-log endpoints work for test runs.

### Limits and configuration

- At most 2 concurrent test runs per composition, following the existing `TooManyTestRunsException` pattern.
- `axiom.workflow-test-runs.retention` (default `24h`).
- `axiom.workflow-test-runs.wait-timeout` (default `1h`).

## Section 2: Executing drafts and keeping them out of production paths

### Launch flow (`WorkflowTestRunService`, `app/.../composition/`)

1. Load the composition and validate it with `CompositionValidator`. Return 400 with the validation errors if it
   is invalid.
2. Snapshot the draft workflow definition and the draft action types and tools.
3. Resolve the trigger event:
   - For `eventId`, load the existing `StreamEventEntity`.
   - For a fabricated event, build an in-memory event. It is not persisted to the event stream.
4. Create the test subject (`isTest=true`, `ref=test:<uuid>`, `expiresAt=now+retention`):
   - Its values come from `subjectValueForEvent(event)` with `subjectOverrides` merged in.
   - Its workspace is created with `workspaceService.ensureWorkspace`.
5. Start the run through a shared core extracted from `WorkflowExecutionService.triggerWorkflow`:
   `startRun(workflow, subject, eventSupplier, runAttributes)`.
   - The production path keeps its current behavior: it resolves the published definition and then calls this
     core.
   - The test path passes the snapshot and the test attributes.

### Resolving draft action types and tools

- Introduce `ActionDefinitionResolver` with two implementations:
  - The default implementation reads the published catalog.
  - The draft implementation checks the run's `actionOverlaySnapshot` first and falls back to the catalog.
- The engine selects the resolver per run based on whether an overlay is present.
- Agent action types in drafts become testable through this path.

### Recovery and resumption

Wherever the engine reloads a run's definition (restart recovery, resuming a waiting run), it uses
`definitionSnapshot` and the overlay when they are present.

### Isolation from production

- `EventStreamOrchestrator` ignores receive-event subscriptions owned by test runs. Test runs advance only
  through the fabricated-event endpoint.
- Rules that create runs from events never match `test:` subjects.
- Run and subject listings, summaries, dashboards, waiting-run views and per-definition run counts filter
  `isTest = false` by default. Run listing accepts `?includeTest=true`.
- Post-run hooks (AI run summaries and notifications) are skipped for test runs. Traces and task logs are still
  recorded.
- The one-active-run-per-definition-per-subject conflict check is naturally scoped to the test subject.

### Cleanup

A scheduled `TestRunReaper` runs every 15 minutes. It:

- Cancels test runs that have been waiting longer than `wait-timeout`.
- Deletes expired test subjects and their runs, tasks, traces, thread entries and workspace directories.

### Errors

- Step failures fail the run exactly as in production and are reported in the test-run response.
- Launch problems return 4xx with clear messages: an invalid draft, an unknown event, or too many concurrent test
  runs.

## Section 3: Assistant tools and workbench UI

### MCP tools (`templates/axiom-workbench-mcp/server.js`)

| Tool | Purpose |
|---|---|
| `start_workflow_test_run` | Args: `eventId` or `event` `{type, ref, data}`, plus optional `subjectOverrides`. Returns the run ID, status and a side-effect reminder. |
| `get_workflow_test_run` | Args: `runId`, `wait` (0–30s, default 20). Returns status, per-step status, truncated outputs and errors, the last N log lines of failed or running steps, and the wait condition. |
| `list_workflow_test_runs` | Recent test runs with status and the draft revision tested. |
| `send_test_event` | Args: `runId`, `event`. Resumes a waiting test run. |
| `cancel_workflow_test_run` | Args: `runId`. |

- Deeper inspection uses the existing `get_trace` and task-log tools with the test run ID.
- Outputs and logs are truncated and redacted with `TestRunRedactor` so they fit in context and secrets stay
  masked.
- The workbench session template gains guidance on the test loop:
  1. Validate the draft.
  2. Pick or fabricate an event, and tell the user about real side effects before launching.
  3. Launch the run and poll until it reaches a terminal state.
  4. Diagnose failures.
  5. Propose changes with `propose_workflow_changes`.
  6. Re-run the test.
- The side-effect warning lives in the tool description. There is no confirmation round trip.

### Workbench UI

- A **Test run** button in the workbench toolbar opens a dialog with:
  - A choice between **From event stream** (a searchable picker built on the stream event list) and
    **Fabricate** (a JSON editor for `type`, `ref` and `data`, pre-filled from the workflow's subscription filter
    where possible).
  - An optional subject-overrides JSON editor.
  - A warning banner about real side effects.
- A **Test runs** panel lists test runs (status, draft revision, start time) and opens a run view that reuses the
  existing run and trace components. A waiting run shows its wait condition and a **Send event** action that
  opens the fabricate dialog.
- The panel updates live from the existing run SSE events, filtered by composition ID.
- Test runs started by the assistant appear in the same panel.

## Testing

- Unit tests:
  - Draft resolver lookup order (draft first, then catalog).
  - Snapshot handling at launch and on resume.
  - Production query filters that exclude test runs and test subjects.
- `@QuarkusTest` integration tests:
  - A fabricated event drives a run to completion.
  - A picked stream event triggers a run.
  - A waiting run resumes after `send_test_event`.
  - A waiting run is cancelled at the wait timeout.
  - The reaper deletes expired test subjects and their runs.
  - Real stream events do not advance test runs.
  - Test runs and subjects are absent from default listings.
  - The concurrency limit is enforced.
  - An invalid draft returns 400.
- MCP tools: a smoke test of each tool against a dev instance.
- UI: manual verification on `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500
  --dataDir=/tmp/axiom-test/data`.

## Out of scope

- Mocking or stubbing individual steps, and a dry-run mode for actions.
- Pushing a completion notification into the assistant session.
- Comparing results across test runs.
