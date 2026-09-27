# Event Sourcing Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Event Source system with a three-layer architecture:
EventSourceConnections (production), a normalized Event Stream (storage), and Event
Subscriptions (filtered routing) — with typed, schema-validated event payloads and
GitHub polling via the Repository Events API.

**Architecture:** Connections poll external systems (GitHub Repository Events API, Jira
JQL+changelog) and write normalized events with typed payloads to a unified event stream
table. Subscriptions define filtered views over the stream and route matching events to
destinations (Manager triage, workflow dispatch). The old EventSource, poller, and
filter code is removed in a final cleanup phase.

**Tech Stack:** Java 21 / Quarkus, Hibernate ORM Panache, Flyway (H2 + Postgres),
JUnit 5 + `@QuarkusTest`, Jackson for JSON serialization, Java HttpClient for API calls.

**Spec:** `docs/developer-guide/event-sourcing-design.md`

## Global Constraints

- API-first development: REST API changes start in `common/api/src/main/resources/openapi.json`,
  then `mvn install` generates interfaces and beans into `common/api/target/generated-sources/`.
- 4-space indentation, explicit types, Javadoc on public methods.
- Tests use JUnit 5 with `@QuarkusTest`; scheduled/polling jobs are disabled in test profile.
- Flyway migrations must work on both H2 (tests) and Postgres. Next free migration: `V60`.
- Panache entity ID sequences use `START WITH 1 INCREMENT BY 50`.
- To compile `app` after `core` changes: `mvn -q -pl core install -DskipTests`.
- Line references are to `main` at time of plan writing; verify with grep before editing.
- Connection IDs (slugs) are user-provided, constrained to `[a-z0-9-]+`, max 63 characters.
- Event `ref` fields are full URLs, unique across connections.
- Event payloads are typed per event type; raw source data goes in the `sourceData` escape hatch.

---

## Phase 1: Normalized Event Payload Model

Build the typed Java model for normalized event payloads. These are pure data classes
with no dependencies on persistence or REST — they live in `core` and are used by
pollers, REST resources, and pipeline consumers.

### Task 1: Core event payload records

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/Actor.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedIssue.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedPullRequest.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedComment.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedReview.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedLabel.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/Change.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/Commit.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/EventType.java` (enum)
- Test: `core/src/test/java/io/apitomy/axiom/core/events/model/EventTypeTest.java`

**Produces:**
- Java records for all sub-object types defined in the event schema spec (Actor, Issue,
  PullRequest, Comment, Review, Label, Change, Commit).
- `EventType` enum with all 26 event type strings (`ISSUE_CREATED("issue.created")`, etc.)
  and a `fromString()` parser.
- Jackson-serializable (constructor-based deserialization via `@JsonProperty`).

### Task 2: Typed event payload classes

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/EventPayload.java` (sealed interface)
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/payloads/IssueCreatedPayload.java`
- Create: (one class per event type, 26 total, in the `payloads` subpackage)
- Test: `core/src/test/java/io/apitomy/axiom/core/events/model/EventPayloadSerializationTest.java`

**Produces:**
- Sealed interface `EventPayload` with `EventType type()` accessor.
- One record per event type implementing `EventPayload` (e.g., `IssueCreatedPayload`
  contains `NormalizedIssue issue`; `IssueAssignedPayload` contains `NormalizedIssue issue`
  + `Actor assignee`; `PushPayload` contains `ref`, `branch`, `beforeSha`, `afterSha`,
  `forced`, `List<Commit> commits`).
- Serialization tests verifying round-trip JSON for each payload type.
- Jackson `@JsonTypeInfo`/`@JsonSubTypes` annotations for polymorphic deserialization
  keyed on event type.

### Task 3: Event envelope record

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedEvent.java`
- Test: `core/src/test/java/io/apitomy/axiom/core/events/model/NormalizedEventTest.java`

