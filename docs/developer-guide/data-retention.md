# Data Retention

Axiom deletes old data with scheduled cleanup jobs in `app/src/main/java/io/apitomy/axiom/app/`. The
retention periods live in the single-row `retention_config` table and are edited on the **Data Retention**
page (`/data-retention`) or with `GET`/`PUT /api/v1/system/retention`.

The rule for every cleanup job is: **after it runs, no row points at a deleted row**, except for the
documented soft references listed under [Accepted dangling references](#accepted-dangling-references).

## Settings

| Column                             | API field                      | Default | Cleanup job            |
|------------------------------------|--------------------------------|---------|------------------------|
| `closed_project_retention_days`    | `closedProjectRetentionDays`   | 90      | `ClosedProjectCleanup` |
| `trace_retention_days`             | `traceRetentionDays`           | 30      | `TraceCleanup`         |
| `event_retention_days`             | `eventRetentionDays`           | 90      | `StreamEventCleanup`   |
| `scheduled_job_run_retention_days` | `scheduledJobRunRetentionDays` | 0       | `HistoryCleanup`       |
| `report_retention_days`            | `reportRetentionDays`          | 0       | `HistoryCleanup`       |
| `workflow_run_retention_days`      | `workflowRunRetentionDays`     | 0       | `HistoryCleanup`       |
| `activity_log_retention_days`      | `activityLogRetentionDays`     | 0       | `HistoryCleanup`       |
| `ai_usage_retention_days`          | `aiUsageRetentionDays`         | 0       | `HistoryCleanup`       |

The five history settings were added in `V72` (#429). For them, `0` means **keep forever**, and `0` is the
default, so upgrading never deletes data unexpectedly. Negative values are rejected with `400`. The three
original settings must be at least 1, because 0 would delete everything; smaller values are also rejected with
`400`. A `PUT` that omits any setting (or sends `null`) leaves its current value unchanged. `V72` also dropped
the unused `event_source_log_retention_days` column.

## Batching and Retries

Every job runs hourly (staggered with `delayed`), selects at most 500 IDs per transaction, and deletes by
those IDs or with sub-selects, never with an unbounded `IN` list. Each batch runs in its own transaction
through `CleanupRetry`, which retries database lock failures. Each job has a package-private `doCleanup()`
that runs all batches in the caller's transaction; tests use it.

## What Each Job Does

### `TraceCleanup`

Deletes traces whose `started_on` is older than the retention period, with their `trace_node` and
`tool_execution` rows. Before deleting, it clears (sets to `NULL`):

- `trace_id` on `task`, `scheduled_job_run`, `report`, `workflow_run`, `routing_outcome`, `activity_log` and
  `ai_usage`;
- `triggered_by_trace_id` on `scheduled_job_run` and `report`;
- `trace_node_id` on `routing_outcome_item` and `workflow_run_resume`, for the deleted trace's nodes.

`TraceCleanupTest.everyTraceColumnIsHandled` scans every entity for columns named `trace_id`, `*_trace_id`
or `trace_node_id` and fails if one is not in its list of handled columns. When you add such a column, clear
it in `TraceCleanup`, add it to the assertions in `TraceCleanupTest`, and add it to the list.

### `StreamEventCleanup`

Deletes stream events older than the retention period, with their ledger entries, routing outcomes and
routing outcome items. An event is **kept** while a workflow run with status `running` or `waiting`:

- has it as `trigger_event_id`, or
- has a `workflow_run_resume` row with that `event_id`.

Such an event becomes eligible on the first cleanup after the run finishes, so the run → event link is never
lost while the run can still use it. The job also deletes connection poll logs older than 3 days.

### `ClosedProjectCleanup` / `ProjectDeletionService`

Deletes `Completed` projects whose `updated_on` is older than the retention period (the same code runs when a
project is deleted through the API). It deletes the project's thread entries, AI usage, activity log entries,
tasks, workflow runs and their waits, event subscriptions and resume rows, labels and workspace.

Traces and routing outcomes are event history, not project data, so they are **kept** and their references
are cleared: `trace.project_id`, `routing_outcome.project_id` and `task_id`, and `routing_outcome_item`
`project_id`, `task_id` and `workflow_run_id`. Configuration versions are not project-scoped.

### `HistoryCleanup`

Each setting is skipped when it is `0`.

| Data                | Deleted rows                                                                    |
|---------------------|---------------------------------------------------------------------------------|
| Scheduled job runs  | Status `Completed` or `Failed` and `created_on` older than the period           |
| Reports             | Status `Completed` or `Failed` and `created_on` older than the period, with labels |
| Workflow runs       | Status `completed`, `failed` or `cancelled`, and `completed_on` (or `started_on` if unset) older than the period |
| Activity log        | `created_on` older than the period, with or without a project                   |
| AI usage            | `created_on` older than the period, with or without a project                   |

Workflow runs use an allow-list of finished statuses (`FINISHED_WORKFLOW_RUN_STATUSES`), so runs in a status
added later are kept until the list is updated. Deleting a workflow run deletes its `workflow_wait`,
`workflow_event_subscription` and `workflow_run_resume` rows and clears `task.workflow_run_id` and
`routing_outcome_item.workflow_run_id`. Tasks are kept: they belong to the project and are deleted with it.
The run's trace follows trace retention.

Deleting a job run or report does not touch its configuration version (`scheduled_job_version` /
`report_definition_version`), which belongs to the definition.

## Concurrency

The cleanup jobs use bulk JPQL `UPDATE` and `DELETE` statements, which bypass the persistence context. If
another transaction has the same row loaded as a managed entity (for example a task whose `trace_id` is being
cleared) and flushes it after the cleanup commits, it can write the old value back, or fail on a row that no
longer exists. The window is small, because cleanup only touches rows that are past their retention period,
and this was already true before #429. The next cleanup run clears any value that was written back.

## Accepted Dangling References

These columns are soft references with no foreign key. They are kept on purpose so history survives, and
consumers must treat them as possibly dangling:

- `scheduled_job_run_id`, `report_id` and `report_definition_id` on `ai_usage` and `activity_log`, and
  `trace.report_id`, after a job run, report or definition is deleted (see [Correlation](correlation.md)).
- `event_id` on `trace`, `task`, `activity_log`, `ai_usage` and `workflow_run_resume`, `ledger_id` on
  `workflow_run_resume`, and `trigger_event_id` / `trigger_ledger_id` on `workflow_run`, after event
  retention deletes the event of a finished run (see
  [Tracing](tracing.md) and [Event System Design](event-sourcing-design.md)).
- `trace_node.entity_type` / `entity_id`, which are free-form display references.
