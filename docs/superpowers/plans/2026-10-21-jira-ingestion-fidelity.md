# Jira Ingestion Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make every Jira event carry the issue state as it was at the time of the event, emit the missing
event types (comment deletion, priority/resolution/due date/fix version/component/extra-field updates),
decide open/closed by status category, and harden Jira and GitHub event-source configuration.

**Architecture:** A new `JiraIssueHistory` rebuilds a `JiraIssueState` snapshot for every changelog entry by
walking the changelog backwards from the current issue; `JiraEventNormalizerV2` only classifies changelog
items and maps snapshots to `NormalizedIssue`/`sourceData`. Per-poll lookups (status categories from
`GET /rest/api/3/status`, field IDs from `GET /rest/api/3/field`) are bundled into a
`JiraNormalizationContext` built by `JiraEventSourcePoller`. Configuration rules live in
`ConfigItemValidationService`; `EventSourcesResourceImpl` returns 400 with the OpenAPI `Error` body.

**Tech Stack:** Java 25, Quarkus, Jackson, JUnit 5, `com.sun.net.httpserver` (client tests), RestAssured
(`@QuarkusTest`), Python 3 stdlib (ground-truth tool).

**Spec:** `docs/superpowers/specs/2026-10-21-event-schemas-and-jira-ingestion-design.md`, section
"Plan 2: Jira Ingestion Fixes" plus the Jira rows of "Error Handling Summary", "Upgrade Notes" and
"Documentation".

**Depends on:** Plan 1 (event schema registry) is merged. This plan relies on its fixed contract
(`/tmp/axiom-test/plan1-interfaces.md`):

- `io.apitomy.axiom.core.events.schema.EventSchemaRegistry` with `public EventSchemaRegistry(ObjectMapper)`
  (usable with `new` in unit tests) and `public List<String> validate(NormalizedEvent event)` (empty when
  valid).
- `EventStreamService.persistEvent(NormalizedEvent)` returns `io.apitomy.axiom.events.core.PersistOutcome`
  (`PERSISTED`, `PERSISTED_INVALID`, `DUPLICATE`; `boolean persisted()`), and the Jira poller already counts
  invalid events for its poll-log message.
- `JiraEventNormalizerV2` already has `static String isoTimestamp(String jiraTimestamp)` and already formats
  `NormalizedIssue.createdAt/updatedAt` and `NormalizedComment.createdAt/updatedAt` as ISO-8601 `…Z`.
- `StreamEvent` (REST bean) has optional `validationErrors: string[]`.
- Uniqueness of stored events is `(event_source_id, source_event_id)`.

## Global Constraints

- Contract-first REST: no new endpoints in this plan; the existing `Error` schema
  (`{message: string, errorCode: integer}`) is the 400 body. Never add `@Path` to impl classes.
- Java style: 4-space indentation, Javadoc on every public method and public type, explicit types (no `var`),
  JUnit 5, camelCase variables, PascalCase classes.
- All timestamps written to normalized payloads (`createdAt`, `updatedAt`, `closedAt`, comment timestamps,
  event `timestamp`) are ISO-8601 UTC ending in `Z` (pattern `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$`).
- `state` is `closed` exactly when the status category key is `done`.
- Status-category lookup failure: log a warning and fall back to name matching (`done`, `closed`, `resolved`,
  case-insensitive).
- Unknown `extraFields` names (not returned by `GET /rest/api/3/field`) are ignored.
- Comment fetch failure: same handling as a changelog fetch failure — 404/403 fall back to the embedded list
  with a warning; 5xx/429/network abort the window without advancing the checkpoint.
- `sourceEventId` formats: `KEY-created`, `KEY-{historyId}` or `KEY-{historyId}-{i}`, `KEY-comment-{id}`,
  `KEY-comment-{id}-updated-{updatedEpochMillis}`.
- `configuration.projects` entries must match `^[A-Z][A-Z0-9_]+$`; JQL quotes each key.
- `configuration.extraFields` is optional; when present it is a list of non-blank strings.
- `baseUrl` (Jira and GitHub) must match `^https?://`; a trailing `/` is stripped when saved.
- Every normalizer output produced in tests is checked with
  `new EventSchemaRegistry(new ObjectMapper()).validate(event)` and must be empty.
- Test fixtures contain no real account IDs, names, emails, avatar URLs or free text.
- Live testing only on an isolated instance:
  `AXIOM_HOME=/tmp/axiom-plan2/home ./dev.sh --skip-ui --portOffset=500 --dataDir=/tmp/axiom-plan2/data`
  (backend on `9590`). Never use ports `9191` or `9090` or `~/.axiom`.
- Commit messages: conventional style used in this repo (`feat(jira): …`, `fix(jira): …`, `test(jira): …`,
  `docs: …`); no AI attribution.

## Test Commands

Module tests run through the reactor so upstream modules (core, events/core) are built:

- Jira module, one class: `mvn -q -pl events/jira -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`
- Jira module, all: `mvn -q -pl events/jira -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jira*'`
- App, one class: `mvn -q -pl app -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`
- Full build: `./build.sh` (runs `mvn clean package`, all tests).

## File Structure

New (production, `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/`):

| File | Responsibility |
|---|---|
| `JiraStatusCategories.java` | Status ID/name → category key map from `GET /rest/api/3/status`, with name fallback. |
| `JiraFieldCatalog.java` | Field name → field ID lookup from `GET /rest/api/3/field` (case-insensitive). |
| `JiraNormalizationContext.java` | Per-poll inputs to the normalizer: status categories and configured extra fields (name → ID). |
| `JiraIssueState.java` | Mutable working copy / immutable-by-convention snapshot of the tracked issue fields. |
| `JiraIssueHistory.java` | Rebuilds snapshots (creation, after each history entry, as-of an instant) by walking the changelog backwards; computes `closedAt`. |

Modified (production):

| File | Change |
|---|---|
| `events/jira/.../v2/JiraEventNormalizerV2.java` | Uses snapshots, category-based closed/reopened, assignee `login`, new event types, new comment-edit ID, new `sourceData`. ADF and label helpers become `static`. |
| `events/jira/.../v2/JiraEventsApiClient.java` | `fetchStatuses`, `fetchFields`, paging `fetchIssueComments`, quoted JQL, extra requested fields. |
| `events/jira/.../v2/JiraEventSourcePoller.java` | Builds the context per poll, reads `extraFields`, completes comment lists, strips trailing `/` from `baseUrl`, fixes `resolveCredentials` Javadoc. |
| `app/.../validation/ConfigItemValidationService.java` | Project-key regex, `extraFields`, `baseUrl` scheme rule. |
| `app/.../validation/BaseUrls.java` (new) | `normalize(String)` — trims and strips trailing `/`. |
| `app/.../rest/EventSourcesResourceImpl.java` | 400 with `Error` body; stores normalized `baseUrl`. |
| `app/.../ImportExportService.java` | Stores normalized `baseUrl` on import (two places). |
| `tools/jira-groundtruth.py` (new) | Compares raw Jira activity with ingested events; reports schema validation errors. |
| `docs/developer-guide/event-sourcing-design.md` | Jira section rewrite. |

Tests:

| File | Purpose |
|---|---|
| `events/jira/src/test/resources/fixtures/*.json` (new) | Sanitized real APICURIO-137 data, statuses, fields, IPT-modelled issue and comments. |
| `events/jira/src/test/java/.../v2/JiraFixtures.java` (new) | Fixture loader and `assertSchemaValid`. |
| `events/jira/src/test/java/.../v2/JiraFixturesTest.java` (new) | Sanitization guard. |
| `events/jira/src/test/java/.../v2/JiraStatusCategoriesTest.java` (new) | Category lookup and fallback. |
| `events/jira/src/test/java/.../v2/JiraFieldCatalogTest.java` (new) | Field ID lookup. |
| `events/jira/src/test/java/.../v2/JiraIssueHistoryTest.java` (new) | Snapshot correctness. |
| `events/jira/src/test/java/.../v2/JiraEventNormalizerV2FixtureTest.java` (new) | Regression tests on real fixtures. |
| Existing `JiraEventNormalizerV2Test`, `JiraEventsApiClientTest`, `JiraEventSourcePollerTest`, `ConfigItemValidationServiceTest`, `EventSourcesResourceTest` | Extended. |

---

### Task 1: Sanitized Jira fixtures and test helpers

Fixtures are derived from the spike evidence (`/tmp/axiom-test/raw-137.json`, the real changelog of
APICURIO-137). History IDs, timestamps, status IDs, field names and the order of items are kept exactly;
account IDs, names, avatars, emails, the base URL and free text are replaced with placeholders. The raw spike
file only contains `status`, `resolution` and `resolutiondate` in `fields`, so the remaining current fields
are the latest `toString` values from the changelog. No raw IPT data was captured during the spike, so the IPT
fixture is modelled on IPT's shape (custom done status, fix versions, components, due date, a numeric custom
field, a truncated comment list) with synthetic values.

**Files:**
- Create: `events/jira/src/test/resources/fixtures/apicurio-137-issue.json`
- Create: `events/jira/src/test/resources/fixtures/jira-statuses.json`
- Create: `events/jira/src/test/resources/fixtures/jira-fields.json`
- Create: `events/jira/src/test/resources/fixtures/ipt-1001-issue.json`
- Create: `events/jira/src/test/resources/fixtures/ipt-1001-comments.json`
- Create: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixtures.java`
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixturesTest.java`

**Interfaces:**
- Consumes: `EventSchemaRegistry(ObjectMapper)`, `EventSchemaRegistry.validate(NormalizedEvent)` (Plan 1).
- Produces (test scope): `JiraFixtures.load(String name): JsonNode` (fresh mutable copy each call),
  `JiraFixtures.assertSchemaValid(List<NormalizedEvent> events): void`, `JiraFixtures.MAPPER: ObjectMapper`.

- [ ] **Step 1: Write the failing sanitization test**

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixturesTest.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the Jira test fixtures: they must load and must not contain real personal or tenant data.
 */
class JiraFixturesTest {

    private static final List<String> FIXTURES = List.of("apicurio-137-issue.json", "jira-statuses.json",
            "jira-fields.json", "ipt-1001-issue.json", "ipt-1001-comments.json");

    /** Fragments of real data seen in the spike evidence that must never appear in fixtures. */
    private static final List<String> FORBIDDEN = List.of("70121:", "redhat", "atl-paas", "@",
            "Wittmann", "rfv1:");

    @Test
    void fixturesLoadAndAreSanitized() {
        for (String name : FIXTURES) {
            JsonNode fixture = JiraFixtures.load(name);
            String text = fixture.toString();
            for (String forbidden : FORBIDDEN) {
                assertFalse(text.contains(forbidden), name + " contains '" + forbidden + "'");
            }
        }
    }

    @Test
    void apicurio137FixtureKeepsTheRealChangelogShape() {
        JsonNode histories = JiraFixtures.load("apicurio-137-issue.json").path("changelog").path("histories");
        assertEquals(12, histories.size());
        assertEquals("93654267", histories.get(0).path("id").asText());
        assertEquals("93654105", histories.get(11).path("id").asText());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraFixturesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol: variable JiraFixtures`.

- [ ] **Step 3: Create the helper**

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixtures.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads sanitized Jira fixtures from {@code src/test/resources/fixtures} and validates normalizer output
 * against the event schemas.
 */
final class JiraFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final EventSchemaRegistry SCHEMAS = new EventSchemaRegistry(new ObjectMapper());

    private JiraFixtures() {
    }

    /**
     * Loads a fixture as a fresh, mutable JSON tree.
     *
     * @param name file name under {@code /fixtures/}
     * @return the parsed fixture
     */
    static JsonNode load(String name) {
        try (InputStream in = JiraFixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Asserts that every event validates against its event-type schema.
     *
     * @param events the normalizer output
     */
    static void assertSchemaValid(List<NormalizedEvent> events) {
        for (NormalizedEvent event : events) {
            List<String> errors = SCHEMAS.validate(event);
            assertTrue(errors.isEmpty(), event.type().value() + " " + event.sourceEventId() + ": " + errors);
        }
    }
}
```

- [ ] **Step 4: Create the fixtures**

`events/jira/src/test/resources/fixtures/apicurio-137-issue.json` (search-result shape, histories newest first
as Jira embeds them):

```json
{
  "id": "7358053",
  "key": "APICURIO-137",
  "fields": {
    "summary": "Spike issue title (renamed)",
    "description": {
      "type": "doc",
      "version": 1,
      "content": [
        {"type": "paragraph", "content": [{"type": "text", "text": "Updated description."}]}
      ]
    },
    "status": {
      "id": "10142",
      "name": "New",
      "statusCategory": {"id": 2, "key": "new", "name": "To Do"}
    },
    "resolution": null,
    "resolutiondate": null,
    "assignee": null,
    "reporter": {
      "accountId": "acct-reporter-1",
      "displayName": "Reporter One",
      "avatarUrls": {"48x48": "https://avatars.example.com/acct-reporter-1/48"}
    },
    "labels": ["axiom-spike", "spike-b"],
    "priority": {"id": "10002", "name": "Major"},
    "created": "2026-10-21T14:53:07.835+0000",
    "updated": "2026-10-21T14:54:17.424+0000",
    "project": {"id": "10001", "key": "APICURIO", "name": "Apicurio Project"},
    "issuetype": {"id": "3", "name": "Task"},
    "components": [],
    "fixVersions": [],
    "duedate": null,
    "comment": {
      "startAt": 0,
      "maxResults": 1,
      "total": 1,
      "comments": [
        {
          "id": "19040900",
          "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
          "body": {
            "type": "doc",
            "version": 1,
            "content": [
              {"type": "paragraph", "content": [{"type": "text", "text": "First comment (edit 2)"}]}
            ]
          },
          "created": "2026-10-21T14:53:45.185+0000",
          "updated": "2026-10-21T14:53:56.781+0000"
        }
      ]
    }
  },
  "changelog": {
    "startAt": 0,
    "maxResults": 12,
    "total": 12,
    "histories": [
      {"id": "93654267", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:54:17.424+0000", "items": [
        {"field": "resolution", "fieldtype": "jira", "fieldId": "resolution", "from": "10000", "fromString": "Done", "to": null, "toString": null},
        {"field": "status", "fieldtype": "jira", "fieldId": "status", "from": "6", "fromString": "Closed", "to": "10142", "toString": "New"}]},
      {"id": "93654254", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:54:10.349+0000", "items": [
        {"field": "assignee", "fieldtype": "jira", "fieldId": "assignee", "from": "acct-reporter-1", "fromString": "Reporter One", "to": null, "toString": null}]},
      {"id": "93654249", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:54:06.982+0000", "items": [
        {"field": "resolution", "fieldtype": "jira", "fieldId": "resolution", "from": null, "fromString": null, "to": "10000", "toString": "Done"},
        {"field": "status", "fieldtype": "jira", "fieldId": "status", "from": "3", "fromString": "In Progress", "to": "6", "toString": "Closed"}]},
      {"id": "93654235", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:54:03.287+0000", "items": [
        {"field": "Comment", "fieldtype": "jira", "fieldId": "comment", "from": null, "fromString": "Deleted comment text", "to": null, "toString": null}]},
      {"id": "93654174", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:41.795+0000", "items": [
        {"field": "status", "fieldtype": "jira", "fieldId": "status", "from": "10143", "fromString": "Refinement", "to": "3", "toString": "In Progress"}]},
      {"id": "93654169", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:38.380+0000", "items": [
        {"field": "status", "fieldtype": "jira", "fieldId": "status", "from": "10142", "fromString": "New", "to": "10143", "toString": "Refinement"}]},
      {"id": "93654157", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:34.988+0000", "items": [
        {"field": "priority", "fieldtype": "jira", "fieldId": "priority", "from": "10005", "fromString": "Undefined", "to": "10002", "toString": "Major"}]},
      {"id": "93654152", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:28.375+0000", "items": [
        {"field": "labels", "fieldtype": "jira", "fieldId": "labels", "from": null, "fromString": "axiom-spike spike-a spike-b", "to": null, "toString": "axiom-spike spike-b"}]},
      {"id": "93654147", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:25.001+0000", "items": [
        {"field": "labels", "fieldtype": "jira", "fieldId": "labels", "from": null, "fromString": "axiom-spike", "to": null, "toString": "axiom-spike spike-a spike-b"}]},
      {"id": "93654138", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:21.586+0000", "items": [
        {"field": "description", "fieldtype": "jira", "fieldId": "description", "from": null, "fromString": "Initial description.", "to": null, "toString": "Updated description."}]},
      {"id": "93654123", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:18.191+0000", "items": [
        {"field": "summary", "fieldtype": "jira", "fieldId": "summary", "from": null, "fromString": "Spike issue title", "to": null, "toString": "Spike issue title (renamed)"}]},
      {"id": "93654105", "author": {"accountId": "acct-reporter-1", "displayName": "Reporter One"},
       "created": "2026-10-21T14:53:09.898+0000", "items": [
        {"field": "Intelligence Requested", "fieldtype": "custom", "fieldId": "customfield_10986", "from": null, "fromString": null, "to": "opaque-value", "toString": "opaque-value"}]}
    ]
  }
}
```

`events/jira/src/test/resources/fixtures/jira-statuses.json` (the four real APICURIO statuses plus a custom
done status used by the IPT-modelled fixture):

```json
[
  {"id": "10142", "name": "New", "statusCategory": {"id": 2, "key": "new", "name": "To Do"}},
  {"id": "10143", "name": "Refinement", "statusCategory": {"id": 2, "key": "new", "name": "To Do"}},
  {"id": "3", "name": "In Progress", "statusCategory": {"id": 4, "key": "indeterminate", "name": "In Progress"}},
  {"id": "6", "name": "Closed", "statusCategory": {"id": 3, "key": "done", "name": "Done"}},
  {"id": "10500", "name": "Shipped", "statusCategory": {"id": 3, "key": "done", "name": "Done"}}
]
```

`events/jira/src/test/resources/fixtures/jira-fields.json`:

```json
[
  {"id": "summary", "name": "Summary", "custom": false},
  {"id": "customfield_10028", "name": "Story Points", "custom": true},
  {"id": "customfield_10986", "name": "Intelligence Requested", "custom": true}
]
```

`events/jira/src/test/resources/fixtures/ipt-1001-issue.json` (comment list incomplete: `total` 3, one
embedded):

```json
{
  "id": "8800001",
  "key": "IPT-1001",
  "fields": {
    "summary": "Modelled IPT issue",
    "description": "Plain description.",
    "status": {
      "id": "10500",
      "name": "Shipped",
      "statusCategory": {"id": 3, "key": "done", "name": "Done"}
    },
    "resolution": {"id": "10000", "name": "Done"},
    "resolutiondate": "2026-10-20T09:20:00.000+0000",
    "assignee": {"accountId": "acct-dev-1", "displayName": "Developer One"},
    "reporter": {"accountId": "acct-reporter-2", "displayName": "Reporter Two"},
    "labels": [],
    "priority": {"id": "10002", "name": "Major"},
    "created": "2026-10-20T08:00:00.000+0000",
    "updated": "2026-10-20T09:40:00.000+0000",
    "project": {"id": "20001", "key": "IPT", "name": "IPT Project"},
    "issuetype": {"id": "1", "name": "Story"},
    "components": [{"id": "30001", "name": "core"}],
    "fixVersions": [{"id": "40001", "name": "1.2.0"}],
    "duedate": "2026-10-30",
    "customfield_10028": 5.0,
    "comment": {
      "startAt": 0,
      "maxResults": 1,
      "total": 3,
      "comments": [
        {"id": "3001", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
         "body": "Comment one.", "created": "2026-10-20T09:30:00.000+0000",
         "updated": "2026-10-20T09:30:00.000+0000"}
      ]
    }
  },
  "changelog": {
    "startAt": 0,
    "maxResults": 4,
    "total": 4,
    "histories": [
      {"id": "2004", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
       "created": "2026-10-20T09:20:00.000+0000", "items": [
        {"field": "resolution", "fieldtype": "jira", "fieldId": "resolution", "from": null, "fromString": null, "to": "10000", "toString": "Done"},
        {"field": "status", "fieldtype": "jira", "fieldId": "status", "from": "3", "fromString": "In Progress", "to": "10500", "toString": "Shipped"}]},
      {"id": "2003", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
       "created": "2026-10-20T09:10:00.000+0000", "items": [
        {"field": "Story Points", "fieldtype": "custom", "fieldId": "customfield_10028", "from": null, "fromString": null, "to": null, "toString": "5"}]},
      {"id": "2002", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
       "created": "2026-10-20T09:05:00.000+0000", "items": [
        {"field": "duedate", "fieldtype": "jira", "fieldId": "duedate", "from": null, "fromString": null, "to": "2026-10-30", "toString": "2026-10-30 00:00:00.0"}]},
      {"id": "2001", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
       "created": "2026-10-20T09:00:00.000+0000", "items": [
        {"field": "Fix Version", "fieldtype": "jira", "fieldId": "fixVersions", "from": null, "fromString": null, "to": "40001", "toString": "1.2.0"},
        {"field": "Component", "fieldtype": "jira", "fieldId": "components", "from": null, "fromString": null, "to": "30001", "toString": "core"}]}
    ]
  }
}
```

`events/jira/src/test/resources/fixtures/ipt-1001-comments.json` (the `GET /issue/{key}/comment` page shape;
comment 3002 was edited):

```json
{
  "startAt": 0,
  "maxResults": 100,
  "total": 3,
  "comments": [
    {"id": "3001", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
     "body": "Comment one.", "created": "2026-10-20T09:30:00.000+0000",
     "updated": "2026-10-20T09:30:00.000+0000"},
    {"id": "3002", "author": {"accountId": "acct-reporter-2", "displayName": "Reporter Two"},
     "body": "Comment two (edited).", "created": "2026-10-20T09:31:00.000+0000",
     "updated": "2026-10-20T09:35:00.000+0000"},
    {"id": "3003", "author": {"accountId": "acct-dev-1", "displayName": "Developer One"},
     "body": "Comment three.", "created": "2026-10-20T09:40:00.000+0000",
     "updated": "2026-10-20T09:40:00.000+0000"}
  ]
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraFixturesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add events/jira/src/test/resources/fixtures events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixtures.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFixturesTest.java
git commit -m "test(jira): add sanitized Jira fixtures from the ingestion spike"
```

---

### Task 2: Status categories, field catalog and normalization context

**Files:**
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategories.java`
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalog.java`
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraNormalizationContext.java`
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategoriesTest.java`
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalogTest.java`

**Interfaces:**
- Consumes: `JiraFixtures.load` (Task 1), `JiraEventNormalizerV2.textOrNull(JsonNode, String)` (existing,
  static, package-private).
- Produces:
  - `public final class JiraStatusCategories` — `public static final String DONE = "done"`;
    `public static JiraStatusCategories empty()`; `public static JiraStatusCategories fromStatuses(JsonNode)`;
    `public String categoryKey(String statusId, String statusName)` (nullable);
    `public boolean isDone(String statusId, String statusName)`; `public boolean isEmpty()`.
  - `public final class JiraFieldCatalog` — `public static JiraFieldCatalog fromFields(JsonNode)`;
    `public String idFor(String name)` (case-insensitive; null when unknown).
  - `public record JiraNormalizationContext(JiraStatusCategories statusCategories, Map<String, String>
    extraFields)` — `extraFields` maps configured name → field ID (ID may be null);
    `public static JiraNormalizationContext defaults()`; `public boolean isExtraField(String changelogField)`;
    `public String extraFieldName(String changelogField)` (configured spelling or null);
    `public List<String> extraFieldIds()` (non-null IDs, in configured order).

- [ ] **Step 1: Write the failing tests**

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategoriesTest.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JiraStatusCategories}.
 */
class JiraStatusCategoriesTest {

    private final JiraStatusCategories categories =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    @Test
    void categoryIsResolvedById() {
        assertEquals("new", categories.categoryKey("10143", "Refinement"));
        assertEquals("indeterminate", categories.categoryKey("3", "In Progress"));
        assertEquals("done", categories.categoryKey("6", "Closed"));
    }

    @Test
    void customDoneStatusNameIsDoneByCategory() {
        assertTrue(categories.isDone("10500", "Shipped"));
    }

    @Test
    void unknownIdFallsBackToKnownName() {
        assertEquals("done", categories.categoryKey("99999", "shipped"));
    }

    @Test
    void emptyLookupFallsBackToDoneNames() {
        JiraStatusCategories empty = JiraStatusCategories.empty();
        assertTrue(empty.isEmpty());
        assertTrue(empty.isDone(null, "Resolved"));
        assertTrue(empty.isDone("6", "CLOSED"));
        assertTrue(empty.isDone(null, "Done"));
        assertFalse(empty.isDone("10500", "Shipped"));
        assertNull(empty.categoryKey(null, "In Progress"));
        assertNull(empty.categoryKey(null, null));
    }

    @Test
    void contextExposesExtraFieldsCaseInsensitively() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        extra.put("Team", null);
        JiraNormalizationContext context = new JiraNormalizationContext(categories, extra);
        assertTrue(context.isExtraField("story points"));
        assertEquals("Story Points", context.extraFieldName("STORY POINTS"));
        assertFalse(context.isExtraField("priority"));
        assertEquals(List.of("customfield_10028"), context.extraFieldIds());
        assertTrue(JiraNormalizationContext.defaults().extraFields().isEmpty());
    }
}
```

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalogTest.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link JiraFieldCatalog}.
 */
class JiraFieldCatalogTest {

