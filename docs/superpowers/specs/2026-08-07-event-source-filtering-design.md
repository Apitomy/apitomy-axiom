# Event Source Filtering Design

**GitHub Issue:** #127 — feat: user-configurable event source filtering
**Date:** 2026-08-07

## Summary

Replace the hardcoded bot and slash-command pre-filters in `PipelineOrchestrator.shouldSkipEvent()`
with a general-purpose, per-event-source filtering system. Users configure include/exclude filter
rules on each event source. Filtering happens post-ingest, pre-Manager — events are in the DB but
blocked before the AI Manager LLM call. A dry-run endpoint lets users preview filter behavior
against live events fetched from the source API.

## Filter Data Model

### Filter rule

Each filter rule has a **type**, an optional **JSON Pointer**, and a **glob pattern**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | enum: `event-type`, `payload` | yes | What the rule matches against |
| `pointer` | string (RFC 6901 JSON Pointer) | when type=`payload` | Path into the event payload |
| `pattern` | string (glob) | yes | Glob pattern with `*` and `?` wildcards |

- **`event-type` rules** match the glob against the event's `eventType` string (e.g. `issue-*`,
  `pr-created`).
- **`payload` rules** resolve the JSON Pointer against the event payload using
  `JsonNode.at(pointer)`, then match the glob against the resulting text value.

### Filter object

```json
{
  "include": [<EventSourceFilterRule>, ...],
  "exclude": [<EventSourceFilterRule>, ...]
}
```

### Evaluation semantics

1. **Include phase:** If `include` is empty, the event passes. If non-empty, the event must match
   at least one include rule to pass.
2. **Exclude phase:** If the event matches any exclude rule, it is blocked.
3. **Missing pointer:** If a JSON Pointer resolves to a missing or null node, the rule does not
   match (fails open).

### Storage

A new top-level `filters` field on `EventSource` and `NewEventSource`, stored as a `TEXT` column
containing JSON on the `event_source` table. Separate from the `configuration` blob.

### Default filters

All event sources (new and existing via migration) get these defaults:

```json
{
  "include": [],
  "exclude": [
    {"type": "payload", "pointer": "/user/login", "pattern": "*[bot]"},
    {"type": "payload", "pointer": "/comment/user/login", "pattern": "*[bot]"},
    {"type": "payload", "pointer": "/comment/body", "pattern": "/*"}
  ]
}
```

These replace the hardcoded `shouldSkipEvent()` logic:
- Bot filter (`*[bot]` login suffix) covered by the first two rules (top-level user and comment
  user).
- Slash-command filter covered by the third rule (comment body starting with `/`).

## OpenAPI Schema Changes

### New schema definitions

```json
"EventSourceFilterRule": {
  "required": ["type", "pattern"],
  "properties": {
    "type": {
      "enum": ["event-type", "payload"],
      "type": "string"
    },
    "pointer": {
      "description": "JSON Pointer (RFC 6901) to the payload field. Required when type=payload.",
      "type": "string"
    },
    "pattern": {
      "description": "Glob pattern to match against (supports * and ? wildcards).",
      "type": "string"
    }
  }
}

"EventSourceFilters": {
  "properties": {
    "include": {
      "description": "Rules for events to include. Empty list means allow all.",
      "type": "array",
      "items": { "$ref": "#/components/schemas/EventSourceFilterRule" }
    },
    "exclude": {
      "description": "Rules for events to exclude. Applied after include rules.",
      "type": "array",
      "items": { "$ref": "#/components/schemas/EventSourceFilterRule" }
    }
  }
}
```

### Updated schemas

Both `EventSource` and `NewEventSource` gain:

```json
"filters": { "$ref": "#/components/schemas/EventSourceFilters" }
```

### New endpoint schemas

**Dry-run request:**

```json
"FilterDryRunRequest": {
  "required": ["sourceType", "configuration", "filters"],
  "properties": {
    "sourceType": { "enum": ["github", "jira"], "type": "string" },
    "configuration": { "type": "object", "additionalProperties": {} },
    "secretName": { "type": "string" },
    "filters": { "$ref": "#/components/schemas/EventSourceFilters" }
  }
}
```

**Dry-run response:**

```json
"FilterDryRunResponse": {
  "properties": {
    "results": {
      "type": "array",
      "items": { "$ref": "#/components/schemas/FilterDryRunResult" }
    },
    "totalEvaluated": { "type": "integer" },
    "totalAllowed": { "type": "integer" },
    "totalBlocked": { "type": "integer" }
  }
}

"FilterDryRunResult": {
  "properties": {
    "eventType": { "type": "string" },
    "issueRef": { "type": "string" },
    "summary": { "type": "string" },
    "allowed": { "type": "boolean" },
    "matchedRule": { "type": "string" }
  }
}
```

### New endpoint

```
POST /api/event-sources/filters/dry-run
```

Request body: `FilterDryRunRequest`
Response body: `FilterDryRunResponse`

## Backend Design

### EventFilterEvaluator

New class in `core` module:

```
io.apitomy.axiom.core.filters.EventFilterEvaluator
```

