# Agent Capabilities Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace free-form comma-separated agent capabilities with a glob-pattern-based
capability system using a dedicated `agent_capability` table, add `slug` fields to report
definitions and scheduled jobs, and wire capability matching into all AI workload routing.

**Architecture:** New `agent_capability` join table replaces the `capabilities` TEXT column on
`agent`. A `GlobMatcher` utility handles pattern matching (e.g. `action:*`, `report:daily-*`).
All three execution services (`TaskExecutionService`, `ReportExecutionService`,
`ScheduledJobExecutionService`) construct capability requirement strings and route through
`AgentPool` with strict matching — no fallback to unmatched agents.

**Tech Stack:** Java 25 / Quarkus 3.33 / Panache / Flyway / H2+PostgreSQL (backend), React 19
/ PatternFly 6 (frontend)

**Spec:** `docs/superpowers/specs/2026-08-24-agent-capabilities-redesign.md`

## Global Constraints

- Follow contract-first development: OpenAPI spec changes first, then `mvn install` to
  generate interfaces, then implement.
- REST resource impls must implement generated interfaces — no `@Path` on impl classes.
- Use generated beans from `io.apitomy.axiom.api.beans` for request/response types.
- Entities use Panache active record style (public fields, extend `PanacheEntityBase`).
- Do not run tests or Maven builds automatically — the user handles compilation and testing.
- Do not include Claude attribution in commit messages.

---

### Task 1: GlobMatcher Utility

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/util/GlobMatcher.java`
- Create: `core/src/test/java/io/apitomy/axiom/core/util/GlobMatcherTest.java`

**Interfaces:**
- Produces: `GlobMatcher.matches(String pattern, String value): boolean` — used by
  `AgentPool` in Task 4

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/io/apitomy/axiom/core/util/GlobMatcherTest.java`:

```java
package io.apitomy.axiom.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {

    @ParameterizedTest
    @CsvSource({
        // Global wildcard
        "*, action:auto-tag, true",
        "*, report:daily-github-activity, true",
        "*, job:test-job, true",

        // Flow-level wildcards
        "action:*, action:auto-tag, true",
        "action:*, action:git-label, true",
        "report:*, report:daily-github-activity, true",
        "job:*, job:nightly-cleanup, true",

        // Exact matches
        "action:auto-tag, action:auto-tag, true",
        "report:daily-github-activity, report:daily-github-activity, true",
        "job:test-job, job:test-job, true",

        // Prefix wildcards
        "action:git-*, action:git-label, true",
        "action:git-*, action:git-triage, true",
        "action:git-*, action:auto-tag, false",
        "report:daily-*, report:daily-github-activity, true",
        "report:daily-*, report:weekly-summary, false",
        "job:github-*, job:github-sync, true",
        "job:github-*, job:nightly-cleanup, false",

        // Case insensitivity
        "ACTION:AUTO-TAG, action:auto-tag, true",
        "action:Auto-Tag, action:auto-tag, true",

        // No match
        "action:auto-tag, action:git-label, false",
        "report:*, action:auto-tag, false",
        "job:*, report:daily, false",

        // Wildcard in middle
        "action:auto-*-issues, action:auto-tag-issues, true",
        "action:auto-*-issues, action:auto-label-issues, true",
        "action:auto-*-issues, action:auto-tag, false",
    })
    void testMatches(String pattern, String value, boolean expected) {
        assertEquals(expected, GlobMatcher.matches(pattern, value));
    }

    @Test
    void testNullAndEmptyInputs() {
        assertFalse(GlobMatcher.matches(null, "action:auto-tag"));
        assertFalse(GlobMatcher.matches("action:*", null));
        assertFalse(GlobMatcher.matches(null, null));
        assertFalse(GlobMatcher.matches("", "action:auto-tag"));
        assertFalse(GlobMatcher.matches("action:*", ""));
    }

    @Test
    void testAnyCapabilityMatches() {
        var capabilities = java.util.List.of("report:*", "action:auto-tag");
        assertTrue(GlobMatcher.anyMatches(capabilities, "report:daily-github"));
        assertTrue(GlobMatcher.anyMatches(capabilities, "action:auto-tag"));
        assertFalse(GlobMatcher.anyMatches(capabilities, "action:git-label"));
        assertFalse(GlobMatcher.anyMatches(capabilities, "job:nightly"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl core -Dtest=GlobMatcherTest -f pom.xml`