**Produces:**
- `NormalizedEvent` record with fields: `id` (UUID string), `sourceEventId`, `source`,
  `connectionId` (slug string), `type` (EventType), `ref` (full URL), `timestamp`,
  `actor`, `payload` (EventPayload), `sourceData` (JsonNode, nullable).
- Full envelope serialization test with a sample event.

---

## Phase 2: Database Schema

Create the new database tables for connections, the normalized event stream, and
subscriptions. Old tables are left in place — they'll be removed in the cleanup phase.

### Task 4: EventSourceConnection entity + migration

**Files:**
- Create: `app/src/main/resources/db/migration/V60__create_event_source_connection.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/EventSourceConnectionEntity.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/EventSourceConnectionEntityTest.java`

**Produces:**
- `event_source_connection` table with columns: `id` (VARCHAR PK, the slug), `name`,
  `description`, `source_type` (github/jira), `enabled`, `base_url`, `secret_name`,
  `poll_interval`, `configuration` (JSON TEXT — repositories list or projects list),
  `last_polled_at`, `created_on`, `modified_on`.
- `EventSourceConnectionEntity` — **not** extending `PanacheEntity` (string PK instead
  of auto-generated Long). Uses `PanacheEntityBase` with `@Id String id`.
- Basic persistence test.

### Task 5: Normalized event stream entity + migration

**Files:**
- Create: `app/src/main/resources/db/migration/V61__create_event_stream.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/StreamEventEntity.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/StreamEventEntityTest.java`

**Produces:**
- `stream_event` table with columns: `id` (UUID PK), `source_event_id` (unique index
  for dedup), `source`, `connection_id` (FK to `event_source_connection.id`), `type`,
  `ref` (full URL), `timestamp`, `actor` (JSON TEXT), `payload` (JSON TEXT),
  `source_data` (JSON TEXT, nullable), `created_on`.
- Indexes on `type`, `connection_id`, `ref`, `timestamp`, `source_event_id`.
- `StreamEventEntity` using `PanacheEntityBase` with `@Id UUID id`.
- Test verifying persistence and dedup constraint.

### Task 6: EventSubscription entity + migration

**Files:**
- Create: `app/src/main/resources/db/migration/V62__create_event_subscription.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/EventSubscriptionEntity.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/EventSubscriptionEntityTest.java`

**Produces:**
- `event_subscription` table with columns: `id` (BIGINT PK), `name`, `description`,
  `enabled`, `filters` (JSON TEXT — same include/exclude model as today), `labels`
  (join table `event_subscription_label`), `created_on`, `modified_on`.
- `EventSubscriptionEntity extends PanacheEntity`.
- Labels via `@ElementCollection` (mirrors current `event_source_label` pattern).
- Test verifying persistence with filters and labels.

---

## Phase 3: OpenAPI Spec + REST Resources

Define the REST API contract and implement the resource classes. Follows the API-first
pattern: spec first, generate, then implement.

### Task 7: OpenAPI spec — Connection endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Produces:**
- Schemas: `EventSourceConnection`, `NewEventSourceConnection`,
  `EventSourceConnectionSearchResults`, `EventSourceConnectionStatus`.
- Paths: `GET/POST /connections`, `GET/PUT/DELETE /connections/{connectionId}`,
  `GET /connections/{connectionId}/status`.
- Generated interface: `ConnectionResource`.

### Task 8: OpenAPI spec — Event stream endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Produces:**
- Schemas: `StreamEvent`, `StreamEventSearchResults`.
- Paths: `GET /events` (paginated, filterable by type, connectionId, ref),
  `GET /events/{eventId}`.
- Note: the existing `/events` endpoints serve the old event model. These new endpoints
  will use a different path prefix or the old ones will be updated in the cleanup phase.
  Decision: use `/stream/events` during transition, rename to `/events` after cleanup.