    private final JiraFieldCatalog catalog = JiraFieldCatalog.fromFields(JiraFixtures.load("jira-fields.json"));

    @Test
    void namesResolveCaseInsensitively() {
        assertEquals("customfield_10028", catalog.idFor("Story Points"));
        assertEquals("customfield_10028", catalog.idFor("story points"));
    }

    @Test
    void unknownNamesResolveToNull() {
        assertNull(catalog.idFor("No Such Field"));
        assertNull(catalog.idFor(null));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest='JiraStatusCategoriesTest,JiraFieldCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol: class JiraStatusCategories`.

- [ ] **Step 3: Implement the three types**

`events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategories.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Maps Jira statuses to their status category key ({@code new}, {@code indeterminate}, {@code done}),
 * built from {@code GET /rest/api/3/status}. Lookups go by status ID first, then by status name; when
 * neither is known (for example because the lookup failed) the well-known done names
 * {@code done}, {@code closed} and {@code resolved} are treated as {@code done}.
 */
public final class JiraStatusCategories {

    /** The category key Jira uses for finished work. */
    public static final String DONE = "done";

    private static final Set<String> DONE_NAMES = Set.of("done", "closed", "resolved");

    private final Map<String, String> byId;
    private final Map<String, String> byName;

    private JiraStatusCategories(Map<String, String> byId, Map<String, String> byName) {
        this.byId = byId;
        this.byName = byName;
    }

    /**
     * Returns a lookup with no statuses, which only applies the done-name fallback.
     *
     * @return an empty lookup
     */
    public static JiraStatusCategories empty() {
        return new JiraStatusCategories(Map.of(), Map.of());
    }

    /**
     * Builds the lookup from the response of {@code GET /rest/api/3/status}.
     *
     * @param statuses the JSON array of statuses
     * @return the lookup (empty when the input is not an array)
     */
    public static JiraStatusCategories fromStatuses(JsonNode statuses) {
        Map<String, String> byId = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        if (statuses != null && statuses.isArray()) {
            for (JsonNode status : statuses) {
                String key = textOrNull(status.path("statusCategory"), "key");
                if (key == null) {
                    continue;
                }
                String id = textOrNull(status, "id");
                String name = textOrNull(status, "name");
                if (id != null) {
                    byId.put(id, key);
                }
                if (name != null) {
                    byName.putIfAbsent(name.toLowerCase(Locale.ROOT), key);
                }
            }
        }
        return new JiraStatusCategories(Map.copyOf(byId), Map.copyOf(byName));
    }

    /**
     * Returns the category key of a status.
     *
     * @param statusId   the status ID (changelog {@code from}/{@code to}), may be null
     * @param statusName the status name (changelog {@code fromString}/{@code toString}), may be null
     * @return the category key, or null when unknown and not a well-known done name
     */
    public String categoryKey(String statusId, String statusName) {
        if (statusId != null && byId.containsKey(statusId)) {
            return byId.get(statusId);
        }
        if (statusName == null) {
            return null;
        }
        String lower = statusName.toLowerCase(Locale.ROOT);
        if (byName.containsKey(lower)) {
            return byName.get(lower);
        }
        return DONE_NAMES.contains(lower) ? DONE : null;
    }

    /**
     * Returns whether a status belongs to the {@code done} category.
     *
     * @param statusId   the status ID, may be null
     * @param statusName the status name, may be null
     * @return true when the category is {@code done}
     */
    public boolean isDone(String statusId, String statusName) {
        return DONE.equals(categoryKey(statusId, statusName));
    }

    /**
     * Returns whether no statuses were loaded.
     *
     * @return true when only the name fallback applies
     */
    public boolean isEmpty() {
        return byId.isEmpty() && byName.isEmpty();
    }
}
```

`events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalog.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Resolves Jira field names (as users configure them and as the changelog records them) to field IDs,
 * built from {@code GET /rest/api/3/field}.
 */
public final class JiraFieldCatalog {

    private final Map<String, String> idsByLowerName;

    private JiraFieldCatalog(Map<String, String> idsByLowerName) {
        this.idsByLowerName = idsByLowerName;
    }

    /**
     * Builds the catalog from the response of {@code GET /rest/api/3/field}.
     *
     * @param fields the JSON array of fields
     * @return the catalog (empty when the input is not an array)
     */
    public static JiraFieldCatalog fromFields(JsonNode fields) {
        Map<String, String> ids = new HashMap<>();
        if (fields != null && fields.isArray()) {
            for (JsonNode field : fields) {
                String id = textOrNull(field, "id");
                String name = textOrNull(field, "name");
                if (id != null && name != null) {
                    ids.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
                }
            }
        }
        return new JiraFieldCatalog(Map.copyOf(ids));
    }

    /**
     * Returns the ID of the field with the given name, ignoring case.
     *
     * @param name the field name
     * @return the field ID, or null when unknown
     */
    public String idFor(String name) {
        return name == null ? null : idsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }
}
```

`events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraNormalizationContext.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-poll inputs to {@link JiraEventNormalizerV2}: the status-category lookup and the configured
 * {@code extraFields}.
 *
 * @param statusCategories status category lookup (never null)
 * @param extraFields      configured extra field name to Jira field ID, in configured order; the ID is null
 *                         when it could not be looked up (changelog-based events still work, current
 *                         values are unknown)
 */
public record JiraNormalizationContext(JiraStatusCategories statusCategories, Map<String, String> extraFields) {

    /**
     * Normalizes null arguments and makes the map unmodifiable.
     *
     * @param statusCategories status category lookup, null for empty
     * @param extraFields      extra fields, null for none
     */
    public JiraNormalizationContext {
        statusCategories = statusCategories != null ? statusCategories : JiraStatusCategories.empty();
        extraFields = extraFields != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(extraFields)) : Map.of();
    }

    /**
     * Returns a context with no status lookup and no extra fields.
     *
     * @return the default context
     */
    public static JiraNormalizationContext defaults() {
        return new JiraNormalizationContext(JiraStatusCategories.empty(), Map.of());
    }

    /**
     * Returns whether a changelog {@code field} value names a configured extra field (ignoring case).
     *
     * @param changelogField the changelog item's {@code field}
     * @return true when configured
     */
    public boolean isExtraField(String changelogField) {
        return extraFieldName(changelogField) != null;
    }

    /**
     * Returns the configured spelling of an extra field that matches a changelog {@code field} value.
     *
     * @param changelogField the changelog item's {@code field}
     * @return the configured name, or null when not configured
     */
    public String extraFieldName(String changelogField) {
        if (changelogField == null) {
            return null;
        }
        return extraFields.keySet().stream()
                .filter(name -> name.equalsIgnoreCase(changelogField))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the resolved field IDs of the configured extra fields, for the search request.
     *
     * @return the non-null field IDs in configured order
     */
    public List<String> extraFieldIds() {
        return extraFields.values().stream().filter(Objects::nonNull).toList();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dtest='JiraStatusCategoriesTest,JiraFieldCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategories.java events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalog.java events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraNormalizationContext.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraStatusCategoriesTest.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraFieldCatalogTest.java
git commit -m "feat(jira): add status category, field catalog and normalization context lookups"
```

---

### Task 3: Issue state rebuild (`JiraIssueState`, `JiraIssueHistory`)

Algorithm (from the spec): sort histories newest first, start the working copy from the current issue, and
for each entry snapshot the copy as "state after this entry" and then undo the entry's items (`to` → `from`).
What remains is the creation state. `closedAt` is computed afterwards in a forward pass: it is the timestamp
of the most recent resolution change to a non-null value at or before the snapshot, and null while the
resolution is empty. If the changelog has no resolution items at all, the current `resolutiondate` is kept
(fields with no history keep their current values).

**Files:**
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueState.java`
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistory.java`
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java`
  (`extractDescription`, `extractAdfText`, `parseSpaceSeparatedLabels` become `static`)
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistoryTest.java`

**Interfaces:**
- Consumes: `JiraNormalizationContext`, `JiraStatusCategories` (Task 2); `JiraFixtures` (Task 1);
  `JiraEventNormalizerV2.textOrNull`, `parseJiraTimestamp`, `isoTimestamp` (existing / Plan 1, static).
- Produces:
  - `final class JiraIssueState` (package-private) with package-private mutable fields `statusId`,
    `statusName`, `statusCategoryKey`, `summary`, `description`, `assigneeAccountId`, `assigneeDisplayName`,
    `assigneeAvatarUrl`, `labels: List<String>`, `resolution`, `closedAt`, `priority`, `dueDate`,
    `fixVersions: List<String>`, `components: List<String>`, `extraFields: Map<String, String>`, `updatedAt`;
    `JiraIssueState copy()`. Snapshots returned by `JiraIssueHistory` must be treated as read-only.
  - `final class JiraIssueHistory` (package-private):
    `static JiraIssueHistory rebuild(JsonNode issue, JiraNormalizationContext context)`,
    `JiraIssueState created()`, `JiraIssueState after(String historyId)` (current state when unknown),
    `JiraIssueState asOf(Instant instant)`, `JiraIssueState current()`,
    `static String displayValue(JsonNode value)`.
  - `static String JiraEventNormalizerV2.extractDescription(JsonNode)`,
    `static Set<String> JiraEventNormalizerV2.parseSpaceSeparatedLabels(String)`.

- [ ] **Step 1: Write the failing tests**

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistoryTest.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Snapshot-correctness tests for {@link JiraIssueHistory}, using the sanitized APICURIO-137 changelog and the
 * IPT-modelled fixture.
 */
class JiraIssueHistoryTest {

    private static final JiraStatusCategories CATEGORIES =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    private static JiraIssueHistory apicurio137() {
        return JiraIssueHistory.rebuild(JiraFixtures.load("apicurio-137-issue.json"),
                new JiraNormalizationContext(CATEGORIES, Map.of()));
    }

    private static JiraNormalizationContext iptContext() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        return new JiraNormalizationContext(CATEGORIES, extra);
    }

    private static void assertState(JiraIssueState s, String statusName, String category, String summary,
                                     String description, List<String> labels, String priority,
                                     String resolution, String closedAt, String assigneeId) {
        assertEquals(statusName, s.statusName, "statusName");
        assertEquals(category, s.statusCategoryKey, "statusCategoryKey");
        assertEquals(summary, s.summary, "summary");
        assertEquals(description, s.description, "description");
        assertEquals(labels, s.labels, "labels");
        assertEquals(priority, s.priority, "priority");
        assertEquals(resolution, s.resolution, "resolution");
        assertEquals(closedAt, s.closedAt, "closedAt");
        assertEquals(assigneeId, s.assigneeAccountId, "assignee");
    }

    @Test
    void creationSnapshot_isTheStateBeforeEveryChange() {
        JiraIssueState s = apicurio137().created();
        assertState(s, "New", "new", "Spike issue title", "Initial description.", List.of("axiom-spike"),
                "Undefined", null, null, "acct-reporter-1");
        assertEquals("10142", s.statusId);
        assertEquals("Reporter One", s.assigneeDisplayName);
        assertEquals("2026-10-21T14:53:07.835Z", s.updatedAt);
    }

    @Test
    void snapshotAfterEachHistory_matchesApicurio137GroundTruth() {
        JiraIssueHistory h = apicurio137();
        String t = "Spike issue title";
        String r = "Spike issue title (renamed)";
        String d0 = "Initial description.";
        String d1 = "Updated description.";
        List<String> l0 = List.of("axiom-spike");
        List<String> l1 = List.of("axiom-spike", "spike-a", "spike-b");
        List<String> l2 = List.of("axiom-spike", "spike-b");
        String a = "acct-reporter-1";
        String closed = "2026-10-21T14:54:06.982Z";

        assertState(h.after("93654105"), "New", "new", t, d0, l0, "Undefined", null, null, a);
        assertState(h.after("93654123"), "New", "new", r, d0, l0, "Undefined", null, null, a);
        assertState(h.after("93654138"), "New", "new", r, d1, l0, "Undefined", null, null, a);
        assertState(h.after("93654147"), "New", "new", r, d1, l1, "Undefined", null, null, a);
        assertState(h.after("93654152"), "New", "new", r, d1, l2, "Undefined", null, null, a);
        assertState(h.after("93654157"), "New", "new", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654169"), "Refinement", "new", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654174"), "In Progress", "indeterminate", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654235"), "In Progress", "indeterminate", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654249"), "Closed", "done", r, d1, l2, "Major", "Done", closed, a);
        assertState(h.after("93654254"), "Closed", "done", r, d1, l2, "Major", "Done", closed, null);
        assertState(h.after("93654267"), "New", "new", r, d1, l2, "Major", null, null, null);

        assertEquals("2026-10-21T14:54:06.982Z", h.after("93654249").updatedAt);
        assertEquals("2026-10-21T14:54:17.424Z", h.after("93654267").updatedAt);
        assertEquals("6", h.after("93654249").statusId);
    }

    @Test
    void unknownHistoryId_returnsCurrentState() {
        JiraIssueHistory h = apicurio137();
        assertSame(h.current(), h.after("does-not-exist"));
        assertSame(h.after("93654267"), h.current());
    }

    @Test
    void asOf_returnsSnapshotInEffectAtTheInstant() {
        JiraIssueHistory h = apicurio137();
        assertEquals("In Progress", h.asOf(Instant.parse("2026-10-21T14:53:45.185Z")).statusName);
        assertSame(h.created(), h.asOf(Instant.parse("2026-10-21T14:53:08Z")));
        assertSame(h.after("93654267"), h.asOf(Instant.parse("2030-01-01T00:00:00Z")));
        assertSame(h.after("93654169"), h.asOf(Instant.parse("2026-10-21T14:53:38.380Z")));
    }

