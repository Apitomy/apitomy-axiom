# Event Source Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace hardcoded event pre-filters with user-configurable per-event-source
include/exclude filtering, plus a dry-run endpoint for previewing filter behavior against live
source data.

**Architecture:** Filters are stored as a JSON column on `event_source`, evaluated post-ingest
in `PipelineOrchestrator` before the AI Manager call. A shared `EventFilterEvaluator` in `core`
handles glob-based rule matching. A dry-run endpoint fetches live events from the source API,
classifies them using extracted poller logic, and returns filter verdicts. The frontend adds a
Filters tab to the event source detail page with rule editing and dry-run preview.

**Tech Stack:** Java 25, Quarkus 3.33, Panache (Active Record), Jackson, JUnit 5, React 19,
PatternFly 6, TypeScript

**Design Spec:**
`docs/superpowers/specs/2026-08-07-event-source-filtering-design.md`

**GitHub Issue:** #127

## Global Constraints

- Contract-first: all REST API changes start in `common/api/src/main/resources/openapi.json`,
  then `mvn install` (or `build.sh`) generates JAX-RS interfaces and beans into
  `common/api/target/generated-sources/jaxrs/`
- Generated beans live in `io.apitomy.axiom.api.beans`
- REST impl classes in `app/src/main/java/.../rest/` implement the generated interface — never
  add `@Path` annotations directly on impls
- Entities use Panache Active Record pattern (public fields, `PanacheEntity` base)
- 4-space indentation, explicit types, camelCase variables, PascalCase classes
- Javadoc on all public methods
- JUnit 5 for tests
- No Claude attribution in commits

## File Map

### New Files

| File | Module | Purpose |
|------|--------|---------|
| `core/src/main/java/.../core/filters/EventSourceFilterRule.java` | core | Filter rule record |
| `core/src/main/java/.../core/filters/EventSourceFilters.java` | core | Include/exclude filter container |
| `core/src/main/java/.../core/filters/FilterResult.java` | core | Evaluation result record |
| `core/src/main/java/.../core/filters/EventFilterEvaluator.java` | core | Filter evaluation logic |
| `core/src/test/java/.../core/filters/EventFilterEvaluatorTest.java` | core | Unit tests for evaluator |
| `app/src/main/resources/db/migration/V34__add_event_source_filters.sql` | app | DB migration |
| `events/github/src/main/java/.../github/GitHubEventClassifier.java` | events/github | Extracted classify/wrap logic |
| `events/github/src/main/java/.../github/GitHubDryRunService.java` | events/github | Dry-run fetch+classify for GitHub |
| `events/jira/src/main/java/.../jira/JiraEventClassifier.java` | events/jira | Extracted classify/wrap logic |
| `events/jira/src/main/java/.../jira/JiraDryRunService.java` | events/jira | Dry-run fetch+classify for Jira |
| `events/core/src/main/java/.../events/core/DryRunEvent.java` | events/core | Shared dry-run event record |

(All paths relative to project root. Package prefix: `io.apitomy.axiom`)

### Modified Files

| File | Module | Changes |
|------|--------|---------|
| `common/api/src/main/resources/openapi.json` | common/api | New schemas + endpoint |
| `core/src/main/java/.../core/entities/EventSourceEntity.java` | core | Add `filters` field |
| `app/src/main/java/.../app/rest/EventSourcesResourceImpl.java` | app | Filters serialization + dry-run impl |
| `app/src/main/java/.../app/PipelineOrchestrator.java` | app | Replace `shouldSkipEvent()` |
| `events/github/src/main/java/.../github/GitHubPoller.java` | events/github | Delegate to classifier |
| `events/jira/src/main/java/.../jira/JiraPoller.java` | events/jira | Delegate to classifier |
| `ui/src/config/api.ts` | ui | Filter types + dry-run API call |
| `ui/src/pages/EventSourceDetailPage.tsx` | ui | Add Filters tab |

---

### Task 1: OpenAPI Schema and Endpoint Definitions

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Consumes: nothing
- Produces: `EventSourceFilterRule`, `EventSourceFilters`, `FilterDryRunRequest`,
  `FilterDryRunResponse`, `FilterDryRunResult` schemas; `filters` property on `EventSource`
  and `NewEventSource`; `POST /event-sources/filters/dry-run` endpoint. After `mvn install`,
  generates corresponding Java beans and adds `dryRunFilters()` to the `EventResource`
  interface.

- [ ] **Step 1: Add `EventSourceFilterRule` schema**

In `openapi.json`, in the `components.schemas` section (after the last schema `NewDashboard`
around line 7428), add:

```json
"EventSourceFilterRule": {
    "required": [
        "type",
        "pattern"
    ],
    "type": "object",
    "properties": {
        "type": {
            "description": "What the rule matches against: event-type or payload field",
            "enum": [
                "event-type",
                "payload"
            ],
            "type": "string"
        },
        "pointer": {
            "description": "JSON Pointer (RFC 6901) to the payload field. Required when type is payload.",
            "type": "string"
        },
        "pattern": {
            "description": "Glob pattern to match against. Supports * (any sequence) and ? (any single character) wildcards.",
            "type": "string"
        }
    }
}
```

- [ ] **Step 2: Add `EventSourceFilters` schema**

Immediately after `EventSourceFilterRule`:

```json
"EventSourceFilters": {
    "type": "object",
    "properties": {
        "include": {
            "description": "Rules for events to include. An empty list means allow all event types.",
            "type": "array",
            "items": {
                "$ref": "#/components/schemas/EventSourceFilterRule"
            }
        },
        "exclude": {
            "description": "Rules for events to exclude. Applied after include rules.",
            "type": "array",
            "items": {
                "$ref": "#/components/schemas/EventSourceFilterRule"
            }
        }
    }
}
```

- [ ] **Step 3: Add `filters` property to `EventSource` schema**

In the `EventSource` schema properties (around line 4970, after the `labels` property), add:

```json
"filters": {
    "$ref": "#/components/schemas/EventSourceFilters"
}
```

- [ ] **Step 4: Add `filters` property to `NewEventSource` schema**

