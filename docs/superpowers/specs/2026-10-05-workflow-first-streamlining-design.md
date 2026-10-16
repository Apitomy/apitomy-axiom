# Workflow-First Streamlining Design

**Date:** 2026-10-05
**Status:** Approved (pending spec review)

## Goal

Refocus Apitomy Axiom on workflows. The two core use cases are **Event → Create Workflow** and **Event →
Dispatch to Workflow**, plus a **Schedule → Create Workflow** trigger. Workflow runs become the primary
user-facing object. Projects are replaced by a thin **Subject** concept. All parallel execution subsystems
(reports, scheduled jobs, AI Manager, direct action invocation) are removed.

## Non-Goals

- Reports. These may return later as a Report SDK that a workflow invokes to publish a report to a
  Subject (listed on the Subject page). Not part of this work.
- Workflow outputs and sub-workflows.
- Data migration or backward compatibility of any kind.

## 1. Scope and Domain Model

### Removed

- **Report definitions and reports**: `ReportDefinitionEntity`, `ReportDefinitionVersionEntity`,
  `ReportEntity`, `ReportScheduler`, `ReportQueueConsumer`, `ReportExecutionService`, `ReportAiService`,
  `ReportsResourceImpl`, `ReportDefinitionValidator`, related UI pages, tests, and docs.
- **Scheduled jobs**: `ScheduledJobEntity`, `ScheduledJobVersionEntity`, `ScheduledJobRunEntity`,
  `ScheduledJobScheduler`, `ScheduledJobQueueConsumer`, `ScheduledJobExecutionService`,
  `ScheduledJobsResourceImpl`, `ScheduledJobValidator`, related UI pages, tests, and docs.
- **AI Manager**: the entire `manager` module, the `manager` routing type, confidence thresholds, and
  decision handling.
- **`invoke-action` routing type.**
- **Manual action invocation** from a project/subject page.
- **All Action Type flags** (e.g. user-triggerable, manager-triggerable, emits-event).
- **Project lifecycle**: `ProjectLifecycle`, `ProjectStatus`, and the status column.

### Action Types

Action Types are invoked **only** from workflow `action` nodes. There is no other entry point (no events,
no UI trigger, no API trigger). Execution fields (`executionMode`, templates, `allowedTools`, `model`,
`engine`, `maxSteps`, `maxBudgetUsd`, `timeoutSeconds`, `environment`) are retained.

### Subject (replaces Project)

`ProjectEntity` is renamed to `SubjectEntity`. Fields:

- `id`, `name`/title, `body`, `type`, `refSource`, `ref`, `repository`, `metadata`, `diskUsageBytes`,
  timestamps.
- Owns the workspace directory and the thread (`ThreadEntryEntity`).
- Has **no lifecycle of its own**. Subject status is derived: *active* if any of its runs is `running` or
  `waiting`, otherwise *inactive*.
- Workspace cleanup (replacing `ClosedProjectCleanup`) runs for subjects with no active runs for N days
  (configurable).

All `projectId` references (tasks, runs, thread entries, events, AI usage, workflow event subscriptions)
become `subjectId`.

### Workflow Runs

Workflow runs are the primary UI object. `WorkflowRunEntity` fields:

- Existing: `id`, `definitionId`, `instanceState`, `status`, `currentNodeId`, `failureReason`, `traceId`,
  `triggerEventId`, `triggerLedgerId`, `startedOn`, `completedOn`.
- `subjectId` — **required** (non-null) for every run.
- New: `definitionVersion`, `title`, `labels` (JSON metadata), `inputs` (JSON).
- **No outputs.** Per-node results remain available via each action node's task output. This matches
  Apitomy Flow (inputs on Start node, no outputs on End node).

`title` defaults to `<definition name> — <subject title>` when not provided.

### Concurrency Rule

At most **one active run per (subject, workflow definition)**. Replaces the old one-active-run-per-project
rule. This permits:

- Sequential runs on the same subject (re-open, retry), sharing workspace and history.
- Concurrent runs of *different* definitions on the same subject.

A trigger that would violate the rule is **skipped** (not queued), and the skip is recorded.

### Schedule Trigger (new)

A workflow definition may have zero or more schedule triggers:

- `cron` expression.
- `subjectName` — a **fixed subject**, required. Created on first firing if it does not exist; reused on
  every subsequent firing so run history accumulates on that subject.
- Optional static `inputs` passed to the run.
- `enabled` flag, `nextRunAt`.

When the trigger fires and the subject already has an active run of the same definition, the firing is
skipped and logged.

## 2. Database and API

### Schema Reset

- Delete all existing Flyway migrations.
- Create a single baseline `V1__baseline.sql` representing the new model.
- No data migration. All existing data is discarded.

### REST API (contract-first)

All changes start in `common/api/src/main/resources/openapi.json`, followed by regenerating the JAX-RS
interfaces and beans.

- Remove report, scheduled-job, and manager endpoints and schemas.
- Remove Action Type flag properties and any "invoke action" endpoints.
- Rename `/projects` → `/subjects` and `Project*` schemas → `Subject*`. No aliases.
- Remove subject status/lifecycle transition endpoints; expose derived `active` boolean instead.
- Add run fields `subjectId` (required), `definitionVersion`, `title`, `labels`, `inputs`; add filtering
  of runs by status, definition, subject, and label.
- Add schedule trigger CRUD under workflow definitions
  (e.g. `/workflows/{definitionId}/triggers/schedules`).
- Remove `manager` and `invoke-action` from the routing rule schema.

## 3. Event Pipeline

1. Source connections (GitHub, Jira) poll and produce stream events (unchanged).
2. Event subscriptions apply filters, then one of two routing types:
   - **`create-workflow`**: find or create the Subject from the event `ref`; start a run of the configured
     definition with `subjectId`, `ref`, and `event` in context. If the subject already has an active run
     of that definition, skip and record the skip in the routing outcome.
   - **`workflow-dispatch`**: deliver the event to **every** waiting run on the correlated subject that has
     a `receive-event` node subscribed to a matching event type (broadcast — current behavior). If the
     event does not correlate to a subject, deliver to all waiting runs with a matching event type
     (current behavior).
3. Routing outcomes and the processing ledger are retained for auditing.

Events matching no subscription are recorded in the ledger only.

### Inbox

The Inbox is retained **only** for human-task nodes awaiting a person. Manager escalations and
low-confidence decisions no longer exist.

## 4. UI

- **Navigation**: Runs (default landing page), Subjects, Workflows, Action Types, Event Sources /
  Subscriptions, Inbox.
- **Removed pages**: Reports, Scheduled Jobs, Manager-related views, run-action-from-project.
- **Runs list**: filter by status, definition, subject, labels.
- **Run detail**: graph with current node, inputs, tasks (with per-task outputs), triggering event.
- **Subject page**: header (ref, metadata, derived active state), timeline of all runs (active and past),
  thread, workspace. (Future: list of published reports.)
- **Workflow definition editor**: new **Triggers** tab for schedule triggers (cron, fixed subject name,
  inputs, enabled). Event triggers remain configured via subscriptions.
- **Action Type editor**: flags removed.

## 5. Testing

- Delete tests for removed subsystems (reports, scheduled jobs, manager, invoke-action, project lifecycle).
- New JUnit 5 tests:
  - Schedule trigger fires → subject created on first firing, reused thereafter.
  - Schedule trigger skipped when the subject has an active run of the same definition.
  - `create-workflow`: sequential runs on the same subject after completion (scenario B).
  - `create-workflow`: concurrent runs of different definitions on one subject (scenario C).
  - `create-workflow`: second run of the same definition on an active subject is skipped and recorded.
  - `workflow-dispatch`: broadcast to multiple waiting runs on the same subject.
  - Run creation fails without a `subjectId`.
  - Derived subject `active` state.
- Update UI/E2E tests for the Project → Subject rename and removed pages.

## 6. Documentation

- Rewrite `docs/user-guide/concepts.md` around the workflow-first model (Subjects, Workflows, Runs, Action
  Types, Events, Schedule Triggers, Inbox).
- Update `docs/user-guide/workflows.md` with schedule triggers and the concurrency rule.
- Remove reports, scheduled jobs, and Manager user-guide content.
- Update developer-guide references to projects and the Manager.
