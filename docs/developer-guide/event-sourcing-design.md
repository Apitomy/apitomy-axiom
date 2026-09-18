# Event Sourcing Redesign — Design Document

## Overview

This document describes the redesign of Axiom's event system. The current system
conflates event production, filtering, and routing into a single "Event Source" concept.
The new design separates these into three distinct layers:

1. **EventSourceConnection** — an authenticated link to an external system that produces
   a raw, normalized event stream
2. **Event Stream** — a single table of typed, schema-validated events from all connections
3. **Event Subscription** — a filtered view over the event stream that routes matching
   events to a destination (Manager triage, workflow dispatch, etc.)

## Motivation

The current event system has several problems:

- **Missing events from GitHub.** The current pollers use GitHub REST APIs not designed
  for polling (issues, comments, PRs, PR review comments). They infer event types from
  timestamp heuristics, which is lossy — rapid state changes, label changes, assignments,
  and review approvals can be missed.
- **Untyped payloads.** Events carry a raw JSON blob that varies by source and event type.
  Downstream consumers (workflow EL expressions, Manager prompts, action scripts) must
  know the internal structure, making integrations fragile.
- **Conflated concerns.** Each Event Source owns the external connection, the polling, the
  filtering, and the routing. This makes it impossible to have multiple filtered views
  over the same event stream without duplicating connections and polling work.

## Architecture

### EventSourceConnection

A configured, authenticated link to an external system. Produces a raw, normalized event
stream. No filtering — every detectable event gets recorded.

- Each connection has a user-provided ID (slug) that serves as its primary key.
  The slug is limited to lowercase letters, numbers, and dashes (e.g., `github-com`,
  `github-ibm`, `jira-prod`).
- Multiple connections of the same type are supported (e.g., github.com + GitHub Enterprise)
- Each connection specifies what to watch (repositories for GitHub, projects for Jira)
- A background poller runs per connection, iterating over watched resources
- Events carry a `connectionId` (the slug) for traceability

**GitHub connection:**
- Uses the Repository Events API (`GET /repos/{owner}/{repo}/events`) as the primary source
- ETag-based conditional polling for efficiency (304 when nothing changed)
- Per-repo polling under a shared authentication token
- Configuration: `baseUrl` (default `https://api.github.com`), `secretName`, `repositories` (list), `pollInterval`

**Jira connection:**
- Uses JQL search with changelog expansion (`expand=changelog`) for field-level change detection
- Single query covers all configured projects via `project in (A, B, C)` clause
- Configuration: `baseUrl`, `secretName`, `projects` (list), `pollInterval`

### Event Stream

A single database table of normalized, schema-validated events produced by all connections.

- Each event has a well-defined typed payload determined by its event type
- Deduplication handled by the sourcer using source-specific event IDs
- Configurable retention policy
- Browsable via REST API (`GET /events`)

### Event Subscription (renamed from Event Source)

A filtered view over the event stream. Each subscription defines:

- **Filter criteria** — event type patterns, payload field matching, connection scoping
- **Labels** — for routing and categorization
- **Routing destination** — Manager triage, workflow dispatch; future: direct action invocation, workflow creation

Filtering happens exclusively at the subscription level. The event source component
produces the complete raw stream.

## REST API

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/connections` | List all connections |
| POST | `/connections` | Create a connection |
| GET | `/connections/{connectionId}` | Get a connection (by slug) |
| PUT | `/connections/{connectionId}` | Update a connection |
| DELETE | `/connections/{connectionId}` | Delete a connection |
| GET | `/connections/{connectionId}/status` | Poll health, last poll time, errors |
| GET | `/events` | Browse the normalized event stream (paginated, filterable) |
| GET | `/events/{id}` | Get a single event with full typed payload |
| GET | `/subscriptions` | List all event subscriptions |
| POST | `/subscriptions` | Create an event subscription |
| GET | `/subscriptions/{id}` | Get an event subscription |
| PUT | `/subscriptions/{id}` | Update an event subscription |
| DELETE | `/subscriptions/{id}` | Delete an event subscription |

## Migration

This is a clean break from the existing Event Source system. Existing `EventSourceEntity`
data is not migrated. Users will need to create new connections and subscriptions.

---

# Event Schema

## Common Event Envelope

Every event in the stream shares this envelope structure.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | System-generated unique event ID (UUID) |
| `sourceEventId` | `string` | Original ID from the source system (for dedup). GitHub: Events API `id`. Jira: synthesized from issue key + changelog entry ID + timestamp |
| `source` | `string` | Source system identifier: `"github"` or `"jira"` |
| `connectionId` | `string` | Slug of the EventSourceConnection that produced this event (e.g., `github-com`, `jira-prod`) |
| `type` | `string` | Normalized event type (e.g., `issue.created`, `pr.merged`) |
| `ref` | `string` | Full URL uniquely identifying the subject of the event. GitHub: `https://github.com/owner/repo/issues/123` or `https://github.com/owner/repo/pull/456`. Jira: `https://myorg.atlassian.net/browse/PROJ-123`. For repo-level events (push, branch, tag): `https://github.com/owner/repo` |
| `timestamp` | `ISO-8601` | When the event occurred in the source system |
| `actor` | `Actor` | Who performed the action |
| `payload` | `object` | Typed payload, schema determined by `type` |
| `sourceData` | `object?` | Raw source-specific data escape hatch |

