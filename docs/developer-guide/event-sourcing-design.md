# Event System — Design Document

## Overview

Axiom's event system provides a three-layer architecture for monitoring external systems
and reacting to changes:

1. **Connections** — authenticated links to external systems (GitHub, Jira) that poll
   for changes and produce a normalized event stream
2. **Event Stream** — a single database table of typed, schema-validated events from
   all connections, browsable via REST API and UI
3. **Subscriptions** — filtered views over the event stream with EL expression matching
   and configurable routing rules that send matched events to destinations (Manager
   triage, workflow dispatch, workflow creation, action invocation)

## Architecture

### Connections (EventSourceConnection)

A configured, authenticated link to an external system. Produces a raw, normalized event
stream. No filtering — every detectable event gets recorded.

- Each connection has a user-provided **slug ID** as its primary key (lowercase letters,
  numbers, dashes; max 63 characters, e.g., `github-com`, `github-ibm`, `jira-prod`)
- Multiple connections of the same type are supported (e.g., github.com + GitHub Enterprise)
- Each connection specifies what to watch (repositories for GitHub, projects for Jira)
- A background poller runs per connection, iterating over watched resources
- Poll results are audited in the `connection_poll_log` table with status, timing,
  events ingested, and error details (3-day retention)
- The connection's `baseUrl` stores the **human-readable URL** (e.g., `https://github.com`);
  the poller derives the API URL automatically

**GitHub connection:**
- Uses the Repository Events API (`GET /repos/{owner}/{repo}/events`)
- ETag-based conditional polling for efficiency (304 when nothing changed)
- Per-repo polling under a shared authentication token
- PR event payloads are truncated by the API; the poller backfills via
  `GET /repos/{owner}/{repo}/pulls/{number}`
- Configuration: `baseUrl` (e.g., `https://github.com`), `secretName`,
  `repositories` (list of `owner/repo`), `pollInterval`

**Jira connection:**
- Uses JQL search with changelog expansion (`expand=changelog`)
- Single query covers all configured projects via `project in (A, B, C)` clause
- Field-level change detection from changelog entries
- ADF-to-plain-text conversion for description and comment bodies
- Configuration: `baseUrl` (e.g., `https://myorg.atlassian.net`), `secretName`,
  `projects` (list of project keys), `pollInterval`

**Authentication:** Three-tier fallback for both types: per-connection secret (by name
from the secrets store), default provider secret (`GH_TOKEN`/`GITHUB_TOKEN` for GitHub,
`JIRA_API_TOKEN` for Jira), environment variable.

### Event Stream

A single `stream_event` database table of normalized, schema-validated events produced
by all connections.

- Each event has a UUID primary key and a well-defined typed payload determined by its
  event type (26 types across issue, PR, and repository events)
- Deduplication via unique index on `source_event_id`
- Configurable retention via the `eventRetentionDays` setting
- Browsable via REST API (`GET /stream/events`) and the Event Stream UI page

### Subscriptions (EventSubscription)

A filtered view over the event stream with configurable routing. Each subscription has:

- **Filter Expression** — a Jakarta EL expression that evaluates to boolean against the
  event map (e.g., `event.type.startsWith('pr.') && event.connectionId == 'github-com'`).
  Empty expression matches all events.
- **Routing Rules** — a list of destinations for matched events (see Routing below)
- **Labels** — free-form strings for categorization and downstream scoping
- **Enabled/Disabled** — subscriptions must be explicitly enabled to process events

**Filter expression evaluation** uses the flow engine's `ConditionEvaluator` (Jakarta EL).
The event is exposed as an `event` variable with fields: `type`, `source`, `connectionId`,
`ref`, `timestamp`, `actor.*`, and `payload.*` (with full typed payload field access).

### Routing Rules

Each subscription defines zero or more routing rules. When an event matches the filter
expression, each routing rule executes in order:

