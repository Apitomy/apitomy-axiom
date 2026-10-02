# Design: Trace ID as the Correlation ID (#427)

Part of epic #431 (traceability). Builds on #414 (UUID event IDs) and #415 (trace lifecycle).

## Goal

Given a trace ID, a user can find every activity row, AI usage row, task and run that belongs to that unit of
work, including records that agents create through Axiom APIs.

## Model

- Every unit of work (event processing, task, workflow run, scheduled job run, report) is identified by its
  **trace ID**. No new correlation concept is introduced.
- Every record produced by that work stores the trace ID in a `trace_id` column.
- Trace data has a bounded lifetime. When `TraceCleanup` deletes a trace, it clears `trace_id` on all records
  that reference it (it already does this for tasks, scheduled job runs and reports).

## Database and API

- Migration `V66`: add nullable `trace_id UUID` plus an index to `activity_log` and `ai_usage`. No foreign key,
  consistent with the other `trace_id` columns, since cleanup clears references explicitly.
- `openapi.json`:
  - Add `traceId` (`string`, `format: uuid`) to the `ActivityLogEntry` and `AiUsage` beans.
  - Add a `filterTraceId` query parameter to `GET /activity` and `GET /usage/ai`. A malformed UUID returns 400,
    matching `filterEventId`.
  - Document the optional `X-Axiom-Trace-Id` and `X-Axiom-Parent-Node-Id` request headers on the agent-callable
    write endpoints listed below.

## Populating trace IDs

- `logActivity` helpers in `EventStreamOrchestrator`, `TaskExecutionService`, `ScriptExecutionService`,
  `WorkflowExecutionService`, `ReportExecutionService` and `ScheduledJobExecutionService` gain a `traceId`
  parameter. Each call site passes the trace already in scope (`task.traceId`, `run.traceId`, `report.traceId`,
  or `traceCtx.traceId()`).
- `handleIgnore` and `handleEscalation` in `EventStreamOrchestrator` receive the trace context so their activity
  rows carry the trace ID.
- AI usage written by `TaskExecutionService`, `ReportExecutionService` and `ScheduledJobExecutionService` stores
  the trace ID.
- Report and scheduled-job activity rows set existing ID columns (e.g. `projectId`) where they apply. Run and
  report ID columns are out of scope (#424, #425).

## Agent to Axiom API propagation

- `templates/axiom-mcp-server/sdk-server.js` (`axiomApi`) sends `X-Axiom-Trace-Id` and `X-Axiom-Parent-Node-Id`,
  read from `AXIOM_TRACE_ID` and `AXIOM_PARENT_NODE_ID`, on every request when those variables are set.
- A new JAX-RS `ContainerRequestFilter` reads the headers into a `@RequestScoped` `CallerTraceContext` bean.
  The context is used only if the trace ID is a valid UUID, the trace exists, its status is `in-progress`,
  and the parent node (if given) belongs to that trace. Otherwise the headers are ignored, and a debug log is
  written.
- `POST /projects/{id}/tasks`: when a caller trace is present, the new task joins it. The task's `traceId` is
  set to the caller's trace and its task node is added under the caller's parent node, instead of a new
  `user-action` trace being created. `TaskTraceFinalizer` (#415) already keeps the trace open while that node is
  in progress.
- The other agent write endpoints (`POST /projects/{id}/thread`, `PUT /projects/{id}`, `PUT /projects/{id}/body`,
  `POST /projects/{id}/close`, `POST /projects/{id}/reopen`, `POST /projects/{id}/tasks/{taskId}/respond`,
  `POST /projects`, `POST /events`, `PUT /reports/{id}/labels`) store the caller's trace ID on any activity row
  they write. They do not add trace nodes.

## Out of scope

- AI editor and assistant sessions (`ActionTypeAiService`, `ReportAiService`, `ScriptAiService`,
  `ToolAiService`, `AssistantSessionManager`) have no trace today. Their AI usage rows stay unlinked.
- MDC log correlation (#428), run and report ID columns (#424, #425), lineage view (#430).

## Accepted risk

An agent can keep its trace open by creating tasks that join it. This is accepted because agent-created tasks
are deliberate work that belongs to the trace.

## UI

- Activity log and AI usage pages show the trace ID as a link to `/logs/traces/:traceId` and offer a trace ID
  filter.

## Testing (JUnit 5)

- Activity and AI usage filtered by trace ID return the expected rows. A malformed trace ID returns 400.
- Activity and AI usage written by the task, report and scheduled job paths carry the trace ID.
- A task created with valid headers joins the caller's trace under the parent node, and the trace stays open
  until that task finishes.
- Headers with an unknown, finished or malformed trace are ignored, and a new `user-action` trace is created.
- `TraceCleanup` clears `trace_id` on `activity_log` and `ai_usage`.
- UI type-check passes.

## Documentation

- New `docs/developer-guide/correlation.md` describing the model, the headers and how to find all records for
  a unit of work.
- Update `docs/developer-guide/tracing.md` to reference it.
