# Event Schema Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Give every Axiom event type a JSON Schema, validate every ingested event against it at runtime
(saving invalid events with their errors), expose the schemas over REST, and make event IDs unique per event
source.

**Architecture:** Hand-written schema files in `core` (`envelope.json`, `defs.json`, `payloads/<type>.json`)
are assembled at startup by `EventSchemaRegistry` into one self-contained schema per event type.
`EventStreamService.persistEvent` validates each new event and stores errors in `stream_event.validation_errors`.
A Flyway migration replaces the global unique index on `source_event_id` with one on
(`event_source_id`, `source_event_id`).

**Tech Stack:** Java 21, Quarkus 3.40, Jackson, `com.networknt:json-schema-validator` 1.5.9 (draft 2020-12),
Flyway/H2, JUnit 5, RestAssured.

**Spec:** `docs/superpowers/specs/2026-10-21-event-schemas-and-jira-ingestion-design.md` (section "Plan 1").

## Global Constraints

- REST changes are contract-first: edit `common/api/src/main/resources/openapi.json`, run `mvn install` on
  `common/api`, implement the generated interface. No `@Path` on impl classes.
- `/event-*` paths are generated into `io.apitomy.axiom.api.EventResource`, which is implemented by
  `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java`. The schema endpoints go there.
- `sourceData` is never validated (unconstrained in the schema).
- Invalid events are saved, flagged, logged; ingestion never stops because of validation.
- Schema load or assembly failure fails startup.
- The `sourceEventId` format does not change.
- Java style: 4-space indent, Javadoc on public types and methods, explicit types (no `var`), JUnit 5.
- Never test against ports 9191/9090; live checks use `./dev.sh --skip-ui --portOffset=500` with a temporary
  `AXIOM_HOME` and `--dataDir`.

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (root) | Manage `json-schema-validator` version |
| `core/pom.xml` | Depend on `json-schema-validator` |
| `core/src/main/resources/schemas/events/envelope.json` | Envelope schema |
| `core/src/main/resources/schemas/events/defs.json` | Shared `$defs` |
| `core/src/main/resources/schemas/events/payloads/*.json` | 26 payload schemas |
| `tools/generate-event-payload-schemas.py` | One-time generator for the initial payload files |
| `core/.../core/events/schema/EventSchemaRegistry.java` | Load, assemble, validate |
| `core/.../core/events/schema/EventSchemaSummary.java` | `(type, description)` record |
| `core/src/test/.../schema/EventSchemaRegistryTest.java` | Registry behaviour |
| `core/src/test/.../schema/EventSchemaDriftTest.java` | Schema/record drift guards |
| `events/github/src/test/.../ValidatingGitHubNormalizer.java` | Validates every normalizer test output |
| `events/jira/src/test/.../ValidatingJiraNormalizer.java` | Validates every normalizer test output |
| `events/jira/.../JiraEventNormalizerV2.java` | ISO `…Z` issue/comment timestamps |
| `app/src/main/resources/db/migration/V4__event_schema_validation.sql` | Column + index change |
| `core/.../entities/StreamEventEntity.java` | `validationErrors`, unique constraint |
| `events/core/.../PersistOutcome.java` | Persist result enum |
| `events/core/.../IngestTally.java` | Per-poll ingested/invalid counter |
| `events/core/.../EventStreamService.java` | Per-source dedup, validation |
| GitHub/Jira pollers + test fakes | Use `PersistOutcome`, report invalid counts |
| `common/api/src/main/resources/openapi.json` | Schema endpoints, `validationErrors` |
| `app/.../rest/EventSourcesResourceImpl.java` | Implement schema endpoints |
| `app/.../rest/StreamEventsResourceImpl.java` | Map `validationErrors` |
| `app/src/test/.../EventSchemasResourceTest.java`, `EventStreamValidationTest.java`, `EventStreamValidatorFailureTest.java` | Integration tests |
| `docs/developer-guide/event-sourcing-design.md` | Docs |

Commands used throughout (from the repo root):

- One module's tests: `mvn -q -pl core test -Dtest=EventSchemaRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
- With upstream modules built: add `-am`, e.g. `mvn -q -pl events/jira -am test -Dtest=JiraEventNormalizerV2Test -Dsurefire.failIfNoSpecifiedTests=false`
- Full build: `./build.sh`

---

### Task 1: Schema files and `EventSchemaRegistry`

**Files:**
- Modify: `pom.xml`, `core/pom.xml`
- Create: `core/src/main/resources/schemas/events/envelope.json`, `defs.json`, `payloads/*.json` (26 files)
- Create: `tools/generate-event-payload-schemas.py`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/schema/EventSchemaRegistry.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/events/schema/EventSchemaSummary.java`
- Test: `core/src/test/java/io/apitomy/axiom/core/events/schema/EventSchemaRegistryTest.java`

**Interfaces:**
- Produces: `new EventSchemaRegistry(ObjectMapper)`; `List<EventSchemaSummary> eventTypes()` (sorted by type);
  `Optional<JsonNode> schemaFor(String eventType)` (deep copy); `List<String> validate(NormalizedEvent)`;
  `List<String> validate(JsonNode eventJson)` (empty list = valid; sorted messages such as
  `$.payload.issue.state: does not have a value in the enumeration [open, closed]`).
- Produces: `record EventSchemaSummary(String type, String description)`.

- [ ] **Step 1: Add the dependency**

Root `pom.xml`, inside `<dependencyManagement><dependencies>`:

```xml
            <dependency>
                <groupId>com.networknt</groupId>
                <artifactId>json-schema-validator</artifactId>
                <version>1.5.9</version>
            </dependency>
```

`core/pom.xml`, inside `<dependencies>` (after `jackson-databind`):

```xml
        <dependency>
            <groupId>com.networknt</groupId>
            <artifactId>json-schema-validator</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing registry test**

`core/src/test/java/io/apitomy/axiom/core/events/schema/EventSchemaRegistryTest.java`:

```java
package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSchemaRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static EventSchemaRegistry registry;

    @BeforeAll
    static void load() {
        registry = new EventSchemaRegistry(MAPPER);
    }

    static NormalizedEvent closedEvent(String state, String createdAt) {
        Actor actor = new Actor("u1", "User One", null, null);
        NormalizedIssue issue = new NormalizedIssue("PROJ-1", "Title", "Body", state, "Closed", actor,
                List.of(), List.of("bug"), null, "https://jira.example/browse/PROJ-1",
                createdAt, "2026-10-21T14:54:06.982Z", null);
        return new NormalizedEvent("04f96ff3-6a2c-4766-aa84-27da50fb366f", "PROJ-1-100", "jira", "src",
                EventType.ISSUE_CLOSED, "https://jira.example/browse/PROJ-1", "2026-10-21T14:54:06Z",
                actor, new IssueClosedPayload(issue), MAPPER.createObjectNode().put("anything", 1));
    }

    @Test
    void listsEveryEventTypeSorted() {
        List<EventSchemaSummary> types = registry.eventTypes();
        assertEquals(EventType.values().length, types.size());
        assertEquals("branch.created", types.get(0).type());
        assertTrue(types.stream().allMatch(t -> !t.description().isBlank()));
    }

    @Test
    void assembledSchemaIsSelfContained() {
        JsonNode schema = registry.schemaFor("issue.closed").orElseThrow();
        assertEquals("issue.closed", schema.path("title").asText());
        assertEquals("issue.closed", schema.at("/properties/type/const").asText());
        assertEquals("issue.closed", schema.at("/properties/payload/properties/type/const").asText());
        assertTrue(schema.at("/$defs/NormalizedIssue").isObject());
    }

    @Test
    void unknownTypeHasNoSchema() {
        assertEquals(Optional.empty(), registry.schemaFor("nope"));
    }

    @Test
    void validEventHasNoErrors() {
        assertEquals(List.of(), registry.validate(closedEvent("closed", "2026-10-21T14:53:07.835Z")));
    }

    @Test
    void sourceDataIsNotValidated() {
        NormalizedEvent event = closedEvent("closed", "2026-10-21T14:53:07.835Z");
        assertEquals(List.of(), registry.validate(event));
    }

    @Test
    void reportsBadStateAndNonIsoTimestamp() {
        List<String> errors = registry.validate(closedEvent("weird", "2026-10-21T14:53:07.835+0000"));
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.payload.issue.state")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.payload.issue.createdAt")), errors.toString());
    }

    @Test
    void unknownTypeIsAnError() {
        JsonNode json = MAPPER.createObjectNode().put("type", "nope");
        List<String> errors = registry.validate(json);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("unknown event type"));
    }

    @Test
    void schemaForReturnsACopy() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) registry.schemaFor("push").orElseThrow())
                .put("title", "changed");
        assertEquals("push", registry.schemaFor("push").orElseThrow().path("title").asText());
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `mvn -q -pl core test -Dtest=EventSchemaRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `EventSchemaRegistry` not found.