In the `NewEventSource` schema properties (around line 5015, after the `labels` property), add
the same:

```json
"filters": {
    "$ref": "#/components/schemas/EventSourceFilters"
}
```

- [ ] **Step 5: Add dry-run request/response schemas**

After `EventSourceFilters`, add:

```json
"FilterDryRunRequest": {
    "required": [
        "sourceType",
        "configuration",
        "filters"
    ],
    "type": "object",
    "properties": {
        "sourceType": {
            "enum": [
                "github",
                "jira"
            ],
            "type": "string"
        },
        "configuration": {
            "description": "Source-specific configuration (same shape as EventSource.configuration)",
            "type": "object",
            "additionalProperties": {}
        },
        "secretName": {
            "description": "Name of the secret to use for authentication",
            "type": "string"
        },
        "filters": {
            "$ref": "#/components/schemas/EventSourceFilters"
        }
    }
},
"FilterDryRunResponse": {
    "type": "object",
    "properties": {
        "results": {
            "type": "array",
            "items": {
                "$ref": "#/components/schemas/FilterDryRunResult"
            }
        },
        "totalEvaluated": {
            "type": "integer"
        },
        "totalAllowed": {
            "type": "integer"
        },
        "totalBlocked": {
            "type": "integer"
        }
    }
},
"FilterDryRunResult": {
    "type": "object",
    "properties": {
        "eventType": {
            "type": "string"
        },
        "issueRef": {
            "type": "string"
        },
        "summary": {
            "description": "Human-readable summary of the event (e.g. issue title, comment author)",
            "type": "string"
        },
        "allowed": {
            "type": "boolean"
        },
        "matchedRule": {
            "description": "Description of the filter rule that matched, if blocked",
            "type": "string"
        }
    }
}
```

- [ ] **Step 6: Add dry-run endpoint**

In the `paths` section, add a new path. Place it near the other event-source endpoints (after
`/event-sources/{eventSourceId}/logs`, around line 2865):

```json
"/event-sources/filters/dry-run": {
    "post": {
        "tags": [
            "EventSources"
        ],
        "summary": "Test filter configuration against live events",
        "description": "Fetches recent events from the source API and evaluates the provided filter configuration against them. No events are ingested. Use this to preview what filters would allow or block before saving.",
        "operationId": "dryRunFilters",
        "requestBody": {
            "content": {
                "application/json": {
                    "schema": {
                        "$ref": "#/components/schemas/FilterDryRunRequest"
                    }
                }
            },
            "required": true
        },
        "responses": {
            "200": {
                "description": "Dry run results",
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/FilterDryRunResponse"
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 7: Build to generate code**

Run `mvn install` (or `build.sh`) from the project root. This generates:
- `EventSourceFilterRule.java`, `EventSourceFilters.java` beans in
  `io.apitomy.axiom.api.beans`
- `FilterDryRunRequest.java`, `FilterDryRunResponse.java`, `FilterDryRunResult.java` beans
- Updated `EventSource.java` and `NewEventSource.java` beans with `getFilters()`/`setFilters()`
- Updated `EventResource.java` interface with `dryRunFilters()` method

Verify the generated files exist in `common/api/target/generated-sources/jaxrs/`.

- [ ] **Step 8: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add event source filter schemas and dry-run endpoint (#127)"
```

---

### Task 2: Core Filter Model and Evaluator

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/filters/EventSourceFilterRule.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/filters/EventSourceFilters.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/filters/FilterResult.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/filters/EventFilterEvaluator.java`
- Create: `core/src/test/java/io/apitomy/axiom/core/filters/EventFilterEvaluatorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `EventFilterEvaluator.evaluate(EventSourceFilters, String, JsonNode): FilterResult`
  used by Task 4 (`PipelineOrchestrator`) and Task 5 (dry-run endpoint)

- [ ] **Step 1: Write `EventSourceFilterRule` record**

```java
package io.apitomy.axiom.core.filters;

/**
 * A single filter rule that matches either an event type or a payload field value.
 *
 * @param type    "event-type" or "payload"
 * @param pointer JSON Pointer (RFC 6901) path into the payload; required when type is "payload"
 * @param pattern glob pattern with * and ? wildcards
 */
public record EventSourceFilterRule(String type, String pointer, String pattern) {
}
```

- [ ] **Step 2: Write `EventSourceFilters` record**

```java
package io.apitomy.axiom.core.filters;

import java.util.List;

/**
 * Include/exclude filter configuration for an event source.
 *
 * @param include rules for events to include (empty list means allow all)
 * @param exclude rules for events to exclude (applied after include)
 */
public record EventSourceFilters(List<EventSourceFilterRule> include,
                                  List<EventSourceFilterRule> exclude) {

    /**
     * Returns an empty filters instance that allows all events.
     */
    public static EventSourceFilters allowAll() {
        return new EventSourceFilters(List.of(), List.of());
    }
}
```

- [ ] **Step 3: Write `FilterResult` record**

```java
package io.apitomy.axiom.core.filters;

/**
 * Result of evaluating an event against a filter configuration.
 *
 * @param allowed     true if the event passes the filter
 * @param matchedRule human-readable description of the rule that matched, or null if allowed
 */
public record FilterResult(boolean allowed, String matchedRule) {

    /** An event that passed all filters. */
    public static final FilterResult ALLOWED = new FilterResult(true, null);

    /**
     * Creates a blocked result with a description of the matched rule.
     */
    public static FilterResult blocked(String matchedRule) {
        return new FilterResult(false, matchedRule);
    }
}
```

- [ ] **Step 4: Write failing tests for `EventFilterEvaluator`**

