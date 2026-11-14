# Workflow Run AI Summaries — Design

GitHub issue: [#672](https://github.com/Apitomy/apitomy-axiom/issues/672)

## Goal

Generate an optional, AI-written summary of a workflow run after it finishes, so users can scan the runs list
and understand what a run did at a glance without opening every step.

## Requirements

- **Trigger:** automatic for workflows that opt in (`summarizeRuns`), plus an on-demand "Summarize" button for
  any terminal run.
- **Automatic scope:** only runs that end `completed` or `failed`. Cancelled runs are summarized only on demand.
- **Engine/model:** global settings `summaryEngine` / `summaryModel`. When unset, fall back to the system
  default engine and that engine's configured default model.
- **Format:** one-line headline (≤120 chars) for the runs list, plus a 3–8 bullet markdown body for the run
  detail page, produced by a single agent session that returns JSON.
- **Investigation:** the agent receives a lean digest and can query a read-only set of Axiom MCP tools for
  deeper detail (task outputs, logs, trace).
- **Non-goals:** per-workflow engine/model overrides, deleting summaries, summarizing non-terminal runs,
  summaries for cancelled runs without a manual request, strict per-run tool scoping (see below).

## Architecture Overview

A DB-backed queue (`workflow_run_summary` table) is populated by a hook at run termination and drained by a
`@Scheduled` poller. The poller leases an agent from `AgentPool`, gives it a lean digest plus a read-only,
stateless MCP server (`axiom-run-summary`), parses the JSON result, stores it, records AI usage, and fires an
SSE update after the transaction commits.

## Data Model

### Workflow definition opt-in

A new column `summarize_runs boolean not null default false` on `workflow_definition`, mapped as
`WorkflowDefinitionEntity.summarizeRuns`. Summary generation is **off by default for every workflow**:

- The migration's `default false` covers existing rows, so no existing workflow is opted in.
- New definitions are created with `false` unless the create request sets `summarizeRuns: true`.
- The setting is edited in the definition's existing "Edit Workflow Definition" modal (name/description).
- The global settings never enable summaries for all workflows. `axiom.run-summary.enabled` can only turn the
  feature off.

Why a column instead of a key in the workflow content: the content is parsed into the external flow-engine
`Workflow` record, which has no slot for extra properties, and OpenAPI treats content as opaque. A hidden
top-level key could be silently dropped by the editor. The trade-off is that the setting is not versioned
with the content: it is read from the definition as it stands when a run finishes.

### Table `workflow_run_summary` (Flyway `V12__workflow_run_summary.sql`)

| column | type | notes |
|---|---|---|
| `run_id` | bigint PK, FK → `workflow_run` | `ON DELETE CASCADE` |
| `status` | varchar(20) not null | `pending`, `generating`, `ready`, `failed` |
| `headline` | varchar(200) | nullable |
| `body` | TEXT | markdown, nullable |
| `error` | TEXT | nullable; last failure message |
| `engine` | varchar(255) | engine that produced the summary |
| `model` | varchar(255) | model that produced the summary |
| `trigger_type` | varchar(10) not null | `auto` or `manual` |
| `attempts` | integer not null | failed attempts so far; max 2 |
| `created_on` | timestamp(6) with time zone not null | |
| `completed_on` | timestamp(6) with time zone | nullable |

Deleting a run must delete its summary automatically, at the database level. Runs are removed with bulk JPQL
deletes (`HistoryCleanup`, `SubjectDeletionService`, and workflow definition deletion), and these bypass JPA
cascades. The summary row is therefore protected in two places:

- **Flyway:** the migration declares
  `foreign key (run_id) references workflow_run on delete cascade`, following the `workflow_run_label`
  precedent in `V6`.
- **Entity:** `WorkflowRunSummaryEntity` maps `run_id` as `@OneToOne @MapsId` to `WorkflowRunEntity`, annotated
  `@OnDelete(action = OnDeleteAction.CASCADE)`. The dev and test profiles' Hibernate `drop-and-create` schema
  then gets the same cascade.

No application code deletes summaries explicitly. (`deleteWorkflowDefinition` has a comment preferring explicit
deletes over DB cascades; this feature deliberately follows the `V6` precedent instead, per the requirement.)

### System config

`SystemConfigEntity` gains nullable `summaryEngine` and `summaryModel` columns, added in the same migration.

## REST API (contract-first, `openapi.json`)

The name `WorkflowRunSummary` is already used by the run list item, so the new schema is named
`WorkflowRunAiSummary` and the bean property is `aiSummary`.

- New schema `WorkflowRunAiSummary`: `status`, `headline`, `body`, `error`, `engine`, `model`, `trigger`,
  `completedOn`.
- The full run bean (`WorkflowInstance`, returned by `getWorkflowRun`) gets an optional `aiSummary`.
- The run list item (`WorkflowRunSummary`) gets an optional `aiSummary`, populated with only `status` and
  `headline`.
- `POST /workflow/runs/{runId}/ai-summary` (`generateWorkflowRunAiSummary`) upserts the row to `pending` with
  `trigger=manual`, resets `attempts` to 0, and returns `202 Accepted`. The response is declared as
  `"202": {"content": {"application/json": {}}}` so the generated method returns `Response`.
  - `404` if the run does not exist.
  - `409` if the run is not terminal (`running`/`waiting`), or if its summary is currently `generating`.
  - `503` if `axiom.run-summary.enabled=false`.
  - Allowed for any terminal status, including `cancelled`, whether or not the workflow opted in.
- `WorkflowDefinition`, `CreateWorkflowDefinitionRequest`, and `UpdateWorkflowDefinitionRequest` get an optional
  boolean `summarizeRuns`. On update, an absent value leaves the setting unchanged.
- `SystemConfig` and `UpdateSystemConfigRequest` get optional `summaryEngine` and `summaryModel`.
  `UpdateSystemConfigRequest` keeps `defaultEngine` required. The summary fields are updated only when present
  in the request, and an empty string clears them back to "use the default".

## Generation Pipeline

### Enqueue

A new `onRunTerminal(WorkflowRunEntity)` helper in `WorkflowExecutionService` is called from every terminal
transition: `applyAdvancedInstance`, the synchronous-start completion path, and `cancelWorkflowRun`. It delegates
to `RunSummaryService.enqueueIfEnabled(run)`. That method inserts a `pending` row with `trigger=auto` only when:

- `axiom.run-summary.enabled` is `true`;
- the status is `completed` or `failed`;
- the run's definition has `summarizeRuns = true`;
- no summary row exists for the run.

The insert happens in the run's transaction, so a rollback enqueues nothing.

### Poller (`RunSummaryPoller`)

- `@Scheduled(every = "${axiom.run-summary.poll-interval:5s}", concurrentExecution = SKIP)`; turned `off` in
  the test profile, like the task queue poller.
- Claims up to `axiom.run-summary.batch-size` (default 2) `pending` rows with a conditional update
  (`status = 'generating' where runId = ? and status = 'pending'`), so concurrent pollers cannot double-claim.
- At startup, rows stuck in `generating` are reset to `pending`.
- If no agent lease is available, the claim is reverted to `pending`; work is never dropped.
- Generation runs outside the claim transaction because the agent call is slow.

### Prompt (`RunSummaryPromptBuilder`, pure and unit-testable)

A lean digest, budgeted to about 3,000 characters:

- run ID, workflow name, run title, status, duration, failure reason (first lines);
- the ordered steps, each with task ID, node ID, task status, and a one-line output preview (about 150 chars);
- the total AI cost of the run.

Instructions given to the agent:

- The run ID to pass to the tools.
- Use the available tools only when the digest is insufficient, especially to diagnose failures.
- Respond ONLY with JSON `{"headline": "...", "body": "..."}`.
- `headline` is at most 120 characters; `body` is 3–8 markdown bullets covering what the run did, key outputs,
  and the cause of any failure.
- Never reproduce secrets or credentials.

### Read-only MCP server (`axiom-run-summary`)

A new Node stdio MCP server template under `app/src/main/resources/templates/axiom-run-summary-mcp/`, installed
the same way as the other built-in servers. Its environment holds only `AXIOM_API_URL`. It is **stateless**:
every tool takes a `runId` argument, so a single registration can be shared across concurrent summaries and
across all engines (OpenCode registers MCP servers once per shared process; Claude Code and Copilot use a
per-request config file).

| tool | purpose |
|---|---|
| `get_run(runId)` | inputs, status, timings, labels, failure reason, task list (task ID, node ID, status) |
| `get_task(runId, taskId)` | full task input and output (the task must belong to the run) |
| `get_task_log(runId, taskId, offset, limit)` | paginated execution log for that task |
| `get_trace(runId)` | the run's trace nodes, failed nodes first |

**Scoping trade-off (accepted):** the tools can read any workflow run on this Axiom instance, not only the run
being summarized. The prompt tells the agent which run to examine. The guarantees that do hold:

- The server contains only read-only tools and calls only GET endpoints.
- It can reach only workflow runs and their tasks, logs, and traces; no other Axiom data.
- `AgentRequest.allowedTools` lists exactly these four tools (`mcp__axiom-run-summary__*` names), and the agent
  gets no other tools (no shell, file, or web access).

Wiring per engine:

- **Claude Code / Copilot:** `RunSummaryService` writes a per-summary MCP config file containing only
  `axiom-run-summary` and passes it as `AgentRequest.mcpConfigFile`.
- **OpenCode:** `McpConfigGenerator.getMcpServersForOpenCode` includes `axiom-run-summary` when the allowed tools
  reference it, and `OpenCodeMcpManager` treats it as a built-in server (registered once, never disconnected).

### Execution

- Engine: `summaryEngine`, falling back to `SystemConfigEntity.defaultEngine`, then the registry default. Model:
  `summaryModel`, or `null` so the agent uses its configured default model.
- `maxSteps` = `axiom.run-summary.max-steps` (default 10); timeout = `axiom.run-summary.timeout-seconds`
  (default 300).
- The response is parsed leniently: the first JSON object is extracted, tolerating code fences. `headline` is
  required and truncated to 200 characters; `body` is optional.
- Success: store `ready`, `engine`, `model` (from `AgentResult`, falling back to the requested values),
  `completed_on`, and record an `AiUsageEntity` row with `invocationType = "run-summary"`, linked to the run and
  its workflow definition.
- Failure (agent error, timeout, or invalid JSON): increment `attempts`. If `attempts < 2`, go back to
  `pending`; otherwise store `failed` with `error`.
- After commit, fire `SseEvent.workflowUpdated(subjectId, runId, runStatus)` through an `AFTER_SUCCESS`
  observer (the `SecretExpirySseNotifier` pattern).

### Configuration (`application.properties`)

- `axiom.run-summary.enabled` (default `true`): a global kill switch for the automatic and manual paths.
- `axiom.run-summary.poll-interval` (default `5s`; `%test` = `off`).
- `axiom.run-summary.batch-size` (2), `axiom.run-summary.max-steps` (10),
  `axiom.run-summary.timeout-seconds` (300).

## UI

### Runs list (`WorkflowRunsPage.tsx`, workbench `RunsList.tsx`)

- `ready`: the headline appears as a muted line under the run title.
- `pending`/`generating`: a small spinner with "Summarizing…".
- `failed` or no summary: nothing is shown.
- The list refreshes through the existing `workflow-updated` SSE handling.

### Run detail (`WorkflowRunDetailPage.tsx`)

A Summary card between the header and the tabs:

- `ready`: bold headline, the markdown body, and a muted footer "Generated by {engine}/{model} · {time}". A
  "Regenerate" action.
- `pending`/`generating`: a skeleton with "Generating summary…".
- `failed`: an inline warning with the error and a "Retry" button.
- No summary on a terminal run: a "Summarize this run" button.
- Non-terminal run: the card is hidden.

### Settings (`EngineSettingsPage.tsx`)

A "Run summaries" card with engine and model selects. The engine select has a "System default" option, and the
model select has an "Engine default" option. A hint explains the fallback.

### Workflow definition

A "Generate AI summaries for completed and failed runs" checkbox in the "Edit Workflow Definition" modal,
bound to `summarizeRuns` and unchecked by default.

## Error Handling

- Agent unavailable: the row stays `pending` and is retried on the next poll.
- Agent failure, timeout, or bad JSON: up to 2 attempts, then `failed` with the error shown in the UI and a
  manual Retry.
- Process restart: `generating` rows are recovered to `pending` at startup.
- Run deleted by any path: the summary is removed with it by the DB cascade.
- A summary failure never affects the run's own status.

## Testing

- **Unit (JUnit 5):**
  - `RunSummaryPromptBuilder`: digest contents, budget, and truncation.
  - `RunSummaryResponseParser`: plain JSON, fenced JSON, missing headline, overlong headline.
- **`@QuarkusTest`:**
  - `enqueueIfEnabled` rules: opt-in off by default, terminal status filter, no duplicates, kill switch.
  - Cascade: deleting runs through each bulk path (`HistoryCleanup`, `SubjectDeletionService`, and workflow
    definition deletion) leaves no `workflow_run_summary` rows.
  - POST endpoint: 202, 404, 409, and 503 cases.
  - Generation with a mocked `AgentPool`/`Agent`: success stores `ready` and records AI usage; failures retry
    then end `failed`; no lease reverts to `pending`; startup recovery.
  - Bean mapping: `aiSummary` on run detail and list items; `summarizeRuns` on definitions; summary settings on
    system config.
  - `MigrationSchemaValidationTest` passes with V12.
- **MCP server (`node:test`):** only GET calls, task-belongs-to-run check, log pagination, tool list is exactly the
  four tools.
- **UI (vitest):** summary card states, headline rendering in the runs list, settings card, opt-in checkbox.
- **Manual live check:** an isolated instance
  (`AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data`).