- [ ] **Step 4: Write `envelope.json`**

`core/src/main/resources/schemas/events/envelope.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "sourceEventId", "source", "eventSourceId", "type", "ref", "timestamp", "payload"],
  "properties": {
    "id": {
      "type": "string",
      "description": "Axiom-assigned unique ID (UUID) of the event.",
      "examples": ["04f96ff3-6a2c-4766-aa84-27da50fb366f"]
    },
    "sourceEventId": {
      "type": "string",
      "description": "ID of the event in the source system; unique within one event source.",
      "examples": ["APICURIO-137-93654267", "45623198765"]
    },
    "source": {
      "type": "string",
      "description": "Source system that produced the event.",
      "examples": ["github", "jira"]
    },
    "eventSourceId": {
      "type": "string",
      "description": "Slug of the Axiom event source that ingested the event.",
      "examples": ["my-jira"]
    },
    "type": {
      "type": "string",
      "description": "Normalized event type."
    },
    "ref": {
      "type": "string",
      "description": "Full URL identifying the subject of the event.",
      "examples": ["https://redhat.atlassian.net/browse/APICURIO-137"]
    },
    "timestamp": {
      "$ref": "#/$defs/Timestamp",
      "description": "When the action happened in the source system (ISO-8601 UTC)."
    },
    "actor": {
      "anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}],
      "description": "Who performed the action."
    },
    "payload": {
      "description": "Replaced at assembly time with the payload schema of the event type."
    },
    "sourceData": {
      "description": "Source-specific data. Not validated; contents vary by source."
    }
  }
}
```

- [ ] **Step 5: Write `defs.json`**

`core/src/main/resources/schemas/events/defs.json`:

```json
{
  "$defs": {
    "Timestamp": {
      "type": "string",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$",
      "description": "ISO-8601 UTC timestamp ending in Z.",
      "examples": ["2026-10-21T14:53:07.835Z"]
    },
    "NullableTimestamp": {
      "anyOf": [{"$ref": "#/$defs/Timestamp"}, {"type": "null"}]
    },
    "Actor": {
      "type": "object",
      "additionalProperties": false,
      "description": "A user in the source system.",
      "properties": {
        "login": {"type": ["string", "null"], "description": "Stable user ID (GitHub login, Jira accountId).", "examples": ["octocat", "70121:6eabb14f-d93c-473b-b21c-9ad32a24b460"]},
        "displayName": {"type": ["string", "null"], "description": "Human-readable name.", "examples": ["Eric Wittmann"]},
        "avatarUrl": {"type": ["string", "null"], "description": "Avatar image URL."},
        "url": {"type": ["string", "null"], "description": "Profile URL (null for Jira)."}
      }
    },
    "Change": {
      "type": "object",
      "additionalProperties": false,
      "description": "A single field change.",
      "properties": {
        "field": {"type": ["string", "null"], "description": "Changed field.", "examples": ["status", "summary", "priority"]},
        "from": {"type": ["string", "null"], "description": "Previous value.", "examples": ["In Progress"]},
        "to": {"type": ["string", "null"], "description": "New value.", "examples": ["Closed"]},
        "author": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "Who made the change."}
      }
    },
    "CommitAuthor": {
      "type": "object",
      "additionalProperties": false,
      "description": "Git author identity.",
      "properties": {
        "name": {"type": ["string", "null"], "description": "Author name."},
        "email": {"type": ["string", "null"], "description": "Author email."}
      }
    },
    "Commit": {
      "type": "object",
      "additionalProperties": false,
      "description": "A pushed commit.",
      "properties": {
        "sha": {"type": ["string", "null"], "description": "Commit SHA."},
        "message": {"type": ["string", "null"], "description": "Commit message."},
        "author": {"anyOf": [{"$ref": "#/$defs/CommitAuthor"}, {"type": "null"}], "description": "Git author."},
        "url": {"type": ["string", "null"], "description": "Commit URL."}
      }
    },
    "NormalizedLabel": {
      "type": "object",
      "additionalProperties": false,
      "description": "A label.",
      "properties": {
        "name": {"type": ["string", "null"], "description": "Label name.", "examples": ["bug"]},
        "color": {"type": ["string", "null"], "description": "Label color (GitHub only)."},
        "description": {"type": ["string", "null"], "description": "Label description (GitHub only)."}
      }
    },
    "NormalizedComment": {
      "type": "object",
      "additionalProperties": false,
      "description": "A comment.",
      "properties": {
        "id": {"type": ["string", "null"], "description": "Comment ID (null for deleted Jira comments)."},
        "body": {"type": ["string", "null"], "description": "Comment text."},
        "author": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "Comment author."},
        "url": {"type": ["string", "null"], "description": "Comment URL."},
        "createdAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Creation time."},
        "updatedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Last edit time."}
      }
    },
    "NormalizedIssue": {
      "type": "object",
      "additionalProperties": false,
      "required": ["number", "title", "state", "url"],
      "description": "An issue, as of the event.",
      "properties": {
        "number": {"type": "string", "description": "Issue number (GitHub) or key (Jira).", "examples": ["123", "PROJ-456"]},
        "title": {"type": "string", "description": "Issue title.", "examples": ["Fix login bug"]},
        "body": {"type": ["string", "null"], "description": "Issue description as plain text."},
        "state": {"enum": ["open", "closed"], "description": "Normalized state.", "examples": ["open"]},
        "stateDetail": {"type": ["string", "null"], "description": "Source-specific state (GitHub state_reason, Jira status name).", "examples": ["In Progress"]},
        "author": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "Issue author or reporter."},
        "assignees": {"type": ["array", "null"], "items": {"$ref": "#/$defs/Actor"}, "description": "Assignees."},
        "labels": {"type": ["array", "null"], "items": {"type": "string"}, "description": "Label names.", "examples": [["bug"]]},
        "milestone": {"type": ["string", "null"], "description": "Milestone title (GitHub only)."},
        "url": {"type": "string", "description": "Issue URL."},
        "createdAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Creation time."},
        "updatedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Last update time as of the event."},
        "closedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Close (resolution) time."}
      }
    },
    "NormalizedPullRequest": {
      "type": "object",
      "additionalProperties": false,
      "required": ["number", "title", "state", "url"],
      "description": "A pull request.",
      "properties": {
        "number": {"type": "string", "description": "PR number.", "examples": ["42"]},
        "title": {"type": "string", "description": "PR title."},
        "body": {"type": ["string", "null"], "description": "PR description."},
        "state": {"enum": ["open", "closed"], "description": "Normalized state."},
        "stateDetail": {"type": ["string", "null"], "description": "Source-specific detail."},
        "author": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "PR author."},
        "assignees": {"type": ["array", "null"], "items": {"$ref": "#/$defs/Actor"}, "description": "Assignees."},
        "labels": {"type": ["array", "null"], "items": {"type": "string"}, "description": "Label names."},
        "milestone": {"type": ["string", "null"], "description": "Milestone title."},
        "url": {"type": "string", "description": "PR URL."},
        "createdAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Creation time."},
        "updatedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Last update time."},
        "closedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Close time."},
        "headBranch": {"type": ["string", "null"], "description": "Source branch.", "examples": ["feature/x"]},
        "baseBranch": {"type": ["string", "null"], "description": "Target branch.", "examples": ["main"]},
        "headSha": {"type": ["string", "null"], "description": "Head commit SHA."},
        "isDraft": {"type": ["boolean", "null"], "description": "Draft PR."},
        "isMerged": {"type": ["boolean", "null"], "description": "Merged."},
        "mergedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Merge time."},
        "mergedBy": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "Who merged."},
        "additions": {"type": ["integer", "null"], "description": "Lines added."},
        "deletions": {"type": ["integer", "null"], "description": "Lines deleted."},
        "changedFiles": {"type": ["integer", "null"], "description": "Files changed."}
      }
    },
    "NormalizedReview": {
      "type": "object",
      "additionalProperties": false,
      "description": "A PR review.",
      "properties": {
        "id": {"type": ["string", "null"], "description": "Review ID."},
        "state": {"type": ["string", "null"], "description": "Review state.", "examples": ["approved", "changes_requested"]},
        "body": {"type": ["string", "null"], "description": "Review text."},
        "author": {"anyOf": [{"$ref": "#/$defs/Actor"}, {"type": "null"}], "description": "Reviewer."},
        "url": {"type": ["string", "null"], "description": "Review URL."},
        "submittedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Submission time."}
      }
    }
  }
}
```