Expected: Compilation failure — `GlobMatcher` class does not exist.

- [ ] **Step 3: Implement GlobMatcher**

Create `core/src/main/java/io/apitomy/axiom/core/util/GlobMatcher.java`:

```java
package io.apitomy.axiom.core.util;

import java.util.List;
import java.util.regex.Pattern;

public final class GlobMatcher {

    private GlobMatcher() {
    }

    /**
     * Checks whether a glob pattern matches a value. The only special character
     * is {@code *}, which matches any sequence of characters. Matching is
     * case-insensitive.
     */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isEmpty() || value == null || value.isEmpty()) {
            return false;
        }
        String regex = globToRegex(pattern.toLowerCase());
        return Pattern.matches(regex, value.toLowerCase());
    }

    /**
     * Returns {@code true} if any capability pattern in the list matches the
     * given value.
     */
    public static boolean anyMatches(List<String> capabilities, String value) {
        if (capabilities == null || value == null) {
            return false;
        }
        return capabilities.stream().anyMatch(cap -> matches(cap, value));
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl core -Dtest=GlobMatcherTest -f pom.xml`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/util/GlobMatcher.java \
       core/src/test/java/io/apitomy/axiom/core/util/GlobMatcherTest.java
git commit -m "feat: add GlobMatcher utility for agent capability pattern matching"
```

---

### Task 2: DB Migration + Entities

**Files:**
- Create: `app/src/main/resources/db/migration/V46__agent_capabilities_and_slugs.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/AgentCapabilityEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/AgentEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/ReportDefinitionEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/ScheduledJobEntity.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/util/SlugUtil.java`
- Create: `core/src/test/java/io/apitomy/axiom/core/util/SlugUtilTest.java`

**Interfaces:**
- Produces: `AgentCapabilityEntity` with fields `agentId: Long`, `capability: String`
- Produces: `SlugUtil.slugify(String name): String`
- Produces: `AgentEntity` with `capabilities` field removed; capabilities accessed via
  `AgentCapabilityEntity.list("agentId", id)`
- Produces: `ReportDefinitionEntity.slug: String`
- Produces: `ScheduledJobEntity.slug: String`

- [ ] **Step 1: Create the DB migration**

Create `app/src/main/resources/db/migration/V46__agent_capabilities_and_slugs.sql`:

```sql
-- Create agent_capability table
CREATE TABLE agent_capability (
    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    capability VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, capability)
);

-- Migrate existing comma-separated capabilities into the new table.
-- Prefix non-wildcard values with 'action:'.
INSERT INTO agent_capability (agent_id, capability)
SELECT a.id, CASE WHEN TRIM(c.cap) = '*' THEN '*' ELSE 'action:' || TRIM(c.cap) END
FROM agent a,
     LATERAL regexp_split_to_table(a.capabilities, ',') AS c(cap)
WHERE a.capabilities IS NOT NULL AND a.capabilities <> '';

-- Drop the old column
ALTER TABLE agent DROP COLUMN capabilities;

-- Add slug columns
ALTER TABLE report_definition ADD COLUMN slug VARCHAR(255);
ALTER TABLE scheduled_job ADD COLUMN slug VARCHAR(255);

-- Backfill slugs from existing names (lowercase, spaces to hyphens, strip non-alphanumeric)
UPDATE report_definition SET slug = LOWER(REGEXP_REPLACE(REPLACE(name, ' ', '-'), '[^a-z0-9-]', '', 'g'));
UPDATE scheduled_job SET slug = LOWER(REGEXP_REPLACE(REPLACE(name, ' ', '-'), '[^a-z0-9-]', '', 'g'));

