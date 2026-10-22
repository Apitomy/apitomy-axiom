# Event Schemas and Jira Ingestion Fidelity — Design

**Date:** 2026-10-21
**Status:** Approved design, pending implementation plans

## Background

A live spike against the Red Hat Jira projects IPT and APICURIO (30-day backfill, 275 issues, 357 events,
plus scripted activity on test issue APICURIO-137) compared raw Jira data with what Axiom ingested. For the
five changelog fields currently handled, new issues, and new comments, nothing was missed. Event *content*,
however, had these defects:

1. `payload.issue` is the issue's state at poll time, not at event time (`issue.closed` with `state: open`;
   `comment.created` showing text from a later edit).
2. Repeated comment edits share one `sourceEventId`, so only the first edit is recorded.
3. `stream_event.source_event_id` is globally unique, so two sources watching the same project silently drop
   each other's events.
4. `sourceData.project` is never populated (`project` is not requested from Jira).
5. `closedAt` is always null (`resolutiondate` is not requested).
6. Assigned/unassigned events have no `login` for the assignee.
7. Issue/comment timestamps use Jira's raw format (`…+0000`) rather than ISO-8601 `…Z`.
8. Comment deletions (recorded in the changelog as field `Comment`) produce no event.
9. Priority, resolution, due date, fix version, component, and custom-field changes are ignored.

Latent issues that were not hit in this data: closed/reopened is decided by status *name* rather than
category; comments come only from the embedded (possibly incomplete) list; project keys are unquoted in JQL;
`baseUrl` is not validated; the event-source 400 response has an empty body.

There is also no machine-readable contract for the event format. It exists only as prose in
`docs/developer-guide/event-sourcing-design.md`.

## Goals

- Every Axiom event type has a JSON Schema. Schemas are the runtime contract and the reference for writing
  EL expressions, and lay the groundwork for future user-defined events.
- Every ingested event (GitHub and Jira) is validated at runtime. Invalid events are saved and flagged.
- Jira events accurately describe what happened, as of when it happened.
- The UI's EL reference is driven by the schemas.

## Non-goals

- Validating `sourceData` (it is free-form and source-specific).
- User-defined event types or schema editing.
- Autocomplete inside the EL editor.
- Re-validating or rewriting events stored before the upgrade.
- Jira links, attachments, or work-item links as event types.
- Jira Data Center / PAT auth, webhooks, historical backfill.

## Delivery

One spec, three implementation plans, each shipped as its own PR, in order:

1. **Plan 1:** Event schema registry, runtime validation, schema API, per-source unique IDs.
2. **Plan 2:** Jira ingestion fixes.
3. **Plan 3:** UI EL reference driven by schemas, and validation warnings on Event Detail.

Each plan must build and pass tests on its own.

## Plan 1: Event Schema Registry

### Schema files

Location: `core/src/main/resources/schemas/events/`. JSON Schema draft 2020-12. Every property has a
`description`, and properties have `examples` where useful (the UI displays both).

- `envelope.json` covers the `NormalizedEvent` envelope: `id`, `sourceEventId`, `source`, `eventSourceId`,
  `type`, `ref`, `timestamp`, `actor`, `payload`, `sourceData`.
  - `timestamp` is required and must be ISO-8601 UTC ending in `Z`.
  - `sourceData` is unconstrained (any value, or absent).
- `defs.json` holds the shared definitions: `Actor`, `NormalizedIssue`, `NormalizedComment`,
  `NormalizedPullRequest`, `Change`, `NormalizedLabel`, and any other shared payload records. Every
  timestamp property in these definitions uses the `…Z` format.
- `payloads/<event-type>.json` exists for every `EventType` value (26 today), e.g. `issue.closed.json`.
  It describes `payload` for that type and references `defs.json`.