    @Test
    void fixVersionsComponentsDueDateAndExtraFields_areRebuilt() {
        JiraIssueHistory h = JiraIssueHistory.rebuild(JiraFixtures.load("ipt-1001-issue.json"), iptContext());

        JiraIssueState created = h.created();
        assertEquals("In Progress", created.statusName);
        assertEquals("indeterminate", created.statusCategoryKey);
        assertEquals(List.of(), created.fixVersions);
        assertEquals(List.of(), created.components);
        assertNull(created.dueDate);
        assertNull(created.extraFields.get("Story Points"));
        assertNull(created.resolution);
        assertNull(created.closedAt);

        assertEquals(List.of("1.2.0"), h.after("2001").fixVersions);
        assertEquals(List.of("core"), h.after("2001").components);
        assertNull(h.after("2001").dueDate);
        assertEquals("2026-10-30", h.after("2002").dueDate);
        assertNull(h.after("2002").extraFields.get("Story Points"));
        assertEquals("5", h.after("2003").extraFields.get("Story Points"));
        assertEquals("Shipped", h.after("2004").statusName);
        assertEquals("done", h.after("2004").statusCategoryKey);
        assertEquals("2026-10-20T09:20:00Z", h.after("2004").closedAt);
    }

    @Test
    void fieldsWithoutHistory_keepCurrentValues() {
        ObjectNode issue = (ObjectNode) JiraFixtures.load("ipt-1001-issue.json");
        issue.remove("changelog");

        JiraIssueState created = JiraIssueHistory.rebuild(issue, iptContext()).created();

        assertEquals(List.of("core"), created.components);
        assertEquals("Done", created.resolution);
        assertEquals("2026-10-20T09:20:00Z", created.closedAt);
        assertEquals("5", created.extraFields.get("Story Points"));
        assertEquals("2026-10-20T08:00:00Z", created.updatedAt);
    }

    @Test
    void displayValue_rendersCommonFieldShapes() {
        ObjectNode values = JiraFixtures.MAPPER.createObjectNode();
        values.put("text", "abc");
        values.put("integral", 5.0);
        values.put("decimal", 2.5);
        values.putNull("none");
        values.putObject("option").put("value", "Blue");
        ArrayNode users = values.putArray("users");
        users.addObject().put("displayName", "Dev");
        users.addObject().put("displayName", "Ops");

        assertEquals("abc", JiraIssueHistory.displayValue(values.path("text")));
        assertEquals("5", JiraIssueHistory.displayValue(values.path("integral")));
        assertEquals("2.5", JiraIssueHistory.displayValue(values.path("decimal")));
        assertNull(JiraIssueHistory.displayValue(values.path("none")));
        assertNull(JiraIssueHistory.displayValue(values.path("missing")));
        assertEquals("Blue", JiraIssueHistory.displayValue(values.path("option")));
        assertEquals("Dev, Ops", JiraIssueHistory.displayValue(values.path("users")));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraIssueHistoryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `cannot find symbol: class JiraIssueHistory`.

- [ ] **Step 3: Make the normalizer helpers static**

In `JiraEventNormalizerV2.java` change the three signatures (bodies unchanged):

```java
    static String extractDescription(JsonNode descriptionNode) {
```

```java
    private static void extractAdfText(JsonNode node, StringBuilder sb) {
```

```java
    static Set<String> parseSpaceSeparatedLabels(String labelString) {
```

- [ ] **Step 4: Implement `JiraIssueState`**

`events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueState.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tracked fields of a Jira issue at one point in time. Used as the mutable working copy while
 * {@link JiraIssueHistory} walks the changelog backwards, and as read-only snapshots afterwards.
 * Timestamps ({@code updatedAt}, {@code closedAt}) are ISO-8601 UTC strings.
 */
final class JiraIssueState {

    String statusId;
    String statusName;
    String statusCategoryKey;
    String summary;
    String description;
    String assigneeAccountId;
    String assigneeDisplayName;
    String assigneeAvatarUrl;
    List<String> labels = new ArrayList<>();
    String resolution;
    String closedAt;
    String priority;
    String dueDate;
    List<String> fixVersions = new ArrayList<>();
    List<String> components = new ArrayList<>();
    Map<String, String> extraFields = new LinkedHashMap<>();
    String updatedAt;

    /**
     * Returns a deep copy (lists and maps are copied).
     *
     * @return the copy
     */
    JiraIssueState copy() {
        JiraIssueState copy = new JiraIssueState();
        copy.statusId = statusId;
        copy.statusName = statusName;
        copy.statusCategoryKey = statusCategoryKey;
        copy.summary = summary;
        copy.description = description;
        copy.assigneeAccountId = assigneeAccountId;
        copy.assigneeDisplayName = assigneeDisplayName;
        copy.assigneeAvatarUrl = assigneeAvatarUrl;
        copy.labels = new ArrayList<>(labels);
        copy.resolution = resolution;
        copy.closedAt = closedAt;
        copy.priority = priority;
        copy.dueDate = dueDate;
        copy.fixVersions = new ArrayList<>(fixVersions);
        copy.components = new ArrayList<>(components);
        copy.extraFields = new LinkedHashMap<>(extraFields);
        copy.updatedAt = updatedAt;
        return copy;
    }
}
```

- [ ] **Step 5: Implement `JiraIssueHistory`**

`events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistory.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.isoTimestamp;
import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.parseJiraTimestamp;
import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Rebuilds the state of a Jira issue as of each changelog entry by walking the changelog backwards from the
 * current issue. Limits: fields without changelog history keep their current values; a rebuilt description
 * is the changelog's plain/wiki text while the current one is converted from Atlassian Document Format.
 */
final class JiraIssueHistory {

    /** One changelog entry and the state right after it. */
    private record Entry(Instant at, JsonNode history, JiraIssueState state) {
    }

    private final JiraIssueState created;
    private final List<Entry> timeline;
    private final Map<String, JiraIssueState> byHistoryId;

    private JiraIssueHistory(JiraIssueState created, List<Entry> timeline,
                             Map<String, JiraIssueState> byHistoryId) {
        this.created = created;
        this.timeline = timeline;
        this.byHistoryId = byHistoryId;
    }

    /**
     * Rebuilds all snapshots of an issue.
     *
     * @param issue   the issue JSON (search result shape, with the complete changelog)
     * @param context status categories and configured extra fields
     * @return the rebuilt history
     */
    static JiraIssueHistory rebuild(JsonNode issue, JiraNormalizationContext context) {
        JsonNode fields = issue.path("fields");
        JiraIssueState working = currentState(fields, context);
        List<Entry> timeline = new ArrayList<>();
        for (JsonNode history : newestFirst(issue.path("changelog").path("histories"))) {
            Instant at = parseJiraTimestamp(textOrNull(history, "created"));
            JiraIssueState snapshot = working.copy();
            if (at != null) {
                snapshot.updatedAt = at.toString();
            }
            timeline.add(new Entry(at, history, snapshot));
            undo(history, working, context);
        }
        working.updatedAt = isoTimestamp(textOrNull(fields, "created"));
        Collections.reverse(timeline);
        assignClosedAt(working, timeline, fields);

        Map<String, JiraIssueState> byHistoryId = new HashMap<>();
        for (Entry entry : timeline) {
            String id = textOrNull(entry.history(), "id");
            if (id != null) {
                byHistoryId.put(id, entry.state());
            }
        }
        return new JiraIssueHistory(working, List.copyOf(timeline), byHistoryId);
    }

    /**
     * Returns the state at creation (all changelog entries undone).
     *
     * @return the creation snapshot
     */
    JiraIssueState created() {
        return created;
    }

    /**
     * Returns the current state (after the newest changelog entry).
     *
     * @return the current snapshot
     */
    JiraIssueState current() {
        return timeline.isEmpty() ? created : timeline.get(timeline.size() - 1).state();
    }

    /**
     * Returns the state right after a changelog entry.
     *
     * @param historyId the changelog history ID
     * @return the snapshot, or the current state when the ID is unknown
     */
    JiraIssueState after(String historyId) {
        JiraIssueState state = historyId != null ? byHistoryId.get(historyId) : null;
        return state != null ? state : current();
    }

    /**
     * Returns the snapshot in effect at an instant: the state after the newest entry at or before it, or the
     * creation state when there is none.
     *
     * @param instant the instant
     * @return the snapshot
     */
    JiraIssueState asOf(Instant instant) {
        JiraIssueState result = created;
        for (Entry entry : timeline) {
            if (entry.at() == null) {
                continue;
            }
            if (entry.at().isAfter(instant)) {
                break;
            }
            result = entry.state();
        }
        return result;
    }

    /**
     * Renders a Jira field value as display text: scalars as text (integral numbers without a fraction),
     * objects by {@code value}, {@code name}, {@code displayName} or {@code key}, arrays joined with
     * {@code ", "}.
     *
     * @param value the field value
     * @return the text, or null for missing/null/empty values
     */
    static String displayValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            return number == Math.rint(number) && !Double.isInfinite(number)
                    ? String.valueOf(value.asLong()) : value.asText();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : value) {
                String part = displayValue(element);
                if (part != null) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        for (String key : List.of("value", "name", "displayName", "key")) {
            if (value.hasNonNull(key)) {
                return value.get(key).asText();
            }
        }
        return value.toString();
    }

    private static JiraIssueState currentState(JsonNode fields, JiraNormalizationContext context) {
        JiraIssueState state = new JiraIssueState();
        JsonNode status = fields.path("status");
        state.statusId = textOrNull(status, "id");
        state.statusName = textOrNull(status, "name");
        String categoryKey = textOrNull(status.path("statusCategory"), "key");
        state.statusCategoryKey = categoryKey != null
                ? categoryKey : context.statusCategories().categoryKey(state.statusId, state.statusName);
        state.summary = textOrNull(fields, "summary");
        state.description = JiraEventNormalizerV2.extractDescription(fields.path("description"));
        JsonNode assignee = fields.path("assignee");
        if (assignee.isObject()) {
            state.assigneeAccountId = textOrNull(assignee, "accountId");
            state.assigneeDisplayName = textOrNull(assignee, "displayName");
            state.assigneeAvatarUrl = textOrNull(assignee.path("avatarUrls"), "48x48");
        }
        JsonNode labels = fields.path("labels");
        if (labels.isArray()) {
            labels.forEach(label -> state.labels.add(label.asText()));
        }
        state.resolution = textOrNull(fields.path("resolution"), "name");
        state.priority = textOrNull(fields.path("priority"), "name");
        state.dueDate = textOrNull(fields, "duedate");
        state.fixVersions = names(fields.path("fixVersions"));
        state.components = names(fields.path("components"));
        for (Map.Entry<String, String> extra : context.extraFields().entrySet()) {
            String fieldId = extra.getValue();
            state.extraFields.put(extra.getKey(), fieldId != null ? displayValue(fields.path(fieldId)) : null);
        }
        state.updatedAt = isoTimestamp(textOrNull(fields, "updated"));
        return state;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode element : array) {
                String name = textOrNull(element, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<JsonNode> newestFirst(JsonNode histories) {
        List<JsonNode> list = new ArrayList<>();
        if (histories.isArray()) {
            histories.forEach(list::add);
        }
        Comparator<JsonNode> oldestFirst = Comparator
                .comparing((JsonNode history) -> {
                    Instant at = parseJiraTimestamp(textOrNull(history, "created"));
                    return at != null ? at : Instant.EPOCH;
                })
                .thenComparingLong(JiraIssueHistory::numericId);
        list.sort(oldestFirst.reversed());
        return list;
    }

    private static long numericId(JsonNode history) {
        try {
            return Long.parseLong(textOrNull(history, "id"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Undoes one changelog entry on the working copy, last item first. */
    private static void undo(JsonNode history, JiraIssueState state, JiraNormalizationContext context) {
        JsonNode items = history.path("items");
        if (!items.isArray()) {
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonNode item = items.get(i);
            String field = textOrNull(item, "field");
            if (field == null) {
                continue;
            }
            String from = textOrNull(item, "from");
            String fromString = textOrNull(item, "fromString");
            String toString = textOrNull(item, "toString");
            switch (field.toLowerCase(Locale.ROOT)) {
                case "status" -> {
                    state.statusId = from;
                    state.statusName = fromString;
                    state.statusCategoryKey = context.statusCategories().categoryKey(from, fromString);
                }
                case "summary" -> state.summary = fromString;
                case "description" -> state.description = fromString;
                case "assignee" -> {
                    state.assigneeAccountId = from;
                    state.assigneeDisplayName = fromString;
                    state.assigneeAvatarUrl = null;
                }
                case "labels" -> state.labels =
                        new ArrayList<>(JiraEventNormalizerV2.parseSpaceSeparatedLabels(fromString));
                case "resolution" -> state.resolution = fromString;
                case "priority" -> state.priority = fromString;
                case "duedate" -> state.dueDate = from != null ? from : fromString;
                case "fix version" -> undoMultiValue(state.fixVersions, fromString, toString);
                case "component" -> undoMultiValue(state.components, fromString, toString);
                default -> {
                    String extraName = context.extraFieldName(field);
                    if (extraName != null) {
                        state.extraFields.put(extraName, fromString);
                    }
                }
            }
        }
    }

    /** Multi-value fields record one item per added ({@code to}) or removed ({@code from}) value. */
    private static void undoMultiValue(List<String> values, String fromString, String toString) {
        if (toString != null) {
            values.remove(toString);
        }
        if (fromString != null && !values.contains(fromString)) {
            values.add(fromString);
        }
    }

    /** Forward pass computing {@code closedAt} for every snapshot. */
    private static void assignClosedAt(JiraIssueState created, List<Entry> timeline, JsonNode fields) {
        boolean hasResolutionHistory = timeline.stream().anyMatch(entry -> hasResolutionItem(entry.history()));
        if (!hasResolutionHistory) {
            String current = isoTimestamp(textOrNull(fields, "resolutiondate"));
            created.closedAt = created.resolution != null ? current : null;
            for (Entry entry : timeline) {
                entry.state().closedAt = entry.state().resolution != null ? current : null;
            }
            return;
        }
        String lastSetAt = created.resolution != null ? created.updatedAt : null;
        created.closedAt = lastSetAt;
        for (Entry entry : timeline) {
            for (JsonNode item : entry.history().path("items")) {
                if ("resolution".equalsIgnoreCase(textOrNull(item, "field"))) {
                    boolean set = textOrNull(item, "to") != null || textOrNull(item, "toString") != null;
                    lastSetAt = set && entry.at() != null ? entry.at().toString() : null;
                }
            }
            entry.state().closedAt = entry.state().resolution != null ? lastSetAt : null;
        }
    }

    private static boolean hasResolutionItem(JsonNode history) {
        for (JsonNode item : history.path("items")) {
            if ("resolution".equalsIgnoreCase(textOrNull(item, "field"))) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dtest='JiraIssueHistoryTest,JiraEventNormalizerV2Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`JiraEventNormalizerV2Test` is unchanged and still green after the `static` changes).

- [ ] **Step 7: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueState.java events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistory.java events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraIssueHistoryTest.java
git commit -m "feat(jira): rebuild issue state as of each changelog entry"
```

---

### Task 4: Normalizer uses snapshots, status categories, assignee login and the new `sourceData`

This task rewires `JiraEventNormalizerV2` onto `JiraIssueHistory`: every event carries the snapshot in effect
at that event, `issue.closed`/`issue.reopened` are decided by transitions into/out of the `done` category,
assignee actors get `login` from the changelog `to`/`from`, and `sourceData` is the selected set from the
spec. New event types and the new comment-edit ID come in Task 5, so tests here look events up by type and
timestamp, not by `sourceEventId`.

**Files:**
- Modify (full rewrite shown): `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java`
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2Test.java`
- Create: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2FixtureTest.java`

**Interfaces:**
- Consumes: `JiraIssueHistory.rebuild/created/after/asOf`, `JiraIssueState` fields (Task 3);
  `JiraNormalizationContext` (Task 2); `JiraFixtures` (Task 1).
- Produces:
  - `public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since, String eventSourceId,
    String baseUrl, JiraNormalizationContext context)` (new overload, used by the poller in Task 7).
  - The existing 4-argument `normalizeIssue` delegates with `JiraNormalizationContext.defaults()`.
  - `NormalizedIssue toNormalizedIssue(String issueKey, JsonNode fields, JiraIssueState state, String
    baseUrl)` (package-private).
  - `JsonNode buildSourceData(JsonNode issue, JsonNode fields, JiraIssueState state,
    JiraNormalizationContext context)` (package-private).
  - `private List<NormalizedEvent> classifyItem(JsonNode item, ItemContext itemContext,
    JiraNormalizationContext context)` and the nested `private record ItemContext(...)` — extended in Task 5.
  - `mapIssue(...)` and `isDoneStatus(...)` are removed.

- [ ] **Step 1: Write the failing fixture tests**

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2FixtureTest.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the Jira ingestion spike defects, run against sanitized real fixtures. Every
 * normalizer output is validated against the event schemas.
 */
class JiraEventNormalizerV2FixtureTest {

    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");
    private static final String SOURCE_ID = "jira-fixtures";
    private static final String BASE_URL = "https://example.atlassian.net";
    private static final Pattern ISO_UTC =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    private static final JiraStatusCategories CATEGORIES =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    private final JiraEventNormalizerV2 normalizer = new JiraEventNormalizerV2(JiraFixtures.MAPPER);

    private List<NormalizedEvent> normalize(String fixture, JiraNormalizationContext context) {
        List<NormalizedEvent> events = normalizer.normalizeIssue(JiraFixtures.load(fixture), SINCE, SOURCE_ID,
                BASE_URL, context);
        JiraFixtures.assertSchemaValid(events);
        return events;
    }

    private List<NormalizedEvent> apicurio137() {
        return normalize("apicurio-137-issue.json", new JiraNormalizationContext(CATEGORIES, Map.of()));
    }

    private static JiraNormalizationContext iptContext(JiraStatusCategories categories) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        return new JiraNormalizationContext(categories, extra);
    }

    private static List<NormalizedEvent> ofType(List<NormalizedEvent> events, EventType type, String timestamp) {
        return events.stream().filter(e -> e.type() == type && e.timestamp().equals(timestamp)).toList();
    }

    private static NormalizedEvent single(List<NormalizedEvent> events, EventType type, String timestamp) {
        List<NormalizedEvent> matches = ofType(events, type, timestamp);
        assertEquals(1, matches.size(), type + " at " + timestamp + " in "
                + events.stream().map(e -> e.type().value() + "@" + e.timestamp()).toList());
        return matches.get(0);
    }

    private static JsonNode issue(NormalizedEvent event) {
        JsonNode payload = JiraFixtures.MAPPER.valueToTree(event.payload());
        return payload.path("issue");
    }

    private static String text(JsonNode node, String field) {
        return JiraEventNormalizerV2.textOrNull(node, field);
    }

    private static List<String> labels(JsonNode issue) {
        return JiraFixtures.MAPPER.convertValue(issue.path("labels"),
                JiraFixtures.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    @Test
    void everyEvent_carriesTheIssueStateAsOfThatEvent() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode created = issue(single(events, EventType.ISSUE_CREATED, "2026-10-21T14:53:07.835Z"));
        assertEquals("Spike issue title", text(created, "title"));
        assertEquals("Initial description.", text(created, "body"));
        assertEquals("New", text(created, "stateDetail"));
        assertEquals("open", text(created, "state"));
        assertEquals(List.of("axiom-spike"), labels(created));
        assertEquals("acct-reporter-1", text(created.path("assignees").get(0), "login"));
        assertEquals("2026-10-21T14:53:07.835Z", text(created, "updatedAt"));

        JsonNode renamed = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:18.191Z"));
        assertEquals("Spike issue title (renamed)", text(renamed, "title"));
        assertEquals("Initial description.", text(renamed, "body"));

        JsonNode described = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:21.586Z"));
        assertEquals("Updated description.", text(described, "body"));

        List<NormalizedEvent> labeled = ofType(events, EventType.ISSUE_LABELED, "2026-10-21T14:53:25.001Z");
        assertEquals(2, labeled.size());
        assertEquals(List.of("axiom-spike", "spike-a", "spike-b"), labels(issue(labeled.get(0))));

        JsonNode unlabeled = issue(single(events, EventType.ISSUE_UNLABELED, "2026-10-21T14:53:28.375Z"));
        assertEquals(List.of("axiom-spike", "spike-b"), labels(unlabeled));

        JsonNode refinement = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:38.380Z"));
        assertEquals("Refinement", text(refinement, "stateDetail"));
        assertEquals("open", text(refinement, "state"));

        JsonNode inProgress = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:41.795Z"));
        assertEquals("In Progress", text(inProgress, "stateDetail"));

        JsonNode commented = issue(single(events, EventType.ISSUE_COMMENT_CREATED, "2026-10-21T14:53:45.185Z"));
        assertEquals("In Progress", text(commented, "stateDetail"));

        JsonNode reopened = issue(single(events, EventType.ISSUE_REOPENED, "2026-10-21T14:54:17.424Z"));
        assertEquals("open", text(reopened, "state"));
        assertEquals("New", text(reopened, "stateDetail"));
        assertNull(text(reopened, "closedAt"));
        assertEquals(0, reopened.path("assignees").size());
    }

    @Test
    void closedEvent_hasClosedSnapshotWithClosedAt() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode closed = issue(single(events, EventType.ISSUE_CLOSED, "2026-10-21T14:54:06.982Z"));
        assertEquals("closed", text(closed, "state"));
        assertEquals("Closed", text(closed, "stateDetail"));
        assertEquals("2026-10-21T14:54:06.982Z", text(closed, "closedAt"));

        JsonNode unassigned = issue(single(events, EventType.ISSUE_UNASSIGNED, "2026-10-21T14:54:10.349Z"));
        assertEquals("closed", text(unassigned, "state"));
        assertEquals("2026-10-21T14:54:06.982Z", text(unassigned, "closedAt"));

        for (NormalizedEvent event : events) {
            JsonNode snapshot = issue(event);
            if ("closed".equals(text(snapshot, "state"))) {
                assertTrue(text(snapshot, "closedAt") != null, event.timestamp() + " closed without closedAt");
            }
        }
    }

    @Test
    void statusChangeBetweenOpenCategories_isAnUpdateNotAClose() {
        List<NormalizedEvent> events = apicurio137();

        NormalizedEvent refinement = single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:38.380Z");
        JsonNode change = JiraFixtures.MAPPER.valueToTree(refinement.payload()).path("change");
        assertEquals("status", text(change, "field"));
        assertEquals("New", text(change, "from"));
        assertEquals("Refinement", text(change, "to"));
    }

    @Test
    void unassignedEvent_hasPreviousAssigneeLoginAndName() {
        NormalizedEvent event = single(apicurio137(), EventType.ISSUE_UNASSIGNED, "2026-10-21T14:54:10.349Z");
        IssueUnassignedPayload payload = (IssueUnassignedPayload) event.payload();
        assertEquals("acct-reporter-1", payload.assignee().login());
        assertEquals("Reporter One", payload.assignee().displayName());
    }

    @Test
    void allTimestamps_areIsoUtc() {
        for (NormalizedEvent event : apicurio137()) {
            assertTrue(ISO_UTC.matcher(event.timestamp()).matches(), event.timestamp());
            JsonNode payload = JiraFixtures.MAPPER.valueToTree(event.payload());
            for (String field : List.of("createdAt", "updatedAt", "closedAt")) {
                String value = text(payload.path("issue"), field);
                assertTrue(value == null || ISO_UTC.matcher(value).matches(), field + "=" + value);
                String commentValue = text(payload.path("comment"), field);
                assertTrue(commentValue == null || ISO_UTC.matcher(commentValue).matches(),
                        "comment." + field + "=" + commentValue);
            }
        }
    }

    @Test
    void sourceData_hasProjectStatusAndSnapshotValues() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode created = single(events, EventType.ISSUE_CREATED, "2026-10-21T14:53:07.835Z").sourceData();
        assertEquals("7358053", text(created, "jiraId"));
        assertEquals("APICURIO", text(created.path("project"), "key"));
        assertEquals("Apicurio Project", text(created.path("project"), "name"));
        assertEquals("Task", text(created.path("issueType"), "name"));
        assertEquals("New", text(created.path("status"), "name"));
        assertEquals("new", text(created.path("status"), "categoryKey"));
        assertEquals("Undefined", text(created.path("priority"), "name"));
        assertFalse(created.has("resolution"));
        assertFalse(created.has("extraFields"));

        JsonNode closed = single(events, EventType.ISSUE_CLOSED, "2026-10-21T14:54:06.982Z").sourceData();
        assertEquals("Closed", text(closed.path("status"), "name"));
        assertEquals("done", text(closed.path("status"), "categoryKey"));
        assertEquals("Major", text(closed.path("priority"), "name"));
        assertEquals("Done", text(closed.path("resolution"), "name"));
    }

