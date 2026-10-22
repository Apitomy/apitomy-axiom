# Event System — Design Document

## Overview

Axiom's event system provides a three-layer architecture for monitoring external systems
and reacting to changes:

1. **Event Sources** — authenticated links to external systems (GitHub, Jira) that poll
   for changes and produce a normalized event stream
2. **Event Stream** — a single database table of typed, schema-validated events from
   all event sources, browsable via REST API and UI
3. **Subscriptions** — filtered views over the event stream with EL expression matching
   and configurable routing rules that send matched events to workflows (workflow creation
   and workflow dispatch)

## Architecture

### Event Sources (EventSourceEntity)

A configured, authenticated link to an external system. Produces a raw, normalized event
stream. No filtering — every detectable event gets recorded.

- Each event source has a user-provided **slug ID** as its primary key (lowercase letters,
  numbers, dashes; max 63 characters, e.g., `github-com`, `github-ibm`, `jira-prod`)
- Multiple event sources of the same type are supported (e.g., github.com + GitHub Enterprise)
- Each event source specifies what to watch (repositories for GitHub, projects for Jira)
- A background poller runs per event source, iterating over watched resources
- Poll results are audited in the `event_source_poll_log` table with status, timing,
  events ingested, and error details (3-day retention)
- The event source's `baseUrl` identifies the server (e.g., `https://github.com`); for GitHub the poller
  resolves the web and API URLs from it (see "Base URL resolution" below)

**GitHub event source:**
- Uses the Repository Events API (`GET /repos/{owner}/{repo}/events`)
- ETag-based conditional polling for efficiency (304 when nothing changed)
- Per-repo polling under a shared authentication token
- PR event payloads are truncated by the API; the poller backfills via
  `GET /repos/{owner}/{repo}/pulls/{number}`
- Configuration: `baseUrl` (e.g., `https://github.com`), `secretName`,
  `repositories` (list of `owner/repo`), `pollInterval`

**Jira event source:**
- Uses JQL search with changelog expansion (`expand=changelog`)
- Single query covers all configured projects via `project in (A, B, C)` clause
- Field-level change detection from changelog entries
- ADF-to-plain-text conversion for description and comment bodies
- Configuration: `baseUrl` (e.g., `https://myorg.atlassian.net`), `secretName`,
  `projects` (list of project keys), `pollInterval`

**Authentication:** Three-tier fallback for both types: per-event-source secret (by name
from the secrets store), default provider secret (`GH_TOKEN`/`GITHUB_TOKEN` for GitHub,
`JIRA_API_TOKEN` for Jira), environment variable.

### Event Stream

A single `stream_event` database table of normalized, schema-validated events produced
by all event sources.

- Each event has a UUID primary key and a well-defined typed payload determined by its
  event type (26 types across issue, PR, and repository events)
- Deduplication via unique index on `source_event_id`
- Configurable retention via the `eventRetentionDays` setting
- Browsable via REST API (`GET /stream/events`) and the Event Stream UI page

### Subscriptions (EventSubscription)

A filtered view over the event stream with configurable routing. Each subscription has:

- **Filter Expression** — a Jakarta EL expression that evaluates to boolean against the
  event map (e.g., `event.type.startsWith('pr.') && event.eventSourceId == 'github-com'`).
  Empty expression matches all events.
- **Routing Rules** — a list of destinations for matched events (see Routing below)
- **Labels** — free-form strings for categorization and downstream scoping
- **Enabled/Disabled** — subscriptions must be explicitly enabled to process events

**Filter expression evaluation** uses the flow engine's `ConditionEvaluator` (Jakarta EL).
The event is exposed as an `event` variable with fields: `type`, `source`, `eventSourceId`,
`ref`, `timestamp`, `actor.*`, and `payload.*` (with full typed payload field access).

### Routing Rules

Each subscription defines zero or more routing rules. When an event matches the filter
expression, each routing rule executes in order. There are exactly two routing types:

| Destination Type | Config Required | What Happens |
|------------------|-----------------|--------------|
| `create-workflow` | `workflowDefinitionId` (or `workflowDefinitionName`, resolved to the id on save) | Finds or auto-creates a Subject from the event's `ref` URL, then starts a new workflow run of the specified definition on that subject, with `subjectId`, `ref`, and `event` in its context. If the subject already has an active (`running` or `waiting`) run of that definition, no run is started or queued: the outcome records a `skipped` `workflow-run` item. |
| `workflow-dispatch` | None | Broadcasts the event to **every** workflow run parked at a `receive-event` node whose event type and EL expression match (flow engine `matchesEvent`). When the event's `ref` correlates to a subject, delivery is limited to waiting runs on that subject (all of them, across definitions); otherwise all waiting runs with a matching event type are offered the event. |

