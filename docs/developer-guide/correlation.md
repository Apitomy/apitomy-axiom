# Correlation by Trace ID

Axiom uses the **trace ID** as its correlation ID. Every unit of work (event processing, a task, a workflow
run, a scheduled job run or a report) is identified by its trace ID. Every record produced by that work stores
the trace ID, so given one trace ID you can find everything that belongs to that unit of work. See
[Tracing](tracing.md) for the trace model itself.

## Tables That Carry `trace_id`

| Table               | Notes                                     |
|---------------------|-------------------------------------------|
| `task`              | Trace the task runs in                    |
| `report`            | Trace of the report generation           |
| `workflow_run`      | Trace of the workflow run                 |
| `scheduled_job_run` | Trace of the scheduled job run            |
| `routing_outcome`   | Trace of the event that was routed        |
| `activity_log`      | Added in migration `V66`                  |
| `ai_usage`          | Added in migration `V66`                  |

All columns are nullable UUIDs with no foreign key. Trace data has a bounded lifetime: when `TraceCleanup`
deletes a trace, it clears `trace_id` on `activity_log`, `ai_usage`, `task`, `scheduled_job_run` and `report`.

## Finding All Records for a Trace

- `GET /traces/{id}` returns the trace tree (nodes, tool calls).
- `GET /activity?filterTraceId={id}` returns the activity rows for the trace.
- `GET /usage/ai?filterTraceId={id}` returns the AI usage rows for the trace.
- Tasks, workflow runs, scheduled job runs and reports expose a `traceId` field.

For both filters, a malformed `filterTraceId` returns `400 Bad Request`.

In the UI, the Activity log and AI usage pages show a **Trace** column that links to `/logs/traces/:traceId`,
and a **Trace ID** filter. Both pages accept a `?traceId=` query parameter as a deep link, for example
`/logs/activity?traceId=...`.

## Agent Calls: Joining the Caller's Trace

When an agent calls Axiom APIs through the Axiom MCP server, `sdk-server.js` sends two headers on every request
when the matching environment variables are set:

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

Only `POST /projects/{id}/tasks` acts on a valid caller trace: the new task joins the caller's trace and its
task node is added under the caller's parent node, instead of a new `user-action` trace being created. The
task's `createdBy` stays `user`. Other endpoints ignore the caller trace.

## Accepted Risk

An agent can keep its trace open by creating tasks that join it, because `TaskTraceFinalizer` keeps a trace
open while any task node is in progress. This is accepted: agent-created tasks are deliberate work that belongs
to the trace.

## Known Limitations

- Any in-progress trace can be joined. There is no check that the caller belongs to the same project.
- AI editor and assistant sessions (`ActionTypeAiService`, `ReportAiService`, `ScriptAiService`,
  `ToolAiService`, `AssistantSessionManager`) are not traced. Their AI usage rows have no trace ID.
- Out of scope for now: MDC log correlation (#428), run and report ID columns (#424, #425), and the lineage
  view (#430).
