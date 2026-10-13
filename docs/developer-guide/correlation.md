# Correlation by Trace ID

Axiom uses the **trace ID** as its correlation ID. Every unit of work — a workflow run and the tasks its
nodes create — is identified by its trace ID. Every record produced by that work stores
the trace ID, so given one trace ID you can find everything that belongs to that unit of work. See
[Tracing](tracing.md) for the trace model itself.

## Tables That Carry `trace_id`

| Table               | Notes                                     |
|---------------------|-------------------------------------------|
| `task`              | Trace the task runs in                    |
| `workflow_run`      | Trace of the workflow run                 |
| `routing_outcome`   | Trace of the run the routing rule started |
| `activity_log`      | Trace of the work that wrote the row      |
| `ai_usage`          | Trace of the work that incurred the cost  |

All columns are nullable UUIDs with no foreign key. Trace data has a bounded lifetime: when `TraceCleanup`
deletes a trace, it clears `trace_id` on `activity_log`, `ai_usage`, `task`, `workflow_run` and
`routing_outcome`. It also clears `trace_node_id` on `routing_outcome_item` and `workflow_run_resume` for the
deleted trace's nodes. See [Data Retention](data-retention.md).

## Finding All Records for a Trace

- `GET /traces/{id}` returns the trace tree (nodes, tool calls).
- `GET /activity?filterTraceId={id}` returns the activity rows for the trace.
- `GET /usage/ai?filterTraceId={id}` returns the AI usage rows for the trace.
- Tasks and workflow runs expose a `traceId` field.

For both filters, a malformed `filterTraceId` returns `400 Bad Request`.

In the UI, the Activity log and AI usage pages show a **Trace** column that links to `/logs/traces/:traceId`,
and a **Trace ID** filter. Both pages accept a `?traceId=` query parameter as a deep link, for example
`/logs/activity?traceId=...`.

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

A valid caller trace is put into the logging MDC for the rest of the request, so server log lines written
while handling the agent's call carry the caller's trace ID. No endpoint creates work in the caller's
trace: tasks are created only by workflow nodes, in the run's own trace.

## Server Logs: Correlation IDs in the MDC

Server log lines carry the correlation IDs of the work they belong to. The IDs are put into the
logging MDC (`org.jboss.logging.MDC`) by `io.apitomy.axiom.core.logging.LogContext`:

| MDC key         | Value                                  |
|-----------------|----------------------------------------|
| `traceId`       | Trace (correlation) ID                 |
| `eventId`       | Stream event being routed              |
| `subjectId`     | Subject                                |
| `taskId`        | Task                                   |
| `workflowRunId` | Workflow run                           |

`LogContext` also maintains the composite key `axiomCtx`, which holds only the keys that are present, for
example `[traceId=3f2a9c1e-... subjectId=7 taskId=42] `, and is absent when none are set. The console and file
log formats in `application.properties` print it with `%X{axiomCtx}`, so lines outside any unit of work look
exactly as before:

```
2026-10-03 11:53:27 INFO  [io.ap.ax.ap.TaskExecutionService] (main) [traceId=3f2a9c1e-... subjectId=7 taskId=42] Task 42 completed
2026-10-03 11:53:27 INFO  [io.ap.ax.ap.EventStreamOrchestrator] (executor-thread-1) Recovered 2 orphaned pending ledger entries
```

### Grepping the Logs

Given a trace ID (from the trace UI, a task, a workflow run or a `routing_outcome` row):

```bash
grep 'traceId=3f2a9c1e-0000-4000-8000-000000000001' axiom.log
# Everything for one task, workflow run or stream event
grep -E 'taskId=42[] ]' axiom.log
grep -E 'workflowRunId=17[] ]' axiom.log
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
| `EventStreamOrchestrator`, per event and ledger retry | `eventId`; per routing rule also `traceId` once the started run's trace exists |
| `TaskExecutionService.executeTask`, `onTaskCompleted`, `failTask` | `traceId`, `eventId`, `subjectId`, `taskId`, `workflowRunId` |
| `ScriptExecutionService.executeScript` (incl. the async script body) | as for tasks                     |
| `WorkflowExecutionService` start and advance (task completion, wait elapsed, event received) | `traceId`, `subjectId`, `workflowRunId` |
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
- Context propagation across the `CompletableFuture` callbacks and scheduler threads has the same
  problem as the MDC and would need the same wrapping (or SmallRye Context Propagation's `ManagedExecutor`).
- It needs a collector to be useful; Axiom is a single-process app with its own trace UI, so the benefit is
  mainly for deployments that already run an observability stack. It is not added for now.

## Known Limitations

- Any in-progress trace can be named in the caller headers. There is no check that the caller belongs to the
  same subject; the header only affects log correlation.
- AI editor and assistant sessions (`ActionTypeAiService`, `ScriptAiService`, `AssistantSessionManager`) are
  not traced. Their AI usage rows have no trace ID.
- Script-mode tasks have no `ai_usage` row, because no AI is invoked.
- The lineage view that follows these links across entities is described in [Lineage](lineage.md).