**Public API:**

```java
public FilterResult evaluate(EventSourceFilters filters, String eventType, JsonNode payload);
```

**FilterResult record:**

```java
public record FilterResult(boolean allowed, String matchedRule) {}
```

- `allowed = true`, `matchedRule = null` — event passes.
- `allowed = false`, `matchedRule = "exclude payload /user/login matched *[bot]"` — event blocked
  with human-readable description of the matched rule.

**Glob matching** uses a custom wildcard matcher that converts `*` and `?` to regex equivalents
while treating all other characters as literal. This avoids `PathMatcher`'s interpretation of
`[...]` as character classes — users write `*[bot]` to mean the literal string `[bot]`, not
"one of b, o, t". No external library needed.

### PipelineOrchestrator changes

The `shouldSkipEvent()` method and `SLASH_COMMANDS` constant are deleted. The pre-filter check
(lines 174-183) is replaced with:

1. Load `EventSourceEntity` for `event.eventSourceId`.
2. Deserialize `filters` JSON into `EventSourceFilters`.
3. Parse `event.payload` into `JsonNode`.
4. Call `EventFilterEvaluator.evaluate(filters, event.eventType, payload)`.
5. If `!result.allowed`: log activity as `"event-pre-filtered"` with `result.matchedRule()` in
   the summary, mark queue entry `"completed"`, return.
6. If `allowed`: continue to Manager evaluation.

If `filters` is null or empty, all events pass (backward compatible). If `event.eventSourceId`
is null (no associated event source), filtering is skipped and the event passes through.

### Dry-run endpoint implementation

The dry-run endpoint needs to reuse poller fetch and event-type-determination logic without
triggering ingestion. This requires extracting the fetch/classify logic from `GitHubPoller` and
`JiraPoller` into reusable components that the dry-run resource can call.

**Flow:**

1. Resolve auth credentials from `secretName`.
2. Fetch recent events from the source API using a recent time window (e.g. last 7 days).
3. Classify each event using the same event-type determination logic the pollers use.
4. Run `EventFilterEvaluator.evaluate()` on each event.
5. Return results with summary counts. No DB writes.

### Activity log enrichment

The `"event-pre-filtered"` activity log entry summary is updated to include the matched rule
detail:

```
Event pre-filtered: comment-added — exclude payload /comment/user/login matched *[bot]
```

This replaces the current format (`Event pre-filtered: comment-added — bot activity from
dependabot[bot]`).

## Database Migration (V34)

```sql
-- Add filters column to event_source
ALTER TABLE event_source ADD COLUMN filters TEXT;

-- Populate existing event sources with default filters
UPDATE event_source SET filters = '{"include":[],"exclude":[
  {"type":"payload","pointer":"/user/login","pattern":"*[bot]"},
  {"type":"payload","pointer":"/comment/user/login","pattern":"*[bot]"},
  {"type":"payload","pointer":"/comment/body","pattern":"/*"}
]}';
```

## Frontend Design

### New "Filters" tab on EventSourceDetailPage

Tab order:
- Tab 0: Info (existing)
- Tab 1: Filters (new)
- Tab 2: Poll Logs (existing, shifted from index 1)

### Filter configuration section

A card with two sub-sections — "Include Rules" and "Exclude Rules" — each containing a table:

| Type | Pointer | Pattern | Actions |
|------|---------|---------|---------|
| `event-type` | — | `issue-*` | Edit / Delete |
| `payload` | `/user/login` | `*[bot]` | Edit / Delete |

- Pointer column is blank/disabled for `event-type` rules.
- "Add rule" button opens an inline form: type dropdown (`event-type` or `payload`), pointer text
  field (shown only for `payload` type), pattern text field.
- "Save" button persists via `PUT /api/event-sources/{id}`.

### Dry-run preview section

Below the filter config, a "Test Filters" button:

1. Calls `POST /api/event-sources/filters/dry-run` with the event source's configuration, auth,
   and the currently edited (unsaved) filters.
2. Displays results in a table:

| Result | Event Type | Reference | Summary | Matched Rule |
|--------|-----------|-----------|---------|-------------|
| Allowed | `issue-created` | `#42` | Fix login bug | — |
| Blocked | `comment-added` | `#99` | Comment by dependabot[bot] | `exclude /comment/user/login: *[bot]` |

- Allowed rows: green check icon. Blocked rows: red X icon.
- Summary counts above the table: "38 allowed, 12 blocked out of 50 events".
- Preview uses the in-memory (unsaved) filter state so users can tweak and re-test before saving.

## Observability

- Each filtered event produces an `"event-pre-filtered"` activity log entry with the matched rule
  in the summary. No changes to poll log schema.
- Users can see filtered events in the activity log, filterable by entry type.

## Scope Exclusions

The following are explicitly deferred to future work:

- Branch pattern filters (e.g. only PRs targeting `main`)
- Label pattern filters
- Filter Log tab (dedicated UI for filter history)
- Array wildcard support in JSON Pointers
- Regex value matching (glob only for now)
