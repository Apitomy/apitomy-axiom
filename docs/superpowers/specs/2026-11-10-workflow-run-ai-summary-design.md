# Workflow Run AI Summaries — Design

## Goal

Generate an optional, AI-written summary of a workflow run after it finishes, so users can scan the runs list
and understand what a run did at a glance without opening every step.

## Requirements

- **Trigger:** automatic for workflows that opt in (`summarize: true`), plus an on-demand "Summarize" button for
  any terminal run.
- **Automatic scope:** only runs that end `completed` or `failed`. Cancelled runs are summarized only on demand.
- **Engine/model:** global settings `summaryEngine` / `summaryModel`. When unset, fall back to the system
  default engine and its default model.
- **Format:** one-line headline (≤120 chars) for the runs list, plus a 3–8 bullet markdown body for the run
  detail page, produced by a single agent session that returns JSON.
- **Investigation:** the agent receives a lean digest and can query a read-only, run-scoped set of MCP tools for
  deeper detail (task outputs, logs, trace).
- **Non-goals:** per-workflow engine/model overrides, deleting summaries, summarizing non-terminal runs,
  summaries for cancelled runs without a manual request.

## Architecture Overview

A DB-backed queue (`workflow_run_summary` table) is populated by a hook at run termination and drained by a
`@Scheduled` poller. The poller leases an agent from `AgentPool`, gives it a lean digest plus a dedicated
read-only MCP server scoped to the run, parses the JSON result, stores it, records AI usage, and fires an SSE
update after commit.

## Data Model

### Workflow definition

New optional boolean property `summarize` on workflow definitions. Summary generation is **off by default for
every workflow**:

- An absent or `null` value means `false`, and existing workflows are not migrated to `true`.
- The editor checkbox starts unchecked for new workflows.
- Only workflows explicitly set to `summarize: true` get automatic summaries for runs ending in `completed` or
  `failed`.
- The global settings do not enable summaries for all workflows. `axiom.run-summary.enabled` can only turn
  the feature off.

### Table `workflow_run_summary` (Flyway `V12__workflow_run_summary.sql`)

| column | type | notes |
|---|---|---|
| `run_id` | PK, FK → `workflow_run` | `ON DELETE CASCADE`; follows run retention |
| `status` | varchar(20) | `pending`, `generating`, `ready`, `failed` |
| `headline` | varchar(200) | nullable |
| `body` | TEXT | markdown, nullable |
| `error` | TEXT | nullable; last failure message |
| `engine` | varchar | engine that produced the summary |
| `model` | varchar | model that produced the summary |
| `trigger_type` | varchar(10) | `auto` or `manual` |
| `attempts` | int | capped at 2 automatic attempts |
| `created_on` | timestamp | |
| `completed_on` | timestamp | nullable |

Deleting a run must delete its summary automatically, at the database level. Runs are removed with bulk JPQL
deletes (`HistoryCleanup`, `SubjectDeletionService`, and workflow definition deletion), and these bypass JPA
cascades. The summary row is therefore protected in two places:

- **Flyway:** the migration declares
  `foreign key (run_id) references workflow_run on delete cascade`, following the `workflow_run_label`
  precedent in `V6`.
- **Entity:** `WorkflowRunSummaryEntity` maps `run_id` as `@OneToOne @MapsId` to `WorkflowRunEntity`, annotated
  `@OnDelete(action = OnDeleteAction.CASCADE)`. The dev profile's Hibernate `drop-and-create` schema then gets
  the same cascade.

No application code deletes summaries explicitly.

### System config

`SystemConfigEntity` gains nullable `summaryEngine` and `summaryModel` columns, added in the same migration.

## REST API (contract-first, `openapi.json`)

- New schema `WorkflowRunSummary`: `status`, `headline`, `body`, `error`, `engine`, `model`, `trigger`,
  `completedOn`.
- The full run bean (`getWorkflowRun`) includes an optional `summary` (`WorkflowRunSummary`).
- Run list items (`listWorkflowRuns`, `listWorkflowDefinitionRuns`, and the workbench run lists) include an
  optional lightweight `summary` with only `status` and `headline`.
- `POST /workflow/runs/{runId}/summary` (`generateWorkflowRunSummary`) upserts the row to `pending` with
  `trigger=manual`, resets `attempts` to 0, and returns `202 Accepted`.
  - `404` if the run does not exist.
  - `409` if the run is not terminal (`running`/`waiting`), or if its summary is currently `generating`.
  - Allowed for any terminal status, including `cancelled`, whether or not the workflow opted in.
- The system config get/update operations include `summaryEngine` and `summaryModel`.

## Generation Pipeline

### Enqueue

A new `onRunTerminal(WorkflowRunEntity)` helper in `WorkflowExecutionService` is called from every terminal
transition: `applyAdvancedInstance`, the synchronous-start completion path, and `cancelWorkflowRun`. It delegates
to `RunSummaryService.enqueueIfEnabled(run)`. That method inserts a `pending` row with `trigger=auto` only when:

- the status is `completed` or `failed`;
- the run's definition version has `summarize: true`;
- no summary row exists for the run.

The insert happens in the run's transaction, so a rollback enqueues nothing.

### Poller (`RunSummaryPoller`)