-- Add unique constraints
ALTER TABLE report_definition ADD CONSTRAINT uq_report_definition_slug UNIQUE (slug);
ALTER TABLE scheduled_job ADD CONSTRAINT uq_scheduled_job_slug UNIQUE (slug);
```

- [ ] **Step 2: Write SlugUtil tests**

Create `core/src/test/java/io/apitomy/axiom/core/util/SlugUtilTest.java`:

```java
package io.apitomy.axiom.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SlugUtilTest {

    @ParameterizedTest
    @CsvSource({
        "Daily GitHub Activity, daily-github-activity",
        "auto-tag, auto-tag",
        "Test Scheduled Job, test-scheduled-job",
        "My Report!!, my-report",
        "  Spaces  Everywhere  , spaces-everywhere",
        "UPPER CASE NAME, upper-case-name",
        "already-a-slug, already-a-slug",
        "multiple---dashes, multiple-dashes",
    })
    void testSlugify(String input, String expected) {
        assertEquals(expected, SlugUtil.slugify(input));
    }

    @Test
    void testNullAndEmpty() {
        assertNull(SlugUtil.slugify(null));
        assertEquals("", SlugUtil.slugify(""));
    }
}
```

- [ ] **Step 3: Implement SlugUtil**

Create `core/src/main/java/io/apitomy/axiom/core/util/SlugUtil.java`:

```java
package io.apitomy.axiom.core.util;

public final class SlugUtil {

    private SlugUtil() {
    }

    /**
     * Converts a name into a URL-safe slug: lowercase, spaces to hyphens,
     * non-alphanumeric characters stripped, consecutive hyphens collapsed.
     */
    public static String slugify(String name) {
        if (name == null) {
            return null;
        }
        return name.trim()
                .toLowerCase()
                .replace(' ', '-')
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }
}
```

- [ ] **Step 4: Run SlugUtil tests**

Run: `mvn test -pl core -Dtest=SlugUtilTest -f pom.xml`
Expected: All tests PASS.

- [ ] **Step 5: Create AgentCapabilityEntity**

Create `core/src/main/java/io/apitomy/axiom/core/entities/AgentCapabilityEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "agent_capability")
@IdClass(AgentCapabilityEntity.AgentCapabilityId.class)
public class AgentCapabilityEntity extends PanacheEntityBase {

    @Id
    public Long agentId;

    @Id
    public String capability;

    /**
     * Returns all capability strings for the given agent.
     */
    public static List<String> findCapabilities(Long agentId) {
        return find("agentId", agentId)
                .stream()
                .map(e -> ((AgentCapabilityEntity) e).capability)
                .toList();
    }

    /**
     * Replaces all capabilities for the given agent.
     */
    public static void setCapabilities(Long agentId, List<String> capabilities) {
        delete("agentId", agentId);
        if (capabilities != null) {
            for (String cap : capabilities) {
                AgentCapabilityEntity entity = new AgentCapabilityEntity();
                entity.agentId = agentId;
                entity.capability = cap.trim();
                entity.persist();
            }
        }
    }

    public static class AgentCapabilityId implements Serializable {
        public Long agentId;
        public String capability;

        public AgentCapabilityId() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgentCapabilityId that)) return false;
            return Objects.equals(agentId, that.agentId)
                    && Objects.equals(capability, that.capability);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentId, capability);
        }
    }
}
```

- [ ] **Step 6: Update AgentEntity — remove capabilities field and helpers**

Modify `core/src/main/java/io/apitomy/axiom/core/entities/AgentEntity.java`:

- Remove the `capabilities` field (line 29)
- Remove the `capabilitiesAsList()` method
- Remove the `hasCapability(String)` method
- Remove `import java.util.Arrays;` if no longer needed

- [ ] **Step 7: Add slug field to ReportDefinitionEntity**

Modify `core/src/main/java/io/apitomy/axiom/core/entities/ReportDefinitionEntity.java`:

Add field after `name`:

```java
public String slug;
```

- [ ] **Step 8: Add slug field to ScheduledJobEntity**

Modify `core/src/main/java/io/apitomy/axiom/core/entities/ScheduledJobEntity.java`:

Add field after `name`:

```java
public String slug;
```

- [ ] **Step 9: Commit**

```bash
git add app/src/main/resources/db/migration/V46__agent_capabilities_and_slugs.sql \
       core/src/main/java/io/apitomy/axiom/core/entities/AgentCapabilityEntity.java \
       core/src/main/java/io/apitomy/axiom/core/entities/AgentEntity.java \
       core/src/main/java/io/apitomy/axiom/core/entities/ReportDefinitionEntity.java \
       core/src/main/java/io/apitomy/axiom/core/entities/ScheduledJobEntity.java \
       core/src/main/java/io/apitomy/axiom/core/util/SlugUtil.java \
       core/src/test/java/io/apitomy/axiom/core/util/SlugUtilTest.java