| Destination Type | Config Required | What Happens |
|------------------|-----------------|--------------|
| `manager` | None | Sends the event to the AI Manager for triage. Manager returns decisions (create_task, ignore, script_action, escalate) which are processed: tasks are created, projects auto-created, scripts executed, escalations logged with SSE notifications. Decisions below the confidence threshold are auto-escalated. |
| `workflow-dispatch` | None | Offers the event to any workflow instances parked at `receive-event` nodes. Matching uses the flow engine's `matchesEvent` with event type and EL expressions. |
| `create-workflow` | `workflowDefinitionId` | Finds or auto-creates a project from the event's `ref` URL, then starts a new workflow instance from the specified definition on that project. |
| `invoke-action` | `actionTypeId` | Finds or auto-creates a project, then creates a `TaskEntity` with status "Pending" for the specified action type. The task queue picks it up for execution. |

A subscription with no routing rules matches events silently (useful for previewing
matches before committing to a routing destination).

## Processing Ledger

The `event_processing_ledger` table provides durable, restart-safe event processing.
Each `(event_id, subscription_id)` pair gets a ledger entry with one of four statuses:

| Status | Meaning | Next Action |
|--------|---------|-------------|
| `pending` | An attempt is in progress | Transitions to `completed`, `failed`, `exhausted` (or `skipped` when a retry re-evaluates the filter). On startup, orphaned `pending` entries are recovered to `failed` and due immediately (`exhausted` if it was the last attempt). |
| `completed` | All routing rules executed successfully | Terminal. |
| `skipped` | Filter did not match | Terminal. Prevents re-evaluation on future ticks. |
| `failed` | The last attempt failed | Retried automatically once `next_attempt_at` has passed (exponential backoff). |
| `exhausted` | The last allowed attempt failed | Terminal unless retried manually (one more attempt). |

**Key behaviors:**
- **Restart-safe:** No in-memory state. The ledger is the complete record.
- **Cutoff-scoped, not fully retroactive:** Each subscription has a
  `processEventsFrom` timestamp, defaulted to the moment the subscription is created
  (it can be set to an earlier or later time explicitly, e.g. via the API). Only
  events whose `timestamp` is at or after that cutoff are ever evaluated against the
  subscription. Enabling a subscription does not retroactively evaluate events that
  occurred before its cutoff.
- **Retry:** Each ledger entry tracks its attempts (V69, #422): `attempt_count` (the first try
  included), `last_attempt_at` and, while it is `failed`, `next_attempt_at`. A failed attempt sets
  `next_attempt_at = now + min(retry-initial-delay * 2^(n-1), retry-max-delay)` where `n` is the number of
  the attempt that failed (defaults 30s and 1h: 30s, 1m, 2m, ... capped at 1h). The retry pass selects
  only `failed` entries whose `next_attempt_at` has passed (index on `(status, next_attempt_at)`), and
  never an entry attempted since the tick started, so the first pass and the retry pass cannot both run
  an entry in one tick. A retry marks the entry `pending`, increments `attempt_count` and sets
  `last_attempt_at` before routing. When the attempt that reaches `axiom.stream-pipeline.max-attempts`
  (default 3) fails, the entry becomes `exhausted` (a distinct status rather than `failed` with a null
  `next_attempt_at`, so lists, filters and the UI can tell "will retry" from "gave up" by status alone),
  "(giving up after N attempts)" is appended to its error message and a WARN is logged once.
- **Manual retry:** `POST /stream/events/{eventId}/processing/{ledgerId}/retry` (the retry button on the
  event detail page) makes a `failed` or `exhausted` entry `failed` and due immediately. It does not reset
  `attempt_count`, so attempt numbers keep increasing and an exhausted entry gets exactly one more attempt:
  if it fails it is exhausted again.
- **Attempt history:** Every `routing_outcome` records its `attempt_number`, so the outcomes of an entry
  group into attempts; the processing API returns them all with the entry's attempt fields and the
  configured maximum. V69 backfills `attempt_number` (1 + failed outcomes recorded before it) and
  `attempt_count` (failed outcomes, plus one for a `completed` entry), marks failed entries with 3 or more
  attempts (the old default cap) `exhausted` and makes the other failed entries due immediately.