- [ ] **Step 6: Generate the 26 payload files once**

The payload files are the source of truth and are maintained by hand afterwards; this script only creates the
initial set. Create `tools/generate-event-payload-schemas.py`:

```python
#!/usr/bin/env python3
"""One-time generator for core/src/main/resources/schemas/events/payloads/*.json.

The generated files are hand-maintained afterwards. Re-running overwrites them, so only use it to bootstrap.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "core/src/main/resources/schemas/events/payloads"


def ref(name, desc):
    return {"anyOf": [{"$ref": f"#/$defs/{name}"}, {"type": "null"}], "description": desc}


def s(desc, examples=None):
    d = {"type": ["string", "null"], "description": desc}
    if examples:
        d["examples"] = examples
    return d


ISSUE = {"$ref": "#/$defs/NormalizedIssue", "description": "The issue, as of this event."}
PR = {"$ref": "#/$defs/NormalizedPullRequest", "description": "The pull request, as of this event."}

TYPES = {
    "issue.created": ("An issue was created.", {"issue": ISSUE}, ["issue"]),
    "issue.updated": ("An issue field changed.", {"issue": ISSUE, "change": ref("Change", "The field change.")}, ["issue"]),
    "issue.closed": ("An issue was closed.", {"issue": ISSUE}, ["issue"]),
    "issue.reopened": ("A closed issue was reopened.", {"issue": ISSUE}, ["issue"]),
    "issue.assigned": ("A user was assigned to an issue.", {"issue": ISSUE, "assignee": ref("Actor", "The assigned user.")}, ["issue"]),
    "issue.unassigned": ("A user was unassigned from an issue.", {"issue": ISSUE, "assignee": ref("Actor", "The unassigned user.")}, ["issue"]),
    "issue.labeled": ("A label was added to an issue.", {"issue": ISSUE, "label": ref("NormalizedLabel", "The added label.")}, ["issue"]),
    "issue.unlabeled": ("A label was removed from an issue.", {"issue": ISSUE, "label": ref("NormalizedLabel", "The removed label.")}, ["issue"]),
    "issue.comment.created": ("A comment was added to an issue.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The comment.")}, ["issue"]),
    "issue.comment.updated": ("An issue comment was edited.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The comment after the edit.")}, ["issue"]),
    "issue.comment.deleted": ("An issue comment was deleted.", {"issue": ISSUE, "comment": ref("NormalizedComment", "The deleted comment.")}, ["issue"]),
    "pr.created": ("A pull request was opened.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.closed": ("A pull request was closed without merging.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.merged": ("A pull request was merged.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.reopened": ("A pull request was reopened.", {"pullRequest": PR}, ["pullRequest"]),
    "pr.review_requested": ("A review was requested on a pull request.", {"pullRequest": PR, "requestedReviewer": ref("Actor", "The requested reviewer.")}, ["pullRequest"]),
    "pr.review.submitted": ("A pull request review was submitted.", {"pullRequest": PR, "review": ref("NormalizedReview", "The review.")}, ["pullRequest"]),
    "pr.comment.created": ("A comment was added to a pull request.", {"pullRequest": PR, "comment": ref("NormalizedComment", "The comment.")}, ["pullRequest"]),
    "pr.labeled": ("A label was added to a pull request.", {"pullRequest": PR, "label": ref("NormalizedLabel", "The added label.")}, ["pullRequest"]),
    "pr.unlabeled": ("A label was removed from a pull request.", {"pullRequest": PR, "label": ref("NormalizedLabel", "The removed label.")}, ["pullRequest"]),
    "pr.synchronize": ("New commits were pushed to a pull request.", {"pullRequest": PR, "before": s("Head SHA before the push."), "after": s("Head SHA after the push.")}, ["pullRequest"]),
    "push": ("Commits were pushed to a branch.", {
        "ref": s("Full Git ref.", ["refs/heads/main"]), "branch": s("Branch name.", ["main"]),
        "beforeSha": s("SHA before the push."), "afterSha": s("SHA after the push."),
        "forced": {"type": ["boolean", "null"], "description": "Force push."},
        "commits": {"type": ["array", "null"], "items": {"$ref": "#/$defs/Commit"}, "description": "Pushed commits."}}, []),
    "branch.created": ("A branch was created.", {"ref": s("Branch name.", ["feature/x"]), "defaultBranch": s("Repository default branch.", ["main"])}, []),
    "branch.deleted": ("A branch was deleted.", {"ref": s("Branch name.", ["feature/x"])}, []),
    "tag.created": ("A tag was created.", {"ref": s("Tag name.", ["v1.2.0"]), "defaultBranch": s("Repository default branch.", ["main"])}, []),
    "release.published": ("A release was published.", {
        "tagName": s("Release tag.", ["v1.2.0"]), "name": s("Release name."), "body": s("Release notes."),
        "isDraft": {"type": ["boolean", "null"], "description": "Draft release."},
        "isPrerelease": {"type": ["boolean", "null"], "description": "Pre-release."},
        "author": ref("Actor", "Release author."), "url": s("Release URL."),
        "createdAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Creation time."},
        "publishedAt": {"$ref": "#/$defs/NullableTimestamp", "description": "Publication time."}}, []),
}

for event_type, (description, props, required) in TYPES.items():
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "description": description,
        "type": "object",
        "additionalProperties": False,
        "required": ["type"] + required,
        "properties": {"type": {"const": event_type, "description": "Payload type (same as the event type)."}, **props},
    }
    (OUT / f"{event_type}.json").write_text(json.dumps(schema, indent=2) + "\n")
print(f"wrote {len(TYPES)} files to {OUT}")
```

Run: `mkdir -p core/src/main/resources/schemas/events/payloads && python3 tools/generate-event-payload-schemas.py`
Expected: `wrote 26 files to …/payloads`

- [ ] **Step 7: Write `EventSchemaSummary`**

```java
package io.apitomy.axiom.core.events.schema;

/**
 * Summary of one event type's schema.
 *
 * @param type        the event type, e.g. {@code issue.closed}
 * @param description human-readable description of the event type
 */
public record EventSchemaSummary(String type, String description) {
}
```

- [ ] **Step 8: Write `EventSchemaRegistry`**