## Shared Sub-Object Schemas

### Actor

| Field | Type | Description |
|-------|------|-------------|
| `login` | `string` | Username or account ID. GitHub: `user.login`. Jira: `accountId` |
| `displayName` | `string?` | Display name. GitHub: same as login. Jira: `displayName` |
| `avatarUrl` | `string?` | Profile image URL |
| `url` | `string?` | Profile HTML URL |

### Issue

Shared sub-object used across issue-related event types. Normalized to be
source-agnostic — same field names whether the event came from GitHub or Jira.

| Field | Type | Description |
|-------|------|-------------|
| `number` | `string` | Issue identifier. GitHub: `"123"` (issue number). Jira: `"PROJ-123"` (issue key) |
| `title` | `string` | Issue title. GitHub: `title`. Jira: `summary` |
| `body` | `string?` | Issue description. GitHub: `body` (markdown). Jira: `description` (ADF converted to plain text) |
| `state` | `string` | Normalized state: `"open"` or `"closed"`. Jira: mapped from status category (`done` = `closed`, all others = `open`) |
| `stateDetail` | `string?` | Source-specific state detail. GitHub: `state_reason` (`completed`, `not_planned`). Jira: status name (e.g., `"In Progress"`, `"Done"`) |
| `author` | `Actor` | Who created the issue. GitHub: `user`. Jira: `reporter` |
| `assignees` | `Actor[]` | Current assignees. Jira: wrapped single assignee into array |
| `labels` | `string[]` | Label names |
| `milestone` | `string?` | Milestone name. GitHub: `milestone.title`. Jira: sprint name |
| `url` | `string` | HTML URL to the issue |
| `createdAt` | `ISO-8601` | Creation timestamp |
| `updatedAt` | `ISO-8601` | Last update timestamp |
| `closedAt` | `ISO-8601?` | Closure timestamp |

### PullRequest

Extends Issue with PR-specific fields. GitHub only (Jira does not produce PR events).

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
| `body` | `string` | Comment body text. Jira: ADF converted to plain text |
| `author` | `Actor` | Who wrote the comment |
| `url` | `string` | HTML URL to the comment |
| `createdAt` | `ISO-8601` | Creation timestamp |
| `updatedAt` | `ISO-8601` | Last edit timestamp |

### Review

GitHub-only. No Jira equivalent.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `string` | Review ID |
| `state` | `string` | `approved`, `changes_requested`, `commented`, `dismissed` |
| `body` | `string?` | Review body text |
| `author` | `Actor` | Who submitted the review |
| `url` | `string` | HTML URL to the review |
| `submittedAt` | `ISO-8601` | Submission timestamp |

### Label

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Label name |
| `color` | `string?` | Hex color code (GitHub only) |
| `description` | `string?` | Label description (GitHub only) |

### Change

Used by events that represent a specific field change (e.g., `issue.updated`, status
transitions). Particularly important for Jira, where events are derived from changelog
entries.

| Field | Type | Description |
|-------|------|-------------|
| `field` | `string` | Which field changed (e.g., `summary`, `status`, `assignee`, `labels`) |
| `from` | `string?` | Previous value (human-readable) |
| `to` | `string?` | New value (human-readable) |
| `author` | `Actor?` | Who made the change (if different from envelope `actor`) |

---

# Event Type Taxonomy

## Event Types Summary

### Issue Events (GitHub + Jira)