    @Test
    void customDoneStatusName_isClosedByCategory() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));

        JsonNode closed = issue(single(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z"));
        assertEquals("closed", text(closed, "state"));
        assertEquals("Shipped", text(closed, "stateDetail"));
        assertEquals("2026-10-20T09:20:00Z", text(closed, "closedAt"));
    }

    @Test
    void customDoneStatusName_withoutStatusLookup_fallsBackToNameMatching() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                iptContext(JiraStatusCategories.empty()));

        assertTrue(ofType(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z").isEmpty());
        NormalizedEvent update = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_UPDATED && e.timestamp().equals("2026-10-20T09:20:00Z"))
                .filter(e -> "status".equals(text(JiraFixtures.MAPPER.valueToTree(e.payload()).path("change"),
                        "field")))
                .findFirst()
                .orElseThrow();
        assertEquals("Shipped", text(issue(update), "stateDetail"));
    }

    @Test
    void extraFields_sourceDataHoldsValueAsOfEachEvent() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));

        JsonNode created = single(events, EventType.ISSUE_CREATED, "2026-10-20T08:00:00Z").sourceData();
        assertTrue(created.path("extraFields").has("Story Points"));
        assertTrue(created.path("extraFields").path("Story Points").isNull());
        assertFalse(created.has("dueDate"));
        assertFalse(created.has("fixVersions"));

        JsonNode closed = single(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z").sourceData();
        assertEquals("5", text(closed.path("extraFields"), "Story Points"));
        assertEquals("2026-10-30", text(closed, "dueDate"));
        assertEquals("1.2.0", text(closed.path("fixVersions").get(0), "name"));
        assertEquals("core", text(closed.path("components").get(0), "name"));
        assertEquals("IPT", text(closed.path("project"), "key"));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraEventNormalizerV2FixtureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL, `no suitable method found for normalizeIssue(...JiraNormalizationContext)`.

- [ ] **Step 3: Rewrite the normalizer**

Replace the whole of `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java`.
`isoTimestamp` is the Plan 1 helper; if Plan 1's body differs from the one below, keep Plan 1's body (the
contract is "ISO-8601 UTC `…Z`, or null when unparseable").

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueAssignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueReopenedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnlabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Normalizes raw Jira issue JSON (from JQL search results with {@code expand=changelog}, with the complete
 * changelog and comment list) into {@link NormalizedEvent} records with typed payloads. A single issue can
 * produce multiple events: one for its creation, one or more per changelog history entry, and one per
 * comment created or edited after the {@code since} cutoff. Every event carries the issue state as of that
 * event, rebuilt by {@link JiraIssueHistory}.
 */
@ApplicationScoped
public class JiraEventNormalizerV2 {

    private static final Logger LOG = Logger.getLogger(JiraEventNormalizerV2.class);
    private static final String SOURCE = "jira";

    @Inject
    ObjectMapper objectMapper;

    /** Constructor for CDI. */
    public JiraEventNormalizerV2() {
    }

    /**
     * Constructor for testing (direct injection).
     *
     * @param objectMapper the object mapper used to build {@code sourceData}
     */
    public JiraEventNormalizerV2(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalizes an issue with no status-category lookup and no extra fields.
     *
     * @param issue         a single issue JSON object from the Jira search results
     * @param since         only events after this instant are included
     * @param eventSourceId the slug of the EventSource that produced this event
     * @param baseUrl       the Jira instance base URL (e.g., {@code https://myorg.atlassian.net})
     * @return the normalized events (may be empty)
     */
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since,
                                                String eventSourceId, String baseUrl) {
        return normalizeIssue(issue, since, eventSourceId, baseUrl, JiraNormalizationContext.defaults());
    }

    /**
     * Normalizes a single Jira issue into the events that happened after {@code since}.
     *
     * @param issue         a single issue JSON object (complete changelog and comment list)
     * @param since         only events after this instant are included
     * @param eventSourceId the slug of the EventSource that produced this event
     * @param baseUrl       the Jira instance base URL, without trailing slash
     * @param context       status categories and configured extra fields for this poll
     * @return the normalized events (may be empty)
     */
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since, String eventSourceId,
                                                String baseUrl, JiraNormalizationContext context) {
        List<NormalizedEvent> events = new ArrayList<>();

        String issueKey = textOrNull(issue, "key");
        JsonNode fields = issue.path("fields");
        if (issueKey == null || fields.isMissingNode()) {
            LOG.debugf("Skipping issue with missing key or fields");
            return events;
        }

        String ref = baseUrl + "/browse/" + issueKey;
        JiraIssueHistory history = JiraIssueHistory.rebuild(issue, context);

        // 1. Creation
        Instant createdAt = parseJiraTimestamp(textOrNull(fields, "created"));
        if (createdAt != null && createdAt.isAfter(since)) {
            JiraIssueState state = history.created();
            events.add(buildEvent(issueKey + "-created", eventSourceId, EventType.ISSUE_CREATED, ref,
                    formatTimestamp(createdAt), mapActor(fields.path("reporter")),
                    new IssueCreatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl)),
                    buildSourceData(issue, fields, state, context)));
        }

        // 2. Changelog entries
        JsonNode histories = issue.path("changelog").path("histories");
        if (histories.isArray()) {
            for (JsonNode entry : histories) {
                Instant historyCreated = parseJiraTimestamp(textOrNull(entry, "created"));
                JsonNode items = entry.path("items");
                if (historyCreated == null || !historyCreated.isAfter(since) || !items.isArray()) {
                    continue;
                }
                String historyId = textOrNull(entry, "id");
                JiraIssueState state = history.after(historyId);
                ItemContext itemContext = new ItemContext(eventSourceId, ref, formatTimestamp(historyCreated),
                        mapActor(entry.path("author")), toNormalizedIssue(issueKey, fields, state, baseUrl),
                        buildSourceData(issue, fields, state, context));

                List<NormalizedEvent> historyEvents = new ArrayList<>();
                for (JsonNode item : items) {
                    historyEvents.addAll(classifyItem(item, itemContext, context));
                }
                if (historyEvents.size() == 1) {
                    events.add(withSourceEventId(historyEvents.get(0), issueKey + "-" + historyId));
                } else {
                    for (int i = 0; i < historyEvents.size(); i++) {
                        events.add(withSourceEventId(historyEvents.get(i), issueKey + "-" + historyId + "-" + i));
                    }
                }
            }
        }

        // 3. Comments (bodies are always the current text: Jira keeps no comment history)
        JsonNode comments = fields.path("comment").path("comments");
        if (comments.isArray()) {
            for (JsonNode comment : comments) {
                String commentId = textOrNull(comment, "id");
                Instant commentCreated = parseJiraTimestamp(textOrNull(comment, "created"));
                Instant commentUpdated = parseJiraTimestamp(textOrNull(comment, "updated"));
                Actor commentAuthor = mapActor(comment.path("author"));
                NormalizedComment normalizedComment = mapComment(comment, issueKey, baseUrl);

                if (commentCreated != null && commentCreated.isAfter(since)) {
                    JiraIssueState state = history.asOf(commentCreated);
                    events.add(buildEvent(issueKey + "-comment-" + commentId, eventSourceId,
                            EventType.ISSUE_COMMENT_CREATED, ref, formatTimestamp(commentCreated), commentAuthor,
                            new IssueCommentCreatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl),
                                    normalizedComment),
                            buildSourceData(issue, fields, state, context)));
                }

                if (commentUpdated != null && commentCreated != null
                        && commentUpdated.isAfter(commentCreated)
                        && commentUpdated.isAfter(since)) {
                    JiraIssueState state = history.asOf(commentUpdated);
                    events.add(buildEvent(issueKey + "-comment-" + commentId + "-updated", eventSourceId,
                            EventType.ISSUE_COMMENT_UPDATED, ref, formatTimestamp(commentUpdated), commentAuthor,
                            new IssueCommentUpdatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl),
                                    normalizedComment),
                            buildSourceData(issue, fields, state, context)));
                }
            }
        }

        return events;
    }

    /** Shared values for the events produced by one changelog history entry. */
    private record ItemContext(String eventSourceId, String ref, String timestamp, Actor actor,
                               NormalizedIssue issue, JsonNode sourceData) {

        NormalizedEvent event(EventType type, EventPayload payload) {
            return buildEvent(null, eventSourceId, type, ref, timestamp, actor, payload, sourceData);
        }
    }

    /**
     * Classifies one changelog item into zero or more events.
     */
    private List<NormalizedEvent> classifyItem(JsonNode item, ItemContext c, JiraNormalizationContext context) {
        String field = textOrNull(item, "field");
        if (field == null) {
            return List.of();
        }
        String from = textOrNull(item, "from");
        String fromString = textOrNull(item, "fromString");
        String to = textOrNull(item, "to");
        String toString = textOrNull(item, "toString");
        List<NormalizedEvent> out = new ArrayList<>();

        switch (field.toLowerCase(Locale.ROOT)) {
            case "status" -> {
                boolean fromDone = context.statusCategories().isDone(from, fromString);
                boolean toDone = context.statusCategories().isDone(to, toString);
                if (toDone && !fromDone) {
                    out.add(c.event(EventType.ISSUE_CLOSED, new IssueClosedPayload(c.issue())));
                } else if (fromDone && !toDone) {
                    out.add(c.event(EventType.ISSUE_REOPENED, new IssueReopenedPayload(c.issue())));
                } else {
                    out.add(c.event(EventType.ISSUE_UPDATED, new IssueUpdatedPayload(c.issue(),
                            new Change(field, fromString, toString, c.actor()))));
                }
            }
            case "summary", "description" -> out.add(c.event(EventType.ISSUE_UPDATED,
                    new IssueUpdatedPayload(c.issue(), new Change(field, fromString, toString, c.actor()))));
            case "assignee" -> {
                if (toString != null && !toString.isEmpty()) {
                    out.add(c.event(EventType.ISSUE_ASSIGNED,
                            new IssueAssignedPayload(c.issue(), new Actor(to, toString, null, null))));
                } else {
                    Actor previous = from != null || fromString != null
                            ? new Actor(from, fromString, null, null) : null;
                    out.add(c.event(EventType.ISSUE_UNASSIGNED, new IssueUnassignedPayload(c.issue(), previous)));
                }
            }
            case "labels" -> {
                Set<String> oldLabels = parseSpaceSeparatedLabels(fromString);
                Set<String> newLabels = parseSpaceSeparatedLabels(toString);
                for (String label : newLabels) {
                    if (!oldLabels.contains(label)) {
                        out.add(c.event(EventType.ISSUE_LABELED, new IssueLabeledPayload(c.issue(),
                                new NormalizedLabel(label, null, null))));
                    }
                }
                for (String label : oldLabels) {
                    if (!newLabels.contains(label)) {
                        out.add(c.event(EventType.ISSUE_UNLABELED, new IssueUnlabeledPayload(c.issue(),
                                new NormalizedLabel(label, null, null))));
                    }
                }
            }
            default -> LOG.tracef("Ignoring changelog field: %s", field);
        }
        return out;
    }

    // -- Mapping helpers -------------------------------------------------------

    /**
     * Maps an issue snapshot to a {@link NormalizedIssue}. {@code state} is {@code closed} exactly when the
     * snapshot's status category is {@code done}.
     */
    NormalizedIssue toNormalizedIssue(String issueKey, JsonNode fields, JiraIssueState state, String baseUrl) {
        List<Actor> assignees = state.assigneeAccountId == null && state.assigneeDisplayName == null
                ? List.of()
                : List.of(new Actor(state.assigneeAccountId, state.assigneeDisplayName,
                        state.assigneeAvatarUrl, null));
        String openState = JiraStatusCategories.DONE.equals(state.statusCategoryKey) ? "closed" : "open";
        return new NormalizedIssue(
                issueKey,
                state.summary,
                state.description,
                openState,
                state.statusName,
                mapActor(fields.path("reporter")),
                assignees,
                List.copyOf(state.labels),
                null, // milestone
                baseUrl + "/browse/" + issueKey,
                isoTimestamp(textOrNull(fields, "created")),
                state.updatedAt,
                state.closedAt
        );
    }

    /**
     * Maps a Jira user JSON node to an {@link Actor}.
     */
    Actor mapActor(JsonNode user) {
        if (user == null || user.isMissingNode() || user.isNull()) {
            return null;
        }
        String login = textOrNull(user, "accountId");
        String displayName = textOrNull(user, "displayName");
        String avatarUrl = textOrNull(user.path("avatarUrls"), "48x48");
        return new Actor(login, displayName, avatarUrl, null);
    }

    /**
     * Maps a Jira comment JSON node to a {@link NormalizedComment}.
     */
    NormalizedComment mapComment(JsonNode comment, String issueKey, String baseUrl) {
        if (comment == null || comment.isMissingNode()) {
            return null;
        }
        String commentId = textOrNull(comment, "id");
        String url = baseUrl + "/browse/" + issueKey + "?focusedCommentId=" + commentId;
        return new NormalizedComment(
                commentId,
                extractDescription(comment.path("body")),
                mapActor(comment.path("author")),
                url,
                isoTimestamp(textOrNull(comment, "created")),
                isoTimestamp(textOrNull(comment, "updated"))
        );
    }

    /**
     * Builds the selected Jira {@code sourceData} for a snapshot: {@code jiraId}, {@code project{key,name}},
     * {@code issueType{name}}, {@code status{name,categoryKey}}, {@code priority{name}},
     * {@code resolution{name}}, {@code dueDate}, {@code components[{name}]}, {@code fixVersions[{name}]} and,
     * when configured, {@code extraFields{name: value}}.
     */
    JsonNode buildSourceData(JsonNode issue, JsonNode fields, JiraIssueState state,
                             JiraNormalizationContext context) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("jiraId", textOrNull(issue, "id"));

        JsonNode project = fields.path("project");
        if (project.isObject()) {
            ObjectNode projectNode = source.putObject("project");
            projectNode.put("key", textOrNull(project, "key"));
            projectNode.put("name", textOrNull(project, "name"));
        }
        JsonNode issueType = fields.path("issuetype");
        if (issueType.isObject()) {
            source.putObject("issueType").put("name", textOrNull(issueType, "name"));
        }
        ObjectNode status = source.putObject("status");
        status.put("name", state.statusName);
        status.put("categoryKey", state.statusCategoryKey);
        if (state.priority != null) {
            source.putObject("priority").put("name", state.priority);
        }
        if (state.resolution != null) {
            source.putObject("resolution").put("name", state.resolution);
        }
        if (state.dueDate != null) {
            source.put("dueDate", state.dueDate);
        }
        if (!state.components.isEmpty()) {
            ArrayNode components = source.putArray("components");
            state.components.forEach(name -> components.addObject().put("name", name));
        }
        if (!state.fixVersions.isEmpty()) {
            ArrayNode fixVersions = source.putArray("fixVersions");
            state.fixVersions.forEach(name -> fixVersions.addObject().put("name", name));
        }
        if (!context.extraFields().isEmpty()) {
            ObjectNode extra = source.putObject("extraFields");
            for (Map.Entry<String, String> field : context.extraFields().entrySet()) {
                extra.put(field.getKey(), state.extraFields.get(field.getKey()));
            }
        }
        return source;
    }

    // -- Internal helpers -------------------------------------------------------

    /**
     * Extracts text from a Jira description or comment body. If the value is an ADF object, recursively
     * collects all text nodes. If it is a plain string, returns it directly.
     */
    static String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        if (descriptionNode.isTextual()) {
            return descriptionNode.asText();
        }
        if (descriptionNode.isObject()) {
            StringBuilder sb = new StringBuilder();
            extractAdfText(descriptionNode, sb);
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }
        return descriptionNode.toString();
    }

    private static void extractAdfText(JsonNode node, StringBuilder sb) {
        if (node.isTextual()) {
            sb.append(node.asText());
            return;
        }
        if (node.has("text") && node.get("text").isTextual()) {
            sb.append(node.get("text").asText());
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                extractAdfText(child, sb);
            }
        }
    }

    /**
     * Parses space-separated label strings from Jira changelog entries.
     */
    static Set<String> parseSpaceSeparatedLabels(String labelString) {
        if (labelString == null || labelString.trim().isEmpty()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(labelString.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static NormalizedEvent buildEvent(String sourceEventId, String eventSourceId,
                                              EventType type, String ref, String timestamp,
                                              Actor actor, EventPayload payload, JsonNode sourceData) {
        return new NormalizedEvent(UUID.randomUUID().toString(), sourceEventId, SOURCE, eventSourceId, type, ref,
                timestamp, actor, payload, sourceData);
    }

    private static NormalizedEvent withSourceEventId(NormalizedEvent event, String sourceEventId) {
        return new NormalizedEvent(event.id(), sourceEventId, event.source(), event.eventSourceId(),
                event.type(), event.ref(), event.timestamp(), event.actor(), event.payload(), event.sourceData());
    }

    /**
     * Parses a Jira API timestamp string into an Instant. Handles both ISO-8601 format and Jira's
     * {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} format.
     */
    static Instant parseJiraTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (Exception e) {
            try {
                return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(timestamp, Instant::from);
            } catch (Exception e2) {
                LOG.tracef("Failed to parse Jira timestamp: %s", timestamp);
                return null;
            }
        }
    }

    /**
     * Converts a Jira timestamp to ISO-8601 UTC ({@code …Z}).
     *
     * @return the ISO string, or null when absent or unparseable
     */
    static String isoTimestamp(String jiraTimestamp) {
        return formatTimestamp(parseJiraTimestamp(jiraTimestamp));
    }

    private static String formatTimestamp(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
```

- [ ] **Step 4: Validate every existing normalizer test output against the schemas**

In `JiraEventNormalizerV2Test.java`, add this helper below `setUp()`:

```java
    /** Normalizes with the default context and asserts every event is schema-valid. */
    private List<NormalizedEvent> normalize(ObjectNode issue) {
        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, EVENT_SOURCE_ID, BASE_URL);
        JiraFixtures.assertSchemaValid(events);
        return events;
    }
```

Then replace every occurrence (13) of
`normalizer.normalizeIssue(issue, SINCE, EVENT_SOURCE_ID, BASE_URL)` with `normalize(issue)` (edit with
`replaceAll`; the helper's own call is added after the replacement, or re-add it if it was replaced).

Also extend `assigneeSet_producesIssueAssigned` and `assigneeCleared_producesIssueUnassigned` so the changelog
item carries account IDs and the tests assert `login` (spike defect: assignee without login). Add this
builder next to `buildItem`:

```java
    private ObjectNode buildItem(String field, String from, String fromString, String to, String toString) {
        ObjectNode item = buildItem(field, fromString, toString);
        item.put("from", from);
        item.put("to", to);
        return item;
    }
```

In `assigneeSet_producesIssueAssigned` replace `buildItem("assignee", null, "John Doe")` with
`buildItem("assignee", null, null, "acct-john", "John Doe")` and add after the `displayName` assertion:

```java
        assertEquals("acct-john", payload.assignee().login());
```

In `assigneeCleared_producesIssueUnassigned` replace `buildItem("assignee", "John Doe", null)` with
`buildItem("assignee", "acct-john", "John Doe", null, null)` and add at the end:

```java
        IssueUnassignedPayload payload = (IssueUnassignedPayload) event.payload();
        assertEquals("acct-john", payload.assignee().login());
        assertEquals("John Doe", payload.assignee().displayName());
```

- [ ] **Step 5: Run the Jira tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jira*'`
Expected: PASS for `JiraEventNormalizerV2FixtureTest` (9 tests), `JiraEventNormalizerV2Test`,
`JiraIssueHistoryTest`, `JiraEventSourcePollerTest`, `JiraEventsApiClientTest`. If a schema assertion
fails, the message names the event and the JSON path; fix the normalizer mapping, not the schema. One
known case: if Plan 1's `Actor` schema requires `login`, the assignee item in
`multipleChangesInOneHistory_producesMultipleEvents` (built without account IDs) fails validation — give
that item account IDs with the 5-argument `buildItem` (real Jira changelogs always carry them).

- [ ] **Step 6: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2Test.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2FixtureTest.java
git commit -m "fix(jira): carry the issue state as of each event and close by status category"
```

---

### Task 5: New event types and edit-specific comment IDs

**Files:**
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java`
  (`classifyItem`, comment-update ID, one import)
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2FixtureTest.java`

**Interfaces:**
- Consumes: `classifyItem`, `ItemContext` (Task 4); `JiraNormalizationContext.isExtraField` (Task 2).
- Produces: event set per the spec table —
  - changelog field `Comment` (any case) with `fromString` and no `toString` → `issue.comment.deleted`, ID
    `KEY-{historyId}[-{i}]`, payload comment `NormalizedComment(null, fromString, null, null, null, null)`,
    actor = history author;
  - fields `priority`, `resolution`, `duedate`, `Fix Version`, `Component` and configured extra fields
    (case-insensitive on the changelog `field`) → `issue.updated` with `Change(field, fromString, toString,
    actor)`;
  - comment edit ID `KEY-comment-{id}-updated-{updatedEpochMillis}`.

- [ ] **Step 1: Write the failing tests**

Add these imports to `JiraEventNormalizerV2FixtureTest.java`:

```java
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentDeletedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;

import java.util.Set;
import java.util.stream.Collectors;
```

Add these helpers and tests to the class:

```java
    private static NormalizedEvent byId(List<NormalizedEvent> events, String sourceEventId) {
        return events.stream().filter(e -> sourceEventId.equals(e.sourceEventId())).findFirst()
                .orElseThrow(() -> new AssertionError(sourceEventId + " not in "
                        + events.stream().map(NormalizedEvent::sourceEventId).toList()));
    }

    private static void assertChange(NormalizedEvent event, String field, String from, String to) {
        assertEquals(EventType.ISSUE_UPDATED, event.type(), event.sourceEventId());
        IssueUpdatedPayload payload = (IssueUpdatedPayload) event.payload();
        assertEquals(field, payload.change().field());
        assertEquals(from, payload.change().from());
        assertEquals(to, payload.change().to());
    }

    @Test
    void apicurio137_producesTheCompleteEventSet() {
        Set<String> ids = apicurio137().stream().map(NormalizedEvent::sourceEventId).collect(Collectors.toSet());
        assertEquals(Set.of(
                "APICURIO-137-created",
                "APICURIO-137-93654123",
                "APICURIO-137-93654138",
                "APICURIO-137-93654147-0",
                "APICURIO-137-93654147-1",
                "APICURIO-137-93654152",
                "APICURIO-137-93654157",
                "APICURIO-137-93654169",
                "APICURIO-137-93654174",
                "APICURIO-137-93654235",
                "APICURIO-137-93654249-0",
                "APICURIO-137-93654249-1",
                "APICURIO-137-93654254",
                "APICURIO-137-93654267-0",
                "APICURIO-137-93654267-1",
                "APICURIO-137-comment-19040900",
                "APICURIO-137-comment-19040900-updated-1792594436781"), ids);
    }

    @Test
    void commentDeletion_emitsIssueCommentDeleted() {
        NormalizedEvent event = byId(apicurio137(), "APICURIO-137-93654235");
        assertEquals(EventType.ISSUE_COMMENT_DELETED, event.type());
        IssueCommentDeletedPayload payload = (IssueCommentDeletedPayload) event.payload();
        assertNull(payload.comment().id());
        assertNull(payload.comment().author());
        assertEquals("Deleted comment text", payload.comment().body());
        assertEquals("acct-reporter-1", event.actor().login());
        assertEquals("In Progress", payload.issue().stateDetail());
    }

    @Test
    void priorityAndResolutionChanges_emitIssueUpdated() {
        List<NormalizedEvent> events = apicurio137();
        assertChange(byId(events, "APICURIO-137-93654157"), "priority", "Undefined", "Major");
        assertChange(byId(events, "APICURIO-137-93654249-0"), "resolution", null, "Done");
        assertEquals(EventType.ISSUE_CLOSED, byId(events, "APICURIO-137-93654249-1").type());
        assertChange(byId(events, "APICURIO-137-93654267-0"), "resolution", "Done", null);
        assertEquals(EventType.ISSUE_REOPENED, byId(events, "APICURIO-137-93654267-1").type());
        assertEquals("Major", byId(events, "APICURIO-137-93654157").sourceData().path("priority")
                .path("name").asText());
    }

    @Test
    void fixVersionComponentDueDateAndExtraFields_emitIssueUpdated() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));
        assertChange(byId(events, "IPT-1001-2001-0"), "Fix Version", null, "1.2.0");
        assertChange(byId(events, "IPT-1001-2001-1"), "Component", null, "core");
        assertChange(byId(events, "IPT-1001-2002"), "duedate", null, "2026-10-30 00:00:00.0");
        assertChange(byId(events, "IPT-1001-2003"), "Story Points", null, "5");
        assertEquals("5", byId(events, "IPT-1001-2003").sourceData().path("extraFields")
                .path("Story Points").asText());
        assertChange(byId(events, "IPT-1001-2004-0"), "resolution", null, "Done");
        assertEquals(EventType.ISSUE_CLOSED, byId(events, "IPT-1001-2004-1").type());
    }

    @Test
    void unconfiguredCustomField_emitsNothing() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                new JiraNormalizationContext(CATEGORIES, Map.of()));
        assertTrue(events.stream().noneMatch(e -> e.sourceEventId().startsWith("IPT-1001-2003")));
        assertFalse(byId(events, "IPT-1001-created").sourceData().has("extraFields"));
    }

    @Test
    void extraFields_matchChangelogFieldCaseInsensitively() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("story points", "customfield_10028");
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                new JiraNormalizationContext(CATEGORIES, extra));
        assertChange(byId(events, "IPT-1001-2003"), "Story Points", null, "5");
        assertEquals("5", byId(events, "IPT-1001-2003").sourceData().path("extraFields")
                .path("story points").asText());
    }

    @Test
    void repeatedCommentEdits_produceDistinctIds() {
        List<NormalizedEvent> first = apicurio137();
        ObjectNode issue = (ObjectNode) JiraFixtures.load("apicurio-137-issue.json");
        ObjectNode comment = (ObjectNode) issue.path("fields").path("comment").path("comments").get(0);
        comment.put("updated", "2026-10-21T15:10:00.000+0000");
        List<NormalizedEvent> second = normalizer.normalizeIssue(issue, SINCE, SOURCE_ID, BASE_URL,
                new JiraNormalizationContext(CATEGORIES, Map.of()));
        JiraFixtures.assertSchemaValid(second);

        NormalizedEvent firstEdit = byId(first, "APICURIO-137-comment-19040900-updated-1792594436781");
        String secondId = "APICURIO-137-comment-19040900-updated-"
                + Instant.parse("2026-10-21T15:10:00Z").toEpochMilli();
        NormalizedEvent secondEdit = byId(second, secondId);
        assertEquals(EventType.ISSUE_COMMENT_UPDATED, firstEdit.type());
        assertEquals("2026-10-21T15:10:00Z", secondEdit.timestamp());
        assertEquals("In Progress", ((IssueCommentUpdatedPayload) firstEdit.payload()).issue().stateDetail());
        assertEquals("New", ((IssueCommentUpdatedPayload) secondEdit.payload()).issue().stateDetail(),
                "an edit after the reopen sees the reopened state");
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraEventNormalizerV2FixtureTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `apicurio137_producesTheCompleteEventSet` (missing `-93654157`, `-93654235`, `-0` suffixes and
the `-updated-1792594436781` ID), `commentDeletion_emitsIssueCommentDeleted` (`not in`), and the other new
tests.

- [ ] **Step 3: Implement**

In `JiraEventNormalizerV2.java` add the import:

```java
import io.apitomy.axiom.core.events.model.payloads.IssueCommentDeletedPayload;
```

Add the constant below `SOURCE`:

```java
    /** Built-in changelog fields (lower case) that produce {@code issue.updated} with a {@code change}. */
    private static final Set<String> UPDATED_FIELDS = Set.of("summary", "description", "priority",
            "resolution", "duedate", "fix version", "component");
```

In `classifyItem`, replace the `case "summary", "description" -> …;` arm and the `default -> …;` arm with:

```java
            case "comment" -> {
                if (fromString != null && toString == null) {
                    out.add(c.event(EventType.ISSUE_COMMENT_DELETED, new IssueCommentDeletedPayload(c.issue(),
                            new NormalizedComment(null, fromString, null, null, null, null))));
                }
            }
            default -> {
                if (UPDATED_FIELDS.contains(field.toLowerCase(Locale.ROOT)) || context.isExtraField(field)) {
                    out.add(c.event(EventType.ISSUE_UPDATED, new IssueUpdatedPayload(c.issue(),
                            new Change(field, fromString, toString, c.actor()))));
                } else {
                    LOG.tracef("Ignoring changelog field: %s", field);
                }
            }
```

In `normalizeIssue`, change the comment-update source event ID from
`issueKey + "-comment-" + commentId + "-updated"` to:

```java
issueKey + "-comment-" + commentId + "-updated-" + commentUpdated.toEpochMilli()
```

- [ ] **Step 4: Run the Jira tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jira*'`
Expected: PASS. `JiraEventNormalizerV2Test.multipleChangesInOneHistory_producesMultipleEvents` and the
summary test are unaffected (summary/description still produce `issue.updated`).

- [ ] **Step 5: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2FixtureTest.java
git commit -m "feat(jira): emit comment deletions, field updates, extra fields and per-edit comment IDs"
```

---

### Task 6: API client — statuses, fields, all comments, requested fields, quoted JQL

**Files:**
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClient.java`
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClientTest.java`
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePollerTest.java`
  (stub signature only)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `public ApiResult fetchIssuesPage(String baseUrl, List<String> projects, Instant since, String credentials,
    String nextPageToken, List<String> extraFieldIds)` — replaces the 5-argument method (removed, so stubs
    cannot silently miss the call).
  - `public ApiResult fetchStatuses(String baseUrl, String credentials)` — data: JSON array of statuses.
  - `public ApiResult fetchFields(String baseUrl, String credentials)` — data: JSON array of fields.
  - `public ApiResult fetchIssueComments(String baseUrl, String issueKey, String credentials)` — now pages
    through all comments; data: JSON array of all comments (oldest first). Errors are returned as the failing
    page's `ApiResult` (status code preserved).
  - `static String projectClause(List<String> projects)` — `project in ("A", "B")`.
  - `static final List<String> SEARCH_FIELDS`.

- [ ] **Step 1: Write the failing tests**

In `JiraEventsApiClientTest.java`:

1. Update the three existing `fetchIssuesPage(...)` calls to pass `List.of()` as the new last argument, e.g.
   `client.fetchIssuesPage(baseUrl, List.of("P"), Instant.parse("2026-10-01T10:00:00Z"), "a:b", null, List.of())`.
2. Register two more contexts in `setUp()` (before `server.start()`):

```java
        server.createContext("/rest/api/3/status", exchange -> respond(exchange, 200,
                "[{\"id\":\"6\",\"name\":\"Closed\",\"statusCategory\":{\"key\":\"done\"}}]"));
        server.createContext("/rest/api/3/field", exchange -> respond(exchange, 200,
                "[{\"id\":\"customfield_10028\",\"name\":\"Story Points\"}]"));
```

3. Add a field `private final List<String> commentQueries = new ArrayList<>();` and make the issue handler
   dispatch comment requests — replace the first lines of `handleChangelog` with:

```java
    private void handleChangelog(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawPath().endsWith("/comment")) {
            handleComments(exchange);
            return;
        }
        String query = exchange.getRequestURI().getQuery();
```

   and add:

```java
    private void handleComments(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        commentQueries.add(query);
        int startAt = 0;
        for (String part : query.split("&")) {
            if (part.startsWith("startAt=")) {
                startAt = Integer.parseInt(part.substring("startAt=".length()));
            }
        }
        int total = 250;
        int end = Math.min(startAt + 100, total);
        ObjectNode page = MAPPER.createObjectNode();
        page.put("startAt", startAt);
        page.put("maxResults", 100);
        page.put("total", total);
        ArrayNode comments = page.putArray("comments");
        for (int i = startAt; i < end; i++) {
            comments.addObject().put("id", String.valueOf(i));
        }
        respond(exchange, 200, MAPPER.writeValueAsString(page));
    }
```

4. Add the tests:

```java
    @Test
    void fetchIssuesPage_quotesProjectKeysInJql() {
        client.fetchIssuesPage(baseUrl, List.of("P", "Q_2"), Instant.parse("2026-10-01T10:00:00Z"), "a:b",
                null, List.of());

        String jql = searchBodies.get(0).path("jql").asText();
        assertTrue(jql.startsWith("project in (\"P\", \"Q_2\") AND updated >= "), jql);
    }

    @Test
    void projectClause_escapesQuotesAndBackslashes() {
        assertEquals("project in (\"A\\\") OR (x = \\\"y\", \"B\\\\\")",
                JiraEventsApiClient.projectClause(List.of("A\") OR (x = \"y", "B\\")));
    }

    @Test
    void fetchIssuesPage_requestsSnapshotFieldsAndExtraFields() {
        client.fetchIssuesPage(baseUrl, List.of("P"), Instant.parse("2026-10-01T10:00:00Z"), "a:b", null,
                List.of("customfield_10028"));

        List<String> fields = new ArrayList<>();
        searchBodies.get(0).path("fields").forEach(f -> fields.add(f.asText()));
        for (String required : List.of("project", "resolutiondate", "status", "duedate", "priority",
                "resolution", "fixVersions", "components", "assignee", "labels", "comment",
                "customfield_10028")) {
            assertTrue(fields.contains(required), required + " missing from " + fields);
        }
        assertEquals("changelog", searchBodies.get(0).path("expand").asText());
    }

    @Test
    void fetchStatuses_returnsStatusArray() {
        ApiResult result = client.fetchStatuses(baseUrl, "a:b");

        assertTrue(result.success());
        assertEquals("done", result.data().get(0).path("statusCategory").path("key").asText());
    }

    @Test
    void fetchFields_returnsFieldArray() {
        ApiResult result = client.fetchFields(baseUrl, "a:b");

        assertTrue(result.success());
        assertEquals("customfield_10028", result.data().get(0).path("id").asText());
    }

    @Test
    void fetchIssueComments_followsAllPages() {
        ApiResult result = client.fetchIssueComments(baseUrl, "P-1", "a:b");

        assertTrue(result.success());
        assertEquals(250, result.data().size());
        assertEquals("249", result.data().get(249).path("id").asText());
        assertEquals(3, commentQueries.size());
        assertTrue(commentQueries.get(0).contains("orderBy=created"), commentQueries.get(0));
    }
```

In `JiraEventSourcePollerTest.java`, change the stub override signature (body unchanged) so the module
compiles:

```java
        @Override
        public ApiResult fetchIssuesPage(String baseUrl, List<String> projects, Instant since,
                                         String credentials, String nextPageToken, List<String> extraFieldIds) {
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraEventsApiClientTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL (`fetchIssuesPage` with 6 arguments, `fetchStatuses`, `fetchFields`,
`projectClause` not found).

- [ ] **Step 3: Implement**

In `JiraEventsApiClient.java` add `import java.util.stream.Collectors;` and the constants below
`MAX_CHANGELOG_PAGES`:

```java
    private static final int COMMENT_PAGE_SIZE = 100;
    private static final int MAX_COMMENT_PAGES = 50;

    /** Fields requested by the search; the configured extra field IDs are appended. */
    static final List<String> SEARCH_FIELDS = List.of("summary", "status", "assignee", "reporter", "labels",
            "priority", "created", "updated", "comment", "resolution", "resolutiondate", "description",
            "issuetype", "components", "fixVersions", "duedate", "parent", "project");
```

Replace `fetchIssuesPage` (Javadoc kept, plus the new parameter) with:

```java
    /**
     * Fetches a single page of issues (with changelog expansion) across multiple projects
     * that have been updated since the given timestamp. Results are ordered by
     * {@code updated ASC}. Callers must follow {@code nextPageToken} in the response
     * (until it is absent or {@code isLast} is true) to retrieve the full result set.
     *
     * <p>The JQL lower bound is the given {@code since} minus a two-minute buffer (covering
     * modest clock skew), expressed in epoch milliseconds so it is independent of the Jira
     * user's profile timezone. Project keys are quoted. Duplicates resulting from the overlap
     * are removed by source-event de-duplication downstream.</p>
     *
     * @param baseUrl       Jira Cloud base URL (e.g., https://myorg.atlassian.net)
     * @param projects      list of project keys to search
     * @param since         only return issues updated at or after (approximately) this time
     * @param credentials   email:api_token for Basic auth
     * @param nextPageToken the page token from the previous page, or {@code null} for the first page
     * @param extraFieldIds field IDs of the configured extra fields to request in addition to
     *                      {@link #SEARCH_FIELDS}
     * @return API result with one page of the search response
     */
    public ApiResult fetchIssuesPage(String baseUrl, List<String> projects, Instant since,
                                     String credentials, String nextPageToken, List<String> extraFieldIds) {
        Instant bufferedSince = since.minus(QUERY_OVERLAP);
        String jql = projectClause(projects) + " AND updated >= " + bufferedSince.toEpochMilli()
                + " ORDER BY updated ASC";
        String url = baseUrl + "/rest/api/3/search/jql";

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jql", jql);
            body.put("maxResults", SEARCH_PAGE_SIZE);
            body.put("expand", "changelog");
            if (nextPageToken != null && !nextPageToken.isBlank()) {
                body.put("nextPageToken", nextPageToken);
            }
            ArrayNode fields = body.putArray("fields");
            SEARCH_FIELDS.forEach(fields::add);
            if (extraFieldIds != null) {
                extraFieldIds.stream().filter(id -> !SEARCH_FIELDS.contains(id)).forEach(fields::add);
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            return doPost(url, jsonBody, credentials);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build Jira search request");
            return ApiResult.exception(e, "POST", url, null);
        }
    }

    /**
     * Builds the JQL project clause with every key quoted ({@code "} and {@code \} escaped).
     *
     * @param projects project keys
     * @return e.g. {@code project in ("A", "B")}
     */
    static String projectClause(List<String> projects) {
        return projects.stream()
                .map(key -> "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "project in (", ")"));
    }

    /**
     * Fetches all statuses visible to the user ({@code GET /rest/api/3/status}), used to map status IDs to
     * status categories.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is the array of statuses
     */
    public ApiResult fetchStatuses(String baseUrl, String credentials) {
        return doGet(baseUrl + "/rest/api/3/status", credentials);
    }

    /**
     * Fetches all fields ({@code GET /rest/api/3/field}), used to resolve configured extra field names to IDs.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is the array of fields
     */
    public ApiResult fetchFields(String baseUrl, String credentials) {
        return doGet(baseUrl + "/rest/api/3/field", credentials);
    }
```

Replace `fetchIssueComments` with:

```java
    /**
     * Fetches all comments of an issue, following {@code startAt} pagination of
     * {@code /rest/api/3/issue/{key}/comment}. Used when the comment list embedded in search results is
     * incomplete.
     *
     * @param baseUrl     Jira Cloud base URL
     * @param issueKey    issue key (e.g., PROJ-123)
     * @param credentials email:api_token for Basic auth
     * @return API result whose data is an array of all comments (oldest first), or the failing page's result
     */
    public ApiResult fetchIssueComments(String baseUrl, String issueKey, String credentials) {
        ArrayNode comments = objectMapper.createArrayNode();
        String commentUrl = baseUrl + "/rest/api/3/issue/"
                + URLEncoder.encode(issueKey, StandardCharsets.UTF_8).replace("+", "%20") + "/comment";
        int startAt = 0;
        for (int pages = 0; pages < MAX_COMMENT_PAGES; pages++) {
            ApiResult page = doGet(commentUrl + "?orderBy=created&startAt=" + startAt
                    + "&maxResults=" + COMMENT_PAGE_SIZE, credentials);
            if (!page.success()) {
                return page;
            }
            JsonNode values = page.data().path("comments");
            int fetched = values.isArray() ? values.size() : 0;
            if (fetched > 0) {
                values.forEach(comments::add);
            }
            startAt += fetched;
            if (fetched == 0 || startAt >= page.data().path("total").asInt(0)) {
                return ApiResult.ok(comments, "GET", commentUrl, null, null);
            }
        }
        LOG.warnf("Comments of %s exceed %d pages; only the first %d are used",
                issueKey, MAX_COMMENT_PAGES, comments.size());
        return ApiResult.ok(comments, "GET", commentUrl, null, null);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jira*'`
Expected: PASS (`JiraEventsApiClientTest` 11 tests; poller tests still green).

- [ ] **Step 5: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClient.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventsApiClientTest.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePollerTest.java
git commit -m "feat(jira): fetch statuses, fields and all comments; quote project keys in JQL"
```

---

### Task 7: Poller — per-poll context, extra fields, complete comment lists, base URL, credentials Javadoc

**Files:**
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePoller.java`
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePollerTest.java`

**Interfaces:**
- Consumes: `JiraEventsApiClient.fetchStatuses/fetchFields/fetchIssueComments/fetchIssuesPage(…, extraFieldIds)`
  (Task 6); `JiraStatusCategories.fromStatuses/empty`, `JiraFieldCatalog.fromFields/idFor`,
  `JiraNormalizationContext` (Task 2); `normalizeIssue(…, JiraNormalizationContext)` (Task 4).
- Produces:
  - `JiraNormalizationContext buildContext(String baseUrl, String credentials, List<String> extraFieldNames)`
    (package-private).
  - `WindowResult ingestWindow(String eventSourceId, String baseUrl, List<String> projects, Instant since,
    String credentials, Instant windowStart, JiraNormalizationContext context)` (package-private); the
    existing 6-argument overload delegates with `JiraNormalizationContext.defaults()`.
  - `JsonNode withAllComments(String baseUrl, JsonNode issue, String credentials)` (package-private; null on
    transient failure).
  - `static String stripTrailingSlash(String url)` (package-private).

- [ ] **Step 1: Write the failing tests**

In `JiraEventSourcePollerTest.java` extend `StubApiClient` (keep the existing members and the Task 6
signature):

```java
        final Map<String, ApiResult> comments = new HashMap<>();
        final List<List<String>> extraFieldIdRequests = new ArrayList<>();
        ApiResult statuses = ApiResult.ok(MAPPER.createArrayNode(), "GET", "status", null, null);
        ApiResult fields = ApiResult.ok(MAPPER.createArrayNode(), "GET", "field", null, null);
        int fieldCalls;

        @Override
        public ApiResult fetchIssueComments(String baseUrl, String issueKey, String credentials) {
            return comments.get(issueKey);
        }

        @Override
        public ApiResult fetchStatuses(String baseUrl, String credentials) {
            return statuses;
        }

        @Override
        public ApiResult fetchFields(String baseUrl, String credentials) {
            fieldCalls++;
            return fields;
        }
```

and record the IDs in the existing `fetchIssuesPage` override (first line of its body):

```java
            extraFieldIdRequests.add(extraFieldIds);
```

Add these tests (imports: `java.util.Map` is already imported; add
`import static org.junit.jupiter.api.Assertions.assertNull;` only if missing):

```java
    @Test
    void statusLookup_usesCategoriesFromJira() {
        api.statuses = ApiResult.ok(JiraFixtures.load("jira-statuses.json"), "GET", "status", null, null);

        JiraNormalizationContext context = poller.buildContext(BASE_URL, "a:b", List.of());

        assertTrue(context.statusCategories().isDone("10500", "Shipped"));
        assertEquals(0, api.fieldCalls);
    }

    @Test
    void statusLookupFailure_fallsBackToNameMatching() {
        api.statuses = ApiResult.httpError(500, "GET", "status", null, "down");

        JiraNormalizationContext context = poller.buildContext(BASE_URL, "a:b", List.of());

        assertTrue(context.statusCategories().isEmpty());
        assertTrue(context.statusCategories().isDone(null, "Closed"));
    }

    @Test
    void extraFields_areResolvedToIds_andUnknownNamesIgnored() {
        api.fields = ApiResult.ok(JiraFixtures.load("jira-fields.json"), "GET", "field", null, null);

        JiraNormalizationContext context = poller.buildContext(BASE_URL, "a:b",
                List.of("Story Points", "No Such Field"));

        assertEquals(Map.of("Story Points", "customfield_10028"), context.extraFields());
    }

    @Test
    void fieldLookupFailure_keepsNamesWithoutIds() {
        api.fields = ApiResult.httpError(500, "GET", "field", null, "down");

        JiraNormalizationContext context = poller.buildContext(BASE_URL, "a:b", List.of("Story Points"));

        assertTrue(context.extraFields().containsKey("Story Points"));
        assertNull(context.extraFields().get("Story Points"));
        assertEquals(List.of(), context.extraFieldIds());
    }

    @Test
    void extraFieldIds_arePassedToSearch() {
        api.pages.add(page(issues(0, 1, "2026-10-01T10:01:00.000+0000"), null));

        poller.ingestWindow(SOURCE_ID, BASE_URL, List.of("P"), SINCE, "a:b", WINDOW_START,
                new JiraNormalizationContext(JiraStatusCategories.empty(), Map.of("Story Points", "customfield_10028")));

        assertEquals(List.of("customfield_10028"), api.extraFieldIdRequests.get(0));
    }

    @Test
    void incompleteCommentList_fetchesAllComments() {
        api.pages.add(page(List.of(JiraFixtures.load("ipt-1001-issue.json")), null));
        api.comments.put("IPT-1001", ApiResult.ok(JiraFixtures.load("ipt-1001-comments.json").path("comments"),
                "GET", "comment", null, null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertTrue(stream.ids.containsAll(List.of("IPT-1001-comment-3001", "IPT-1001-comment-3002",
                "IPT-1001-comment-3003",
                "IPT-1001-comment-3002-updated-" + Instant.parse("2026-10-20T09:35:00Z").toEpochMilli())),
                stream.ids.toString());
    }

    @Test
    void commentFetchServerError_abortsWindow() {
        api.pages.add(page(List.of(JiraFixtures.load("ipt-1001-issue.json")), null));
        api.comments.put("IPT-1001", ApiResult.httpError(503, "GET", "comment", null, "down"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
        assertTrue(result.errorMessage().contains("comments"), result.errorMessage());
    }

    @Test
    void commentFetchNotFound_usesEmbeddedComments() {
        api.pages.add(page(List.of(JiraFixtures.load("ipt-1001-issue.json")), null));
        api.comments.put("IPT-1001", ApiResult.httpError(404, "GET", "comment", null, "gone"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertTrue(stream.ids.contains("IPT-1001-comment-3001"));
        assertFalse(stream.ids.contains("IPT-1001-comment-3002"));
    }

    @Test
    void stripTrailingSlash_removesTrailingSlashes() {
        assertEquals("https://x.atlassian.net", JiraEventSourcePoller.stripTrailingSlash("https://x.atlassian.net/"));
        assertEquals("https://x.atlassian.net", JiraEventSourcePoller.stripTrailingSlash("https://x.atlassian.net//"));
        assertEquals("https://x.atlassian.net", JiraEventSourcePoller.stripTrailingSlash(" https://x.atlassian.net "));
        assertNull(JiraEventSourcePoller.stripTrailingSlash(null));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl events/jira -am test -Dtest=JiraEventSourcePollerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL (`buildContext`, 7-argument `ingestWindow`, `stripTrailingSlash` not found).

- [ ] **Step 3: Implement**

In `JiraEventSourcePoller.java` add imports `java.util.LinkedHashMap` and `io.apitomy.axiom.events.core.ApiResult`
(if not present).

In `pollEventSource`, after the configuration `try` block has parsed `projects`, also parse `extraFields`
inside the same `try` (declare `List<String> extraFieldNames;` next to `List<String> projects;`):

```java
            extraFieldNames = new ArrayList<>();
            JsonNode extraFieldsNode = config.path("extraFields");
            if (extraFieldsNode.isArray()) {
                for (JsonNode field : extraFieldsNode) {
                    if (field.isTextual() && !field.asText().isBlank()) {
                        extraFieldNames.add(field.asText().trim());
                    }
                }
            }
```

Replace the call

```java
        WindowResult result = ingestWindow(eventSource.id, eventSource.baseUrl, projects,
                since, credentials, windowStart);
```

with

```java
        String baseUrl = stripTrailingSlash(eventSource.baseUrl);
        JiraNormalizationContext context = buildContext(baseUrl, credentials, extraFieldNames);
        WindowResult result = ingestWindow(eventSource.id, baseUrl, projects,
                since, credentials, windowStart, context);
```

Add the context builder:

```java
    /**
     * Builds the per-poll normalization context: the status-category lookup and the configured extra
     * fields resolved to field IDs. A failed status lookup falls back to status-name matching; a failed
     * field lookup keeps the names without IDs (changelog events still work); names unknown to Jira are
     * ignored.
     *
     * @param baseUrl         Jira base URL without trailing slash
     * @param credentials     email:api_token credentials
     * @param extraFieldNames configured {@code extraFields}
     * @return the context
     */
    JiraNormalizationContext buildContext(String baseUrl, String credentials, List<String> extraFieldNames) {
        JiraStatusCategories categories;
        ApiResult statuses = apiClient.fetchStatuses(baseUrl, credentials);
        if (statuses != null && statuses.success()) {
            categories = JiraStatusCategories.fromStatuses(statuses.data());
        } else {
            LOG.warnf("Jira status-category lookup failed for %s (%s); falling back to status-name matching",
                    baseUrl, statuses != null ? statuses.errorMessage() : "no response");
            categories = JiraStatusCategories.empty();
        }

        Map<String, String> extraFields = new LinkedHashMap<>();
        if (extraFieldNames != null && !extraFieldNames.isEmpty()) {
            ApiResult fields = apiClient.fetchFields(baseUrl, credentials);
            if (fields != null && fields.success()) {
                JiraFieldCatalog catalog = JiraFieldCatalog.fromFields(fields.data());
                for (String name : extraFieldNames) {
                    String id = catalog.idFor(name);
                    if (id != null) {
                        extraFields.put(name, id);
                    } else {
                        LOG.warnf("Jira field '%s' in extraFields is unknown on %s; ignored", name, baseUrl);
                    }
                }
            } else {
                LOG.warnf("Jira field lookup failed for %s (%s); extra field values are taken from the "
                        + "changelog only", baseUrl, fields != null ? fields.errorMessage() : "no response");
                extraFieldNames.forEach(name -> extraFields.put(name, null));
            }
        }
        return new JiraNormalizationContext(categories, extraFields);
    }

    /**
     * Strips surrounding whitespace and trailing slashes from a base URL.
     *
     * @param url the URL, may be null
     * @return the normalized URL, or null
     */
    static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
```

Turn the existing `ingestWindow` into the 7-argument version (add `@param context status categories and extra
fields for this poll` to its Javadoc) and add the delegating overload above it:

```java
    /**
     * Same as {@link #ingestWindow(String, String, List, Instant, String, Instant, JiraNormalizationContext)}
     * with no status lookup and no extra fields.
     */
    WindowResult ingestWindow(String eventSourceId, String baseUrl, List<String> projects,
                              Instant since, String credentials, Instant windowStart) {
        return ingestWindow(eventSourceId, baseUrl, projects, since, credentials, windowStart,
                JiraNormalizationContext.defaults());
    }
```

Inside the 7-argument `ingestWindow`:

- the search call becomes
  `apiClient.fetchIssuesPage(baseUrl, projects, since, credentials, nextPageToken, context.extraFieldIds())`;
- right after the existing `if (completeIssue == null) { … }` block insert the comment completion, building
  the failure result exactly like the changelog failure above it (same constructor arguments; only the
  message differs):

```java
                        JsonNode withComments = withAllComments(baseUrl, completeIssue, credentials);
                        if (withComments == null) {
                            return new WindowResult(false, null, issuesProcessed, totalIngested,
                                    "Failed to fetch comments for " + issue.path("key").asText());
                        }
```

- the normalizer call becomes
  `normalizer.normalizeIssue(withComments, normalizeSince, eventSourceId, baseUrl, context)`. Plan 1's
  persistence loop (`PersistOutcome` counting) stays unchanged.

Add the comment completion next to `withFullChangelog`:

```java
    /**
     * Returns the issue with its complete comment list, fetching all comments when the embedded list is
     * incomplete ({@code total} greater than the number returned). A 404 or 403 falls back to the embedded
     * comments with a warning.
     *
     * @return the (possibly augmented) issue, or {@code null} if the comment fetch failed with a transient
     *         error (5xx, 429, network) and the window must be retried
     */
    JsonNode withAllComments(String baseUrl, JsonNode issue, String credentials) {
        JsonNode comment = issue.path("fields").path("comment");
        JsonNode embedded = comment.path("comments");
        int total = comment.path("total").asInt(0);
        int count = embedded.isArray() ? embedded.size() : 0;
        if (total <= count) {
            return issue;
        }
        String key = issue.path("key").asText();
        ApiResult all = apiClient.fetchIssueComments(baseUrl, key, credentials);
        if (all != null && !all.success() && (all.statusCode() == 404 || all.statusCode() == 403)) {
            LOG.warnf("Comment fetch for Jira issue %s returned %d; using the embedded comments",
                    key, all.statusCode());
            return issue;
        }
        if (all == null || !all.success()) {
            return null;
        }
        ObjectNode copy = ((ObjectNode) issue).deepCopy();
        ObjectNode newComment = ((ObjectNode) copy.get("fields")).putObject("comment");
        newComment.put("startAt", 0);
        newComment.put("maxResults", all.data().size());
        newComment.put("total", all.data().size());
        newComment.set("comments", all.data());
        return copy;
    }
```

Fix the stale Javadoc of `resolveCredentials`:

```java
    /**
     * Resolves Jira credentials in {@code email:api_token} form, in this order: the secret named by the
     * event source's {@code secretName}, the {@code JIRA_API_TOKEN} secret, then the
     * {@code JIRA_API_TOKEN} environment variable.
     *
     * @param eventSource the event source
     * @return the decrypted credentials, or null when none is configured
     */
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl events/jira -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Jira*'`
Expected: PASS (all poller tests, including the 9 new ones).

- [ ] **Step 5: Commit**

```bash
git add events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePoller.java events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePollerTest.java
git commit -m "feat(jira): per-poll status categories, extra fields and complete comment lists"
```

---

### Task 8: Event-source configuration validation (project keys, `extraFields`, `baseUrl` scheme)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemValidationService.java:36-45,183-227`
- Modify: `app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java`

**Interfaces:**
- Consumes: existing `validateStringList(JsonNode, String, Pattern, String, List<ConfigProblem>)`,
  `ConfigProblem.error(path, code, message)`.
- Produces: problems with codes `INVALID_CONFIGURATION` (paths `configuration.projects[i]`,
  `configuration.extraFields`, `configuration.extraFields[i]`) and `INVALID_BASE_URL` (path `baseUrl`).

- [ ] **Step 1: Write the failing tests**

In `ConfigItemValidationServiceTest.java`, change the URL list of
`githubEventSourceAcceptsApiAndRepositoryUrls` to drop the scheme-less entry:

```java
        for (String url : List.of("https://api.github.com", "https://github.com/Apicurio/apicurio-registry",
                "https://github.ibm.com/", "https://github.example.com/api/v3")) {
```

and add:

```java
    private List<ConfigProblem> jira(String baseUrl, String configuration) throws Exception {
        return service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "jira-prod", "name": "Jira", "sourceType": "jira", "baseUrl": "%s",
             "configuration": %s}
            """.formatted(baseUrl, configuration)), ValidationContext.unchecked());
    }

    private static List<String> errorPaths(List<ConfigProblem> problems) {
        return problems.stream().filter(ConfigProblem::isError).map(ConfigProblem::path).toList();
    }

    @Test
    void jiraProjectKeysMustBeUppercaseProjectKeys() throws Exception {
        List<ConfigProblem> problems = jira("https://x.atlassian.net",
                "{\"projects\": [\"PROJ\", \"AB_2\", \"proj\", \"A\", \"PROJ-1\", \"P\\\" OR x\"]}");
        assertEquals(List.of("configuration.projects[2]", "configuration.projects[3]",
                "configuration.projects[4]", "configuration.projects[5]"), errorPaths(problems));
        assertTrue(problems.get(0).message().contains("^[A-Z][A-Z0-9_]+$"), problems.get(0).message());
    }

    @Test
    void jiraExtraFieldsAreOptionalNonBlankStrings() throws Exception {
        assertEquals(List.of(), errorPaths(jira("https://x.atlassian.net", "{\"projects\": [\"PROJ\"]}")));
        assertEquals(List.of(), errorPaths(jira("https://x.atlassian.net",
                "{\"projects\": [\"PROJ\"], \"extraFields\": [\"Story Points\", \"Team\"]}")));
        assertEquals(List.of("configuration.extraFields"), errorPaths(jira("https://x.atlassian.net",
                "{\"projects\": [\"PROJ\"], \"extraFields\": \"Story Points\"}")));
        assertEquals(List.of("configuration.extraFields[1]", "configuration.extraFields[2]"),
                errorPaths(jira("https://x.atlassian.net",
                        "{\"projects\": [\"PROJ\"], \"extraFields\": [\"Story Points\", \" \", 5]}")));
    }

    @Test
    void baseUrlMustStartWithHttpOrHttps() throws Exception {
        List<ConfigProblem> jiraProblems = jira("x.atlassian.net", "{\"projects\": [\"PROJ\"]}");
        assertTrue(jiraProblems.stream().anyMatch(p -> p.isError() && p.code().equals("INVALID_BASE_URL")
                && p.path().equals("baseUrl")), jiraProblems.toString());

        List<ConfigProblem> githubProblems = service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "gh", "name": "GH", "sourceType": "github", "baseUrl": "github.ibm.com/api/v3",
             "configuration": {"repositories": ["o/r"]}}
            """), ValidationContext.unchecked());
        assertTrue(githubProblems.stream().anyMatch(p -> p.isError() && p.code().equals("INVALID_BASE_URL")),
                githubProblems.toString());

        assertEquals(List.of(), errorPaths(jira("https://x.atlassian.net/", "{\"projects\": [\"PROJ\"]}")));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl app -am test -Dtest=ConfigItemValidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL in `jiraProjectKeysMustBeUppercaseProjectKeys`, `jiraExtraFieldsAreOptionalNonBlankStrings`
and `baseUrlMustStartWithHttpOrHttps`.

- [ ] **Step 3: Implement**

Add the patterns next to `GITHUB_REPOSITORY`:

```java
    private static final Pattern JIRA_PROJECT_KEY = Pattern.compile("^[A-Z][A-Z0-9_]+$");
    private static final Pattern HTTP_BASE_URL = Pattern.compile("^https?://\\S+$");
```

In `validateEventSource`, replace the Jira branch:

```java
        } else if ("jira".equals(sourceType)) {
            validateStringList(configuration.path("projects"), "configuration.projects", JIRA_PROJECT_KEY,
                    "a Jira project key matching ^[A-Z][A-Z0-9_]+$ (e.g. PROJ)", problems);
            validateExtraFields(configuration.path("extraFields"), problems);
        }
```

and replace the `baseUrl` block:

```java
        String baseUrl = item.path("baseUrl").asText("");
        if (!baseUrl.isBlank() && !HTTP_BASE_URL.matcher(baseUrl.trim()).matches()) {
            problems.add(ConfigProblem.error("baseUrl", "INVALID_BASE_URL",
                    "'baseUrl' must start with http:// or https://, got: '" + baseUrl + "'."));
        } else if ("github".equals(sourceType) && !baseUrl.isBlank()) {
            try {
                GitHubUrls.resolve(baseUrl);
            } catch (IllegalArgumentException e) {
                problems.add(ConfigProblem.error("baseUrl", "INVALID_BASE_URL", e.getMessage()
                        + ". Use the GitHub server's URL, e.g. https://github.com or https://github.example.com."));
            }
        }
```

Add the helper next to `validateStringList`:

```java
    /**
     * Validates the optional Jira {@code configuration.extraFields}: when present, a list of non-blank field
     * names.
     */
    private static void validateExtraFields(JsonNode extraFields, List<ConfigProblem> problems) {
        if (extraFields.isMissingNode() || extraFields.isNull()) {
            return;
        }
        if (!extraFields.isArray()) {
            problems.add(ConfigProblem.error("configuration.extraFields", "INVALID_CONFIGURATION",
                    "'configuration.extraFields' must be an array of Jira field names."));
            return;
        }
        for (int i = 0; i < extraFields.size(); i++) {
            JsonNode entry = extraFields.get(i);
            if (!entry.isTextual() || entry.asText().isBlank()) {
                problems.add(ConfigProblem.error("configuration.extraFields[" + i + "]", "INVALID_CONFIGURATION",
                        "Entry must be a non-blank Jira field name, got: " + entry + "."));
            }
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl app -am test -Dtest='ConfigItemValidationServiceTest,ValidationContextFactoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemValidationService.java app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java
git commit -m "fix(events): validate Jira project keys, extraFields and base URL schemes"
```

---

### Task 9: REST — 400 with `Error` body and trailing-slash-free `baseUrl`

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/BaseUrls.java`
- Create: `app/src/test/java/io/apitomy/axiom/app/validation/BaseUrlsTest.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java:106-150,282`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java:934,989`
- Modify: `app/src/test/java/io/apitomy/axiom/app/EventSourcesResourceTest.java`

**Interfaces:**
- Consumes: generated bean `io.apitomy.axiom.api.beans.Error` (`setMessage(String)`, `setErrorCode(Integer)`);
  validation from Task 8.
- Produces: `public final class BaseUrls` with `public static String normalize(String url)` (trim, strip
  trailing `/`, null-safe).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/io/apitomy/axiom/app/validation/BaseUrlsTest.java`:

```java
package io.apitomy.axiom.app.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link BaseUrls}.
 */
class BaseUrlsTest {

    @Test
    void trailingSlashesAndWhitespaceAreRemoved() {
        assertEquals("https://x.atlassian.net", BaseUrls.normalize("https://x.atlassian.net/"));
        assertEquals("https://x.atlassian.net", BaseUrls.normalize(" https://x.atlassian.net// "));
        assertEquals("https://github.com", BaseUrls.normalize("https://github.com"));
        assertNull(BaseUrls.normalize(null));
    }
}
```

Add to `EventSourcesResourceTest.java`:

```java
    @Test
    void testInvalidJiraProjectKeyReturns400WithErrorBody() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "jira-bad-key",
                    "name": "Bad Jira",
                    "sourceType": "jira",
                    "enabled": false,
                    "baseUrl": "https://x.atlassian.net",
                    "configuration": { "projects": ["bad key"] }
                }
                """)
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", containsString("Jira project key"))
                .body("errorCode", equalTo(400));
    }

    @Test
    void testReservedIdReturns400WithErrorBody() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "health",
                    "name": "Reserved",
                    "sourceType": "github",
                    "baseUrl": "https://api.github.com",
                    "configuration": { "repositories": ["o/r"] }
                }
                """)
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("message", containsString("reserved"));
    }

    @Test
    void testBaseUrlTrailingSlashIsStripped() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "jira-trailing-slash",
                    "name": "Jira",
                    "sourceType": "jira",
                    "enabled": false,
                    "baseUrl": "https://x.atlassian.net/",
                    "configuration": { "projects": ["PROJ"], "extraFields": ["Story Points"] }
                }
                """)
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(200)
                .body("baseUrl", equalTo("https://x.atlassian.net"))
                .body("configuration.extraFields", hasItem("Story Points"));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `mvn -q -pl app -am test -Dtest='BaseUrlsTest,EventSourcesResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAIL for `BaseUrls`; after Step 3's first file only, the REST tests still FAIL
(400 without JSON body; `baseUrl` ends with `/`).

- [ ] **Step 3: Implement**

`app/src/main/java/io/apitomy/axiom/app/validation/BaseUrls.java`:

```java
package io.apitomy.axiom.app.validation;

/**
 * Normalizes event-source base URLs before they are stored.
 */
public final class BaseUrls {

    private BaseUrls() {
    }

    /**
     * Trims whitespace and strips trailing slashes, so URLs can be joined with absolute API paths.
     *
     * @param url the URL as entered, may be null
     * @return the normalized URL, or null
     */
    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
```

In `EventSourcesResourceImpl.java` add imports:

```java
import io.apitomy.axiom.api.beans.Error;
import io.apitomy.axiom.app.validation.BaseUrls;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
```

Add the helper below `validate(...)`:

```java
    /**
     * Builds a 400 exception whose body follows the OpenAPI {@code Error} schema.
     *
     * @param message the error message
     * @return the exception to throw
     */
    private static WebApplicationException badRequest(String message) {
        Error body = new Error();
        body.setMessage(message);
        body.setErrorCode(400);
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON).entity(body).build());
    }
```

Replace the three 400 throws:

```java
        if (slug != null && slug.length() > SLUG_MAX_LENGTH) {
            throw badRequest(INVALID_ID_MESSAGE);
        }
        if (slug != null && RESERVED_ID_HEALTH.equalsIgnoreCase(slug.trim())) {
            throw badRequest(
                    "Invalid event source id: 'health' is reserved (it is used by /event-sources/health)");
        }
```

```java
        problems.stream().filter(ConfigProblem::isError).findFirst().ifPresent(p -> {
            String message = "id".equals(p.path()) ? INVALID_ID_MESSAGE : p.message();
            throw badRequest(message);
        });
```

In `applyFields` replace `entity.baseUrl = data.getBaseUrl();` with:

```java
        entity.baseUrl = BaseUrls.normalize(data.getBaseUrl());
```

In `ImportExportService.java` (both event-source import sites, lines ~934 and ~989) replace
`entity.baseUrl = item.path("baseUrl").asText("");` with:

```java
            entity.baseUrl = BaseUrls.normalize(item.path("baseUrl").asText(""));
```

and add `import io.apitomy.axiom.app.validation.BaseUrls;`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q -pl app -am test -Dtest='BaseUrlsTest,EventSourcesResourceTest,ConfigItemValidationServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (existing `statusCode(400)` assertions still hold).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/validation/BaseUrls.java app/src/test/java/io/apitomy/axiom/app/validation/BaseUrlsTest.java app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java app/src/main/java/io/apitomy/axiom/app/ImportExportService.java app/src/test/java/io/apitomy/axiom/app/EventSourcesResourceTest.java
git commit -m "fix(events): return Error bodies for invalid event sources and store base URLs without trailing slash"
```

---

### Task 10: `tools/jira-groundtruth.py`

A cleaned-up version of the spike script (`/tmp/axiom-test/groundtruth.py`). It reads raw Jira activity for a
project (or one issue), derives the `sourceEventId`s Axiom should have stored using the same rules as the
normalizer, compares them with the events stored for an event source, runs the spike's field-quality checks,
and reports schema-validation results from `StreamEvent.validationErrors`.

**Files:**
- Create: `tools/jira-groundtruth.py`

**Interfaces:**
- Consumes: Jira REST v3 (`/search/jql`, `/issue/{key}/changelog`, `/issue/{key}/comment`); Axiom
  `GET {axiom}/api/v1/stream/events?filterEventSourceId=…&limit=500&page=N` returning `{items: StreamEvent[]}`
  with optional `validationErrors`.
- Produces: CLI
  `python3 tools/jira-groundtruth.py AXIOM_URL --jira-url URL --project KEY --source ID --since ISO
  [--issue KEY] [--extra-field NAME ...]`; exit code 0 when nothing is missing and no event failed
  validation, 1 otherwise. Credentials: `JIRA_EMAIL`, `JIRA_API_TOKEN`.

- [ ] **Step 1: Write the script with its doctests**

`tools/jira-groundtruth.py`:

```python
#!/usr/bin/env python3
"""Compare raw Jira activity with the events Axiom ingested for a Jira event source.

Usage:
    JIRA_EMAIL=me@example.com JIRA_API_TOKEN=... \\
        python3 tools/jira-groundtruth.py http://localhost:9590 \\
        --jira-url https://myorg.atlassian.net --project PROJ --source jira-test \\
        --since 2026-10-21T14:00:00Z [--issue PROJ-1] [--extra-field "Story Points"]

--since must be the event source's baseline (first-poll) time: Axiom does not ingest earlier activity.
Exit code 0 when no expected event is missing and no stored event failed schema validation, 1 otherwise.
"""
import argparse
import base64
import collections
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

UPDATED_FIELDS = {'summary', 'description', 'priority', 'resolution', 'duedate', 'fix version', 'component'}
DONE_NAMES = {'done', 'closed', 'resolved'}
ISO_SUFFIX = 'Z'


def parse_ts(value):
    """Parse a Jira or ISO timestamp into an aware datetime.

    >>> parse_ts('2026-10-21T14:53:07.835+0000').isoformat()
    '2026-10-21T14:53:07.835000+00:00'
    >>> parse_ts('2026-10-21T14:53:07Z').isoformat()
    '2026-10-21T14:53:07+00:00'
    """
    if not value:
        return None
    if value.endswith('Z'):
        value = value[:-1] + '+0000'
    for fmt in ('%Y-%m-%dT%H:%M:%S.%f%z', '%Y-%m-%dT%H:%M:%S%z'):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            pass
    raise ValueError(f'Unparseable timestamp: {value}')


def epoch_millis(value):
    """Epoch milliseconds of a Jira timestamp.

    >>> epoch_millis('2026-10-21T14:53:56.781+0000')
    1792594436781
    """
    dt = parse_ts(value)
    return int(dt.timestamp() * 1000)


def item_event_count(item, extra_fields):
    """Number of events the normalizer emits for one changelog item.

    >>> item_event_count({'field': 'labels', 'fromString': 'a', 'toString': 'a b c'}, set())
    2
    >>> item_event_count({'field': 'Comment', 'fromString': 'gone', 'toString': None}, set())
    1
    >>> item_event_count({'field': 'Story Points', 'toString': '5'}, {'story points'})
    1
    >>> item_event_count({'field': 'Intelligence Requested', 'toString': 'x'}, set())
    0
    """
    field = (item.get('field') or '').lower()
    if field in ('status', 'assignee') or field in UPDATED_FIELDS or field in extra_fields:
        return 1
    if field == 'labels':
        before = set((item.get('fromString') or '').split())
        after = set((item.get('toString') or '').split())
        return len(after - before) + len(before - after)
    if field == 'comment':
        return 1 if item.get('fromString') is not None and item.get('toString') is None else 0
    return 0


def expected_history_ids(key, history, extra_fields):
    """sourceEventIds expected for one changelog history entry.

    >>> h = {'id': '9', 'items': [{'field': 'resolution', 'toString': 'Done'}, {'field': 'status'}]}
    >>> expected_history_ids('P-1', h, set())
    ['P-1-9-0', 'P-1-9-1']
    >>> expected_history_ids('P-1', {'id': '8', 'items': [{'field': 'summary'}]}, set())
    ['P-1-8']
    >>> expected_history_ids('P-1', {'id': '7', 'items': [{'field': 'Rank'}]}, set())
    []
    """
    count = sum(item_event_count(item, extra_fields) for item in history.get('items', []))
    if count == 1:
        return [f'{key}-{history["id"]}']
    return [f'{key}-{history["id"]}-{i}' for i in range(count)]


class Jira:
    """Minimal Jira Cloud REST v3 client with retries."""

    def __init__(self, base_url, email, token):
        self.base = base_url.rstrip('/') + '/rest/api/3'
        self.auth = 'Basic ' + base64.b64encode(f'{email}:{token}'.encode()).decode()

    def request(self, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers={
            'Authorization': self.auth, 'Content-Type': 'application/json', 'Accept': 'application/json'})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=60) as resp:
                    return json.load(resp)
            except (ConnectionResetError, TimeoutError, urllib.error.URLError) as e:
                if isinstance(e, urllib.error.HTTPError) and e.code not in (429, 500, 502, 503, 504):
                    raise
                time.sleep(2 + attempt * 3)
        raise RuntimeError(f'Jira request failed repeatedly: {path}')

    def issues(self, project, since, issue_key=None):
        jql = (f'key = "{issue_key}"' if issue_key else f'project = "{project}"')
        jql += f' AND updated >= {int(since.timestamp() * 1000)} ORDER BY updated ASC'
        out, token = [], None
        while True:
            body = {'jql': jql, 'maxResults': 100, 'fields': ['created', 'status', 'project', 'resolutiondate']}
            if token:
                body['nextPageToken'] = token
            page = self.request('/search/jql', body)
            out += page.get('issues', [])
            token = page.get('nextPageToken')
            if page.get('isLast', True) or not token:
                return out

    def paged(self, path, key):
        values, start = [], 0
        while True:
            sep = '&' if '?' in path else '?'
            page = self.request(f'{path}{sep}startAt={start}&maxResults=100')
            batch = page.get(key, [])
            values += batch
            start += len(batch)
            if not batch or start >= page.get('total', 0):
                return values


def axiom_events(axiom_url, source):
    events, page = [], 1
    while True:
        query = urllib.parse.urlencode({'filterEventSourceId': source, 'limit': 500, 'page': page})
        url = f'{axiom_url.rstrip("/")}/api/v1/stream/events?{query}'
        with urllib.request.urlopen(url, timeout=60) as resp:
            items = json.load(resp).get('items', [])
        events += items
        if len(items) < 500:
            return events
        page += 1


def is_iso(value):
    return value is None or (isinstance(value, str) and value.endswith(ISO_SUFFIX) and 'T' in value)


def quality(events):
    """Field-quality checks from the ingestion spike; every counter except 'events'/'type:*' should be 0."""
    q = collections.Counter()
    for e in events:
        payload = e.get('payload') or {}
        issue = payload.get('issue') or {}
        source_data = e.get('sourceData') or {}
        q['events'] += 1
        q[f'type:{e["type"]}'] += 1
        if 'project' not in source_data:
            q['missing_sourceData.project'] += 1
        if 'status' not in source_data:
            q['missing_sourceData.status'] += 1
        if issue.get('state') == 'closed' and not issue.get('closedAt'):
            q['closed_state_without_closedAt'] += 1
        if e['type'] == 'issue.closed' and issue.get('state') != 'closed':
            q['closed_event_but_snapshot_open'] += 1
        if e['type'] == 'issue.reopened' and issue.get('state') != 'open':
            q['reopened_event_but_snapshot_closed'] += 1
        if e['type'] in ('issue.assigned', 'issue.unassigned') and not (payload.get('assignee') or {}).get('login'):
            q['assign_event_assignee_no_login'] += 1
        if e.get('actor') is None:
            q['null_actor'] += 1
        for field in ('createdAt', 'updatedAt', 'closedAt'):
            if not is_iso(issue.get(field)):
                q[f'non_iso_issue.{field}'] += 1
            if not is_iso((payload.get('comment') or {}).get(field)):
                q[f'non_iso_comment.{field}'] += 1
        if not is_iso(e.get('timestamp')):
            q['non_iso_timestamp'] += 1
        if issue and not issue.get('title'):
            q['missing_title'] += 1
    return q


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('axiom_url', help='Axiom base URL, e.g. http://localhost:9590')
    parser.add_argument('--jira-url', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--source', required=True, help='Axiom event source id')
    parser.add_argument('--since', required=True, help='baseline of the event source, ISO-8601')
    parser.add_argument('--issue', help='limit the comparison to one issue key')
    parser.add_argument('--extra-field', action='append', default=[], help='configured extraFields name')
    args = parser.parse_args(argv)

    email, token = os.environ.get('JIRA_EMAIL'), os.environ.get('JIRA_API_TOKEN')
    if not email or not token:
        parser.error('JIRA_EMAIL and JIRA_API_TOKEN must be set')
    since = parse_ts(args.since).astimezone(timezone.utc)
    extra = {name.lower() for name in args.extra_field}
    jira = Jira(args.jira_url, email, token)

    events = axiom_events(args.axiom_url, args.source)
    if args.issue:
        events = [e for e in events if e.get('ref', '').endswith('/browse/' + args.issue)]
    stored = {e['sourceEventId'] for e in events}

    expected, ignored_fields = set(), collections.Counter()
    for issue in jira.issues(args.project, since, args.issue):
        key = issue['key']
        if parse_ts(issue['fields']['created']) > since:
            expected.add(f'{key}-created')
        for history in jira.paged(f'/issue/{key}/changelog', 'values'):
            if parse_ts(history['created']) <= since:
                continue
            expected.update(expected_history_ids(key, history, extra))
            for item in history.get('items', []):
                if item_event_count(item, extra) == 0 and (item.get('field') or '').lower() != 'comment':
                    ignored_fields[item.get('field')] += 1
        for comment in jira.paged(f'/issue/{key}/comment?orderBy=created', 'comments'):
            created, updated = parse_ts(comment['created']), parse_ts(comment['updated'])
            if created > since:
                expected.add(f'{key}-comment-{comment["id"]}')
            if updated > created and updated > since:
                expected.add(f'{key}-comment-{comment["id"]}-updated-{epoch_millis(comment["updated"])}')

    missing = sorted(expected - stored)
    unexpected = sorted(stored - expected)
    invalid = [e for e in events if e.get('validationErrors')]
    messages = collections.Counter(m for e in invalid for m in e['validationErrors'])

    print(f'===== {args.project}{" / " + args.issue if args.issue else ""} (source {args.source})')
    print(f'expected events: {len(expected)}, stored events: {len(stored)}')
    print(f'missing ({len(missing)}):', missing[:50])
    print(f'unexpected ({len(unexpected)}):', unexpected[:50])
    print('ignored changelog fields:', ignored_fields.most_common(25))
    print('quality:', json.dumps(dict(sorted(quality(events).items())), indent=1))
    print(f'schema validation: {len(events) - len(invalid)} valid, {len(invalid)} invalid')
    for message, count in messages.most_common(20):
        print(f'  {count:5d}  {message}')
    return 0 if not missing and not invalid else 1


if __name__ == '__main__':
    sys.exit(main())
```

- [ ] **Step 2: Run the doctests and a syntax/help check**

Run: `python3 -m doctest -v tools/jira-groundtruth.py 2>&1 | tail -3 && python3 tools/jira-groundtruth.py --help | head -5`
Expected: `Test passed.` (0 failures) and the usage text. (`python3 -m doctest` imports the file
by path; the hyphen in the name is fine for this invocation.)

- [ ] **Step 3: Commit**

```bash
chmod +x tools/jira-groundtruth.py
git add tools/jira-groundtruth.py
git commit -m "chore(tools): add Jira ground-truth comparison script"
```

---

### Task 11: Developer documentation

Plan 1 documents the schema registry, file layout and runtime validation; this task documents the Jira side
(issue-state rebuild and its limits, `sourceEventId` formats, `extraFields`, status-category rules, Jira
`sourceData`, configuration rules, upgrade notes). The user-guide items of the spec's Documentation section
belong to Plan 3 (UI).

**Files:**
- Modify: `docs/developer-guide/event-sourcing-design.md` (the "Jira event source" bullet list near line 41
  and the whole `## Jira` section at the end, from line 483)

**Interfaces:** none.

- [ ] **Step 1: Update the Jira event-source bullets**

Replace the "**Jira event source:**" bullet list with:

```markdown
**Jira event source:**
- Uses JQL search with changelog expansion (`expand=changelog`)
- Single query covers all configured projects via a `project in ("A", "B")` clause (keys are quoted)
- Field-level change detection from changelog entries; every event carries the issue state as of that event
- ADF-to-plain-text conversion for description and comment bodies
- Configuration: `baseUrl` (e.g., `https://myorg.atlassian.net`; must start with `http://` or `https://`,
  a trailing `/` is removed when saved), `secretName`, `projects` (list of project keys matching
  `^[A-Z][A-Z0-9_]+$`), optional `extraFields` (list of Jira field names), `pollInterval`
```

- [ ] **Step 2: Replace the `## Jira` section**

Replace everything from `## Jira` to the end of the file with:

````markdown
## Jira

**Event detection:** JQL search with `expand=changelog`, ordered by `updated`. The search requests the fields
used for snapshots (`summary`, `description`, `status`, `assignee`, `reporter`, `labels`, `priority`,
`resolution`, `resolutiondate`, `duedate`, `fixVersions`, `components`, `issuetype`, `project`, `comment`,
`created`, `updated`, `parent`) plus the IDs of the configured `extraFields`. Truncated changelogs are
completed with `GET /rest/api/3/issue/{key}/changelog`; incomplete comment lists (`total` greater than the
number returned) with `GET /rest/api/3/issue/{key}/comment`. A 404/403 on either falls back to the embedded
data; 5xx, 429 and network errors abort the poll window without advancing the checkpoint.

**Per-poll lookups:** once per event source and poll, `GET /rest/api/3/status` builds a status ID →
category key map, and (only when `extraFields` is configured) `GET /rest/api/3/field` resolves field names to
IDs, ignoring case. Unknown `extraFields` names are ignored with a warning. If the status lookup fails, a
warning is logged and status names `done`, `closed` and `resolved` are treated as done.

**Issue state as of each event:** `JiraIssueHistory` sorts the changelog newest first, starts from the current
issue, and for each entry records the state after that entry and then undoes the entry's items (`to` →
`from`). What remains is the state at creation, used by `issue.created`. Comment events use the state in
effect at the comment's timestamp. Tracked: status (name, ID, category → `state`/`stateDetail`), summary,
description, assignee, labels, resolution and `closedAt`, priority, due date, fix versions, components and
every configured extra field. `updatedAt` of a snapshot is the timestamp of the entry that produced it
(`created` for the creation state). `closedAt` is the time of the most recent resolution change to a
non-null value at or before the snapshot, and null while the resolution is empty.

Limits:
- Fields without changelog history keep their current values in every snapshot.
- A rebuilt description is the changelog's plain/wiki text; the current one is converted from ADF.
- Comment bodies are always the current text (Jira keeps no comment history).
- Extra-field values from the changelog are Jira's display strings (`toString`); current values are rendered
  from the field JSON (`value`, `name`, `displayName` or `key` of objects, arrays joined with `, `, integral
  numbers without a fraction).

**Open/closed:** `state` is `closed` exactly when the status category key is `done`. A status change into
the `done` category emits `issue.closed`, out of it `issue.reopened`, anything else `issue.updated`.

**Changelog classification:**

| Changelog `field` (case-insensitive) | Event Type |
|-------|------------|
| `status` into the done category | `issue.closed` |
| `status` out of the done category | `issue.reopened` |
| other `status` changes | `issue.updated` with `change` |
| `summary`, `description`, `priority`, `resolution`, `duedate`, `Fix Version`, `Component` | `issue.updated` with `change` |
| a name listed in `configuration.extraFields` | `issue.updated` with `change` |
| `assignee` set | `issue.assigned` (`assignee.login` = account ID from `to`) |
| `assignee` cleared | `issue.unassigned` (`assignee.login` = account ID from `from`) |
| `labels` added | `issue.labeled` (one per label) |
| `labels` removed | `issue.unlabeled` (one per label) |
| `Comment` with `fromString` and no `toString` | `issue.comment.deleted` (comment `id` and `author` null, `body` = deleted text; actor = history author) |

**`sourceEventId` formats:**

| Trigger | `sourceEventId` |
|---|---|
| Issue created | `{KEY}-created` |
| Changelog entry producing one event | `{KEY}-{historyId}` |
| Changelog entry producing several events | `{KEY}-{historyId}-{i}` (item order) |
| Comment created | `{KEY}-comment-{commentId}` |
| Comment edited | `{KEY}-comment-{commentId}-updated-{updatedEpochMillis}` (one per edit) |

**Timestamps:** every timestamp in the payload (`createdAt`, `updatedAt`, `closedAt`, comment timestamps) and
the event `timestamp` are ISO-8601 UTC ending in `Z`.

**`sourceData`** (a selected set, not validated by the event schemas; values follow the snapshot of the
event where history exists):

```json
{
  "jiraId": "7358053",
  "project": {"key": "APICURIO", "name": "Apicurio"},
  "issueType": {"name": "Task"},
  "status": {"name": "Closed", "categoryKey": "done"},
  "priority": {"name": "Major"},
  "resolution": {"name": "Done"},
  "dueDate": "2026-10-30",
  "components": [{"name": "core"}],
  "fixVersions": [{"name": "1.2.0"}],
  "extraFields": {"Story Points": "5"}
}
```

`extraFields` is present only when configured; a configured field with no value is `null`.

**`ref` construction:** `{baseUrl}/browse/{issueKey}`

**Upgrade notes:**
- Existing events and IDs are kept.
- A comment edit that already has an old-format `{KEY}-comment-{id}-updated` event gets one more event under
  the new format after the upgrade (accepted one-time effect).
- Changelog entries that now produce more events (for example a status change plus a resolution change)
  use `-{i}` suffixes; such an entry re-read in the first poll window after the upgrade is stored once more
  under the new IDs (accepted one-time effect, limited to the two-minute query overlap).

**Verification tool:** `tools/jira-groundtruth.py` compares raw Jira activity with the stored events and
reports field-quality problems and schema-validation results (see the script's `--help`).
````

- [ ] **Step 3: Check line lengths**

Run: `awk 'length > 110 && !/^\|/ && !/^```/ {print FILENAME": "FNR": "length}' docs/developer-guide/event-sourcing-design.md`
Expected: no output for the lines you added (tables and code are exempt; pre-existing long lines elsewhere
may be left alone).

- [ ] **Step 4: Commit**

```bash
git add docs/developer-guide/event-sourcing-design.md
git commit -m "docs: document Jira issue-state rebuild, event IDs, extraFields and sourceData"
```

---

### Task 12: Full build and live verification on an isolated instance

**Files:** none (verification only).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Full build**

Run: `./build.sh`
Expected: `BUILD SUCCESS`, no test failures.

- [ ] **Step 2: Start an isolated instance (never ports 9191/9090, never `~/.axiom`)**

```bash
rm -rf /tmp/axiom-plan2 && mkdir -p /tmp/axiom-plan2/home /tmp/axiom-plan2/data
AXIOM_HOME=/tmp/axiom-plan2/home nohup ./dev.sh --skip-ui --portOffset=500 --dataDir=/tmp/axiom-plan2/data \
    > /tmp/axiom-plan2/dev.log 2>&1 &
until curl -sf http://localhost:9590/api/v1/system/health >/dev/null; do sleep 2; done; echo up
```

Expected: `up`.

- [ ] **Step 3: Check the new validation over HTTP**

```bash
curl -s -o /dev/stderr -w '%{http_code}\n' -X POST http://localhost:9590/api/v1/event-sources \
  -H 'Content-Type: application/json' \
  -d '{"id":"bad","name":"Bad","sourceType":"jira","baseUrl":"x.atlassian.net","configuration":{"projects":["bad"]}}'
```

Expected: `400` and a JSON body `{"message":"…","errorCode":400}`.

- [ ] **Step 4: Create the secret and the event sources**

```bash
curl -sf -X POST http://localhost:9590/api/v1/secrets -H 'Content-Type: application/json' \
  -d "{\"name\":\"JIRA_VERIFY\",\"value\":\"$JIRA_EMAIL:$JIRA_API_TOKEN\"}"
for p in APICURIO IPT; do
  curl -sf -X POST http://localhost:9590/api/v1/event-sources -H 'Content-Type: application/json' -d "{
    \"id\":\"verify-$(echo $p | tr A-Z a-z)\",\"name\":\"Verify $p\",\"sourceType\":\"jira\",\"enabled\":true,
    \"baseUrl\":\"https://redhat.atlassian.net/\",\"secretName\":\"JIRA_VERIFY\",\"pollInterval\":60,
    \"configuration\":{\"projects\":[\"$p\"],\"extraFields\":[\"Story Points\"]}}"; echo
done
```

Expected: both responses show `"baseUrl":"https://redhat.atlassian.net"` (slash stripped). Wait for the
first poll (≤ 70 s) and note the baseline time:

```bash
curl -sf http://localhost:9590/api/v1/event-sources/verify-apicurio/logs | python3 -m json.tool | head -30
```

Expected: an entry whose message starts with the baseline prefix; its `createdOn` is `BASELINE`.

- [ ] **Step 5: Generate activity on APICURIO-137 after the baseline**

In the Jira UI on APICURIO-137 (the dedicated test issue): rename the summary, edit the description, add
labels `spike-a spike-b` then remove `spike-a`, change priority, move New → Refinement → In Progress, add a
comment and edit it twice, add a second comment and delete it, move to Closed (resolution Done), unassign,
move back to New. Wait two poll intervals (≈ 2 minutes), then check the poll logs show `success` entries.

- [ ] **Step 6: Run the ground-truth tool**

```bash
for args in "--project APICURIO --source verify-apicurio --issue APICURIO-137" \
            "--project APICURIO --source verify-apicurio" \
            "--project IPT --source verify-ipt"; do
  python3 tools/jira-groundtruth.py http://localhost:9590 --jira-url https://redhat.atlassian.net \
      --since "$BASELINE" --extra-field "Story Points" $args; echo "exit=$?"
done
```

Expected for every run: `missing (0)`, `schema validation: N valid, 0 invalid`, all quality counters other
than `events` and `type:*` absent or 0, `exit=0`. `unexpected` may list events from the two-minute overlap
before `BASELINE`; inspect them and confirm each corresponds to real Jira activity. For APICURIO-137 also
confirm `type:issue.comment.deleted` is 1, two `-updated-<millis>` comment events exist, and
`type:issue.closed`/`type:issue.reopened` are 1 each. Any failure: stop, debug with
superpowers:systematic-debugging, add a fixture-based regression test, fix, and repeat from Step 1.

- [ ] **Step 7: Stop the instance and clean up**

```bash
pkill -f 'portOffset=500' ; sleep 3
curl -sf http://localhost:9590/api/v1/system/health && echo "still running" || echo stopped
rm -rf /tmp/axiom-plan2
```

Expected: `stopped`.

---

## Self-Review

Spec coverage (Plan 2 section, plus its share of Error Handling, Upgrade Notes, Documentation):

| Spec requirement | Task |
|---|---|
| Rebuild state per event (status/category, summary, description, assignee, labels, resolution/closedAt, priority, due date, fix versions, components, extraFields); algorithm, rules, `updatedAt`, `closedAt` | 3, 4 |
| Comment events use snapshot at comment timestamp; bodies current text | 4 |
| `fetchStatuses`, per-poll ID → category map; closed/reopened on category transitions; name fallback with warning | 2, 4, 6, 7 |
| Request `project`, `resolutiondate`, `status`, `duedate`, extra field IDs; `GET /field` per poll; unknown names ignored | 6, 7 |
| ISO-8601 `…Z` timestamps (Plan 1 converts; regression test here) | 4 |
| Assignee `login` from changelog `to`/`from`, `displayName` from strings | 4 |
| Comment-edit ID with epoch millis; `issue.comment.deleted`; `issue.updated` for priority/resolution/duedate/Fix Version/Component/extraFields (case-insensitive) | 5 |
| Fetch all comments when embedded list incomplete; comment fetch failure handling | 6, 7 |
| `sourceData` selected set incl. `status{name,categoryKey}`, `dueDate`, `extraFields` as of snapshot | 4, 5 |
| Project key regex, JQL quoting, `extraFields` validation, `baseUrl` scheme (Jira + GitHub), trailing slash stripped, 400 with `Error`, `resolveCredentials` Javadoc | 6, 7, 8, 9 |
| Sanitized real fixtures; every normalizer test output schema-validated; one regression test per spike defect | 1, 4, 5 |
| `tools/jira-groundtruth.py` with env credentials, Axiom URL argument, validation report; run against IPT, APICURIO, APICURIO-137 on isolated `dev.sh` | 10, 12 |
| Developer docs (rebuild and limits, IDs, extraFields, status categories, sourceData) and upgrade notes | 11 |

Spike defects → regression tests: snapshot per event (`everyEvent_carriesTheIssueStateAsOfThatEvent`,
`snapshotAfterEachHistory_matchesApicurio137GroundTruth`), repeated comment edits
(`repeatedCommentEdits_produceDistinctIds`), comment deletion (`commentDeletion_emitsIssueCommentDeleted`),
`closedAt` (`closedEvent_hasClosedSnapshotWithClosedAt`), assignee login
(`unassignedEvent_hasPreviousAssigneeLoginAndName`, extended assignee tests), timestamp format
(`allTimestamps_areIsoUtc`), `sourceData.project` (`sourceData_hasProjectStatusAndSnapshotValues`), custom
done status (`customDoneStatusName_isClosedByCategory`), extraFields (`fixVersionComponentDueDateAndExtraFields_…`,
`extraFields_sourceDataHoldsValueAsOfEachEvent`, `extraFields_matchChangelogFieldCaseInsensitively`), incomplete
comment list (`incompleteCommentList_fetchesAllComments`), project-key validation and JQL quoting
(`jiraProjectKeysMustBeUppercaseProjectKeys`, `fetchIssuesPage_quotesProjectKeysInJql`,
`projectClause_escapesQuotesAndBackslashes`), `baseUrl` normalization (`BaseUrlsTest`,
`testBaseUrlTrailingSlashIsStripped`, `stripTrailingSlash_removesTrailingSlashes`).

Type consistency checked: `JiraNormalizationContext(JiraStatusCategories, Map<String, String>)`,
`extraFieldIds()`, `extraFieldName(String)`, `JiraIssueHistory.rebuild(JsonNode, JiraNormalizationContext)`,
`after/asOf/created/current`, `fetchIssuesPage(…, List<String> extraFieldIds)`,
`buildContext(String, String, List<String>)`, `ingestWindow(…, JiraNormalizationContext)` are used with the
same names and types in every task.