```java
package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventFilterEvaluatorTest {

    private final EventFilterEvaluator evaluator = new EventFilterEvaluator();
    private final ObjectMapper mapper = new ObjectMapper();

    // --- Include rules ---

    @Test
    void emptyIncludeAllowsAllEventTypes() {
        EventSourceFilters filters = new EventSourceFilters(List.of(), List.of());
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void includeEventTypeAllowsMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void includeEventTypeBlocksNonMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "pr-created", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertNotNull(result.matchedRule());
    }

    @Test
    void includePayloadRuleAllowsMatchingPayload() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("payload", "/action", "opened")),
                List.of());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "opened");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertTrue(result.allowed());
    }

    @Test
    void includePayloadRuleBlocksNonMatchingPayload() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("payload", "/action", "opened")),
                List.of());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "closed");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
    }

    // --- Exclude rules ---

    @Test
    void excludeEventTypeBlocksMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("event-type", null, "comment-added")));
        FilterResult result = evaluator.evaluate(filters, "comment-added", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("comment-added"));
    }

    @Test
    void excludePayloadRuleBlocksBotLogin() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "dependabot[bot]");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("/user/login"));
    }

    @Test
    void excludePayloadRuleAllowsNonMatchingValue() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "octocat");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertTrue(result.allowed());
    }

    @Test
    void excludeSlashCommandBlocksCommentStartingWithSlash() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/comment/body", "/*")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("comment").put("body", "/ready");
        FilterResult result = evaluator.evaluate(filters, "comment-added", payload);
        assertFalse(result.allowed());
    }

    // --- Combined include + exclude ---

    @Test
    void excludeOverridesInclude() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "dependabot[bot]");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
    }

    // --- Edge cases ---

    @Test
    void missingPointerFieldDoesNotMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/nonexistent/field", "*")));
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void nullFiltersAllowsAllEvents() {
        FilterResult result = evaluator.evaluate(null, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void nullPayloadWithPayloadRuleDoesNotMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*")));
        FilterResult result = evaluator.evaluate(filters, "issue-created", null);
        assertTrue(result.allowed());
    }

    @Test
    void questionMarkWildcardMatchesSingleCharacter() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "pr-c????d")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "pr-closed", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
    }

    @Test
    void exactMatchWithNoWildcards() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "pr-merged")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "pr-merged", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
    }

    @Test
    void multipleIncludeRulesAnyCanMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(
                        new EventSourceFilterRule("event-type", null, "issue-created"),
                        new EventSourceFilterRule("event-type", null, "pr-created")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "issue-created", mapper.createObjectNode()).allowed());
        assertTrue(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "comment-added", mapper.createObjectNode()).allowed());
    }
}
```

- [ ] **Step 5: Implement `EventFilterEvaluator`**

```java
package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates events against a set of include/exclude filter rules.
 */
@ApplicationScoped
public class EventFilterEvaluator {

    /**
     * Evaluates an event against the given filter configuration.
     *
     * <p>If filters is null or has empty include/exclude lists, all events pass.
     * Otherwise, include rules are checked first (event must match at least one),
     * then exclude rules (any match blocks the event).
     *
     * @param filters   the filter configuration (may be null)
     * @param eventType the event type string (e.g. "issue-created")
     * @param payload   the parsed event payload JSON (may be null)
     * @return the evaluation result
     */
    public FilterResult evaluate(EventSourceFilters filters, String eventType, JsonNode payload) {
        if (filters == null) {
            return FilterResult.ALLOWED;
        }

        List<EventSourceFilterRule> includeRules = filters.include() != null ? filters.include() : List.of();
        List<EventSourceFilterRule> excludeRules = filters.exclude() != null ? filters.exclude() : List.of();

        if (!includeRules.isEmpty()) {
            boolean matched = includeRules.stream()
                    .anyMatch(rule -> ruleMatches(rule, eventType, payload));
            if (!matched) {
                return FilterResult.blocked("no include rule matched for event type: " + eventType);
            }
        }

        for (EventSourceFilterRule rule : excludeRules) {
            if (ruleMatches(rule, eventType, payload)) {
                return FilterResult.blocked(describeRule("exclude", rule));
            }
        }

        return FilterResult.ALLOWED;
    }

    private boolean ruleMatches(EventSourceFilterRule rule, String eventType, JsonNode payload) {
        if ("event-type".equals(rule.type())) {
            return matchesWildcard(eventType, rule.pattern());
        } else if ("payload".equals(rule.type())) {
            if (payload == null || rule.pointer() == null) {
                return false;
            }
            JsonNode node = payload.at(rule.pointer());
            if (node.isMissingNode() || node.isNull()) {
                return false;
            }
            return matchesWildcard(node.asText(), rule.pattern());
        }
        return false;
    }

    private String describeRule(String phase, EventSourceFilterRule rule) {
        if ("event-type".equals(rule.type())) {
            return phase + " event-type " + rule.pattern();
        }
        return phase + " payload " + rule.pointer() + " matched " + rule.pattern();
    }

    static boolean matchesWildcard(String value, String pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }
}
```

- [ ] **Step 6: Verify tests pass**

```bash
mvn test -pl core -Dtest=EventFilterEvaluatorTest
```

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/filters/ \
        core/src/test/java/io/apitomy/axiom/core/filters/
git commit -m "feat(core): add EventFilterEvaluator with wildcard matching (#127)"
```

---

### Task 3: Database Migration and Entity Update

**Files:**
- Create: `app/src/main/resources/db/migration/V34__add_event_source_filters.sql`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/EventSourceEntity.java`

**Interfaces:**
- Consumes: nothing
- Produces: `EventSourceEntity.filters` field (String, JSON), used by Task 4 (REST layer,
  PipelineOrchestrator)

- [ ] **Step 1: Create migration V34**

```sql
-- Add filters column to event_source table
ALTER TABLE event_source ADD COLUMN filters TEXT;

-- Populate all existing event sources with default filter rules
-- These defaults replace the hardcoded shouldSkipEvent() logic:
--   *[bot] login suffix -> bot filter
--   /* comment body -> slash command filter
UPDATE event_source SET filters =
    '{"include":[],"exclude":['
    || '{"type":"payload","pointer":"/user/login","pattern":"*[bot]"},'
    || '{"type":"payload","pointer":"/comment/user/login","pattern":"*[bot]"},'
    || '{"type":"payload","pointer":"/comment/body","pattern":"/*"}'
    || ']}';
```

- [ ] **Step 2: Add `filters` field to `EventSourceEntity`**

In `EventSourceEntity.java`, add a new field after the `configuration` field:

```java
@Column(columnDefinition = "TEXT")
public String filters;
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migration/V34__add_event_source_filters.sql \
        core/src/main/java/io/apitomy/axiom/core/entities/EventSourceEntity.java
git commit -m "feat(db): add filters column to event_source with defaults (#127)"
```

---

### Task 4: Backend Integration (REST Layer + PipelineOrchestrator)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java`

**Interfaces:**
- Consumes: `EventFilterEvaluator.evaluate()` from Task 2,
  `EventSourceEntity.filters` from Task 3,
  generated `EventSourceFilters`/`EventSourceFilterRule` beans from Task 1
- Produces: filters round-trip through REST API; events filtered in the pipeline with
  activity log entries showing matched rule detail

- [ ] **Step 1: Update `applyFields()` in `EventSourcesResourceImpl`**

Add filters serialization after the labels handling (around line 113):

```java
if (data.getFilters() != null) {
    try {
        entity.filters = objectMapper.writeValueAsString(data.getFilters());
    } catch (Exception e) {
        entity.filters = null;
    }
} else {
    entity.filters = null;
}
```

- [ ] **Step 2: Update `toBean()` in `EventSourcesResourceImpl`**

Add filters deserialization after the labels line (around line 155):

```java
if (entity.filters != null) {
    try {
        bean.setFilters(objectMapper.readValue(entity.filters,
                io.apitomy.axiom.api.beans.EventSourceFilters.class));
    } catch (Exception e) {
        // ignore parse errors
    }
}
```

- [ ] **Step 3: Update `createEventSource()` to apply default filters**

In the `createEventSource()` method, after calling `applyFields(entity, data)`, add logic to
set default filters if none were provided:

```java
if (entity.filters == null) {
    try {
        entity.filters = objectMapper.writeValueAsString(defaultFilters());
    } catch (Exception e) {
        entity.filters = null;
    }
}
```

Add a private helper method that builds the default filters object:

```java
private io.apitomy.axiom.api.beans.EventSourceFilters defaultFilters() {
    io.apitomy.axiom.api.beans.EventSourceFilters filters = new io.apitomy.axiom.api.beans.EventSourceFilters();
    filters.setInclude(List.of());

    io.apitomy.axiom.api.beans.EventSourceFilterRule botRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
    botRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
    botRule.setPointer("/user/login");
    botRule.setPattern("*[bot]");

    io.apitomy.axiom.api.beans.EventSourceFilterRule commentBotRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
    commentBotRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
    commentBotRule.setPointer("/comment/user/login");
    commentBotRule.setPattern("*[bot]");

    io.apitomy.axiom.api.beans.EventSourceFilterRule slashRule = new io.apitomy.axiom.api.beans.EventSourceFilterRule();
    slashRule.setType(io.apitomy.axiom.api.beans.EventSourceFilterRule.Type.payload);
    slashRule.setPointer("/comment/body");
    slashRule.setPattern("/*");

    filters.setExclude(List.of(botRule, commentBotRule, slashRule));
    return filters;
}
```

Note: The exact generated bean class names and setter methods depend on the codegen output.
Verify the generated `EventSourceFilterRule` and `EventSourceFilters` classes after Task 1's
build step and adjust accordingly.

- [ ] **Step 4: Replace `shouldSkipEvent()` in `PipelineOrchestrator`**

Add an `@Inject` field at the top of `PipelineOrchestrator`:

```java
@Inject
EventFilterEvaluator filterEvaluator;
```

Add the import:

```java
import io.apitomy.axiom.core.filters.EventFilterEvaluator;
import io.apitomy.axiom.core.filters.EventSourceFilters;
import io.apitomy.axiom.core.filters.FilterResult;
```

- [ ] **Step 5: Replace the pre-filter block in `processEvent()`**

Replace the block at lines 174-183 (the `shouldSkipEvent` call and its handling) with:

```java
FilterResult filterResult = evaluateFilters(event);
if (!filterResult.allowed()) {
    LOG.infof("Pre-filtered event %d: %s", event.id, filterResult.matchedRule());
    QuarkusTransaction.requiringNew().run(() -> {
        logActivity(null, null, event.id, "event-pre-filtered",
                "Event pre-filtered: " + event.eventType + " — " + filterResult.matchedRule());
        markQueueEntry(queueEntryId, "completed");
    });
    return;
}
```

Add a private `evaluateFilters` method:

```java
private FilterResult evaluateFilters(EventEntity event) {
    if (event.eventSourceId == null) {
        return FilterResult.ALLOWED;
    }
    EventSourceEntity source = EventSourceEntity.findById(event.eventSourceId);
    if (source == null || source.filters == null) {
        return FilterResult.ALLOWED;
    }
    try {
        EventSourceFilters filters = objectMapper.readValue(source.filters, EventSourceFilters.class);
        JsonNode payload = objectMapper.readTree(event.payload);
        return filterEvaluator.evaluate(filters, event.eventType, payload);
    } catch (Exception e) {
        LOG.warnf("Failed to evaluate filters for event %d: %s", event.id, e.getMessage());
        return FilterResult.ALLOWED;
    }
}
```

- [ ] **Step 6: Delete `shouldSkipEvent()` and `SLASH_COMMANDS`**

Remove the `SLASH_COMMANDS` constant (lines 45-48) and the entire `shouldSkipEvent()` method
(lines 110-144) from `PipelineOrchestrator`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java \
        app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java
git commit -m "feat(app): wire filter evaluator into REST layer and pipeline (#127)"
```

---

### Task 5: Dry-Run Backend

**Files:**
- Create: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubEventClassifier.java`
- Create: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubDryRunService.java`
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraEventClassifier.java`
- Create: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraDryRunService.java`
- Create: `events/core/src/main/java/io/apitomy/axiom/events/core/DryRunEvent.java`
- Modify: `events/github/src/main/java/io/apitomy/axiom/events/github/GitHubPoller.java`
- Modify: `events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraPoller.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java`

**Interfaces:**
- Consumes: `GitHubApiClient`, `JiraApiClient` (existing),
  `EventFilterEvaluator.evaluate()` from Task 2
- Produces: `POST /event-sources/filters/dry-run` endpoint returning
  `FilterDryRunResponse`

- [ ] **Step 1: Create `DryRunEvent` record in `events/core`**

```java
package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a single event produced during a dry-run evaluation.
 *
 * @param eventType the classified event type (e.g. "issue-created", "pr-merged")
 * @param issueRef  the issue or PR reference (e.g. "owner/repo#42")
 * @param summary   human-readable summary (e.g. issue title or comment author)
 * @param payload   the wrapped event payload JSON
 */