| Event Type | GitHub Source | Jira Source |
|------------|--------------|-------------|
| `issue.created` | `IssuesEvent` / `opened` | Issue with `created` timestamp within poll window |
| `issue.updated` | `IssuesEvent` / `edited` | Changelog: `summary` or `description` field change |
| `issue.closed` | `IssuesEvent` / `closed` | Changelog: status transition to `Done` category |
| `issue.reopened` | `IssuesEvent` / `reopened` | Changelog: status transition from `Done` to non-`Done` |
| `issue.assigned` | `IssuesEvent` / `assigned` | Changelog: `assignee` field set |
| `issue.unassigned` | `IssuesEvent` / `unassigned` | Changelog: `assignee` field cleared |
| `issue.labeled` | `IssuesEvent` / `labeled` | Changelog: `labels` field (label added) |
| `issue.unlabeled` | `IssuesEvent` / `unlabeled` | Changelog: `labels` field (label removed) |
| `issue.comment.created` | `IssueCommentEvent` / `created` | Comment with `created` > last poll |
| `issue.comment.updated` | `IssueCommentEvent` / `edited` | Comment with `updated` > `created` and > last poll |
| `issue.comment.deleted` | `IssueCommentEvent` / `deleted` | Not detectable via polling |

### Pull Request Events (GitHub only)

| Event Type | GitHub Source | Notes |
|------------|--------------|-------|
| `pr.created` | `PullRequestEvent` / `opened` | Requires PR backfill |
| `pr.closed` | `PullRequestEvent` / `closed` + `merged == false` | Requires PR backfill to check `merged` |
| `pr.merged` | `PullRequestEvent` / `closed` + `merged == true` | Requires PR backfill to check `merged` |
| `pr.reopened` | `PullRequestEvent` / `reopened` | Requires PR backfill |
| `pr.review_requested` | `PullRequestEvent` / `review_requested` | Requires PR backfill |
| `pr.review.submitted` | `PullRequestReviewEvent` / `created` | Requires PR backfill |
| `pr.comment.created` | `IssueCommentEvent` / `created` on PR | Detected by `pull_request` key on issue object |
| `pr.labeled` | `PullRequestEvent` / `labeled` | Requires PR backfill |
| `pr.unlabeled` | `PullRequestEvent` / `unlabeled` | Requires PR backfill |
| `pr.synchronize` | `PullRequestEvent` / `synchronize` | Requires PR backfill; new commits pushed |

### Repository Events (GitHub only)

| Event Type | GitHub Source | Notes |
|------------|--------------|-------|
| `push` | `PushEvent` | Up to 20 commits per event |
| `branch.created` | `CreateEvent` / `ref_type == "branch"` | |
| `branch.deleted` | `DeleteEvent` / `ref_type == "branch"` | |
| `tag.created` | `CreateEvent` / `ref_type == "tag"` | |
| `release.published` | `ReleaseEvent` / `published` | Full release visible (excludes drafts) |

---

# Event Type Payload Specifications

## Issue Events

### `issue.created`

| Payload Field | Type |
|---------------|------|
| `issue` | `Issue` |

GitHub: full issue object available directly from Events API. Jira: full issue available
from search results.

### `issue.updated`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | Current state |
| `change` | `Change?` | What changed (field, from, to) |

GitHub: `changes` object in payload contains `{title?: {from}, body?: {from}}`.
Jira: derived from changelog entry for `summary`, `description`, or `priority`.

### `issue.closed`

| Payload Field | Type |
|---------------|------|
| `issue` | `Issue` |

`issue.state` will be `"closed"`. `issue.stateDetail` will contain the reason
(GitHub: `completed`/`not_planned`. Jira: resolution name).

### `issue.reopened`

| Payload Field | Type |
|---------------|------|
| `issue` | `Issue` |

`issue.state` will be `"open"`.

### `issue.assigned`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | Current state with updated `assignees` |
| `assignee` | `Actor` | The user who was assigned |

### `issue.unassigned`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | Current state with updated `assignees` |
| `assignee` | `Actor` | The user who was unassigned |

### `issue.labeled`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | Current state (labels includes the new label) |
| `label` | `Label` | The label that was added |

### `issue.unlabeled`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | Current state (labels no longer includes the removed label) |
| `label` | `Label` | The label that was removed |

### `issue.comment.created`

| Payload Field | Type |
|---------------|------|
| `issue` | `Issue` |
| `comment` | `Comment` |

GitHub: the issue object in `IssueCommentEvent` has no `pull_request` key.
If `pull_request` key is present, the event type becomes `pr.comment.created` instead.

### `issue.comment.updated`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | |
| `comment` | `Comment` | Current state of the edited comment |

### `issue.comment.deleted`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `issue` | `Issue` | |
| `comment` | `Comment` | Last known state of the deleted comment |