### Task 9: OpenAPI spec — Subscription endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Produces:**
- Schemas: `EventSubscription`, `NewEventSubscription`, `EventSubscriptionSearchResults`.
- Paths: `GET/POST /subscriptions`, `GET/PUT/DELETE /subscriptions/{subscriptionId}`.
- Filter schemas reuse the existing `EventSourceFilterRule` / `EventSourceFilters`
  pattern with additions for connection scoping.
- Generated interface: `SubscriptionResource`.

### Task 10: Connection REST resource implementation

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/ConnectionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/ConnectionsResourceImplTest.java`

**Produces:**
- CRUD operations for connections with slug validation (`[a-z0-9-]+`, max 63 chars).
- Status endpoint returning last poll time, event count, error state.
- Secret resolution validation on create/update (verify the named secret exists).
- Integration test for the full CRUD lifecycle.

### Task 11: Event stream REST resource implementation

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/StreamEventsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/StreamEventsResourceImplTest.java`

**Produces:**
- Paginated event listing with filters (type, connectionId, ref substring).
- Single event retrieval with full deserialized payload.
- Integration test.

### Task 12: Subscription REST resource implementation

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/SubscriptionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/SubscriptionsResourceImplTest.java`

**Produces:**
- CRUD operations for subscriptions.
- Filter validation on create/update.
- Integration test for the full CRUD lifecycle.

---

## Phase 4: GitHub Event Sourcing

Implement the GitHub poller using the Repository Events API, event normalization,
and deduplication. This is the largest phase — it replaces the existing four-endpoint
polling approach with a single Events API poller and a full normalization layer.

### Task 13: GitHub API client for Repository Events

**Files:**
- Create: `events/github/src/main/java/io/apitomy/axiom/events/github/v2/GitHubEventsApiClient.java`
- Test: `events/github/src/test/java/io/apitomy/axiom/events/github/v2/GitHubEventsApiClientTest.java`

**Produces:**
- Client wrapping `GET /repos/{owner}/{repo}/events` with ETag support.
- Returns raw JSON event list + response metadata (ETag, poll interval).
- Backfill method: `GET /repos/{owner}/{repo}/pulls/{number}` for truncated PR payloads.
- Unit test with mocked HTTP responses.

### Task 14: GitHub event normalizer

**Files:**
- Create: `events/github/src/main/java/io/apitomy/axiom/events/github/v2/GitHubEventNormalizerV2.java`
- Test: `events/github/src/test/java/io/apitomy/axiom/events/github/v2/GitHubEventNormalizerV2Test.java`

**Produces:**
- Maps each GitHub Events API event type + action to a `NormalizedEvent`:
  - `IssuesEvent` → `issue.created/updated/closed/reopened/assigned/unassigned/labeled/unlabeled`
  - `IssueCommentEvent` → `issue.comment.created/updated/deleted` or `pr.comment.created`
  - `PullRequestEvent` → `pr.created/closed/merged/reopened/review_requested/labeled/unlabeled/synchronize`
  - `PullRequestReviewEvent` → `pr.review.submitted`
  - `PushEvent` → `push`
  - `CreateEvent` → `branch.created` or `tag.created`
  - `DeleteEvent` → `branch.deleted`
  - `ReleaseEvent` → `release.published`
- Constructs full URL `ref` from connection base URL + owner/repo + issue/PR number.
- Populates `sourceData` with raw GitHub payload.
- Handles PR close vs merge detection (requires backfill result).
- Handles issue-comment-on-PR detection (`pull_request` key check).
- Comprehensive unit tests with fixture JSON for each event type.

### Task 15: GitHub connection poller

**Files:**
- Create: `events/github/src/main/java/io/apitomy/axiom/events/github/v2/GitHubConnectionPoller.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/services/EventStreamService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/GitHubConnectionPollerTest.java`

**Produces:**
- `GitHubConnectionPoller` — `@Scheduled` poller that iterates over all enabled GitHub
  connections, polls each configured repository using `GitHubEventsApiClient`, normalizes
  events via `GitHubEventNormalizerV2`, and persists via `EventStreamService`.
- `EventStreamService` — core service that persists `StreamEventEntity` rows with
  deduplication (skip if `sourceEventId` already exists).
- ETag tracking per connection+repo (stored in a new `connection_poll_state` table or
  as JSON in the connection entity's configuration).
- Per-repo last-seen event ID tracking for dedup.
- Integration test with mocked GitHub API responses.

---

## Phase 5: Jira Event Sourcing

Same pattern as Phase 4, but for Jira using JQL search with changelog expansion.

### Task 16: Jira API client for JQL search + changelog

**Files:**
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClient.java`
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClientTest.java`

**Produces:**
- Client wrapping `GET /rest/api/3/search` with `expand=changelog` and multi-project
  JQL (`project in (A, B, C) AND updated >= "{since}"`).
- Comment fetching per issue: `GET /rest/api/3/issue/{key}/comment`.
- Unit test with mocked HTTP responses.

### Task 17: Jira event normalizer

**Files:**
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java`
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2Test.java`

**Produces:**
- Processes changelog entries and classifies into normalized event types.
- Status category mapping (Done → closed, others → open).
- Label diffing (space-separated before/after lists).
- ADF-to-plain-text conversion for description and comment bodies.
- Jira `sourceData` population (project, issueType, priority, sprint, etc.).
- `ref` construction: `{baseUrl}/browse/{issueKey}`.
- Stable `sourceEventId` generation: `{issueKey}-{changelogEntryId}`,
  `{issueKey}-created`, `{issueKey}-comment-{commentId}`.
- Unit tests with fixture JSON for each event type.

### Task 18: Jira connection poller

**Files:**
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraConnectionPoller.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/JiraConnectionPollerTest.java`