public record DryRunEvent(String eventType, String issueRef, String summary, JsonNode payload) {
}
```

- [ ] **Step 2: Create `GitHubEventClassifier`**

Extract `determineIssueEventType`, `determinePrEventType`, all `wrap*AsWebhookPayload`
methods, and `parseGitHubTimestamp` from `GitHubPoller` into a new static utility class:

```java
package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Classifies and wraps raw GitHub API responses into event types and
 * webhook-like payloads. Shared by the poller and the dry-run service.
 */
public class GitHubEventClassifier {

    private GitHubEventClassifier() {
    }

    /**
     * Determines the event type for a GitHub issue based on timestamps.
     */
    public static String determineIssueEventType(JsonNode issue, Instant since) {
        // Exact logic moved from GitHubPoller.determineIssueEventType()
        String state = issue.path("state").asText("");
        Instant createdAt = parseGitHubTimestamp(issue.path("created_at").asText(null));
        Instant updatedAt = parseGitHubTimestamp(issue.path("updated_at").asText(null));
        Instant closedAt = parseGitHubTimestamp(issue.path("closed_at").asText(null));

        if (since == null) return null;
        if (createdAt != null && createdAt.isAfter(since)) return "issue-created";
        if ("closed".equals(state) && closedAt != null && closedAt.isAfter(since)) return "issue-closed";
        if (updatedAt != null && updatedAt.isAfter(since)) {
            if ("open".equals(state) && closedAt != null) return "issue-reopened";
            return "issue-updated";
        }
        return null;
    }

    /**
     * Determines the event type for a GitHub pull request based on timestamps.
     */
    public static String determinePrEventType(JsonNode pr, Instant since) {
        // Exact logic moved from GitHubPoller.determinePrEventType()
        String state = pr.path("state").asText("");
        Instant createdAt = parseGitHubTimestamp(pr.path("created_at").asText(null));
        Instant updatedAt = parseGitHubTimestamp(pr.path("updated_at").asText(null));
        Instant closedAt = parseGitHubTimestamp(pr.path("closed_at").asText(null));
        Instant mergedAt = parseGitHubTimestamp(pr.path("merged_at").asText(null));

        if (since == null) return null;
        if (createdAt != null && createdAt.isAfter(since)) return "pr-created";
        if (mergedAt != null && mergedAt.isAfter(since)) return "pr-merged";
        if ("closed".equals(state) && closedAt != null && closedAt.isAfter(since) && mergedAt == null)
            return "pr-closed";
        if (updatedAt != null && updatedAt.isAfter(since)) {
            if ("open".equals(state) && closedAt != null) return "pr-reopened";
            return "pr-updated";
        }
        return null;
    }

    /**
     * Wraps a raw GitHub issue JSON as a webhook-like payload.
     */
    public static ObjectNode wrapIssue(ObjectMapper mapper, JsonNode issue, String eventType) {
        // Logic moved from GitHubPoller.wrapIssueAsWebhookPayload()
        String action = switch (eventType) {
            case "issue-created" -> "opened";
            case "issue-closed" -> "closed";
            case "issue-reopened" -> "reopened";
            default -> "edited";
        };
        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("issue", issue);
        return node;
    }

    /**
     * Wraps a raw GitHub comment JSON as a webhook-like payload.
     */
    public static ObjectNode wrapComment(ObjectMapper mapper, JsonNode comment, int issueNumber) {
        ObjectNode node = mapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("issue").put("number", issueNumber);
        node.set("comment", comment);
        return node;
    }

    /**
     * Wraps a raw GitHub PR JSON as a webhook-like payload.
     */
    public static ObjectNode wrapPullRequest(ObjectMapper mapper, JsonNode pr, String eventType) {
        String action = switch (eventType) {
            case "pr-created" -> "opened";
            case "pr-closed" -> "closed";
            case "pr-merged" -> "closed";
            case "pr-reopened" -> "reopened";
            default -> "edited";
        };
        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("pull_request", pr);
        return node;
    }

    /**
     * Wraps a raw GitHub review comment JSON as a webhook-like payload.
     */
    public static ObjectNode wrapReviewComment(ObjectMapper mapper, JsonNode comment, int prNumber) {
        ObjectNode node = mapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("pull_request").put("number", prNumber);
        node.set("comment", comment);
        return node;
    }

