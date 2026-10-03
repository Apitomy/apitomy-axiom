# Correlation by Trace ID

Axiom uses the **trace ID** as its correlation ID. Every unit of work (event processing, a task, a workflow
run, a scheduled job run or a report) is identified by its trace ID. Every record produced by that work stores
the trace ID, so given one trace ID you can find everything that belongs to that unit of work. See
[Tracing](tracing.md) for the trace model itself.

## Tables That Carry `trace_id`

| Table               | Notes                                     |
|---------------------|-------------------------------------------|
| `task`              | Trace the task runs in                    |
| `report`            | Trace of the report generation            |
| `workflow_run`      | Trace of the workflow run                 |
| `scheduled_job_run` | Trace of the scheduled job run            |
| `routing_outcome`   | Trace of the event that was routed        |
| `activity_log`      | Added in migration `V66`                  |
| `ai_usage`          | Added in migration `V66`                  |

All columns are nullable UUIDs with no foreign key. Trace data has a bounded lifetime: when `TraceCleanup`
deletes a trace, it clears `trace_id` on `activity_log`, `ai_usage`, `task`, `scheduled_job_run`, `report`,
`workflow_run` and `routing_outcome`, and `triggered_by_trace_id` on `scheduled_job_run` and `report`. It also
clears `trace_node_id` on `routing_outcome_item` and `workflow_run_resume` for the deleted trace's nodes. See
[Data Retention](data-retention.md).

## Scheduled Job Run and Report Columns