**Produces:**
- `@Scheduled` poller iterating over all enabled Jira connections.
- Single JQL query covering all configured projects.
- Changelog timestamp filtering (only entries newer than last poll).
- Comment timestamp filtering.
- Dedup via `EventStreamService`.
- Integration test with mocked Jira API responses.

---

## Phase 6: Subscription Evaluation + Pipeline Integration

Wire subscriptions into the event processing pipeline. Each new event from the stream
is evaluated against all active subscriptions, and matching events are routed to their
destination.

### Task 19: Subscription filter evaluator

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/filters/SubscriptionFilterEvaluator.java`
- Test: `core/src/test/java/io/apitomy/axiom/core/filters/SubscriptionFilterEvaluatorTest.java`

**Produces:**
- Evaluates a `StreamEventEntity` against an `EventSubscriptionEntity`'s filter rules.
- Reuses the existing include/exclude glob pattern model from `EventFilterEvaluator`
  but operates on the normalized event envelope (type, ref, connectionId) and typed
  payload fields (via JSON Pointer into the serialized payload).
- Returns `FilterResult` (allowed/blocked + matched rule description).
- Unit tests covering event type patterns, payload field matching, connection scoping,
  and include/exclude precedence.

### Task 20: Event stream pipeline orchestrator

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/EventStreamOrchestrator.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/EventStreamOrchestratorTest.java`

**Produces:**
- `@Scheduled` poller that processes new `StreamEventEntity` rows (uses a cursor/marker
  to track the last processed event, similar to the current `EventQueueEntity` pattern).
- For each new event, evaluates all enabled subscriptions via `SubscriptionFilterEvaluator`.
- For each matching subscription, routes the event based on subscription labels/config:
  - **Manager triage:** Calls `ManagerService.evaluate()` with the event data.
  - **Workflow dispatch:** Calls `WorkflowEventDispatcher.dispatchEvent()` with the
    normalized event mapped to the format the flow engine expects.
- Logs activity, fires SSE events, records traces.
- Integration test verifying event → subscription match → routing.