    /**
     * Parses a GitHub API timestamp string into an Instant.
     */
    public static Instant parseGitHubTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
```

- [ ] **Step 3: Update `GitHubPoller` to delegate to `GitHubEventClassifier`**

Replace the private `determineIssueEventType`, `determinePrEventType`,
`wrapIssueAsWebhookPayload`, `wrapCommentAsWebhookPayload`, `wrapPrAsWebhookPayload`,
`wrapReviewCommentAsWebhookPayload`, and `parseGitHubTimestamp` methods with calls to the
corresponding static methods on `GitHubEventClassifier`.

For example, in `processIssues()`, change:
```java
String eventType = determineIssueEventType(issue, since);
```
to:
```java
String eventType = GitHubEventClassifier.determineIssueEventType(issue, since);
```

And:
```java
JsonNode wrappedPayload = wrapIssueAsWebhookPayload(issue, repoFullName, eventType);
```
to:
```java
JsonNode wrappedPayload = GitHubEventClassifier.wrapIssue(objectMapper, issue, eventType);
```

Apply the same pattern across all four `process*` methods. Then delete the now-unused private
methods from `GitHubPoller`.

Note: The existing `GitHubPollerPrEventTypeTest` directly calls `poller.determinePrEventType()`.
Update it to call `GitHubEventClassifier.determinePrEventType()` instead and remove the
`GitHubPoller` instance field.

- [ ] **Step 4: Create `GitHubDryRunService`**

```java
package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.events.core.DryRunEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches recent events from a GitHub repository and classifies them
 * for dry-run filter evaluation. Does not ingest or persist anything.
 */
@ApplicationScoped
public class GitHubDryRunService {

    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Inject
    GitHubApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Fetches recent events from the GitHub repository described by the
     * configuration and classifies them into event types with payloads.
     *
     * @param owner  repository owner
     * @param name   repository name
     * @param token  authentication token
     * @return list of classified events
     */
    public List<DryRunEvent> fetchRecentEvents(String owner, String name, String token) {
        Instant since = Instant.now().minus(LOOKBACK);
        List<DryRunEvent> events = new ArrayList<>();

        var issueResult = apiClient.fetchIssuesUpdatedSince(owner, name, since, token);
        if (issueResult.data() != null && issueResult.data().isArray()) {
            for (JsonNode issue : issueResult.data()) {
                if (issue.has("pull_request")) continue;
                String eventType = GitHubEventClassifier.determineIssueEventType(issue, since);
                if (eventType != null) {
                    String ref = owner + "/" + name + "#" + issue.path("number").asInt();
                    String summary = issue.path("title").asText("");
                    ObjectNode payload = GitHubEventClassifier.wrapIssue(objectMapper, issue, eventType);
                    events.add(new DryRunEvent(eventType, ref, summary, payload));
                }
            }
        }

        var commentResult = apiClient.fetchCommentsUpdatedSince(owner, name, since, token);
        if (commentResult.data() != null && commentResult.data().isArray()) {
            for (JsonNode comment : commentResult.data()) {
                Instant createdAt = GitHubEventClassifier.parseGitHubTimestamp(
                        comment.path("created_at").asText(null));
                if (createdAt != null && createdAt.isAfter(since)) {
                    String issueUrl = comment.path("issue_url").asText("");
                    int issueNumber = extractIssueNumber(issueUrl);
                    String ref = owner + "/" + name + "#" + issueNumber;
                    String author = comment.path("user").path("login").asText("unknown");
                    ObjectNode payload = GitHubEventClassifier.wrapComment(
                            objectMapper, comment, issueNumber);
                    events.add(new DryRunEvent("comment-added", ref,
                            "Comment by " + author, payload));
                }
            }
        }

        var prResult = apiClient.fetchPullsUpdatedSince(owner, name, token);
        if (prResult.data() != null && prResult.data().isArray()) {
            for (JsonNode pr : prResult.data()) {
                String eventType = GitHubEventClassifier.determinePrEventType(pr, since);
                if (eventType != null) {
                    String ref = owner + "/" + name + "#" + pr.path("number").asInt();
                    String summary = pr.path("title").asText("");
                    ObjectNode payload = GitHubEventClassifier.wrapPullRequest(
                            objectMapper, pr, eventType);
                    events.add(new DryRunEvent(eventType, ref, summary, payload));
                }
            }
        }

        var reviewResult = apiClient.fetchPullReviewCommentsUpdatedSince(
                owner, name, since, token);
        if (reviewResult.data() != null && reviewResult.data().isArray()) {
            for (JsonNode comment : reviewResult.data()) {
                Instant createdAt = GitHubEventClassifier.parseGitHubTimestamp(
                        comment.path("created_at").asText(null));
                if (createdAt != null && createdAt.isAfter(since)) {
                    String prUrl = comment.path("pull_request_url").asText("");
                    int prNumber = extractIssueNumber(prUrl);
                    String ref = owner + "/" + name + "#" + prNumber;
                    String author = comment.path("user").path("login").asText("unknown");
                    ObjectNode payload = GitHubEventClassifier.wrapReviewComment(
                            objectMapper, comment, prNumber);
                    events.add(new DryRunEvent("pr-review-comment", ref,
                            "Review comment by " + author, payload));
                }
            }
        }

        return events;
    }