```java
package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Loads the hand-written event JSON Schemas from {@code schemas/events/} on the classpath, assembles one
 * self-contained schema per {@link EventType}, and validates normalized events against them.
 * Fails at construction (and therefore at startup) if any schema is missing or invalid.
 */
@ApplicationScoped
@Startup
public class EventSchemaRegistry {

    static final String BASE = "schemas/events/";

    private ObjectMapper objectMapper;
    private Map<String, ObjectNode> assembled;
    private Map<String, JsonSchema> compiled;

    /**
     * Loads and compiles every event schema.
     *
     * @param objectMapper mapper used to serialize events before validation
     * @throws IllegalStateException if a schema file is missing, unreadable or invalid
     */
    @Inject
    public EventSchemaRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.assembled = new TreeMap<>();
        this.compiled = new HashMap<>();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        ObjectNode envelope = load("envelope.json");
        JsonNode defs = load("defs.json").path("$defs");
        for (EventType type : EventType.values()) {
            String name = type.value();
            ObjectNode schema = assemble(envelope, defs, load("payloads/" + name + ".json"), name);
            try {
                JsonSchema jsonSchema = factory.getSchema(schema);
                jsonSchema.initializeValidators();
                compiled.put(name, jsonSchema);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid event schema for " + name + ": " + e.getMessage(), e);
            }
            assembled.put(name, schema);
        }
    }

    /**
     * Lists every event type that has a schema.
     *
     * @return summaries sorted by event type
     */
    public List<EventSchemaSummary> eventTypes() {
        List<EventSchemaSummary> result = new ArrayList<>();
        assembled.forEach((type, schema) ->
                result.add(new EventSchemaSummary(type, schema.path("description").asText(""))));
        return result;
    }

    /**
     * Returns the assembled schema for an event type.
     *
     * @param eventType the event type, e.g. {@code issue.closed}
     * @return a deep copy of the schema, or empty if the type is unknown
     */
    public Optional<JsonNode> schemaFor(String eventType) {
        ObjectNode schema = assembled.get(eventType);
        return schema == null ? Optional.empty() : Optional.of(schema.deepCopy());
    }

    /**
     * Validates a normalized event.
     *
     * @param event the event
     * @return validation errors, sorted; empty if the event is valid
     */
    public List<String> validate(NormalizedEvent event) {
        return validate(objectMapper.valueToTree(event));
    }

    /**
     * Validates the JSON form of an event.
     *
     * @param eventJson the serialized event
     * @return validation errors, sorted; empty if the event is valid
     */
    public List<String> validate(JsonNode eventJson) {
        String type = eventJson.path("type").asText(null);
        JsonSchema schema = type == null ? null : compiled.get(type);
        if (schema == null) {
            return List.of("$.type: unknown event type '" + type + "'");
        }
        return schema.validate(eventJson).stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    private static ObjectNode assemble(ObjectNode envelope, JsonNode defs, ObjectNode payload, String type) {
        ObjectNode schema = envelope.deepCopy();
        schema.put("title", type);
        schema.put("description", payload.path("description").asText(""));
        schema.set("$defs", defs.deepCopy());
        ObjectNode properties = (ObjectNode) schema.get("properties");
        ((ObjectNode) properties.get("type")).put("const", type);
        ObjectNode payloadSchema = payload.deepCopy();
        payloadSchema.remove("$schema");
        properties.set("payload", payloadSchema);
        return schema;
    }

    private ObjectNode load(String path) {
        try (InputStream in = EventSchemaRegistry.class.getClassLoader().getResourceAsStream(BASE + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing event schema resource: " + BASE + path);
            }
            return (ObjectNode) objectMapper.readTree(in);
        } catch (IOException | ClassCastException e) {
            throw new IllegalStateException("Unreadable event schema resource: " + BASE + path, e);
        }
    }
}
```

Note: `load` uses `objectMapper`, so the field is assigned first in the constructor.

- [ ] **Step 9: Run the test**

Run: `mvn -q -pl core test -Dtest=EventSchemaRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (8 tests). If `reportsBadStateAndNonIsoTimestamp` fails because the message prefix differs,
print `errors` and adjust only the expected prefix (networknt 1.5.x uses `$.a.b` paths).

- [ ] **Step 10: Commit**

```bash
git add pom.xml core/pom.xml core/src/main/resources/schemas tools/generate-event-payload-schemas.py \
    core/src/main/java/io/apitomy/axiom/core/events/schema core/src/test/java/io/apitomy/axiom/core/events/schema
git commit -m "feat(events): add JSON Schemas and registry for all event types"
```

### Task 2: Drift guards, normalizer conformance, Jira ISO timestamps

Every existing GitHub and Jira normalizer test output must validate. The spike showed Jira issue/comment
timestamps use `+0000`, so the Jira fix that makes them `…Z` belongs here (otherwise Plan 1 would flag every
Jira event).

**Files:**
- Test: `core/src/test/java/io/apitomy/axiom/core/events/schema/EventSchemaDriftTest.java`
- Create: `events/github/src/test/java/io/apitomy/axiom/events/github/v2/ValidatingGitHubNormalizer.java`
- Create: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/ValidatingJiraNormalizer.java`
- Modify: `events/github/src/test/java/io/apitomy/axiom/events/github/v2/GitHubEventNormalizerV2Test.java:37`
- Modify: `events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2Test.java:41`
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventNormalizerV2.java` (lines ~321-322,
  ~359-360, helper near `formatTimestamp` ~535)

**Interfaces:**
- Consumes: `EventSchemaRegistry` (Task 1).
- Produces: `static String JiraEventNormalizerV2.isoTimestamp(String jiraTimestamp)` (null-safe; returns
  `Instant.toString()` form, or the input unchanged if unparseable).

- [ ] **Step 1: Write the drift test**

```java
package io.apitomy.axiom.core.events.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.Commit;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards against the hand-written schemas drifting from the Java event model. */
class EventSchemaDriftTest {

    private static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    @Test
    void payloadFilesMatchEventTypesExactly() {
        File dir = new File("src/main/resources/schemas/events/payloads");
        Set<String> files = Arrays.stream(dir.list()).map(n -> n.replace(".json", ""))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> types = Arrays.stream(EventType.values()).map(EventType::value)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(types, files);
    }

    @Test
    void sharedDefsCoverEveryRecordComponent() {
        JsonNode defs = REGISTRY.schemaFor("push").orElseThrow().path("$defs");
        Map<String, Class<?>> records = new LinkedHashMap<>();
        records.put("Actor", Actor.class);
        records.put("Change", Change.class);
        records.put("Commit", Commit.class);
        records.put("CommitAuthor", Commit.CommitAuthor.class);
        records.put("NormalizedComment", NormalizedComment.class);
        records.put("NormalizedIssue", NormalizedIssue.class);
        records.put("NormalizedLabel", NormalizedLabel.class);
        records.put("NormalizedPullRequest", NormalizedPullRequest.class);
        records.put("NormalizedReview", NormalizedReview.class);
        List<String> problems = new ArrayList<>();
        records.forEach((def, type) -> compare(def, type, defs.path(def).path("properties"), Set.of(), problems));
        assertEquals(List.of(), problems);
    }

    @Test
    void payloadSchemasCoverEveryPayloadRecordComponent() {
        List<String> problems = new ArrayList<>();
        for (JsonSubTypes.Type sub : EventPayload.class.getAnnotation(JsonSubTypes.class).value()) {
            JsonNode props = REGISTRY.schemaFor(sub.name()).orElseThrow().at("/properties/payload/properties");
            compare(sub.name(), sub.value(), props, Set.of("type"), problems);
        }
        assertEquals(List.of(), problems);
    }