Traces are deleted after the retention period, so scheduled job runs and reports are also linked to their AI
usage and activity rows by ID (migration `V70`, #424 and #425). These links remain after the trace is
deleted.

| Table               | Column                  | Written by                                          |
|---------------------|-------------------------|-----------------------------------------------------|
| `ai_usage`          | `scheduled_job_run_id`  | `ScheduledJobExecutionService` (agent mode)         |
| `ai_usage`          | `report_id`             | `ReportExecutionService`                            |
| `activity_log`      | `scheduled_job_run_id`  | `ScheduledJobExecutionService` (agent and script)   |
| `activity_log`      | `report_id`             | `ReportExecutionService`                            |
| `activity_log`      | `report_definition_id`  | `ReportExecutionService`                            |

All are nullable `BIGINT` columns with no foreign key. Rows written before `V70` have no value.

Deleting a scheduled job, a report or a report definition, or removing job runs and reports with the
retention settings, intentionally keeps their `ai_usage` and `activity_log` rows, so cost and activity history
survive. The same applies to `trace.report_id`. Their `scheduled_job_run_id`, `report_id` and
`report_definition_id` values may then refer to rows that no longer exist; consumers must treat these IDs as
possibly dangling.

In the UI, the Activity log and AI usage pages have a **Source** column that links a row to its report
(`/reports/:id`) or, for job runs, to the job runs page (`/logs/job-runs`). The rows carry only the run ID,
not the job ID, and that page cannot yet be filtered or anchored to a single run.

- `GET /activity?filterScheduledJobRunId={id}` and `GET /usage/ai?filterScheduledJobRunId={id}` return the
  rows of one run. `filterReportId` does the same for a report.
- `GET /traces?filterScheduledJobRunId={id}` returns the run's trace. Reports already had
  `GET /traces?filterReportId={id}`.
- A malformed (non-numeric) value for any of these filters returns `400 Bad Request`. These filters are
  typed as strings in OpenAPI for this reason. Integer-typed query parameters such as `filterProjectId`
  return `404` for malformed values, which is standard JAX-RS behaviour.

In the UI, each row of the Scheduled Job Runs page links to the run's trace, AI usage
(`/metrics/ai-usage?scheduledJobRunId=`) and activity (`/logs/activity?scheduledJobRunId=`). The report
detail page shows the report's AI cost with links to `/metrics/ai-usage?reportId=` and
`/logs/activity?reportId=`. Both pages accept these query parameters as deep links, as with `?traceId=`.

## Who Triggered a Run or Report

`scheduled_job_run` and `report` record how and by whom they were triggered:

| Column                  | Values                                                                        |
|-------------------------|-------------------------------------------------------------------------------|
| `run_trigger` / `report_trigger` | `scheduled` or `manual`                                              |
| `triggered_by`          | `scheduler` for scheduled runs, `manual` for runs started through the REST API |
| `triggered_by_trace_id` | For manual runs, the caller's trace if the request carried a valid caller trace |

Axiom has no authentication, so `triggered_by` cannot hold a user identity. If authentication is added later,
`triggered_by` is where the user should be recorded. `triggered_by_trace_id` is set when an agent triggers a
run through `POST /scheduled-jobs/{id}/run` or `POST /reports/definitions/{id}/run` and its request carries
the caller trace headers (see below). The new run still gets its own trace; it does not join the caller's.

`V70` sets `triggered_by` on existing scheduled job runs from `run_trigger`. Existing reports keep `NULL` for
all three columns, because their trigger was never recorded. The API exposes these as `trigger`,
`triggeredBy` and `triggeredByTraceId` on `ScheduledJobRun` and `Report`.

## Finding All Records for a Trace

- `GET /traces/{id}` returns the trace tree (nodes, tool calls).
- `GET /activity?filterTraceId={id}` returns the activity rows for the trace.
- `GET /usage/ai?filterTraceId={id}` returns the AI usage rows for the trace.
- Tasks, workflow runs, scheduled job runs and reports expose a `traceId` field.

For both filters, a malformed `filterTraceId` returns `400 Bad Request`.

In the UI, the Activity log and AI usage pages show a **Trace** column that links to `/logs/traces/:traceId`,
and a **Trace ID** filter. Both pages accept a `?traceId=` query parameter as a deep link, for example
`/logs/activity?traceId=...`.

The Manager Decisions page links each evaluation to its event and its trace. Expanding a `manager-evaluated`
row shows the decisions recorded in the trace (with their reasoning) and the tasks each decision created.
The Manager's `manager-evaluated` / `manager-error` activity row and its `ai_usage` row carry both the event
ID and the trace ID, so the AI cost of an evaluation can be found from either. This includes manual
evaluations through `POST /manager/evaluate/{eventId}`: their rows carry the ID of the `manager-dry-run`
trace, which the endpoint returns in the `X-Axiom-Trace-Id` response header.

## Agent Calls: Joining the Caller's Trace

When an agent calls Axiom APIs through the Axiom MCP server, `sdk-server.js` sends two headers on every
request when the matching environment variables are set:

| Header                   | Source variable         |
|--------------------------|-------------------------|
| `X-Axiom-Trace-Id`       | `AXIOM_TRACE_ID`        |
| `X-Axiom-Parent-Node-Id` | `AXIOM_PARENT_NODE_ID`  |

`CallerTraceFilter` validates the headers in this order:

1. The trace ID is a valid UUID.
2. The trace exists.
3. The trace status is `in-progress`.
4. The parent node belongs to that trace. If the parent node header is missing, the trace's root node is used.

If any check fails, the headers are removed and ignored, and a debug log is written. A request never fails
because of these headers.

`POST /projects/{id}/tasks` joins a valid caller trace: the new task joins the caller's trace and its task
node is added under the caller's parent node, instead of a new `user-action` trace being created. The task's
`createdBy` stays `user`. `POST /scheduled-jobs/{id}/run` and `POST /reports/definitions/{id}/run` only
record the caller trace in `triggered_by_trace_id`. Other endpoints ignore the caller trace.

## Server Logs: Correlation IDs in the MDC

Server log lines carry the correlation IDs of the work they belong to (#428). The IDs are put into the
logging MDC (`org.jboss.logging.MDC`) by `io.apitomy.axiom.core.logging.LogContext`:

| MDC key         | Value                                  |
|-----------------|----------------------------------------|
| `traceId`       | Trace (correlation) ID                 |
| `eventId`       | Stream event being routed              |
| `projectId`     | Project                                |
| `taskId`        | Task                                   |
| `runId`         | Scheduled job run                      |
| `workflowRunId` | Workflow run                           |
| `reportId`      | Report                                 |

`LogContext` also maintains the composite key `axiomCtx`, which holds only the keys that are present, for
example `[traceId=3f2a9c1e-... projectId=7 taskId=42] `, and is absent when none are set. The console and file
log formats in `application.properties` print it with `%X{axiomCtx}`, so lines outside any unit of work look
exactly as before:

```
2026-10-03 11:53:27 INFO  [io.ap.ax.ap.TaskExecutionService] (main) [traceId=3f2a9c1e-... projectId=7 taskId=42] Task 42 completed
2026-10-03 11:53:27 INFO  [io.ap.ax.ap.EventStreamOrchestrator] (executor-thread-1) Recovered 2 orphaned pending ledger entries
```

### Grepping the Logs

Given a trace ID (from the trace UI, a task, a report, a run or a `routing_outcome` row):

```bash
grep 'traceId=3f2a9c1e-0000-4000-8000-000000000001' axiom.log
# Everything for one task, scheduled job run, report or stream event
grep -E 'taskId=42[] ]' axiom.log
grep -E 'runId=17[] ]' axiom.log
grep -E 'reportId=5[] ]' axiom.log
grep 'eventId=6b1d...' axiom.log
```

Match numeric IDs with a trailing `]` or space (as above) so that `taskId=4` does not also match `taskId=42`.
The console log goes to standard output; the file log is written only when `quarkus.log.file.enabled=true`.

A failed routing rule logs two lines: `Routing rule <type> failed for event <id>: <message>`, written inside
the rule's context (so it carries the `eventId` and the rule's `traceId`) and **without** a stack trace, and then
`Routing failed for event <id> / subscription <id>`, written in the event's context (`eventId` only) **with**
the stack trace. The stack trace is logged once, on the second line, because not every routing failure
passes through a rule. To find the stack trace for a trace ID, grep the trace ID, then grep the `eventId` shown
on the rule line.

### Where the Context Is Set

| Entry point                                        | Keys                                                  |
|----------------------------------------------------|-------------------------------------------------------|
| `EventStreamOrchestrator`, per event and ledger retry | `eventId`; per routing rule also `traceId` once the Manager or invoke-action trace exists |
| `TaskExecutionService.executeTask`, `onTaskCompleted`, `failTask` | `traceId`, `eventId`, `projectId`, `taskId`, `workflowRunId` |
| `ScriptExecutionService.executeScript` (incl. the async script body) | as for tasks                     |
| `ScheduledJobQueueConsumer`, `ScheduledJobExecutionService.executeRun` | `runId`, then `traceId`      |
| `ReportQueueConsumer`, `ReportExecutionService.generateReport` | `reportId`, then `traceId`           |
| `WorkflowExecutionService` start and advance (task completion, wait elapsed, event received) | `traceId`, `projectId`, `workflowRunId` |
| `CallerTraceFilter` (REST requests with a valid `X-Axiom-Trace-Id`) | `traceId`                           |

### Rules for New Code

- Always use try-with-resources: `try (LogContext ignored = LogContext.create().traceId(id).taskId(t)) {...}`.
  `close()` restores the previous values, so contexts nest (a task started during event routing logs with the
  task's trace, then the event's context is back) and nothing leaks to the next job on a pooled, virtual or
  scheduler thread, including when the body throws.
- The MDC belongs to the current thread (or, on Vert.x, to the request's duplicated context). Work handed to
  another thread loses it. Agent completion callbacks (`CompletableFuture.thenAccept` / `exceptionally`) run
  on the agent's thread, usually a common `ForkJoinPool` worker, so they are wrapped with
  `LogContext.wrap(...)`, which captures the context when the callback is created and restores it, and
  removes it again, around the callback. Do the same for new asynchronous code, or open a `LogContext`
  inside the asynchronous body as `ScriptExecutionService` does.
- Code running inside agent subprocess readers (`agents/*`) runs on threads Axiom does not wrap; those lines
  have no correlation IDs.

### OpenTelemetry (Follow-Up Evaluation)

`quarkus-opentelemetry` would add W3C trace context propagation, spans for REST, JDBC and the scheduler, and
export to a collector (Jaeger, Tempo). With it enabled, Quarkus also puts its own `traceId`/`spanId` in the
MDC. Notes for a follow-up:

- OpenTelemetry trace IDs are 32 hex characters; Axiom trace IDs are UUIDs stored in the database. They would
  be separate IDs. Keep the Axiom trace ID as the correlation ID and add it as a span attribute
  (`axiom.trace_id`) rather than trying to make the two equal. The MDC key `traceId` would clash with the
  OpenTelemetry one; rename ours (for example `axiomTraceId`) at that point.
- Context propagation across the `CompletableFuture` callbacks and the queue consumer threads has the same
  problem as the MDC and would need the same wrapping (or SmallRye Context Propagation's `ManagedExecutor`).
- It needs a collector to be useful; Axiom is a single-process app with its own trace UI, so the benefit is
  mainly for deployments that already run an observability stack. It is not added for now.

## Accepted Risk

An agent can keep its trace open by creating tasks that join it, because `TaskTraceFinalizer` keeps a trace
open while any task node is in progress. This is accepted: agent-created tasks are deliberate work that
belongs to the trace.

## Known Limitations

- Any in-progress trace can be joined. There is no check that the caller belongs to the same project.
- AI editor and assistant sessions (`ActionTypeAiService`, `ReportAiService`, `ScriptAiService`,
  `ToolAiService`, `AssistantSessionManager`) are not traced. Their AI usage rows have no trace ID.
- Tasks that join a report, scheduled-job or workflow trace never complete it; the owning service does. If
  the owner finishes first, its trace is marked complete while the joined task may still be running. The
  task's own node still completes when the task finishes.
- Script-mode scheduled job runs have no `ai_usage` row, because no AI is invoked.
- Out of scope for now: the lineage view (#430).