GitHub only. Not detectable from Jira via polling.

## Pull Request Events

All PR event payloads require a **backfill API call** because the GitHub Repository Events
API truncates `PullRequestEvent` payloads to only `{id, number, url, head, base}`.

The sourcer must call `GET /repos/{owner}/{repo}/pulls/{number}` to fetch the full PR
before normalizing. PR objects can be cached per poll cycle to avoid redundant calls when
multiple events fire for the same PR.

### `pr.created`

| Payload Field | Type |
|---------------|------|
| `pullRequest` | `PullRequest` |

### `pr.closed`

| Payload Field | Type |
|---------------|------|
| `pullRequest` | `PullRequest` |

Detection: `PullRequestEvent` action `closed` where backfilled PR has `merged == false`.

### `pr.merged`

| Payload Field | Type |
|---------------|------|
| `pullRequest` | `PullRequest` |

Detection: `PullRequestEvent` action `closed` where backfilled PR has `merged == true`.

### `pr.reopened`

| Payload Field | Type |
|---------------|------|
| `pullRequest` | `PullRequest` |

### `pr.review_requested`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `pullRequest` | `PullRequest` | |
| `requestedReviewer` | `Actor` | The user whose review was requested |

### `pr.review.submitted`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `pullRequest` | `PullRequest` | |
| `review` | `Review` | The submitted review |

### `pr.comment.created`

| Payload Field | Type |
|---------------|------|
| `pullRequest` | `PullRequest` |
| `comment` | `Comment` |

Detected when `IssueCommentEvent` has a `pull_request` key on the issue object.

### `pr.labeled`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `pullRequest` | `PullRequest` | |
| `label` | `Label` | The label that was added |

### `pr.unlabeled`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `pullRequest` | `PullRequest` | |
| `label` | `Label` | The label that was removed |

### `pr.synchronize`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `pullRequest` | `PullRequest` | PR with updated `headSha` |
| `before` | `string` | Previous head commit SHA |
| `after` | `string` | New head commit SHA |

## Repository Events

### `push`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `ref` | `string` | Full git ref (e.g., `refs/heads/main`) |
| `branch` | `string` | Short branch name (extracted from ref) |
| `beforeSha` | `string` | SHA before the push |
| `afterSha` | `string` | SHA after the push |
| `forced` | `boolean` | Whether this was a force push |
| `commits` | `Commit[]` | Commits in the push (max 20) |

**Commit sub-object:**

| Field | Type | Description |
|-------|------|-------------|
| `sha` | `string` | Full commit SHA |
| `message` | `string` | Commit message |
| `author` | `object` | `{name: string, email: string}` (git identity, not platform user) |
| `url` | `string` | HTML URL to the commit |

### `branch.created`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `ref` | `string` | Branch name |
| `defaultBranch` | `string` | Repository's default branch name |

### `branch.deleted`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `ref` | `string` | Branch name that was deleted |

### `tag.created`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `ref` | `string` | Tag name |
| `defaultBranch` | `string` | Repository's default branch name |

### `release.published`

| Payload Field | Type | Description |
|---------------|------|-------------|
| `tagName` | `string` | Git tag associated with the release (e.g., `v1.2.0`) |
| `name` | `string` | Release title |
| `body` | `string?` | Release notes (markdown) |
| `isDraft` | `boolean` | Whether the release is a draft (always `false` for `published`) |
| `isPrerelease` | `boolean` | Whether this is a pre-release |
| `author` | `Actor` | Who created the release |
| `url` | `string` | HTML URL to the release page |
| `createdAt` | `ISO-8601` | Creation timestamp |
| `publishedAt` | `ISO-8601` | Publication timestamp |

GitHub `ReleaseEvent` also supports actions `created`, `edited`, `deleted`,
`prereleased`, `released`, and `unpublished`. Only `published` is normalized as an
event type for now. Additional actions can be added if needed.

---

# Source-Specific Mapping Notes

## GitHub

### Event Detection

GitHub events are detected via the Repository Events API (`GET /repos/{owner}/{repo}/events`).
Each API event has a `type` (e.g., `IssuesEvent`) and a `payload` containing an `action`
field and source-specific data.

### PR Backfill

The Events API truncates `PullRequestEvent` payloads to only `{id, number, url, head, base}`.
The sourcer must fetch the full PR object from `GET /repos/{owner}/{repo}/pulls/{number}`
for all PR-related events. Caching per poll cycle reduces redundant calls.