- `@Scheduled(every = "5s", concurrentExecution = SKIP)`.
- Claims up to `axiom.run-summary.batch-size` (default 2) `pending` rows with a conditional update to
  `generating`, so concurrent pollers cannot double-claim.
- At startup, rows stuck in `generating` are reset to `pending`.
- If no agent lease is available, the claim is reverted to `pending`; work is never dropped.
- Generation runs outside the claim transaction because the agent call is slow.

### Prompt (`RunSummaryPromptBuilder`, pure and unit-testable)

A lean digest, budgeted to about 3,000 characters:

- workflow name, run title, status, duration, failure reason (first lines);
- the ordered steps, each with node name, task status, and a one-line output preview (about 150 chars);
- the total AI cost of the run.

Instructions given to the agent:

- Use the available tools only when the digest is insufficient, especially to diagnose failures.
- Respond ONLY with JSON `{"headline": "...", "body": "..."}`.
- `headline` is at most 120 characters; `body` is 3–8 markdown bullets covering what the run did, key outputs,
  and the cause of any failure.
- Never reproduce secrets or credentials.

### Run-scoped MCP server (`axiom-run-summary-mcp`)

A new Node stdio MCP server template under `app/src/main/resources/templates/axiom-run-summary-mcp/`, installed
the same way as the other built-in servers. Its environment holds `AXIOM_API_URL`, `AXIOM_RUN_ID`, and
`AXIOM_SESSION_TOKEN`.

| tool | purpose |
|---|---|
| `get_run` | inputs, status, timings, labels, failure reason |
| `get_task(nodeId)` | full task input and output for the run's task at that node |
| `get_task_log(nodeId, offset, limit)` | paginated execution log for that task |
| `get_trace` | the run's trace nodes, failed nodes first |

- The server contains only read-only tools. It ignores any caller-supplied run ID and always uses
  `AXIOM_RUN_ID`. The backend also checks that the session token is bound to that run.
- `AgentRequest.allowedTools` lists exactly these four tools by name, and the agent gets no other tools
  (no shell, no file access, no web).

### Execution

- Engine: `summaryEngine`, falling back to the system default. Model: `summaryModel`, falling back to that
  engine's default.
- `maxSteps` = `axiom.run-summary.max-steps` (default 10); timeout = `axiom.run-summary.timeout-seconds`
  (default 300).
- The response is parsed leniently: the first JSON object is extracted, tolerating code fences. `headline` is
  required and truncated to 200 characters; `body` is optional.
- Success: store `ready`, `engine`, `model`, `completed_on`, and record an `AiUsageEntity` row linked to the
  run's workflow.
- Failure (agent error, timeout, or invalid JSON): increment `attempts`. If `attempts < 2`, go back to
  `pending`; otherwise store `failed` with `error`.
- After commit, fire `SseEvent.workflowUpdated(runId)`.

### Configuration (`application.properties`)

- `axiom.run-summary.enabled` (default `true`): a global kill switch for the automatic and manual paths.
- `axiom.run-summary.batch-size`, `axiom.run-summary.max-steps`, `axiom.run-summary.timeout-seconds`.

## UI

### Runs list (`WorkflowRunsPage.tsx`, workbench `RunsList.tsx`)

- `ready`: the headline appears as a muted line under the run title.
- `pending`/`generating`: a small spinner with "Summarizing…".
- `failed` or no summary: nothing is shown.
- The list refreshes through the existing `workflowUpdated` SSE handling.

### Run detail (`WorkflowRunDetailPage.tsx`)

A Summary card above the steps:

- `ready`: bold headline, the markdown body, and a muted footer "Generated by {engine}/{model} · {time}". A kebab
  menu offers "Regenerate".
- `pending`/`generating`: a skeleton with "Generating summary…".
- `failed`: an inline warning with the error and a "Retry" button.
- No summary on a terminal run: a "Summarize this run" button.
- Non-terminal run: the card is hidden.

### Settings

The system settings page gets a "Run summaries" section with engine and model pickers that reuse the existing
model picker lists. A hint explains the fallback to the system default.

### Workflow editor

A "Generate AI summary for completed/failed runs" checkbox bound to `summarize`.

## Error Handling

- Agent unavailable: the row stays `pending` and is retried on the next poll.
- Agent failure, timeout, or bad JSON: up to 2 attempts, then `failed` with the error shown in the UI and a
  manual Retry.
- Process restart: `generating` rows are recovered to `pending` at startup.
- Run deleted by retention: the summary is removed with it.
- A summary failure never affects the run's own status.

## Testing

- **Unit (JUnit 5):**
  - `RunSummaryPromptBuilder`: digest contents, budget, and truncation.
  - The lenient JSON parser.
  - `enqueueIfEnabled` rules: opt-in (absent or `false` means no row), terminal status filter, no duplicates.
- **Cascade:** deleting runs through each bulk path (`HistoryCleanup`, `SubjectDeletionService`, and workflow
  definition deletion) leaves no orphaned `workflow_run_summary` rows.
- **`@QuarkusTest` with a stub `Agent`:**
  - POST endpoint: 202, 404, and 409 cases.
  - Poller: claiming, recovery after restart, and the retry → `failed` path.
  - AI usage is recorded.
  - SSE fires after commit.
- **MCP server:** run scoping (other run IDs rejected or ignored) and log pagination.
- **UI:** summary card states, and headline rendering in the runs list.
- **Manual live check:** an isolated instance
  (`AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data`).