    private static void compare(String name, Class<?> type, JsonNode props, Set<String> extraAllowed,
                                List<String> problems) {
        Set<String> components = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> schemaProps = new TreeSet<>();
        for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
            schemaProps.add(it.next());
        }
        for (String c : components) {
            if (!schemaProps.contains(c)) {
                problems.add(name + ": schema missing property '" + c + "'");
            }
        }
        for (String p : schemaProps) {
            if (!components.contains(p) && !extraAllowed.contains(p)) {
                problems.add(name + ": schema property '" + p + "' has no record component");
            }
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -q -pl core test -Dtest=EventSchemaDriftTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If it reports a mismatch, fix the schema file (the Java model is authoritative for field
names) and rerun.

- [ ] **Step 3: Add the validating normalizers**

`events/github/src/test/java/io/apitomy/axiom/events/github/v2/ValidatingGitHubNormalizer.java`:

```java
package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test normalizer that fails the test if any produced event does not match its JSON Schema. */
class ValidatingGitHubNormalizer extends GitHubEventNormalizerV2 {

    private static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    ValidatingGitHubNormalizer(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public NormalizedEvent normalize(JsonNode event, String eventSourceId, String baseUrl, JsonNode fullPr) {
        NormalizedEvent result = super.normalize(event, eventSourceId, baseUrl, fullPr);
        if (result != null) {
            assertEquals(List.of(), REGISTRY.validate(result), "schema errors for " + result.type().value());
        }
        return result;
    }
}
```

`events/jira/src/test/java/io/apitomy/axiom/events/jira/v2/ValidatingJiraNormalizer.java`:

```java
package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test normalizer that fails the test if any produced event does not match its JSON Schema. */
class ValidatingJiraNormalizer extends JiraEventNormalizerV2 {

    static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    ValidatingJiraNormalizer(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since, String eventSourceId,
                                                String baseUrl) {
        List<NormalizedEvent> events = super.normalizeIssue(issue, since, eventSourceId, baseUrl);
        for (NormalizedEvent event : events) {
            assertEquals(List.of(), REGISTRY.validate(event), "schema errors for " + event.sourceEventId());
        }
        return events;
    }
}
```

Use the exact parameter names/types of the real `normalizeIssue` signature at
`JiraEventNormalizerV2.java:81` if they differ.

In `GitHubEventNormalizerV2Test.java:37` change `normalizer = new GitHubEventNormalizerV2(mapper);` to
`normalizer = new ValidatingGitHubNormalizer(mapper);`. In `JiraEventNormalizerV2Test.java:41` change
`normalizer = new JiraEventNormalizerV2(mapper);` to `normalizer = new ValidatingJiraNormalizer(mapper);`.
Also use `ValidatingJiraNormalizer` in `JiraEventSourcePollerTest.java:52`.

- [ ] **Step 4: Run normalizer tests and confirm Jira fails on timestamps**

Run: `mvn -q -pl events/github,events/jira -am test -Dtest='GitHubEventNormalizerV2Test,JiraEventNormalizerV2Test,JiraEventSourcePollerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: GitHub PASS; Jira FAIL with `$.payload.issue.createdAt: does not match the regex pattern`.
If a GitHub test fails, decide per error: fix the schema when it is stricter than reality (e.g. a field
GitHub legitimately leaves null that the schema requires), or fix the normalizer when it emits malformed data.
Note each decision in the commit message.

- [ ] **Step 5: Add a Jira timestamp unit test**

Append to `JiraEventNormalizerV2Test`:

```java
    @Test
    void isoTimestampConvertsJiraOffsetFormat() {
        assertEquals("2026-10-21T14:53:07.835Z",
                JiraEventNormalizerV2.isoTimestamp("2026-10-21T14:53:07.835+0000"));
        assertEquals(null, JiraEventNormalizerV2.isoTimestamp(null));
        assertEquals("garbage", JiraEventNormalizerV2.isoTimestamp("garbage"));
    }
```

- [ ] **Step 6: Implement `isoTimestamp` and use it**

In `JiraEventNormalizerV2`, next to `formatTimestamp`:

```java
    /**
     * Converts a Jira timestamp (e.g. {@code 2026-10-21T14:53:07.835+0000}) to ISO-8601 UTC ({@code …Z}).
     *
     * @param jiraTimestamp the Jira timestamp, may be null
     * @return the ISO form, null for null input, or the input unchanged if it cannot be parsed
     */
    static String isoTimestamp(String jiraTimestamp) {
        if (jiraTimestamp == null) {
            return null;
        }
        Instant parsed = parseJiraTimestamp(jiraTimestamp);
        return parsed != null ? parsed.toString() : jiraTimestamp;
    }
```

Replace `textOrNull(fields, "created")` / `textOrNull(fields, "updated")` inside `mapIssue` (~321-322) with
`isoTimestamp(textOrNull(fields, "created"))` / `isoTimestamp(textOrNull(fields, "updated"))`, and
`textOrNull(comment, "created")` / `textOrNull(comment, "updated")` inside `mapComment` (~359-360) the same
way. If `mapIssue` passes a `closedAt` from a raw field, wrap it too. Note: `Instant.toString()` drops a
`.000` fraction (`…:07Z`); the schema accepts both.

- [ ] **Step 7: Run tests**

Run the command from Step 4. Expected: all PASS. Fix any existing assertions that compared the old `+0000`
strings by updating the expected values to the `…Z` form.

- [ ] **Step 8: Commit**

```bash
git add core/src/test events/github/src/test events/jira/src
git commit -m "test(events): validate normalizer output against event schemas; emit ISO timestamps for Jira"
```

---

### Task 3: Migration, entity, per-source dedup, runtime validation

**Files:**
- Create: `app/src/main/resources/db/migration/V4__event_schema_validation.sql`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/StreamEventEntity.java`
- Create: `events/core/src/main/java/io/apitomy/axiom/events/core/PersistOutcome.java`
- Modify: `events/core/src/main/java/io/apitomy/axiom/events/core/EventStreamService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/EventStreamValidationTest.java`,
  `app/src/test/java/io/apitomy/axiom/app/EventStreamValidatorFailureTest.java`

**Interfaces:**
- Produces: `enum PersistOutcome { PERSISTED, PERSISTED_INVALID, DUPLICATE; boolean persisted(); }`
- Produces: `PersistOutcome EventStreamService.persistEvent(NormalizedEvent)`;
  `Set<String> EventStreamService.existingSourceEventIds(String eventSourceId, Collection<String> ids)`.
- Produces: `StreamEventEntity.validationErrors` (`String`, JSON array text, nullable).

This task changes `persistEvent`'s return type, so the pollers stop compiling until Task 4. Run only `app`
tests here with `-am`, which compiles pollers too: do Task 4 Step 3 (mechanical caller updates) inside this
task if compilation blocks you, then continue.

- [ ] **Step 1: Write the failing integration test**

`app/src/test/java/io/apitomy/axiom/app/EventStreamValidationTest.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.PersistOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventStreamValidationTest {

    @Inject
    EventStreamService service;

    static NormalizedEvent event(String eventSourceId, String sourceEventId, String createdAt) {
        Actor actor = new Actor("u1", "User One", null, null);
        NormalizedIssue issue = new NormalizedIssue("PROJ-1", "Title", null, "closed", "Closed", actor,
                List.of(), List.of(), null, "https://jira.example/browse/PROJ-1", createdAt, null, null);
        return new NormalizedEvent(UUID.randomUUID().toString(), sourceEventId, "jira", eventSourceId,
                EventType.ISSUE_CLOSED, "https://jira.example/browse/PROJ-1", "2026-10-21T14:54:06Z",
                actor, new IssueClosedPayload(issue), null);
    }

    static StreamEventEntity stored(String eventSourceId, String sourceEventId) {
        return QuarkusTransaction.requiringNew().call(() -> StreamEventEntity
                .<StreamEventEntity>find("eventSourceId = ?1 and sourceEventId = ?2", eventSourceId, sourceEventId)
                .firstResult());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
                StreamEventEntity.delete("eventSourceId in ?1", List.of("val-a", "val-b")));
    }

    @Test
    void validEventIsPersistedWithoutErrors() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-1", "2026-10-21T14:53:07Z")));
        assertNull(stored("val-a", "P-1-1").validationErrors);
    }

    @Test
    void invalidEventIsPersistedWithErrors() {
        PersistOutcome outcome = service.persistEvent(event("val-a", "P-1-2", "2026-10-21T14:53:07.835+0000"));
        assertEquals(PersistOutcome.PERSISTED_INVALID, outcome);
        String errors = stored("val-a", "P-1-2").validationErrors;
        assertTrue(errors.startsWith("[") && errors.contains("$.payload.issue.createdAt"), errors);
    }

    @Test
    void sameSourceEventIdInTwoSourcesIsStoredTwice() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-3", "2026-10-21T14:53:07Z")));
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-b", "P-1-3", "2026-10-21T14:53:07Z")));
    }

    @Test
    void sameSourceEventIdInOneSourceIsDuplicate() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-4", "2026-10-21T14:53:07Z")));
        assertEquals(PersistOutcome.DUPLICATE, service.persistEvent(event("val-a", "P-1-4", "2026-10-21T14:53:07Z")));
    }

    @Test
    void existingIdsAreScopedToTheSource() {
        service.persistEvent(event("val-a", "P-1-5", "2026-10-21T14:53:07Z"));
        assertEquals(Set.of("P-1-5"), service.existingSourceEventIds("val-a", List.of("P-1-5", "P-1-6")));
        assertEquals(Set.of(), service.existingSourceEventIds("val-b", List.of("P-1-5")));
    }
}
```

`app/src/test/java/io/apitomy/axiom/app/EventStreamValidatorFailureTest.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.PersistOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventStreamValidatorFailureTest {

    @Inject
    EventStreamService service;

    @InjectMock
    EventSchemaRegistry registry;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.delete("eventSourceId", "val-x"));
    }

    @Test
    void validatorExceptionIsRecordedAndEventSaved() {
        Mockito.when(registry.validate(Mockito.any(NormalizedEvent.class)))
                .thenThrow(new IllegalStateException("kaboom"));
        NormalizedEvent event = EventStreamValidationTest.event("val-x", "P-9-1", "2026-10-21T14:53:07Z");
        assertEquals(PersistOutcome.PERSISTED_INVALID, service.persistEvent(event));
        String errors = EventStreamValidationTest.stored("val-x", "P-9-1").validationErrors;
        assertTrue(errors.contains("kaboom"), errors);
    }
}
```

Check the `@InjectMock` import used elsewhere in `app/src/test` (`grep -rn "InjectMock" app/src/test | head -1`)
and match it.

- [ ] **Step 2: Run to see it fail**

Run: `mvn -q -pl app -am test -Dtest='EventStreamValidationTest,EventStreamValidatorFailureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`PersistOutcome` missing).

- [ ] **Step 3: Migration**

`app/src/main/resources/db/migration/V4__event_schema_validation.sql`:

```sql
-- Axiom 3.x incremental migration 4.
--
-- Event schema validation: store each event's validation errors, and make source event IDs unique per
-- event source instead of globally (two sources watching the same project each keep their own events).
ALTER TABLE stream_event ADD COLUMN validation_errors TEXT;

DROP INDEX idx_stream_event_source_event_id;
CREATE UNIQUE INDEX idx_stream_event_source_event ON stream_event (event_source_id, source_event_id);
```

- [ ] **Step 4: Entity**

In `StreamEventEntity`, change the class annotation and add the column:

```java
@Entity
@Table(name = "stream_event", uniqueConstraints = @UniqueConstraint(
        name = "idx_stream_event_source_event", columnNames = {"event_source_id", "source_event_id"}))
public class StreamEventEntity extends PanacheEntityBase {
```

```java
    /**
     * JSON array of schema validation errors, or null when the event was valid or was stored before
     * validation existed.
     */
    @Column(name = "validation_errors", columnDefinition = "TEXT")
    public String validationErrors;
```

Add `import jakarta.persistence.UniqueConstraint;`.

- [ ] **Step 5: `PersistOutcome`**

```java
package io.apitomy.axiom.events.core;

/** Result of {@link EventStreamService#persistEvent}. */
public enum PersistOutcome {
    /** Stored; passed schema validation. */
    PERSISTED,
    /** Stored; failed schema validation (errors saved with the event). */
    PERSISTED_INVALID,
    /** Not stored; the event source already has an event with this source event ID. */
    DUPLICATE;

    /**
     * Whether the event was stored.
     *
     * @return true for {@link #PERSISTED} and {@link #PERSISTED_INVALID}
     */
    public boolean persisted() {
        return this != DUPLICATE;
    }
}
```

- [ ] **Step 6: `EventStreamService`**

Add the injection and imports (`io.apitomy.axiom.core.events.schema.EventSchemaRegistry`):

```java
    @Inject
    EventSchemaRegistry schemaRegistry;
```

Replace `existingSourceEventIds`:

```java
    /**
     * Returns which of the given source event IDs the event source has already stored.
     *
     * @param eventSourceId  the event source
     * @param sourceEventIds the source event IDs to look up
     * @return the subset of {@code sourceEventIds} already stored for that event source
     */
    @Transactional
    public Set<String> existingSourceEventIds(String eventSourceId, Collection<String> sourceEventIds) {
        if (sourceEventIds.isEmpty()) {
            return Set.of();
        }
        List<String> found = StreamEventEntity.getEntityManager()
                .createQuery("select e.sourceEventId from StreamEventEntity e "
                        + "where e.eventSourceId = :source and e.sourceEventId in :ids", String.class)
                .setParameter("source", eventSourceId)
                .setParameter("ids", sourceEventIds)
                .getResultList();
        return new HashSet<>(found);
    }
```

In `persistEvent`: change the signature to `public PersistOutcome persistEvent(NormalizedEvent event)`, update
the Javadoc (`@return` the outcome; duplicates are per event source; invalid events are saved with their
errors), and replace everything from `// Dedup check` to the end of the method with:

```java
        long existing = StreamEventEntity.count("eventSourceId = ?1 and sourceEventId = ?2",
                event.eventSourceId(), event.sourceEventId());
        if (existing > 0) {
            LOG.debugf("Skipping duplicate event: %s (sourceEventId: %s)",
                    event.type().value(), event.sourceEventId());
            return PersistOutcome.DUPLICATE;
        }

        List<String> errors = validationErrors(event);
        if (!errors.isEmpty()) {
            entity.validationErrors = toJson(errors);
            LOG.warnf("Event %s (%s) failed schema validation: %s",
                    event.sourceEventId(), event.type().value(), errors);
        }

        entity.createdOn = Instant.now();
        entity.persist();

        LOG.debugf("Persisted event %s: %s [%s]", entity.id, event.type().value(), event.ref());

        persistedEvents.fire(new StreamEventPersisted(
                entity.id.toString(), event.type().value(), event.eventSourceId()));

        return errors.isEmpty() ? PersistOutcome.PERSISTED : PersistOutcome.PERSISTED_INVALID;
    }

    private List<String> validationErrors(NormalizedEvent event) {
        try {
            return schemaRegistry.validate(event);
        } catch (RuntimeException e) {
            return List.of("Schema validation failed: " + e.getMessage());
        }
    }

    private String toJson(List<String> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception e) {
            return "[\"" + String.join("; ", errors).replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        }
    }
```

Update the class Javadoc: duplicates are detected per event source; every new event is schema-validated.

- [ ] **Step 7: Run tests**

Run the Step 2 command, plus `-Dtest='…,MigrationSchemaValidationTest,EventStreamServicePersistTest'`.
Expected: PASS (fix poller compilation first via Task 4 Step 3 if needed).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/resources/db/migration/V4__event_schema_validation.sql core/src/main/java events/core app/src/test
git commit -m "feat(events): validate events on persist and make source event IDs unique per event source"
```

---

### Task 4: Pollers report invalid events

**Files:**
- Create: `events/core/src/main/java/io/apitomy/axiom/events/core/IngestTally.java`
- Modify: `events/github/src/main/java/io/apitomy/axiom/events/github/v2/GitHubEventSourcePoller.java`
  (~122-153, ~176-240)
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/v2/JiraEventSourcePoller.java`
  (`pollEventSource` ~120-185, `ingestWindow` ~200-275)
- Modify: test fakes `GitHubEventSourcePollerTest.RecordingStream` (~226-246),
  `JiraEventSourcePollerTest.RecordingStream` (~345-362)
- Test: `events/jira/src/test/java/io/apitomy/axiom/events/core/IngestTallyTest.java`, plus one test per
  poller test class

**Interfaces:**
- Consumes: `PersistOutcome`, `existingSourceEventIds(String, Collection)` (Task 3).
- Produces: `IngestTally` with `void record(PersistOutcome)`, `int ingested()`, `int invalid()`,
  `String validationSuffix()` (empty when 0, else `" (N failed schema validation)"`).

- [ ] **Step 1: Write `IngestTallyTest`** (in the Jira module, which already hosts events-core tests)

```java
package io.apitomy.axiom.events.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestTallyTest {

    @Test
    void countsPersistedAndInvalid() {
        IngestTally tally = new IngestTally();
        tally.record(PersistOutcome.PERSISTED);
        tally.record(PersistOutcome.PERSISTED_INVALID);
        tally.record(PersistOutcome.DUPLICATE);
        assertEquals(2, tally.ingested());
        assertEquals(1, tally.invalid());
        assertEquals(" (1 failed schema validation)", tally.validationSuffix());
    }

    @Test
    void noSuffixWhenAllValid() {
        IngestTally tally = new IngestTally();
        tally.record(PersistOutcome.PERSISTED);
        assertEquals("", tally.validationSuffix());
    }
}
```

- [ ] **Step 2: Implement `IngestTally`**

```java
package io.apitomy.axiom.events.core;

/** Counts persisted and schema-invalid events during one poll. Not thread-safe; one per poll. */
public class IngestTally {

    private int ingested;
    private int invalid;

    /**
     * Records the outcome of one persist call.
     *
     * @param outcome the outcome
     */
    public void record(PersistOutcome outcome) {
        if (outcome.persisted()) {
            ingested++;
        }
        if (outcome == PersistOutcome.PERSISTED_INVALID) {
            invalid++;
        }
    }

    /**
     * Number of events stored.
     *
     * @return stored count, valid and invalid
     */
    public int ingested() {
        return ingested;
    }

    /**
     * Number of stored events that failed schema validation.
     *
     * @return invalid count
     */
    public int invalid() {
        return invalid;
    }

    /**
     * Suffix for poll-log messages.
     *
     * @return {@code ""} when no event was invalid, else {@code " (N failed schema validation)"}
     */
    public String validationSuffix() {
        return invalid == 0 ? "" : " (" + invalid + " failed schema validation)";
    }
}
```

- [ ] **Step 3: Update callers and fakes (mechanical)**

GitHub poller, `pollRepository`: change `eventStreamService.existingSourceEventIds(pageIds)` to
`eventStreamService.existingSourceEventIds(eventSourceId, pageIds)`. Add a `IngestTally tally` parameter as the
last parameter of `pollRepository`, keeping the old signature as a delegating overload so existing tests
still compile:

```java
    int pollRepository(String eventSourceId, String owner, String repo, String token, String apiBaseUrl,
                       String htmlBaseUrl, StringBuilder errors) {
        return pollRepository(eventSourceId, owner, repo, token, apiBaseUrl, htmlBaseUrl, errors,
                new IngestTally());
    }
```

(Copy the real parameter list from the existing method.) Inside the loop replace

```java
                if (normalized != null && eventStreamService.persistEvent(normalized)) {
                    ingested++;
                }
```

with

```java
                if (normalized != null) {
                    PersistOutcome outcome = eventStreamService.persistEvent(normalized);
                    tally.record(outcome);
                    if (outcome.persisted()) {
                        ingested++;
                    }
                }
```

In the poll method (~122): create `IngestTally tally = new IngestTally();` next to `totalIngested`, pass it
to `pollRepository`, and change the success message to
`"Polled " + repositories.size() + " repositories" + tally.validationSuffix()` and the error message to
`"Polled " + repositories.size() + " repositories with errors" + tally.validationSuffix()`.

Jira poller: give `ingestWindow` an extra last parameter `IngestTally tally` with a delegating overload
using `new IngestTally()` (same pattern). In the event loop replace

```java
                            if (eventStreamService.persistEvent(event)) {
                                totalIngested++;
                            }
```

with

```java
                            PersistOutcome outcome = eventStreamService.persistEvent(event);
                            tally.record(outcome);
                            if (outcome.persisted()) {
                                totalIngested++;
                            }
```

In `pollEventSource`, create the tally before `ingestWindow`, pass it, and change the success message to
`"Polled " + projects.size() + " projects, " + result.issuesProcessed() + " issues" + tally.validationSuffix()`.

GitHub test fake:

```java
        @Override
        public Set<String> existingSourceEventIds(String eventSourceId, Collection<String> sourceEventIds) {
            lookups++;
            Set<String> found = new HashSet<>(sourceEventIds);
            found.retainAll(ids);
            return found;
        }

        @Override
        public PersistOutcome persistEvent(NormalizedEvent event) {
            if (event.sourceEventId().equals(failOn)) {
                throw new IllegalStateException("db down");
            }
            if (!ids.add(event.sourceEventId())) {
                return PersistOutcome.DUPLICATE;
            }
            return invalidIds.contains(event.sourceEventId())
                    ? PersistOutcome.PERSISTED_INVALID : PersistOutcome.PERSISTED;
        }
```

with a new field `final Set<String> invalidIds = new HashSet<>();`. Jira fake likewise:

```java
        final Set<String> invalidIds = new HashSet<>();

        @Override
        public PersistOutcome persistEvent(NormalizedEvent event) {
            attempts++;
            if (event.sourceEventId().equals(failOn)) {
                throw new IllegalStateException("db down");
            }
            boolean added = ids.add(event.sourceEventId());
            if (onPersist != null) {
                onPersist.run();
            }
            if (!added) {
                return PersistOutcome.DUPLICATE;
            }
            return invalidIds.contains(event.sourceEventId())
                    ? PersistOutcome.PERSISTED_INVALID : PersistOutcome.PERSISTED;
        }
```

Add `import io.apitomy.axiom.events.core.PersistOutcome;` where needed.

- [ ] **Step 4: Add poller tests for the message**

In each poller test class, add one test modelled on an existing successful-poll test in the same class (copy
its setup verbatim), set `stream.invalidIds.add("<one sourceEventId that test produces>")`, and assert that
the recorded poll-log message ends with `" (1 failed schema validation)"`. Use the same mechanism the existing
tests use to capture `recordPollLog` (find it with `grep -n "recordPollLog" <TestClass>.java`); if the class has
no capture mechanism, add an override in the test's poller subclass:

```java
        final List<String> logMessages = new ArrayList<>();

        @Override
        void recordPollLog(String eventSourceId, String status, String message, String detail,
                           int eventsIngested, long durationMs) {
            logMessages.add(message);
        }
```

- [ ] **Step 5: Run tests**

Run: `mvn -q -pl events/github,events/jira -am test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add events
git commit -m "feat(events): report schema-invalid event counts in poll logs"
```

---

### Task 5: REST API — schema endpoints and `validationErrors`

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/StreamEventsResourceImpl.java` (`toBean` ~521)
- Test: `app/src/test/java/io/apitomy/axiom/app/EventSchemasResourceTest.java`

**Interfaces:**
- Produces: `GET /api/v1/event-schemas` → `EventSchemaSummary[]`; `GET /api/v1/event-schemas/{eventType}` →
  `EventSchemaDocument` (404 for unknown); `StreamEvent.validationErrors: string[]`.

- [ ] **Step 1: Edit the OpenAPI spec**

Add a tag `{"name": "EventSchemas", "description": "JSON Schemas of normalized event types"}` to `tags`.
Add to `paths`:

```json
"/event-schemas": {
  "get": {
    "tags": ["EventSchemas"],
    "summary": "List event types that have a JSON Schema",
    "operationId": "listEventSchemas",
    "responses": {
      "200": {
        "description": "Event types, sorted",
        "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/EventSchemaSummary"}}}}
      }
    }
  }
},
"/event-schemas/{eventType}": {
  "parameters": [
    {"name": "eventType", "in": "path", "required": true, "description": "Event type, e.g. issue.closed", "schema": {"type": "string"}}
  ],
  "get": {
    "tags": ["EventSchemas"],
    "summary": "Get the JSON Schema of an event type",
    "description": "Self-contained JSON Schema (draft 2020-12) for the full event envelope of this type. sourceData is unconstrained.",
    "operationId": "getEventSchema",
    "responses": {
      "200": {"description": "The schema", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/EventSchemaDocument"}}}},
      "404": {"$ref": "#/components/responses/NotFound"}
    }
  }
}
```

Add to `components.schemas`:

```json
"EventSchemaSummary": {
  "type": "object",
  "required": ["type", "description"],
  "properties": {
    "type": {"type": "string", "description": "Event type, e.g. issue.closed"},
    "description": {"type": "string", "description": "What the event means"}
  }
},
"EventSchemaDocument": {
  "type": "object",
  "additionalProperties": {},
  "description": "A JSON Schema document"
}
```

Add to `StreamEvent.properties`:

```json
"validationErrors": {
  "type": "array",
  "items": {"type": "string"},
  "description": "Schema validation errors recorded when the event was ingested. Absent when the event was valid or was ingested before validation existed."
}
```

Run: `mvn -q -pl common/api install` and confirm `EventResource.java` under
`common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/` now declares `listEventSchemas()` and
`getEventSchema(String eventType)`; note their exact return types.

- [ ] **Step 2: Write the failing REST test**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;

@QuarkusTest
class EventSchemasResourceTest {

    @Inject
    EventStreamService service;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.delete("eventSourceId", "schema-rest"));
    }

    @Test
    void listsAllEventTypes() {
        given().when().get("/api/v1/event-schemas").then()
                .statusCode(200)
                .body("$", hasSize(26))
                .body("type", hasItem("issue.closed"));
    }

    @Test
    void returnsAssembledSchema() {
        given().when().get("/api/v1/event-schemas/issue.closed").then()
                .statusCode(200)
                .body("title", equalTo("issue.closed"))
                .body("properties.payload.properties.type.const", equalTo("issue.closed"));
    }

    @Test
    void unknownTypeIs404() {
        given().when().get("/api/v1/event-schemas/nope").then().statusCode(404);
    }

    @Test
    void streamEventExposesValidationErrors() {
        NormalizedEvent event = EventStreamValidationTest.event("schema-rest", "P-7-1",
                "2026-10-21T14:53:07.835+0000");
        service.persistEvent(event);
        given().when().get("/api/v1/stream/events/" + event.id()).then()
                .statusCode(200)
                .body("validationErrors", not(empty()));
    }
}
```

Run: `mvn -q -pl app -am test -Dtest=EventSchemasResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`EventSourcesResourceImpl` does not implement the new methods).

- [ ] **Step 3: Implement the endpoints**

In `EventSourcesResourceImpl` add:

```java
    @Inject
    EventSchemaRegistry eventSchemaRegistry;

    /**
     * Lists event types that have a JSON Schema.
     *
     * @return summaries sorted by type
     */
    @Override
    public List<EventSchemaSummary> listEventSchemas() {
        return eventSchemaRegistry.eventTypes().stream().map(s -> {
            EventSchemaSummary bean = new EventSchemaSummary();
            bean.setType(s.type());
            bean.setDescription(s.description());
            return bean;
        }).toList();
    }

    /**
     * Returns the assembled JSON Schema of an event type.
     *
     * @param eventType the event type
     * @return the schema
     */
    @Override
    public EventSchemaDocument getEventSchema(String eventType) {
        return eventSchemaRegistry.schemaFor(eventType)
                .map(schema -> objectMapper.convertValue(schema, EventSchemaDocument.class))
                .orElseThrow(() -> new WebApplicationException("Unknown event type: " + eventType, 404));
    }
```

Imports: `io.apitomy.axiom.api.beans.EventSchemaSummary`, `io.apitomy.axiom.api.beans.EventSchemaDocument`,
`io.apitomy.axiom.core.events.schema.EventSchemaRegistry`. The core record of the same simple name is not
imported (referenced only through `s`). If the class has no `objectMapper` field, add
`@Inject ObjectMapper objectMapper;`. Match the generated return types noted in Step 1.

In `StreamEventsResourceImpl.toBean`, before `return bean;`:

```java
        if (entity.validationErrors != null) {
            try {
                bean.setValidationErrors(objectMapper.readValue(entity.validationErrors,
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
            } catch (Exception e) {
                LOG.warnf("Failed to parse validation errors for event %s: %s", entity.id, e.getMessage());
            }
        }
```

`SubscriptionsResourceImpl` (~338) builds its own event beans; if it builds `StreamEvent`, add the same
block there.

- [ ] **Step 4: Run tests**

Run: `mvn -q -pl app -am test -Dtest='EventSchemasResourceTest,EventSourcesResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/api/src/main/resources/openapi.json app/src
git commit -m "feat(api): expose event JSON Schemas and per-event validation errors"
```

---

### Task 6: Docs, full build, live check

**Files:**
- Modify: `docs/developer-guide/event-sourcing-design.md`

- [ ] **Step 1: Update docs**

In `event-sourcing-design.md`, near the envelope schema (~line 290), add a section "Event schemas" (prose
wrapped at 110 chars) covering:
- the file layout (`envelope.json`, `defs.json`, `payloads/<type>.json` under
  `core/src/main/resources/schemas/events/`), and that the files are hand-maintained source of truth (the
  generator script is bootstrap-only);
- assembly into one self-contained schema per type, served by `GET /api/v1/event-schemas[/{type}]`;
- `sourceData` is not validated;
- runtime validation: invalid events are saved with `validationErrors`, logged, counted in the poll log;
  validator exceptions are recorded as an error; schema load failure stops startup;
- the drift tests (`EventSchemaDriftTest`, validating normalizers) and how to add a new event type
  (enum value, payload record, `@JsonSubTypes` entry, payload schema file);
- source event IDs are unique per event source (migration V4);
- all timestamps are ISO-8601 UTC ending in `Z`.

- [ ] **Step 2: Full build**

Run: `./build.sh`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Live check on an isolated instance**

```bash
mkdir -p /tmp/axiom-plan1/home /tmp/axiom-plan1/data
AXIOM_HOME=/tmp/axiom-plan1/home ./dev.sh --skip-ui --portOffset=500 --dataDir=/tmp/axiom-plan1/data
```

In another shell, after startup:

```bash
curl -s http://localhost:9590/api/v1/event-schemas | python3 -c "import sys,json;print(len(json.load(sys.stdin)))"
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9590/api/v1/event-schemas/issue.closed
```

Expected: `26`, `200`. Also confirm Flyway applied V4 (look for `Migrating schema ... to version "4"` in the
log). Stop the instance (Ctrl+C) and `rm -rf /tmp/axiom-plan1`.

- [ ] **Step 4: Commit**

```bash
git add docs/developer-guide/event-sourcing-design.md
git commit -m "docs: describe event schemas and runtime validation"
```