A subscription with no routing rules matches events silently (useful for previewing
matches before committing to a routing destination).

Event processing is driven by `EventStreamOrchestrator`, a scheduled job that runs every
`axiom.stream-pipeline.poll-interval` (default 5s). Each tick evaluates new events against enabled
subscriptions, records the result in the processing ledger (below), runs the routing rules of matching
subscriptions, and then retries failed ledger entries that are due.

### Starting a run from an event

`create-workflow` rules start runs through `EventStreamOrchestrator.startRunFromEvent`. The same method is
used for two runs that are not started by a subscription:

- **Manual runs from an event.** The event detail page's "Run workflow…" action
  (`POST /stream/events/{eventId}/runs`) starts any published workflow for the event. Workflows started by
  `create-workflow` rules of subscriptions that matched the event are suggested first
  (`GET /stream/events/{eventId}/workflow-suggestions`). The run gets the label `trigger:manual` in addition
  to any labels in the request.
- **Re-runs.** `POST /workflow/runs/{runId}/rerun` re-runs a failed run. When the failed run was started
  from an event that still exists, the re-run is started from that event.

`startRunFromEvent` finds or creates the subject from the event's `ref`, puts the event into the run's
context as `event`, and starts the run in its own transaction with the event recorded as its trigger. The
run, its trace and its `event` context are the same however the run was started. The difference is the
ledger: only a subscription's `create-workflow` rule has a ledger entry. Manual runs and re-runs write no
ledger entry and no routing outcome, so their `trigger_ledger_id` is null. If the subject already has an
active run of the workflow, a manual run fails with HTTP 409 (a routing rule records a `skipped` item
instead).

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
- **Retry:** Each ledger entry tracks its attempts: `attempt_count` (the first try included),
  `last_attempt_at` and, while it is `failed`, `next_attempt_at`. A failed attempt sets `next_attempt_at = now
  + min(retry-initial-delay * 2^(n-1), retry-max-delay)` where `n` is the number of the attempt that failed
  (defaults 30s and 1h: 30s, 1m, 2m, ... capped at 1h). The retry pass selects only `failed` entries whose
  `next_attempt_at` has passed (index on `(status, next_attempt_at)`), and never an entry attempted since the
  tick started, so the first pass and the retry pass cannot both run an entry in one tick. A retry marks the
  entry `pending`, increments `attempt_count` and sets `last_attempt_at` before routing, in one conditional
  update (`... where id = ? and status = 'failed'`). If it changes no row, a manual retry or another node got
  the entry first and it is skipped, so two nodes can never start the same attempt. A retry that re-evaluates
  the filter (see below) and marks the entry `skipped` still counts as an attempt. When the attempt that
  reaches `axiom.stream-pipeline.max-attempts` (default 3) fails, the entry becomes `exhausted` (a distinct
  status rather than `failed` with a null `next_attempt_at`, so lists, filters and the UI can tell "will
  retry" from "gave up" by status alone), "(giving up after N attempts)" is appended to its error message and
  a WARN is logged once.
- **Manual retry:** `POST /stream/events/{eventId}/processing/{ledgerId}/retry` (the retry button on the
  event detail page) makes a `failed` or `exhausted` entry `failed` and due immediately, with one
  conditional update (`... where status in ('failed', 'exhausted')`); it returns 409 for any other status,
  including an attempt in progress (`pending`), so it can never make a running attempt due again or roll
  back its attempt count. It does not reset
  `attempt_count`, so attempt numbers keep increasing and an exhausted entry gets exactly one more attempt:
  if it fails it is exhausted again.
- **Attempt history:** Every `routing_outcome` records its `attempt_number`, so the outcomes of an entry
  group into attempts; the processing API returns them all with the entry's attempt fields and the
  configured maximum.
- **Configuration validation:** startup fails with a message naming the property when
  `max-attempts` < 1, `retry-initial-delay` <= 0 or `retry-max-delay` < `retry-initial-delay`. Invalid
  values are rejected rather than clamped, so a misconfiguration is never silently replaced.
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
trace ID, error). Everything the rule produced is recorded as `routing_outcome_item` rows,
children of the outcome deleted with it (`ON DELETE CASCADE`; `StreamEventCleanup` also deletes them
explicitly):