- **No replay on retry:** A retry skips routing rules that already have a `completed` outcome for the
  ledger entry, so a later rule's failure does not re-create tasks or workflows, or re-dispatch the
  event. Outcomes do not store the rule's position, so the k-th rule of a routing type is matched to the
  k-th completed outcome of that type. Rules run in order and stop at the first failure, so this is exact
  while the subscription's rules are unchanged; editing the rules between attempts can skip or replay
  the wrong rule.
- **Processing failures count:** A failure outside the routing rules (building the event context or
  evaluating the filter) is recorded as a failed outcome with routing type `processing`, so it counts
  toward the attempt cap. On the first pass such a failure happens before the ledger entry exists, so it
  creates a `failed` entry (attempt 1); the pair then leaves the unledgered `NOT IN` query and is only
  retried by the retry pass, with backoff and the cap. A retry of an entry without any routing-rule
  outcome re-evaluates the filter, since it may never have matched, and marks the entry `skipped` if it
  does not match. An unparseable payload is not a failure: it is routed with an empty payload map, on the
  first pass and on retries alike.
- **Dedup:** Unique constraint on `(event_id, subscription_id)` prevents duplicate processing.
- **Startup recovery:** Orphaned `pending` entries from a previous crash are bulk-updated
  to `failed` on the first tick, then retried normally.

### Routing outcomes and items