The schema depends only on the event type, not the source. Source-specific value differences (e.g. Jira
`issue.number` is `"PROJ-1"`, GitHub's is `"123"`) are expressed only as permissive types (`string`) and
documented in descriptions.

### `EventSchemaRegistry`

A CDI `@ApplicationScoped` bean in `core`.

- At startup, loads `envelope.json`, `defs.json`, and all `payloads/*.json`, and builds one assembled schema
  per event type: the envelope with `payload` replaced by a reference to that type's payload schema.
- `List<EventTypeSchemaSummary> eventTypes()` returns the type and description for each event type.
- `JsonNode schemaFor(String type)` returns the assembled schema, or empty for an unknown type.
- `List<String> validate(NormalizedEvent event)` serializes the event with the application `ObjectMapper`
  and validates it. It returns human-readable errors (`$.payload.issue.state: must be one of [open, closed]`).
  An unknown event type is itself an error.
- New dependency: `com.networknt:json-schema-validator` (Jackson-based).
- If loading the schemas fails, startup fails: a broken schema is a bug, not a runtime condition.

### Runtime validation

In `EventStreamService.persistEvent`, for every source:

1. Run `registry.validate(event)`.
2. If there are errors, save the event anyway, with the errors stored in `stream_event.validation_errors`
   (JSON array of strings), and log a warning with the `sourceEventId` and the errors.
3. If the validator throws, save the event with a single error describing the exception. Ingestion never
   stops because of validation.
4. The result returned to the caller tells it whether the event was valid, so pollers can count invalid
   events.

GitHub and Jira pollers include the invalid count in the poll-log message, for example
`245 events ingested (2 failed schema validation)`. The checkpoint advances normally.

### Per-source unique IDs

A Flyway migration (next `V` number):

- Adds a nullable column `stream_event.validation_errors` (CLOB/JSON text).
- Drops the global unique index on `stream_event(source_event_id)`.
- Creates a unique index on `stream_event(event_source_id, source_event_id)`.

`EventStreamService` duplicate checks (`persistEvent` and `existingSourceEventIds`) match on
(`eventSourceId`, `sourceEventId`). The `sourceEventId` format is unchanged, so existing rows remain valid
duplicate keys after the upgrade.

### API (contract-first)

In `common/api/src/main/resources/openapi.json`:

- `GET /schemas/events` returns `EventSchemaSummary[]` (`type`, `description`).
- `GET /schemas/events/{type}` returns the assembled JSON Schema (untyped object). It returns 404 for an
  unknown type.
- `StreamEvent` gains an optional `validationErrors: string[]`.

The paths live under `/schemas/events` rather than `/event-schemas` because apitomy-codegen groups paths
into resource interfaces by the first word before `-`, so `/event-schemas` would collide with
`/event-sources`. The endpoints are implemented by `EventSchemasResourceImpl`, which implements the
generated interface `SchemasResource`.

### Drift guards (tests)

- Every `EventType` value has a `payloads/<type>.json` file, and there are no extra files.
- Every assembled schema is itself a valid draft 2020-12 schema.
- Every existing GitHub and Jira normalizer test output validates with no errors.
- A reflection-based test lists record components of the payload/defs records that have no corresponding
  schema property, and fails if there are any.
- `EventStreamService` tests cover: an invalid event is saved with errors; a validator exception is saved with
  an error; the same `sourceEventId` under two event sources is saved twice; the same `sourceEventId` under
  one source is a duplicate.

## Plan 2: Jira Ingestion Fixes

### Issue state as of each event

The normalizer rebuilds the issue state as of each event by walking the changelog backwards from the
current issue.

- **Input:** the current issue from search results, plus the full changelog (fetched via the existing
  truncated-changelog path when needed), plus all comments.
- **Tracked state:** status (name, ID, category, so `state` and `stateDetail`), summary (`title`),
  description (`body`), assignee (`assignees`), labels, resolution and resolution date (`closedAt`), priority,
  due date, fix versions, components, and every `extraFields` value.
- **Algorithm:**
  1. Sort histories newest first.
  2. Start the working copy from the current issue.
  3. For each history entry, newest first, snapshot the working copy as the state after that entry, then
     undo that entry's items (`to` → `from`) on the copy.
  4. After the loop, the copy is the state at creation, used for `issue.created`.
  5. Comment events get the snapshot in effect at the comment's timestamp.
- **Rules:**
  - A rebuilt `body` uses the changelog's plain/wiki text. The current description is converted from Atlassian
    Document Format (Jira's rich-text JSON) as today.
  - Fields with no changelog history keep their current values. This limitation is documented.
  - Comment bodies are always the current text (Jira keeps no comment history). The edit-specific
    `sourceEventId` identifies the edit.
  - `updatedAt` on a snapshot is the timestamp of the entry that produced it (or `created` for the creation
    snapshot).
- **Resolution date:** `closedAt` is the history timestamp of the most recent resolution change to a non-null
  value at or before the snapshot. If the resolution was cleared after that, `closedAt` is null.

### Open/closed by status category

- `JiraEventsApiClient.fetchStatuses()` calls `GET /rest/api/3/status`. The poller builds a status ID →
  category key map once per source per poll.
- `state` is `closed` when the category key is `done`.
- `issue.closed` and `issue.reopened` events are emitted on transitions into or out of the `done`
  category.
- If the lookup fails, the poller logs a warning and falls back to the current name match
  (done/closed/resolved).

### Requested fields and formatting

- Add `project`, `resolutiondate`, `status`, `duedate`, and the configured `extraFields` to the search
  request's `fields`. Field names are resolved to field IDs through `GET /rest/api/3/field`, cached per
  poll. Unknown names are ignored.
- All timestamps written to the normalized payload (`createdAt`, `updatedAt`, `closedAt`, comment timestamps)
  are formatted as ISO-8601 UTC `…Z`.
- Assignee `Actor` on `issue.assigned` and `issue.unassigned` events gets `login` from the changelog item's
  `to`/`from` account ID, and `displayName` from `toString`/`fromString`.

### Event set and IDs

| Trigger | Event | `sourceEventId` |
|---|---|---|
| Issue created after cutoff | `issue.created` | `KEY-created` (unchanged) |
| Changelog entry | as today, by field | `KEY-{historyId}` or `KEY-{historyId}-{i}` (unchanged) |
| Comment created | `issue.comment.created` | `KEY-comment-{id}` (unchanged) |
| Comment edited | `issue.comment.updated` | `KEY-comment-{id}-updated-{updatedEpochMillis}` (new) |
| Changelog field `Comment` with a `fromString` and no `toString` | `issue.comment.deleted` | `KEY-{historyId}` |
| `priority`, `resolution`, `duedate`, `Fix Version`, `Component` | `issue.updated` with `change` | `KEY-{historyId}[-{i}]` |
| A field named in `configuration.extraFields` | `issue.updated` with `change` | `KEY-{historyId}[-{i}]` |

- `issue.comment.deleted` carries a `NormalizedComment` with `id` null, `body` set to the deleted text, and
  `author` null. The actor is the history author.
- `extraFields` names are matched against the changelog item's `field` value (the name Jira records in the
  history), case-insensitively.
- If an issue's embedded comment list is incomplete (`total` greater than the number returned), comments are
  read with `fetchIssueComments`, paging through all of them.

### Jira `sourceData`

A selected set of fields, not validated:

- `jiraId`, `project{key,name}`, `issueType{name}`, `status{name,categoryKey}`, `priority{name}`,
  `resolution{name}`, `dueDate`, `components[{name}]`, `fixVersions[{name}]`.
- `extraFields{<name>: <value as of the snapshot>}`, present only when configured.

`sourceData` values follow the same rebuilt-as-of-the-event rule as the payload where history exists.

### Configuration hardening

- `configuration.projects` entries must match `^[A-Z][A-Z0-9_]+$`. JQL quotes each key.
- `configuration.extraFields` is optional; when present it must be a list of non-blank strings.
- `baseUrl` (Jira and GitHub) must match `^https?://`, and a trailing `/` is stripped when saved.
- Event-source validation failures return 400 with an error body following the existing `Error` schema.
- Fix the stale `resolveCredentials` Javadoc.

### Tests

- Cleaned-up real fixtures in `events/jira/src/test/resources/fixtures/`: APICURIO-137 (search result, full
  changelog, comments, statuses) and two or three IPT issues. Account IDs, names, avatars, and free text are
  replaced with placeholder values.
- Every normalizer output in tests is checked against `EventSchemaRegistry`.
- Regression tests, one per spike defect: snapshot correctness for every event of APICURIO-137, repeated
  comment edits, comment deletion, `closedAt`, assignee `login`, timestamp format, `sourceData.project`,
  category-based closed with a custom done status name, `extraFields` emission and `sourceData` values,
  incomplete comment list, project-key validation and JQL quoting, `baseUrl` normalization.

### Manual verification

- `tools/jira-groundtruth.py` is a cleaned-up version of the spike script.
  - Credentials come from `JIRA_EMAIL` and `JIRA_API_TOKEN`; the Axiom URL is an argument.
  - It compares raw Jira activity with ingested events, and also reports schema validation results from
    the API.
- Before Plan 2 is complete, run it against IPT, APICURIO, and APICURIO-137 on an isolated `dev.sh` instance
  (port offset, temporary `AXIOM_HOME` and `--dataDir`).

## Plan 3: UI

### Data

- A `useEventSchemas` hook uses the generated API client for `GET /schemas/events` and
  `GET /schemas/events/{type}`, and caches results for the session.
- A `schemaToPaths(schema)` utility walks the assembled schema and returns rows of
  `{path, type, description, examples}`.
  - It follows `$ref`s.
  - Arrays are shown as `[0]`.
  - `sourceData` is a single row of type "any (source-specific)".
  - Recursion is bounded.
  - It is unit-tested with Vitest.

### Subscription Detail page

- Replace the hard-coded field tables in `ui/src/pages/SubscriptionDetailPage.tsx` (around lines 338 and 763)
  with an **Event fields** panel.
- The panel shows the event types in the subscription's type filter. If there are none, a type picker
  appears with `issue.opened` selected.
- For the selected type, the panel lists paths with type, description, and example. Clicking a path copies it
  into the EL expression.
- The existing "common expressions" examples stay.

### Event Detail page

When `validationErrors` is non-empty, show a warning alert listing the errors.

## Error Handling Summary

| Situation | Behaviour |
|---|---|
| Schema files fail to load or assemble | Startup fails |
| Event fails validation | Saved with `validation_errors`; warning logged; counted in poll log |
| Validator throws | Saved with one error describing the exception |
| Status-category lookup fails | Warning; fall back to name matching |
| Unknown `extraFields` name | Ignored (documented) |
| Comment fetch fails | Same handling as a changelog fetch failure: checkpoint not advanced on 5xx/429/network |

## Upgrade Notes

- Existing events and IDs are kept. `validation_errors` is null for events stored before the upgrade.
- Comment edits that already have an old-format `-updated` event will produce one more event under the new ID
  format after the upgrade. This is an accepted one-time effect.
- Two sources watching the same project each receive their own events from the upgrade onward.

## Documentation

- `docs/developer-guide/event-sourcing-design.md`: schema registry and file layout, runtime validation, the
  issue-state rebuild and its limits, `sourceEventId` formats, `extraFields`, status-category rules, the
  Jira `sourceData` shape.
- User guide: the Event fields panel on the Subscription Detail page, and validation warnings on Event Detail.