git commit -m "feat: add agent_capability table, slug fields, and SlugUtil"
```

---

### Task 3: AgentPool Glob Matching

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/AgentPool.java`
- Modify: `app/src/test/java/io/apitomy/axiom/app/AgentsResourceTest.java` (or create a
  dedicated AgentPool test if one doesn't exist)

**Interfaces:**
- Consumes: `GlobMatcher.anyMatches(List<String>, String)` from Task 1
- Consumes: `AgentCapabilityEntity.findCapabilities(Long)` from Task 2
- Produces: `AgentPool.tryLease(String capability, Long agentEntityId, String workloadType, Long workloadId): Optional<AgentLease>` — same signature, new matching logic

- [ ] **Step 1: Update AgentPool.tryLease() matching logic**

Modify `app/src/main/java/io/apitomy/axiom/app/AgentPool.java`:

Replace the `tryLease` method body. Key changes:
- Tier 2: use `GlobMatcher.anyMatches(AgentCapabilityEntity.findCapabilities(agent.id), capability)` instead of `agent.hasCapability(capability)`
- Remove Tier 3 (fallback) entirely — if no agent matches, return `Optional.empty()`

```java
import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.util.GlobMatcher;

// ...

@Transactional
public Optional<AgentLease> tryLease(String capability, Long agentEntityId,
                                      String workloadType, Long workloadId) {
    // Tier 1: explicit agent assignment
    if (agentEntityId != null) {
        AgentEntity agent = AgentEntity.findById(agentEntityId);
        if (agent != null && agent.enabled && !isLeased(agent.id)) {
            return Optional.of(createLease(agent, workloadType, workloadId));
        }
        return Optional.empty();
    }

    // Tier 2: capability match via glob patterns
    List<AgentEntity> agents = AgentEntity.list("enabled = true ORDER BY priority ASC");
    for (AgentEntity agent : agents) {
        if (!isLeased(agent.id)) {
            List<String> capabilities = AgentCapabilityEntity.findCapabilities(agent.id);
            if (capability == null || GlobMatcher.anyMatches(capabilities, capability)) {
                return Optional.of(createLease(agent, workloadType, workloadId));
            }
        }
    }

    // No fallback — strict matching
    if (capability != null) {
        Log.warnf("No agent with capability matching '%s' is available", capability);
    }
    return Optional.empty();
}
```

Note: when `capability` is `null` (direct SPI calls that don't need routing), any idle agent
matches. This preserves backward compatibility for internal callers that don't yet construct
requirement strings.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/AgentPool.java
git commit -m "feat: use glob matching for agent capability routing, remove fallback tier"
```

---

### Task 4: Caller Migrations + Manager + Seed Data

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ReportExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ScheduledJobExecutionService.java`
- Modify: `manager/src/main/java/io/apitomy/axiom/manager/ManagerPromptBuilder.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java`
- Modify: `manager/src/test/java/io/apitomy/axiom/manager/ManagerPromptBuilderTest.java`

**Interfaces:**
- Consumes: `AgentPool.tryLease(String, Long, String, Long)` from Task 3
- Consumes: `ReportDefinitionEntity.slug` and `ScheduledJobEntity.slug` from Task 2
- Consumes: `SlugUtil.slugify(String)` from Task 2
- Consumes: `AgentCapabilityEntity.setCapabilities(Long, List<String>)` from Task 2

- [ ] **Step 1: Update TaskExecutionService**

Modify `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`:

Change the `tryLease` call (around line 145) from:

```java
Optional<AgentLease> lease = agentPool.tryLease(
    task.actionType, task.agentId, "task", task.id);
```

to:

```java
String capability = "action:" + task.actionType;
Optional<AgentLease> lease = agentPool.tryLease(
    capability, task.agentId, "task", task.id);
```

- [ ] **Step 2: Update ReportExecutionService**

Modify `app/src/main/java/io/apitomy/axiom/app/ReportExecutionService.java`:

Change the `tryLease` call (around line 98) from:

```java
Optional<AgentLease> lease = agentPool.tryLease(
    null, null, "report", report.id);
```

to:

```java
import io.apitomy.axiom.core.util.SlugUtil;

// ...

String slug = definition.slug != null ? definition.slug : SlugUtil.slugify(definition.name);
String capability = "report:" + slug;
Optional<AgentLease> lease = agentPool.tryLease(
    capability, null, "report", report.id);
```

Where `definition` is the `ReportDefinitionEntity` — it should already be in scope (look
for where `definition` or `reportDefinition` is loaded in the method).

- [ ] **Step 3: Update ScheduledJobExecutionService**

Modify `app/src/main/java/io/apitomy/axiom/app/ScheduledJobExecutionService.java`:

Change the `tryLease` call (around line 112) from:

```java
Optional<AgentLease> lease = agentPool.tryLease(
    null, null, "scheduled-job", run.id);
```

to:

```java
import io.apitomy.axiom.core.util.SlugUtil;

// ...

String slug = job.slug != null ? job.slug : SlugUtil.slugify(job.name);
String capability = "job:" + slug;
Optional<AgentLease> lease = agentPool.tryLease(
    capability, null, "scheduled-job", run.id);
```

Where `job` is the `ScheduledJobEntity` — it should already be in scope.

- [ ] **Step 4: Remove capabilities from ManagerPromptBuilder**

Modify `manager/src/main/java/io/apitomy/axiom/manager/ManagerPromptBuilder.java`:

In the `formatAgents` method (around lines 220-245), remove the capabilities block:

```java
// Remove these lines:
if (agent.capabilities != null && !agent.capabilities.isBlank()) {
    sb.append(" [capabilities: ").append(agent.capabilities).append("]");
}
```

The method should still list agents by name, type, and enabled status — just drop the
capabilities annotation.

- [ ] **Step 5: Update ManagerPromptBuilderTest**

Modify `manager/src/test/java/io/apitomy/axiom/manager/ManagerPromptBuilderTest.java`:

Find any test assertions that check for `[capabilities: ...]` in the formatted agent output
and remove or update them to reflect that capabilities are no longer included.

- [ ] **Step 6: Update SeedDataInitializer**

Modify `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java`:

In the `seedAgents` method (around lines 85-105):

1. Remove `agent1.capabilities = "auto-tag";` and `agent2.capabilities = "auto-tag";`
2. After persisting each agent, insert capabilities into the new table:

```java
import io.apitomy.axiom.core.entities.AgentCapabilityEntity;

// ...

private void seedAgents() {
    if (AgentEntity.count() == 0) {
        AgentEntity agent1 = new AgentEntity();
        agent1.name = "Claude Code";
        agent1.agentType = "claude-code";
        agent1.enabled = true;
        agent1.priority = 1;
        agent1.persist();
        AgentCapabilityEntity.setCapabilities(agent1.id, List.of("*"));

        AgentEntity agent2 = new AgentEntity();
        agent2.name = "OpenCode";
        agent2.agentType = "opencode";
        agent2.enabled = false;
        agent2.priority = 2;
        agent2.persist();
        AgentCapabilityEntity.setCapabilities(agent2.id, List.of("*"));
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java \
       app/src/main/java/io/apitomy/axiom/app/ReportExecutionService.java \
       app/src/main/java/io/apitomy/axiom/app/ScheduledJobExecutionService.java \
       manager/src/main/java/io/apitomy/axiom/manager/ManagerPromptBuilder.java \
       manager/src/test/java/io/apitomy/axiom/manager/ManagerPromptBuilderTest.java \
       app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java
git commit -m "feat: wire capability requirement strings into all execution services"
```

---

### Task 5: OpenAPI Spec + REST API Updates

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AgentsResourceImpl.java`

**Interfaces:**
- Consumes: `AgentCapabilityEntity.findCapabilities(Long)` and
  `AgentCapabilityEntity.setCapabilities(Long, List<String>)` from Task 2
- Consumes: Generated beans from OpenAPI spec

- [ ] **Step 1: Add slug to ReportDefinition schema in OpenAPI spec**

Modify `common/api/src/main/resources/openapi.json`:

In the `ReportDefinition` schema (and `NewReportDefinition` / `UpdateReportDefinition` if
they exist), add the `slug` property after `name`:

```json
"slug": {
    "type": "string",
    "description": "Stable identifier used for agent capability matching. Auto-generated from name if not set."
}
```

- [ ] **Step 2: Add slug to ScheduledJob schema in OpenAPI spec**

In the `ScheduledJob` schema (and `NewScheduledJob` / `UpdateScheduledJob` if they exist),
add the `slug` property after `name`:

```json
"slug": {
    "type": "string",
    "description": "Stable identifier used for agent capability matching. Auto-generated from name if not set."
}
```

- [ ] **Step 3: Run `mvn install` to regenerate interfaces**

The user runs: `mvn install -DskipTests` to regenerate JAX-RS interfaces and beans with
the new `slug` field.

- [ ] **Step 4: Update AgentsResourceImpl — capabilities serialization**

Modify `app/src/main/java/io/apitomy/axiom/app/rest/AgentsResourceImpl.java`:

In `toAgentResponse()` (around line 183), change:

```java
map.put("capabilities", entity.capabilitiesAsList());
```

to:

```java
map.put("capabilities", AgentCapabilityEntity.findCapabilities(entity.id));
```

Add import: `import io.apitomy.axiom.core.entities.AgentCapabilityEntity;`

- [ ] **Step 5: Update AgentsResourceImpl — capabilities deserialization**

In `updateAgent()` (around line 60), change:

```java
if (agentData.containsKey("capabilities")) {
    @SuppressWarnings("unchecked")
    List<String> caps = (List<String>) agentData.get("capabilities");
    entity.capabilities = caps == null ? "" : String.join(",", caps);
}
```

to:

```java
if (agentData.containsKey("capabilities")) {
    @SuppressWarnings("unchecked")
    List<String> caps = (List<String>) agentData.get("capabilities");
    AgentCapabilityEntity.setCapabilities(entity.id, caps != null ? caps : List.of());
}
```

Also check the `createAgent()` method — if it sets `entity.capabilities`, update similarly:
persist the agent first to get its ID, then call
`AgentCapabilityEntity.setCapabilities(entity.id, caps)`.

- [ ] **Step 6: Update Report Definition and Scheduled Job REST resources for slug**

Check the report definition and scheduled job REST resource impls. The `slug` field
should flow through naturally via the generated beans — verify that:
- The GET response includes `slug`
- The PUT/POST accepts `slug`
- If `slug` is not provided on create, auto-generate it from the name using `SlugUtil.slugify()`

Look for where the entity is mapped to/from the bean and add slug handling. For example,
in the report definition resource, when creating:

```java
if (definition.slug == null || definition.slug.isBlank()) {
    definition.slug = SlugUtil.slugify(definition.name);
}
```

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
       app/src/main/java/io/apitomy/axiom/app/rest/AgentsResourceImpl.java \
       app/src/main/java/io/apitomy/axiom/app/rest/ReportDefinitionsResourceImpl.java \
       app/src/main/java/io/apitomy/axiom/app/rest/ScheduledJobsResourceImpl.java
git commit -m "feat: update REST API for agent capabilities table and slug fields"
```

---

### Task 6: Fix Existing Tests

**Files:**
- Modify: `app/src/test/java/io/apitomy/axiom/app/AgentsResourceTest.java`
- Modify: `app/src/test/java/io/apitomy/axiom/app/ActionResourceTest.java`
- Modify: `app/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java`
- Modify: `app/src/test/java/io/apitomy/axiom/core/services/ScheduledJobValidatorTest.java`
- Modify: `manager/src/test/java/io/apitomy/axiom/manager/ManagerDecisionTest.java`
- Modify: `manager/src/test/java/io/apitomy/axiom/manager/ManagerDecisionParsingTest.java`

**Interfaces:**
- Consumes: all changes from Tasks 1-5

- [ ] **Step 1: Update AgentsResourceTest**

Update `app/src/test/java/io/apitomy/axiom/app/AgentsResourceTest.java`:

- Any test that sets `agent.capabilities = "auto-tag"` or similar must be changed to
  insert rows into `agent_capability` instead
- Any assertions on the response `capabilities` field should expect the new format
  (e.g. `["action:auto-tag"]` instead of `["auto-tag"]`)
- If test data uses `AgentEntity` directly, remove references to the `capabilities` field

- [ ] **Step 2: Review and fix other test files**

Scan the remaining test files for references to `agent.capabilities`, `hasCapability`,
`capabilitiesAsList`, or the old comma-separated format. Update each to use
`AgentCapabilityEntity.setCapabilities()` or `AgentCapabilityEntity.findCapabilities()`.

Common patterns to find and fix:
- `entity.capabilities = "..."` → `AgentCapabilityEntity.setCapabilities(entity.id, List.of(...))`
- `entity.hasCapability(...)` → `GlobMatcher.anyMatches(AgentCapabilityEntity.findCapabilities(entity.id), ...)`
- Assertions checking for `"auto-tag"` in capabilities → check for `"action:auto-tag"`

- [ ] **Step 3: Run the full test suite**

Run: `mvn test -f pom.xml`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -u  # stage all modified test files
git commit -m "fix: update tests for agent capabilities table migration"
```

---

### Task 7: UI Changes

**Files:**
- Modify: `ui/src/pages/AgentDetailPage.tsx`
- Modify: `ui/src/pages/AgentsPage.tsx`
- Modify: `ui/src/pages/ScheduledJobDetailPage.tsx`
- Modify: `ui/src/pages/ScheduledJobsPage.tsx`
- Modify: `ui/src/components/dashboards/widgets/ActionTypesWidget.tsx` (if it references
  capabilities)

**Interfaces:**
- Consumes: REST API changes from Task 5 (capabilities as `string[]`, slug field on
  report definitions and scheduled jobs)

- [ ] **Step 1: Update capabilities editor in AgentDetailPage**

Modify `ui/src/pages/AgentDetailPage.tsx`:

Update the `CapabilitiesEditor` component (inline, around lines 280-340):

1. Change the autocomplete suggestions to include prefixed capabilities:
   - Fetch action types, report definitions, and scheduled jobs from the API
   - Build suggestions like: `*`, `action:*`, `report:*`, `job:*`, plus specific entries
     like `action:auto-tag`, `report:daily-github-activity`, `job:test-scheduled-job`
2. Add helper text below the input:

```tsx
<HelperText>
    <HelperTextItem>
        Glob patterns: action:*, report:daily-*, job:github-*, or * for all workloads
    </HelperTextItem>
</HelperText>
```

3. Add validation: if a capability doesn't match `<flow>:<pattern>` or bare `*`, show a
   warning (not an error — don't block saving)

- [ ] **Step 2: Add slug field to ScheduledJobDetailPage**

Modify `ui/src/pages/ScheduledJobDetailPage.tsx`:

On the Info tab, add a `Slug` text input below the Name field:

```tsx
<FormGroup label="Slug" fieldId="slug">
    <TextInput
        id="slug"
        value={job.slug || ""}
        onChange={(_e, val) => {
            setJob({ ...job, slug: val });
            setDirty(true);
        }}
        placeholder={slugify(job.name)}
    />
    <HelperText>
        <HelperTextItem>
            Stable identifier used for agent capability matching.
            Auto-generated from name if empty.
        </HelperTextItem>
    </HelperText>
</FormGroup>
```

Add a simple `slugify` function at the top of the file (or in a shared util):

```typescript
function slugify(name: string | undefined): string {
    if (!name) return "";
    return name.trim().toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-]/g, "").replace(/-{2,}/g, "-");
}
```

- [ ] **Step 3: Add slug field to report definition detail page**

Apply the same slug field pattern to the report definition detail page. Find the file
(likely `ui/src/pages/ReportDefinitionDetailPage.tsx` or similar) and add the slug
`FormGroup` on the Info tab, same as Step 2.

- [ ] **Step 4: Update API config if needed**

Check `ui/src/config/api.ts` — if report definitions or scheduled jobs have type
definitions, add the `slug` field to them.

- [ ] **Step 5: Test in the browser**

1. Navigate to http://localhost:9191/
2. Go to Components → Agents → click an agent
3. Verify the Capabilities tab shows the new format (e.g. `action:auto-tag` or `*`)
4. Verify the autocomplete suggests prefixed capabilities
5. Verify helper text is shown
6. Go to Components → Scheduled Jobs → click a job
7. Verify the Slug field appears on the Info tab
8. Go to Components → Report Definitions → click a definition
9. Verify the Slug field appears on the Info tab

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/AgentDetailPage.tsx \
       ui/src/pages/AgentsPage.tsx \
       ui/src/pages/ScheduledJobDetailPage.tsx \
       ui/src/pages/ScheduledJobsPage.tsx
git commit -m "feat: update UI for glob-pattern capabilities and slug fields"
```