Each routing rule run records one `routing_outcome` row per attempt (routing type, status, summary,
trace ID, error). Everything the rule produced is recorded as `routing_outcome_item` rows (V67, #418),
children of the outcome deleted with it (`ON DELETE CASCADE`; `StreamEventCleanup` also deletes them
explicitly):

| Item type | Written by | Links |
|-----------|------------|-------|
| `task` | Manager `create_task` / `script_action` decision; `invoke-action` rule | `task_id`, `project_id` |
| `workflow-run` | `create-workflow` rule | `workflow_run_id`, `project_id` |
| `workflow-resumed` | `workflow-dispatch` rule, one per run the event resumed | `workflow_run_id`, `project_id`, `trace_node_id` (the receive-event node) |
| `no-match` | `workflow-dispatch` rule that resumed no run | — |
| `ignored` | Manager `ignore` decision | — |
| `escalated` | Manager `escalate` decision, or any decision below the confidence threshold | `project_id` if the escalation was posted to a project |
| `decision` | Manager decision of an unknown type (always `failed`) | — |

Each item has a `status` (`completed` or `failed`), a `summary` and, when failed, an `error_message`.
Manager items have one row per decision, including decisions that failed, and `trace_node_id` points to
the decision's `manager-decision` trace node. A `workflow-dispatch` rule writes one `workflow-resumed`
item per resumed run, or a single `no-match` item (outcome summary "No waiting workflow run matched the
event") when nothing matched, so "nothing happened" is recorded explicitly. A run that matched but
failed to resume gets a `failed` `workflow-resumed` item with the error; the outcome stays `completed`
so the rule is not retried (a retry would re-dispatch the event to every run). The processing API adds the
run's `traceId` to `workflow-run` and `workflow-resumed` items, so the event page links to the run and its
trace.

The outcome's own `project_id` and `task_id` columns are kept for backward compatibility. They still hold
only the **first** project and task; readers should use the items. Outcomes recorded before V67 have no
items.

### Links between events and workflow runs

Events and workflow runs are linked both ways (V68, #420, #421). None of these links are foreign keys:
event retention deletes events and ledger entries while runs are kept.

- **Started runs (`create-workflow`).** The run stores `trigger_event_id` and `trigger_ledger_id`; the
  run's `workflow` trace has `eventId` set to the event; the run's context has the event ID as
  `event.id`; and the outcome's `trace_id` is the run's trace. Runs started any other way (e.g.
  `POST /projects/{id}/workflow`) have null trigger fields.
- **Resumed runs (`workflow-dispatch`).** `WorkflowEventDispatcher.dispatchStreamEvent` returns the
  runs it resumed (run, node, trace and receive-event trace node). The `workflow_event_subscription` row
  is deleted when it is consumed, so each resumption is recorded as a `workflow_run_resume` row (run,
  node, event, ledger entry, trace node; deleted with the run), and the receive-event trace node's
  summary gets "resumed by event <id>". The event map passed to the run (`event` in its context)
  includes the event ID as `id`.
- **API.** `WorkflowInstance` and `WorkflowRunSummary` expose `triggerEventId` and `triggerLedgerId`;
  `WorkflowInstance.resumedBy` lists the resuming events. The workflow run page shows "Triggered by"
  and "Resumed by" links to `/events/stream/:id`.

Items never affect retries: the attempt cap and the skip-on-retry rule count `routing_outcome` rows by
status and routing type, not items. Each attempt persists its own outcome together with its items, so the
items of an attempt stay on that attempt's outcome and never appear on a later attempt's outcome.

## REST API

### Connections

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/connections` | List all connections |
| POST | `/connections` | Create a connection |
| GET | `/connections/{connectionId}` | Get a connection (by slug) |
| PUT | `/connections/{connectionId}` | Update a connection |
| DELETE | `/connections/{connectionId}` | Delete a connection |
| GET | `/connections/{connectionId}/status` | Poll health, last poll time, errors |
| GET | `/connections/{connectionId}/logs` | Poll log history |

### Event Stream

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/stream/events` | Browse events (paginated, filterable by type, connectionId, ref) |
| GET | `/stream/events/{eventId}` | Get a single event with full typed payload |

### Subscriptions

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/subscriptions` | List all subscriptions |
| POST | `/subscriptions` | Create a subscription |
| GET | `/subscriptions/{id}` | Get a subscription |
| PUT | `/subscriptions/{id}` | Update a subscription |
| DELETE | `/subscriptions/{id}` | Delete a subscription |
| POST | `/subscriptions/preview` | Preview filter expression against existing events |

## Retention

- **Stream events and ledger entries:** Cleaned up hourly based on `eventRetentionDays`
  setting (default 90 days). Ledger entries are deleted first (FK), then events.
- **Connection poll logs:** Cleaned up hourly with a 3-day fixed retention.

## Configuration Packs

Connections and subscriptions are included in the configuration pack export/import
system, allowing portable sharing of event system configuration.

---

# Event Schema

## Common Event Envelope

Every event in the stream shares this envelope structure.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | System-generated unique event ID (UUID) |
| `sourceEventId` | `string` | Original ID from the source system (for dedup). GitHub: Events API `id`. Jira: synthesized from issue key + changelog entry ID |
| `source` | `string` | Source system identifier: `"github"` or `"jira"` |
| `connectionId` | `string` | Slug of the connection that produced this event |
| `type` | `string` | Normalized event type (e.g., `issue.created`, `pr.merged`) |
| `ref` | `string` | Full URL uniquely identifying the subject of the event |
| `timestamp` | `ISO-8601` | When the event occurred in the source system |
| `actor` | `Actor` | Who performed the action |
| `payload` | `object` | Typed payload, schema determined by `type` |
| `sourceData` | `object?` | Raw source-specific data escape hatch |

## Shared Sub-Object Schemas

### Actor

| Field | Type | Description |
|-------|------|-------------|
| `login` | `string` | Username or account ID. GitHub: `user.login`. Jira: `accountId` |
| `displayName` | `string?` | Display name |
| `avatarUrl` | `string?` | Profile image URL |
| `url` | `string?` | Profile HTML URL |

### Issue

Normalized across GitHub and Jira.

| Field | Type | Description |
|-------|------|-------------|
| `number` | `string` | Issue identifier. GitHub: `"123"`. Jira: `"PROJ-123"` |
| `title` | `string` | Issue title |
| `body` | `string?` | Issue description |
| `state` | `string` | `"open"` or `"closed"` |
| `stateDetail` | `string?` | Source-specific detail (GitHub: `state_reason`. Jira: status name) |
| `author` | `Actor` | Who created the issue |
| `assignees` | `Actor[]` | Current assignees |
| `labels` | `string[]` | Label names |
| `milestone` | `string?` | Milestone or sprint name |
| `url` | `string` | HTML URL |
| `createdAt` | `ISO-8601` | Creation timestamp |
| `updatedAt` | `ISO-8601` | Last update timestamp |
| `closedAt` | `ISO-8601?` | Closure timestamp |

### PullRequest

All Issue fields plus:

| Field | Type | Description |
|-------|------|-------------|
| `headBranch` | `string` | Source branch name |
| `baseBranch` | `string` | Target branch name |
| `headSha` | `string` | Latest commit SHA on head |
| `isDraft` | `boolean` | Whether the PR is a draft |
| `isMerged` | `boolean` | Whether the PR has been merged |
| `mergedAt` | `ISO-8601?` | Merge timestamp |
| `mergedBy` | `Actor?` | Who merged |
| `additions` | `int?` | Lines added |
| `deletions` | `int?` | Lines deleted |
| `changedFiles` | `int?` | Number of changed files |

### Comment

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | Comment ID |
| `body` | `string` | Comment body text |
| `author` | `Actor` | Who wrote the comment |
| `url` | `string` | HTML URL |
| `createdAt` | `ISO-8601` | Creation timestamp |
| `updatedAt` | `ISO-8601` | Last edit timestamp |

### Review (GitHub only)

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | Review ID |
| `state` | `string` | `approved`, `changes_requested`, `commented`, `dismissed` |
| `body` | `string?` | Review body text |
| `author` | `Actor` | Who submitted the review |
| `url` | `string` | HTML URL |
| `submittedAt` | `ISO-8601` | Submission timestamp |

### Label

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Label name |
| `color` | `string?` | Hex color code (GitHub only) |
| `description` | `string?` | Label description (GitHub only) |

### Change

| Field | Type | Description |
|-------|------|-------------|
| `field` | `string` | Which field changed |
| `from` | `string?` | Previous value |
| `to` | `string?` | New value |
| `author` | `Actor?` | Who made the change |

---

# Event Type Taxonomy

## Issue Events (GitHub + Jira)

| Event Type | GitHub Source | Jira Source |
|------------|--------------|-------------|
| `issue.created` | `IssuesEvent` / `opened` | Issue `created` timestamp within poll window |
| `issue.updated` | `IssuesEvent` / `edited` | Changelog: `summary` or `description` change |
| `issue.closed` | `IssuesEvent` / `closed` | Changelog: status to `Done` category |
| `issue.reopened` | `IssuesEvent` / `reopened` | Changelog: status from `Done` to non-`Done` |
| `issue.assigned` | `IssuesEvent` / `assigned` | Changelog: `assignee` field set |
| `issue.unassigned` | `IssuesEvent` / `unassigned` | Changelog: `assignee` field cleared |
| `issue.labeled` | `IssuesEvent` / `labeled` | Changelog: `labels` (added) |
| `issue.unlabeled` | `IssuesEvent` / `unlabeled` | Changelog: `labels` (removed) |
| `issue.comment.created` | `IssueCommentEvent` / `created` | Comment `created` > last poll |
| `issue.comment.updated` | `IssueCommentEvent` / `edited` | Comment `updated` > `created` and > last poll |
| `issue.comment.deleted` | `IssueCommentEvent` / `deleted` | Not detectable |

## Pull Request Events (GitHub only)

| Event Type | GitHub Source | Notes |
|------------|--------------|-------|
| `pr.created` | `PullRequestEvent` / `opened` | Requires PR backfill |
| `pr.closed` | `PullRequestEvent` / `closed` + `merged == false` | Requires PR backfill |
| `pr.merged` | `PullRequestEvent` / `closed` + `merged == true` | Requires PR backfill |
| `pr.reopened` | `PullRequestEvent` / `reopened` | Requires PR backfill |
| `pr.review_requested` | `PullRequestEvent` / `review_requested` | Requires PR backfill |
| `pr.review.submitted` | `PullRequestReviewEvent` / `created` | Requires PR backfill |
| `pr.comment.created` | `IssueCommentEvent` on PR | Detected by `pull_request` key |
| `pr.labeled` | `PullRequestEvent` / `labeled` | Requires PR backfill |
| `pr.unlabeled` | `PullRequestEvent` / `unlabeled` | Requires PR backfill |
| `pr.synchronize` | `PullRequestEvent` / `synchronize` | New commits pushed |

## Repository Events (GitHub only)

| Event Type | GitHub Source | Notes |
|------------|--------------|-------|
| `push` | `PushEvent` | Up to 20 commits |
| `branch.created` | `CreateEvent` / `ref_type == "branch"` | |
| `branch.deleted` | `DeleteEvent` / `ref_type == "branch"` | |
| `tag.created` | `CreateEvent` / `ref_type == "tag"` | |
| `release.published` | `ReleaseEvent` / `published` | Excludes drafts |

---

# Source-Specific Notes

## GitHub

**Event detection:** Repository Events API (`GET /repos/{owner}/{repo}/events`).

**PR backfill:** Events API truncates PR payloads. The poller fetches the full PR via
`GET /repos/{owner}/{repo}/pulls/{number}` for all PR-related events. For
`PullRequestEvent/closed`, the `merged` field distinguishes `pr.closed` from `pr.merged`.

**Base URL derivation:** The stored `baseUrl` is human-readable (e.g., `https://github.com`).
The poller derives the API URL: `https://github.com` becomes `https://api.github.com`;
GHE URLs get `/api/v3` appended.

**`ref` construction:**

| Event Category | Format |
|----------------|--------|
| Issue events | `{baseUrl}/{owner}/{repo}/issues/{number}` |
| PR events | `{baseUrl}/{owner}/{repo}/pull/{number}` |
| Push events | `{baseUrl}/{owner}/{repo}` |
| Branch events | `{baseUrl}/{owner}/{repo}/tree/{branchName}` |
| Tag/release events | `{baseUrl}/{owner}/{repo}/releases/tag/{tagName}` |

**Deduplication:** Each Events API event has a unique `id`. The poller tracks seen IDs
per connection+repo in memory and deduplicates in the database via the `source_event_id`
unique index.

**Polling:** ETag-based conditional polling (304 on no change). Default 60-second interval.

## Jira

**Event detection:** JQL search with `expand=changelog`. Changelog entries are filtered
by timestamp and classified into normalized event types.

**Changelog classification:**

| Field | Event Type |
|-------|------------|
| `status` to Done category | `issue.closed` |
| `status` from Done category | `issue.reopened` |
| `summary` or `description` | `issue.updated` |
| `assignee` set | `issue.assigned` |
| `assignee` cleared | `issue.unassigned` |
| `labels` added | `issue.labeled` |
| `labels` removed | `issue.unlabeled` |

**Label diffing:** Jira provides space-separated before/after lists in changelog.
The sourcer diffs them to emit individual labeled/unlabeled events.

**ADF conversion:** Jira v3 returns description and comment body in Atlassian Document
Format. The sourcer extracts plain text for normalized fields and preserves raw ADF
in `sourceData`.

**`ref` construction:** `{baseUrl}/browse/{issueKey}`

**Deduplication:** Stable `sourceEventId` from `{issueKey}-{changelogEntryId}`,
`{issueKey}-created`, or `{issueKey}-comment-{commentId}`.