| Item type | Written by | Links |
|-----------|------------|-------|
| `workflow-run` | `create-workflow` rule (status `skipped` when the subject already had an active run of the definition) | `workflow_run_id` (not set when skipped), `subject_id` |
| `workflow-resumed` | `workflow-dispatch` rule, one per run the event resumed | `workflow_run_id`, `subject_id`, `trace_node_id` (the receive-event node) |
| `no-match` | `workflow-dispatch` rule that resumed no run | — |

Each item has a `status` (`completed`, `failed`, or `skipped`), a `summary` and, when failed, an
`error_message`. A `workflow-dispatch` rule writes one `workflow-resumed`
item per resumed run, or a single `no-match` item (outcome summary "No waiting workflow run matched the
event") when nothing matched, so "nothing happened" is recorded explicitly. A run that matched but
failed to resume gets a `failed` `workflow-resumed` item with the error; the outcome stays `completed`
so the rule is not retried (a retry would re-dispatch the event to every run). The processing API adds the
run's `traceId` to `workflow-run` and `workflow-resumed` items, so the event page links to the run and its
trace.

The outcome's own `subject_id` and `task_id` columns hold only the **first** subject and task; readers
should use the items.

### Links between events and workflow runs

Events and workflow runs are linked both ways. None of these links are foreign keys:
event retention deletes events and ledger entries while runs are kept (but never while the run is still
running or waiting).

- **Started runs.** A run started from an event (by a `create-workflow` rule, manually from the event, or
  as a re-run of a run started from an event) stores `trigger_event_id`; the run's `workflow` trace has
  `eventId` set to the event; and the run's context has the event ID as `event.id`. Only runs started by a
  `create-workflow` rule also store `trigger_ledger_id`, and their outcome's `trace_id` is the run's trace.
  Runs started any other way (a schedule trigger, or a re-run of such a run) have null trigger
  fields. `GET /workflow/runs?triggerEventId=<id>` lists the runs started from an event; the event detail
  page shows them on its "Runs" tab.
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

### Event Sources

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/event-sources` | List all event sources |
| POST | `/event-sources` | Create an event source |
| GET | `/event-sources/{eventSourceId}` | Get an event source (by slug) |
| PUT | `/event-sources/{eventSourceId}` | Update an event source |
| DELETE | `/event-sources/{eventSourceId}` | Delete an event source |
| GET | `/event-sources/{eventSourceId}/status` | Poll health, last poll time, errors |
| GET | `/event-sources/{eventSourceId}/logs` | Poll log history |

### Event Stream

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/stream/events` | Browse events (paginated; filters `filterType`, `filterEventSourceId`, `filterRef`, `filterActor`) |
| GET | `/stream/events/{eventId}` | Get a single event with full typed payload |
| GET | `/stream/events/{eventId}/processing` | Ledger entries, routing outcomes and items for the event |
| POST | `/stream/events/{eventId}/processing/{ledgerId}/retry` | Retry a `failed` or `exhausted` ledger entry |
| POST | `/stream/events/{eventId}/runs` | Start a workflow run from the event (label `trigger:manual`, no ledger entry) |
| GET | `/stream/events/{eventId}/workflow-suggestions` | Workflows started by `create-workflow` rules of subscriptions that matched the event |

### Subscriptions

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/subscriptions` | List all subscriptions |
| POST | `/subscriptions` | Create a subscription |
| GET | `/subscriptions/{subscriptionId}` | Get a subscription |
| PUT | `/subscriptions/{subscriptionId}` | Update a subscription |
| DELETE | `/subscriptions/{subscriptionId}` | Delete a subscription |
| POST | `/subscriptions/preview` | Preview filter expression against existing events |

## Retention

- **Stream events and ledger entries:** Cleaned up hourly based on `eventRetentionDays`
  setting (default 90 days), in batches of 500. Routing outcomes and ledger entries are deleted first (FK),
  then events. Events that a `running` or `waiting` workflow run was started by (`trigger_event_id`) or
  resumed by (`workflow_run_resume.event_id`) are kept until the run finishes. See
  [Data Retention](data-retention.md).
- **Event source poll logs:** Cleaned up hourly with a 3-day fixed retention.

## Configuration Packs

Event sources and subscriptions are included in the configuration pack export/import
system, allowing portable sharing of event system configuration.

---

# Event Schema

## Event schemas

Every event type has a JSON Schema (draft 2020-12). The schema files live under
`core/src/main/resources/schemas/events/`:

- `envelope.json` — the common envelope (see below).
- `defs.json` — shared `$defs` (`Timestamp`, `NullableTimestamp`, `Actor`, `Change`, `CommitAuthor`,
  `Commit`, `NormalizedLabel`, `NormalizedComment`, `NormalizedIssue`, `NormalizedPullRequest`,
  `NormalizedReview`).
- `payloads/<type>.json` — one payload schema per event type, e.g. `payloads/issue.closed.json`.

These files are hand-maintained and are the source of truth. The script
`tools/generate-event-payload-schemas.py` was used only to bootstrap them; do not re-run it.

`EventSchemaRegistry` loads the files at startup and assembles one self-contained schema per event type
(envelope + payload + all of `defs.json` copied in as local `$defs`). If the schemas cannot be loaded,
startup fails. The assembled schemas are served by `GET /api/v1/schemas/events` (summary list of `type` and `description`) and
`GET /api/v1/schemas/events/{eventType}` (the schema; 404 for an unknown type). The paths use
`/schemas/events` rather than `/event-schemas` because the code generator groups paths by the first word
before `-`, which would collide with `/event-sources`.

`sourceData` (the raw source payload) is not validated; the schemas allow any object there.

### Runtime validation

`EventStreamService` validates each event against its schema before persisting it. An invalid event is
still saved, with the validation messages stored in `stream_event.validation_errors` and exposed as
`validationErrors` on `StreamEvent`. Each invalid event is logged as a warning, and the poll log message
gets the suffix ` (N failed schema validation)`. If the validator itself throws, the exception is recorded
as a validation error rather than dropping the event.

### Drift tests and adding an event type

`EventSchemaDriftTest` checks that the `EventType` enum, the payload records registered in
`@JsonSubTypes`, and the payload schema files stay in sync. `ValidatingGitHubNormalizer` and
`ValidatingJiraNormalizer` wrap the normalizers in tests and validate every event they produce against the
schemas, so a normalizer change that breaks a schema fails the build.

To add a new event type:

1. Add the value to the `EventType` enum.
2. Add a payload record in `core/src/main/java/io/apitomy/axiom/core/events/model/payloads/`.
3. Register the record in the `@JsonSubTypes` list in `EventPayload.java`.
4. Add `payloads/<type>.json` under `core/src/main/resources/schemas/events/`.

### Identity and timestamps

Source event IDs are unique per event source: the unique key is (`eventSourceId`, `sourceEventId`)
(migration `V4__event_schema_validation.sql`). All timestamps are ISO-8601 UTC strings ending in `Z`.

## Common Event Envelope

Every event in the stream shares this envelope structure.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | System-generated unique event ID (UUID) |
| `sourceEventId` | `string` | Original ID from the source system (for dedup). GitHub: Events API `id`. Jira: synthesized from issue key + changelog entry ID |
| `source` | `string` | Source system identifier: `"github"` or `"jira"` |
| `eventSourceId` | `string` | Slug of the event source that produced this event |
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

**Base URL resolution:** `GitHubUrls.resolve` turns the stored `baseUrl` into a web URL (used to build
`ref`s) and a REST API URL (used for polling). The stored value is kept as entered and resolved on each poll.
Scheme and host are case-insensitive, a missing scheme means `https`, and trailing slashes, query and fragment
are ignored. A repository path in the URL is ignored; repositories always come from `configuration`.

| `baseUrl` | Web URL | API URL |
|-----------|---------|---------|
| `github.com`, `www.github.com` or `api.github.com` (any path) | `https://github.com` | `https://api.github.com` |
| `<org>.ghe.com` or `api.<org>.ghe.com` | `https://<org>.ghe.com` | `https://api.<org>.ghe.com` |
| Other host with `/api/v3` in the path | everything before `/api/v3` | up to and including `/api/v3` |
| Any other host (GitHub Enterprise Server) | `<scheme>://<host>` | `<scheme>://<host>/api/v3` |

A value that is not an http(s) URL with a host fails validation and is recorded as a configuration error
when polled. In the `ref` formats below, `{baseUrl}` is the resolved web URL.

**`ref` construction:**

| Event Category | Format |
|----------------|--------|
| Issue events | `{baseUrl}/{owner}/{repo}/issues/{number}` |
| PR events | `{baseUrl}/{owner}/{repo}/pull/{number}` |
| Push events | `{baseUrl}/{owner}/{repo}` |
| Branch events | `{baseUrl}/{owner}/{repo}/tree/{branchName}` |
| Tag/release events | `{baseUrl}/{owner}/{repo}/releases/tag/{tagName}` |

**Deduplication:** Each Events API event has a unique `id`. The poller tracks seen IDs
per event source + repo in memory and deduplicates in the database via the `source_event_id`
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