### Task 21: WorkflowEventDispatcher adaptation

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowEventDispatcher.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowEventMapper.java`
- Modify: `app/src/test/java/io/apitomy/axiom/app/WorkflowEventDispatcherTest.java`

**Produces:**
- Updated `WorkflowEventMapper.toEventMap()` to accept `NormalizedEvent` instead of
  `EventEntity`. The map structure stays compatible with the flow engine's EL evaluation
  (`event.type`, `event.payload.issue.title`, etc.) but now uses the well-defined
  typed payload fields.
- Updated `WorkflowEventDispatcher` to work with `StreamEventEntity` / `NormalizedEvent`.
- Updated tests.

---

## Phase 7: Cleanup + Migration

Remove the old event source system and update references throughout the codebase.

### Task 22: Remove old poller code

**Files:**
- Delete: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubPoller.java`
- Delete: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubEventClassifier.java`
- Delete: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubEventNormalizer.java`
- Delete: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubDryRunService.java`
- Delete: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraPoller.java`
- Delete: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraEventClassifier.java`
- Delete: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraDryRunService.java`
- Delete: `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java`
- Delete: `app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java`
- Delete: associated test files

**Produces:**
- All old polling, classification, normalization, and dry-run code removed.
- Old `EventSourcesResourceImpl` removed (replaced by `ConnectionsResourceImpl` and
  `SubscriptionsResourceImpl`).
- Old `PipelineOrchestrator` removed (replaced by `EventStreamOrchestrator`).
- Build still compiles and all tests pass.

### Task 23: Remove old event source REST endpoints from OpenAPI

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Produces:**
- Old `/event-sources` paths and schemas removed.
- Old `/events` paths updated or removed (replaced by `/stream/events` or renamed back
  to `/events` now that old ones are gone).
- Regenerate and verify build.

### Task 24: Database cleanup migration

**Files:**
- Create: `app/src/main/resources/db/migration/V63__drop_old_event_source_tables.sql`

**Produces:**
- Drops `event_source`, `event_source_label`, `event_source_log` tables.
- Drops `event_queue` table (queue pattern replaced by stream cursor).
- Optionally drops old `event` table columns that are no longer needed, or drops the
  entire table if it's fully superseded by `stream_event`.
- Does NOT drop tables that other features still reference — verify FK dependencies first.

### Task 25: Update Manager integration

**Files:**
- Modify: `manager/src/main/java/io/apitomy/axiom/manager/ManagerService.java`
- Modify: `manager/src/main/java/io/apitomy/axiom/manager/ManagerPromptBuilder.java`
- Test: existing Manager tests

**Produces:**
- `ManagerService.evaluate()` accepts `NormalizedEvent` (or `StreamEventEntity`) instead
  of `EventEntity`.
- `ManagerPromptBuilder` updated to use typed payload fields in the prompt template
  (e.g., `{{issue.title}}` instead of `{{payload}}` raw JSON dump).
- Label-based action type filtering now uses subscription labels instead of event source
  labels.
- Tests updated.

---

## Phasing Summary

| Phase | Tasks | Description | Independently testable? |
|-------|-------|-------------|------------------------|
| 1 | 1-3 | Event payload model (records, enums, serialization) | Yes — pure Java, no DB |
| 2 | 4-6 | Database schema (connections, events, subscriptions) | Yes — migrations + entity tests |
| 3 | 7-12 | OpenAPI spec + REST resources | Yes — CRUD endpoints functional |
| 4 | 13-15 | GitHub poller + normalizer | Yes — events appear in stream |
| 5 | 16-18 | Jira poller + normalizer | Yes — events appear in stream |
| 6 | 19-21 | Subscription evaluation + pipeline | Yes — events routed to Manager/workflows |
| 7 | 22-25 | Cleanup old code + migration | Yes — old system fully removed |

Each phase builds on the previous but produces independently testable, committable work.
Phases 4 and 5 (GitHub and Jira pollers) are independent of each other and could be
implemented in either order or in parallel.