    private int extractIssueNumber(String url) {
        if (url == null || url.isBlank()) return 0;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            try {
                return Integer.parseInt(url.substring(lastSlash + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
```

- [ ] **Step 5: Create `JiraEventClassifier`**

Extract `determineEventType`, `buildIssuePayload`, `buildCommentPayload`, and
`parseJiraTimestamp` from `JiraPoller` into a static utility class following the same pattern
as `GitHubEventClassifier`. The methods should be:

- `JiraEventClassifier.determineEventType(JsonNode fields, Instant since): String`
- `JiraEventClassifier.buildIssuePayload(ObjectMapper, JsonNode issue, JsonNode fields,
  String baseUrl, String eventType): ObjectNode`
- `JiraEventClassifier.buildCommentPayload(ObjectMapper, JsonNode issue, JsonNode fields,
  JsonNode comment, String baseUrl): ObjectNode`
- `JiraEventClassifier.parseJiraTimestamp(String timestamp): Instant`

- [ ] **Step 6: Update `JiraPoller` to delegate to `JiraEventClassifier`**

Same pattern as Step 3 — replace private method calls with
`JiraEventClassifier.methodName(...)` and delete the now-unused private methods.

- [ ] **Step 7: Create `JiraDryRunService`**

Follow the same pattern as `GitHubDryRunService`:

```java
package io.apitomy.axiom.events.jira;

import io.apitomy.axiom.events.core.DryRunEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
// ... other imports

@ApplicationScoped
public class JiraDryRunService {

    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Inject
    JiraApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Fetches recent events from the Jira project described by the
     * configuration and classifies them into event types with payloads.
     *
     * @param baseUrl  Jira base URL
     * @param project  Jira project key
     * @param credentials  authentication credentials
     * @return list of classified events
     */
    public List<DryRunEvent> fetchRecentEvents(String baseUrl, String project,
                                                String credentials) {
        Instant since = Instant.now().minus(LOOKBACK);
        List<DryRunEvent> events = new ArrayList<>();

        var result = apiClient.fetchIssuesUpdatedSince(baseUrl, project, since, credentials);
        if (result.data() != null) {
            JsonNode issues = result.data().path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    JsonNode fields = issue.path("fields");
                    String key = issue.path("key").asText("");
                    String summary = fields.path("summary").asText("");
                    String eventType = JiraEventClassifier.determineEventType(fields, since);
                    if (eventType != null) {
                        ObjectNode payload = JiraEventClassifier.buildIssuePayload(
                                objectMapper, issue, fields, baseUrl, eventType);
                        events.add(new DryRunEvent(eventType, key, summary, payload));
                    }
                    // Check for new comments
                    JsonNode comments = fields.path("comment").path("comments");
                    if (comments.isArray()) {
                        for (JsonNode comment : comments) {
                            Instant commentCreated = JiraEventClassifier.parseJiraTimestamp(
                                    comment.path("created").asText(null));
                            if (commentCreated != null && commentCreated.isAfter(since)) {
                                String author = comment.path("author")
                                        .path("displayName").asText("unknown");
                                ObjectNode payload = JiraEventClassifier.buildCommentPayload(
                                        objectMapper, issue, fields, comment, baseUrl);
                                events.add(new DryRunEvent("comment-added", key,
                                        "Comment by " + author, payload));
                            }
                        }
                    }
                }
            }
        }

        return events;
    }
}
```

- [ ] **Step 8: Implement `dryRunFilters()` in `EventSourcesResourceImpl`**

Add injected fields:

```java
@Inject
GitHubDryRunService githubDryRunService;

@Inject
JiraDryRunService jiraDryRunService;

@Inject
EventFilterEvaluator filterEvaluator;
```

Add the imports for the core filter types and `DryRunEvent`.

Implement the method (the generated interface will have the signature from the OpenAPI spec):

```java
@Override
public FilterDryRunResponse dryRunFilters(FilterDryRunRequest request) {
    // Convert API filter beans to core filter model
    EventSourceFilters coreFilters = toCoreFilters(request.getFilters());

    // Fetch recent events from the source
    List<DryRunEvent> events = fetchDryRunEvents(request);

    // Evaluate filters against each event
    List<FilterDryRunResult> results = new ArrayList<>();
    int allowed = 0;
    int blocked = 0;

    for (DryRunEvent event : events) {
        FilterResult filterResult = filterEvaluator.evaluate(
                coreFilters, event.eventType(), event.payload());
        FilterDryRunResult result = new FilterDryRunResult();
        result.setEventType(event.eventType());
        result.setIssueRef(event.issueRef());
        result.setSummary(event.summary());
        result.setAllowed(filterResult.allowed());
        result.setMatchedRule(filterResult.matchedRule());
        results.add(result);
        if (filterResult.allowed()) allowed++;
        else blocked++;
    }

    FilterDryRunResponse response = new FilterDryRunResponse();
    response.setResults(results);
    response.setTotalEvaluated(events.size());
    response.setTotalAllowed(allowed);
    response.setTotalBlocked(blocked);
    return response;
}
```

Add the helper methods:

```java
private List<DryRunEvent> fetchDryRunEvents(FilterDryRunRequest request) {
    String sourceType = request.getSourceType().value();
    Map<String, Object> config = request.getConfiguration();
    String token = resolveSecretValue(request.getSecretName());

    if ("github".equals(sourceType)) {
        String owner = String.valueOf(config.get("owner"));
        String name = String.valueOf(config.get("name"));
        return githubDryRunService.fetchRecentEvents(owner, name, token);
    } else if ("jira".equals(sourceType)) {
        String baseUrl = String.valueOf(config.get("baseUrl"));
        String project = String.valueOf(config.get("project"));
        return jiraDryRunService.fetchRecentEvents(baseUrl, project, token);
    }
    return List.of();
}

private EventSourceFilters toCoreFilters(
        io.apitomy.axiom.api.beans.EventSourceFilters apiFilters) {
    if (apiFilters == null) return EventSourceFilters.allowAll();
    List<EventSourceFilterRule> include = apiFilters.getInclude() != null
            ? apiFilters.getInclude().stream()
                .map(r -> new EventSourceFilterRule(
                        r.getType().value(), r.getPointer(), r.getPattern()))
                .toList()
            : List.of();
    List<EventSourceFilterRule> exclude = apiFilters.getExclude() != null
            ? apiFilters.getExclude().stream()
                .map(r -> new EventSourceFilterRule(
                        r.getType().value(), r.getPointer(), r.getPattern()))
                .toList()
            : List.of();
    return new EventSourceFilters(include, exclude);
}
```

Note: `resolveSecretValue()` should follow the same pattern the pollers use for resolving
secrets. Check how `GitHubPoller.resolveToken()` works and replicate the logic, or extract
it into a shared service if one doesn't already exist.

- [ ] **Step 9: Commit**

```bash
git add events/core/src/main/java/io/apitomy/axiom/events/core/DryRunEvent.java \
        events/github/src/main/java/io/apitomy/axiom/events/github/GitHubEventClassifier.java \
        events/github/src/main/java/io/apitomy/axiom/events/github/GitHubDryRunService.java \
        events/github/src/main/java/io/apitomy/axiom/events/github/GitHubPoller.java \
        events/github/src/test/java/io/apitomy/axiom/events/github/GitHubPollerPrEventTypeTest.java \
        events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraEventClassifier.java \
        events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraDryRunService.java \
        events/jira/src/main/java/io/apitomy/axiom/events/jira/JiraPoller.java \
        app/src/main/java/io/apitomy/axiom/app/rest/EventSourcesResourceImpl.java
git commit -m "feat(events): extract classifiers and implement dry-run endpoint (#127)"
```

---

### Task 6: Frontend

**Files:**
- Modify: `ui/src/config/api.ts`
- Modify: `ui/src/pages/EventSourceDetailPage.tsx`

**Interfaces:**
- Consumes: `GET /event-sources/{id}` returning `EventSource` with `filters` field,
  `PUT /event-sources/{id}` accepting `filters`,
  `POST /event-sources/filters/dry-run` endpoint
- Produces: Filters tab UI with rule editing and dry-run preview

- [ ] **Step 1: Add TypeScript filter types to `api.ts`**

After the `EventSourceLog` interface (around line 847), add:

```ts
export interface EventSourceFilterRule {
    type: "event-type" | "payload";
    pointer?: string;
    pattern: string;
}

export interface EventSourceFilters {
    include: EventSourceFilterRule[];
    exclude: EventSourceFilterRule[];
}

export interface FilterDryRunRequest {
    sourceType: string;
    configuration: Record<string, string>;
    secretName?: string;
    filters: EventSourceFilters;
}

export interface FilterDryRunResult {
    eventType: string;
    issueRef: string;
    summary: string;
    allowed: boolean;
    matchedRule?: string;
}

export interface FilterDryRunResponse {
    results: FilterDryRunResult[];
    totalEvaluated: number;
    totalAllowed: number;
    totalBlocked: number;
}
```

- [ ] **Step 2: Add `filters` to `EventSource` interface**

Update the `EventSource` interface to include the filters field:

```ts
export interface EventSource {
    id: number;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    pollInterval?: number;
    secretName?: string;
    configuration?: Record<string, string>;
    labels?: string[];
    filters?: EventSourceFilters;
}
```

- [ ] **Step 3: Add `dryRunFilters()` API function**

After the existing event source API functions, add:

```ts
export async function dryRunFilters(request: FilterDryRunRequest): Promise<FilterDryRunResponse> {
    const response = await fetch(`${API}/event-sources/filters/dry-run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
    });
    if (!response.ok) throw new Error(`Dry run failed: ${response.statusText}`);
    return response.json();
}
```

- [ ] **Step 4: Create the `FiltersTab` component**

Add a new inline function component in `EventSourceDetailPage.tsx` (after the `LogsTab`
component). This is the largest piece of frontend work. The component manages:

- Local state for the edited filters (cloned from the source's filters on load)
- Include and exclude rule tables
- Add/edit/delete rule interactions
- Dirty tracking and save
- Dry-run preview

```tsx
function FiltersTab({ source, onSave }: {
    source: EventSource;
    onSave: (filters: EventSourceFilters) => Promise<void>;
}) {
    const [filters, setFilters] = useState<EventSourceFilters>(
        source.filters ?? { include: [], exclude: [] }
    );
    const [dirty, setDirty] = useState(false);
    const [saving, setSaving] = useState(false);
    const [dryRunResults, setDryRunResults] = useState<FilterDryRunResponse | null>(null);
    const [dryRunLoading, setDryRunLoading] = useState(false);

    // State for the "add rule" form
    const [addingTo, setAddingTo] = useState<"include" | "exclude" | null>(null);
    const [newRuleType, setNewRuleType] = useState<"event-type" | "payload">("event-type");
    const [newRulePointer, setNewRulePointer] = useState("");
    const [newRulePattern, setNewRulePattern] = useState("");

    const handleAddRule = (list: "include" | "exclude") => {
        const rule: EventSourceFilterRule = {
            type: newRuleType,
            pattern: newRulePattern,
            ...(newRuleType === "payload" ? { pointer: newRulePointer } : {}),
        };
        const updated = { ...filters };
        updated[list] = [...updated[list], rule];
        setFilters(updated);
        setDirty(true);
        setAddingTo(null);
        setNewRuleType("event-type");
        setNewRulePointer("");
        setNewRulePattern("");
    };

    const handleDeleteRule = (list: "include" | "exclude", index: number) => {
        const updated = { ...filters };
        updated[list] = updated[list].filter((_, i) => i !== index);
        setFilters(updated);
        setDirty(true);
    };

    const handleSave = async () => {
        setSaving(true);
        try {
            await onSave(filters);
            setDirty(false);
        } finally {
            setSaving(false);
        }
    };

    const handleDryRun = async () => {
        setDryRunLoading(true);
        try {
            const request: FilterDryRunRequest = {
                sourceType: source.sourceType,
                configuration: source.configuration ?? {},
                secretName: source.secretName,
                filters,
            };
            const response = await dryRunFilters(request);
            setDryRunResults(response);
        } catch (e) {
            console.error("Dry run failed:", e);
        } finally {
            setDryRunLoading(false);
        }
    };

    // ... render logic below
}
```

The render method should produce:
- A page section header with "Save" button (disabled when not dirty)
- **Include Rules** section: table of rules + "Add rule" button
- **Exclude Rules** section: table of rules + "Add rule" button
- When `addingTo` is set, show an inline form row with: type `FormSelect` (event-type /
  payload), pointer `TextInput` (shown only when type=payload), pattern `TextInput`, and
  Add/Cancel buttons
- **Test Filters** button below the rule tables
- When `dryRunResults` is set, show:
  - Summary line: "N allowed, N blocked out of N events"
  - Results table with columns: Result (green check / red X `Label`), Event Type, Reference,
    Summary, Matched Rule

Each rule table row has columns: Type, Pointer (blank for event-type), Pattern, and a Delete
button (TrashIcon or similar).

Use PatternFly components consistent with the existing page: `Card`, `CardBody`, `Table`,
`Thead`, `Tbody`, `Tr`, `Th`, `Td`, `Button`, `FormSelect`, `TextInput`, `Label`.

- [ ] **Step 5: Add the Filters tab to `EventSourceDetailPage`**

In the `Tabs` section (around line 158), add the new tab between Info and Poll Logs:

```tsx
<Tab eventKey={1} title={<TabTitleText>Filters</TabTitleText>}>
    <TabContent id="filters-tab">
        {source && (
            <FiltersTab
                source={source}
                onSave={async (filters) => {
                    await updateEventSource(source.id, {
                        ...source,
                        filters,
                    } as NewEventSource);
                    loadData();
                }}
            />
        )}
    </TabContent>
</Tab>
```

Update the existing Poll Logs tab `eventKey` from `1` to `2`, and update any references to
`activeTab === 1` for the logs tab to `activeTab === 2`.

Add the new imports at the top of the file:

```ts
import {
    type EventSourceFilters,
    type EventSourceFilterRule,
    type FilterDryRunRequest,
    type FilterDryRunResponse,
    dryRunFilters,
} from "../config/api";
```

- [ ] **Step 6: Commit**

```bash
git add ui/src/config/api.ts ui/src/pages/EventSourceDetailPage.tsx
git commit -m "feat(ui): add Filters tab with config editor and dry-run preview (#127)"
```