For `PullRequestEvent` with action `closed`, the full PR must be fetched to check the
`merged` field and distinguish `pr.closed` from `pr.merged`.

### Issue Comment vs PR Comment

`IssueCommentEvent` covers comments on both issues and pull requests. The sourcer
distinguishes them by checking for a `pull_request` key on the event's `issue` object:
- Present: emit `pr.comment.created`
- Absent: emit `issue.comment.created`

### `ref` Construction

The envelope `ref` field is a full URL constructed from the event data:

| Event Category | `ref` Format |
|----------------|--------------|
| Issue events | `{baseUrl}/{owner}/{repo}/issues/{number}` |
| PR events | `{baseUrl}/{owner}/{repo}/pull/{number}` |
| Push events | `{baseUrl}/{owner}/{repo}` |
| Branch events | `{baseUrl}/{owner}/{repo}/tree/{branchName}` |
| Tag events | `{baseUrl}/{owner}/{repo}/releases/tag/{tagName}` |
| Release events | `{baseUrl}/{owner}/{repo}/releases/tag/{tagName}` |

Where `baseUrl` is the connection's configured base URL (e.g., `https://github.com`
for github.com, or the GHE instance URL).

### Deduplication

Each event from the Repository Events API has a unique string `id`. The sourcer tracks
the last-seen event ID per repository and skips events already processed.

### Polling

- ETag-based conditional polling (304 when nothing changed)
- Recommended interval: 60 seconds per repo (configurable per connection)
- The API retains up to 300 events for the last 90 days

### GitHub `sourceData`

The escape hatch for GitHub events contains the raw API objects:

```json
{
  "githubEventId": "12345678",
  "githubEventType": "IssuesEvent",
  "githubAction": "labeled",
  "rawPayload": { ... }
}
```

## Jira

### Event Detection

Jira events are derived from JQL search results with changelog expansion. A single query
covers all configured projects: `project in (A, B, C) AND updated >= "{since}"`.

### Changelog Processing

The changelog provides field-level change history. Each entry contains `field`,
`fromString`, `toString`, and a timestamp. The sourcer filters changelog entries by
timestamp (only entries newer than the last poll) and classifies them into normalized
event types:

| Changelog Field | Event Type |
|-----------------|------------|
| `status` (to Done category) | `issue.closed` |
| `status` (from Done category) | `issue.reopened` |
| `summary` or `description` | `issue.updated` |
| `assignee` (set) | `issue.assigned` |
| `assignee` (cleared) | `issue.unassigned` |
| `labels` (added) | `issue.labeled` |
| `labels` (removed) | `issue.unlabeled` |

### Label Diffing

Jira's changelog for labels provides space-separated before/after lists
(`fromString: "bug login"`, `toString: "bug login critical"`). The sourcer diffs these
to determine individual additions/removals. Multiple label changes in one edit produce
one event per label change.

### ADF Conversion

Jira Cloud v3 returns `description` and `comment.body` in Atlassian Document Format (ADF).
The normalized `body` fields contain a plain-text conversion. The raw ADF is preserved in
`sourceData` (`descriptionAdf`, `commentBodyAdf`).

### Comment Detection

Comments require separate processing. The sourcer compares comment `created` and `updated`
timestamps against the last poll to detect new and edited comments.

### `ref` Construction

The envelope `ref` field is a full browse URL:

| Event Category | `ref` Format |
|----------------|--------------|
| All issue events | `{baseUrl}/browse/{issueKey}` (e.g., `https://myorg.atlassian.net/browse/PROJ-123`) |

Where `baseUrl` is the connection's configured Jira base URL.

### Deduplication

Jira events are synthesized from changelog entries. The sourcer generates a stable
`sourceEventId` from: `{issueKey}-{changelogEntryId}` (for changelog-derived events)
or `{issueKey}-created` (for issue creation) or `{issueKey}-comment-{commentId}` (for
comment events).

### Jira `sourceData`

```json
{
  "jiraId": "10001",
  "project": { "key": "PROJ", "name": "Project Name" },
  "issueType": { "name": "Bug", "subtask": false },
  "priority": { "name": "Medium" },
  "resolution": { "name": "Done" },
  "components": [{"name": "Backend"}],
  "fixVersions": [{"name": "1.0"}],
  "sprint": { "name": "Sprint 14", "state": "active" },
  "parent": { "key": "PROJ-100", "summary": "Parent epic" },
  "dueDate": "2026-10-15",
  "descriptionAdf": { "type": "doc", "version": 1, "content": [] },
  "customFields": {}
}
```
