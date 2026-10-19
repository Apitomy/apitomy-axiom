# Built-in Session Template Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Let users override `model`, `engine` and `environment` on built-in AI session templates, with a
reset-to-defaults action.

**Architecture:** A sparse `session_template_override` table stores per-built-in overrides.
`SessionTemplateService` merges overrides onto classpath defaults on every read, so session creation picks
them up without changes. A new REST sub-resource `/assistant/templates/{templateId}/overrides` (contract-first)
exposes GET/PUT/DELETE; the UI unlocks the three fields for built-ins.

**Tech Stack:** Java 25, Quarkus, Hibernate Panache, Flyway, JAX-RS (generated from OpenAPI), JUnit 5 +
RestAssured, React + PatternFly + TypeScript.

**Spec:** `docs/superpowers/specs/2026-10-19-builtin-template-overrides-design.md`

## Global Constraints

- Only `model`, `engine`, `environment` are overridable. All other built-in fields remain locked.
- Null override field = use built-in default. `environment` override replaces (does not merge) the default.
- Saving overrides with all three fields null/empty deletes the override row.
- Override rows whose `template_id` is not a current built-in are ignored at read time.
- Overrides are NOT part of configuration packs (`ImportExportService` unchanged).
- Existing `PUT`/`DELETE /assistant/templates/{templateId}` keep returning 403 for built-ins.
- API-first: edit `common/api/src/main/resources/openapi.json` before Java; REST impl uses generated beans
  from `io.apitomy.axiom.api.beans`; never add `@Path` on impl classes.
- Java style: 4-space indent, Javadoc on public methods, explicit types (no ambiguous `var`), JUnit 5.

## File Map

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/resources/db/migration/V3__session_template_override.sql` | Create | Table + sequence |
| `core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateOverrideEntity.java` | Create | JPA entity |
| `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java` | Modify | Merge + override CRUD |
| `app/src/test/java/io/apitomy/axiom/app/assistant/SessionTemplateOverrideServiceTest.java` | Create | Service tests |
| `common/api/src/main/resources/openapi.json` | Modify | New path + schema + `overriddenFields` |
| `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java` | Modify | Implement new ops |
| `app/src/test/java/io/apitomy/axiom/app/rest/SessionTemplateOverridesResourceTest.java` | Create | REST tests |
| `ui/src/config/api.ts` | Modify | Types + client functions |
| `ui/src/pages/SessionTemplateDetailPage.tsx` | Modify | Editable fields for built-ins, reset |
| `ui/src/pages/SessionTemplatesPage.tsx` | Modify | "Customized" label |

---

### Task 1: Persistence + service merge logic

**Files:**
- Create: `app/src/main/resources/db/migration/V3__session_template_override.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateOverrideEntity.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/SessionTemplateOverrideServiceTest.java`

**Interfaces:**
- Produces:
  - `SessionTemplateService.SessionTemplate` record gains a final component
    `Set<String> overriddenFields` (after `capabilities`). Values: `"model"`, `"engine"`, `"environment"`.
  - `public record SessionTemplateService.TemplateOverrides(String model, String engine, String environment)`
    — `environment` is a JSON object string (same format as `SessionTemplate.environment`), or null.
  - `public TemplateOverrides getOverrides(String templateId)`
  - `public SessionTemplate setOverrides(String templateId, TemplateOverrides overrides)` — returns merged
    template.
  - `public void clearOverrides(String templateId)`
  - All three throw `TemplateNotFoundException` (new, `public static class` extending
    `RuntimeException` nested in `SessionTemplateService`) for unknown IDs, and `IllegalArgumentException`
    for user-defined (non-built-in) IDs.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.core.entities.SessionTemplateOverrideEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies built-in session template override persistence and merge behaviour.
 */
@QuarkusTest
class SessionTemplateOverrideServiceTest {

    private static final String BUILT_IN = "general-assistant";

    @Inject
    SessionTemplateService service;

    @AfterEach
    @Transactional
    void cleanUp() {
        SessionTemplateOverrideEntity.deleteAll();
        service.listTemplates().stream()
                .filter(t -> !t.builtIn())
                .forEach(t -> service.deleteTemplate(t.templateId()));
    }

    @Test
    void builtInWithoutOverridesHasNoOverriddenFields() {
        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertTrue(t.overriddenFields().isEmpty());
        assertNull(t.model());
    }

    @Test
    void overridesAreMergedIntoGetAndList() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides(
                "my-model", "opencode", "{\"FOO\":\"bar\"}"));

        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertEquals("my-model", t.model());
        assertEquals("opencode", t.engine());
        assertEquals("{\"FOO\":\"bar\"}", t.environment());
        assertEquals(Set.of("model", "engine", "environment"), t.overriddenFields());
        assertTrue(t.builtIn());
        assertTrue(t.systemPrompt().length() > 0);

        SessionTemplateService.SessionTemplate listed = service.listTemplates().stream()
                .filter(x -> x.templateId().equals(BUILT_IN)).findFirst().orElseThrow();
        assertEquals("my-model", listed.model());
    }

    @Test
    void partialOverrideOnlyReportsSetFields() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        SessionTemplateService.SessionTemplate t = service.getTemplate(BUILT_IN);
        assertEquals(Set.of("model"), t.overriddenFields());
        assertNull(t.engine());
    }

    @Test
    void getOverridesReturnsStoredValuesOrEmpty() {
        SessionTemplateService.TemplateOverrides empty = service.getOverrides(BUILT_IN);
        assertNull(empty.model());
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", "e", null));
        SessionTemplateService.TemplateOverrides o = service.getOverrides(BUILT_IN);
        assertEquals("m", o.model());
        assertEquals("e", o.engine());
    }

    @Test
    void emptySaveDeletesRow() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides(" ", null, "{}"));
        assertEquals(0L, SessionTemplateOverrideEntity.count());
        assertTrue(service.getTemplate(BUILT_IN).overriddenFields().isEmpty());
    }

    @Test
    void clearOverridesRestoresDefaults() {
        service.setOverrides(BUILT_IN, new SessionTemplateService.TemplateOverrides("m", null, null));
        service.clearOverrides(BUILT_IN);
        assertNull(service.getTemplate(BUILT_IN).model());
        assertEquals(0L, SessionTemplateOverrideEntity.count());
    }

    @Test
    void unknownTemplateIsRejected() {
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.getOverrides("nope"));
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.setOverrides("nope",
                        new SessionTemplateService.TemplateOverrides("m", null, null)));
        assertThrows(SessionTemplateService.TemplateNotFoundException.class,
                () -> service.clearOverrides("nope"));
    }

    @Test
    void userTemplateIsRejected() {
        SessionTemplateService.SessionTemplate user = service.createTemplate(
                new SessionTemplateService.SessionTemplate(null, "u", "d", "p", null, null, null,
                        null, null, null, null, null, List.of(), List.of(), false, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.setOverrides(user.templateId(),
                        new SessionTemplateService.TemplateOverrides("m", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.getOverrides(user.templateId()));
        assertThrows(IllegalArgumentException.class, () -> service.clearOverrides(user.templateId()));
    }

    @Test
    @Transactional
    void orphanedOverrideRowIsIgnored() {
        SessionTemplateOverrideEntity orphan = new SessionTemplateOverrideEntity();
        orphan.templateId = "removed-built-in";
        orphan.model = "m";
        orphan.persist();
        assertNull(service.getTemplate("removed-built-in"));
        assertTrue(service.listTemplates().stream()
                .noneMatch(t -> t.templateId().equals("removed-built-in")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app -am test -Dtest=SessionTemplateOverrideServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`SessionTemplateOverrideEntity`, `TemplateOverrides`, `overriddenFields` not
defined).

- [ ] **Step 3: Create the migration**

`app/src/main/resources/db/migration/V3__session_template_override.sql`:

```sql
-- Axiom 3.x incremental migration 3.
--
-- Per-deployment overrides for built-in AI session templates. Built-in templates themselves are loaded from
-- the classpath; only the overridable fields (model, engine, environment) are stored here. A null column
-- means "use the built-in default".
CREATE SEQUENCE session_template_override_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE session_template_override (
    id BIGINT NOT NULL,
    template_id VARCHAR(255) NOT NULL,
    model VARCHAR(255),
    engine VARCHAR(255),
    environment TEXT,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_template_override_template_id UNIQUE (template_id)
);
```

- [ ] **Step 4: Create the entity**

`core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateOverrideEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Stores user overrides for a built-in AI Assistant session template. Only the
 * overridable fields are persisted; a null value means "use the built-in default".
 */
@Entity
@Table(name = "session_template_override")
public class SessionTemplateOverrideEntity extends PanacheEntity {

    /** The built-in template identifier these overrides apply to. */
    @Column(name = "template_id", nullable = false, unique = true)
    public String templateId;

    /** Optional AI model override. */
    @Column(name = "model")
    public String model;

    /** Optional interactive engine override. */
    @Column(name = "engine")
    public String engine;

    /** Optional JSON object of environment variables; replaces the built-in default. */
    @Column(columnDefinition = "TEXT")
    public String environment;

    /**
     * Finds the override row for a template.
     *
     * @param templateId the built-in template identifier
     * @return the override entity, or null if none exists
     */
    public static SessionTemplateOverrideEntity findByTemplateId(String templateId) {
        return find("templateId", templateId).firstResult();
    }
}
```

- [ ] **Step 5: Update `SessionTemplateService`**

5a. Add `Set<String> overriddenFields` as the final record component of `SessionTemplate` and document it
in the record Javadoc (`@param overriddenFields names of fields overridden by the user (built-ins only)`).
Update every constructor call: in `toTemplate(...)` and `parseTemplateJson(...)` append `Set.of()` as the
last argument. Also update `AssistantResourceImpl.fromTemplateBean` (append `java.util.Set.of()`) so the
app compiles; grep for other callers with `grep -rn "new SessionTemplateService.SessionTemplate\|new SessionTemplate(" app/src`
and append `Set.of()` to each. Known callers: `SessionTemplateService.java` (2), `AssistantResourceImpl.java`
(`fromTemplateBean`), and `app/src/test/.../assistant/AssistantToolsetResolutionTest.java:42` — include the
test file in the Step 8 commit.

5b. Add imports `io.apitomy.axiom.core.entities.SessionTemplateOverrideEntity`, `java.util.LinkedHashSet`,
`java.util.stream.Collectors`.

5c. Add nested types after the `SessionTemplate` record:

```java
    /**
     * User overrides for a built-in template. A null field means "use the built-in default".
     *
     * @param model AI model override, or null
     * @param engine interactive engine override, or null
     * @param environment JSON object string of environment variables, or null
     */
    public record TemplateOverrides(String model, String engine, String environment) {
    }

    /**
     * Thrown when a template identifier does not refer to any known template.
     */
    public static class TemplateNotFoundException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param templateId the unknown template identifier
         */
        public TemplateNotFoundException(String templateId) {
            super("Template not found: " + templateId);
        }
    }
```

5d. Replace `listTemplates()` and `getTemplate(...)`:

```java
    public List<SessionTemplate> listTemplates() {
        Map<String, SessionTemplateOverrideEntity> overrides =
                SessionTemplateOverrideEntity.<SessionTemplateOverrideEntity>listAll().stream()
                        .collect(Collectors.toMap(o -> o.templateId, o -> o));
        List<SessionTemplate> result = new ArrayList<>();
        for (SessionTemplate builtIn : builtInTemplates.values()) {
            result.add(applyOverrides(builtIn, overrides.get(builtIn.templateId())));
        }
        List<SessionTemplateEntity> entities = SessionTemplateEntity.listAll();
        for (SessionTemplateEntity entity : entities) {
            result.add(toTemplate(entity));
        }
        return result;
    }

    public SessionTemplate getTemplate(String templateId) {
        SessionTemplate builtIn = builtInTemplates.get(templateId);
        if (builtIn != null) {
            return applyOverrides(builtIn, SessionTemplateOverrideEntity.findByTemplateId(templateId));
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        return entity != null ? toTemplate(entity) : null;
    }
```

(Keep the existing Javadoc on both; add "Built-in templates are returned with user overrides applied." to
each.)

5e. Add the override API methods (after `deleteTemplate`):

```java
    /**
     * Returns the stored overrides for a built-in template.
     *
     * @param templateId the built-in template identifier
     * @return the overrides; all fields null when none are stored
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in
     */
    public TemplateOverrides getOverrides(String templateId) {
        requireBuiltIn(templateId);
        SessionTemplateOverrideEntity entity = SessionTemplateOverrideEntity.findByTemplateId(templateId);
        if (entity == null) {
            return new TemplateOverrides(null, null, null);
        }
        return new TemplateOverrides(entity.model, entity.engine, entity.environment);
    }

    /**
     * Replaces the overrides for a built-in template. Blank values are treated as null; when
     * every field is null the stored overrides are removed.
     *
     * @param templateId the built-in template identifier
     * @param overrides the new overrides
     * @return the built-in template with overrides applied
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in
     */
    @Transactional
    public SessionTemplate setOverrides(String templateId, TemplateOverrides overrides) {
        requireBuiltIn(templateId);
        String model = blankToNull(overrides.model());
        String engine = blankToNull(overrides.engine());
        String environment = normalizeEnvironment(overrides.environment());
        SessionTemplateOverrideEntity entity = SessionTemplateOverrideEntity.findByTemplateId(templateId);
        if (model == null && engine == null && environment == null) {
            if (entity != null) {
                entity.delete();
            }
            return builtInTemplates.get(templateId);
        }
        if (entity == null) {
            entity = new SessionTemplateOverrideEntity();
            entity.templateId = templateId;
        }
        entity.model = model;
        entity.engine = engine;
        entity.environment = environment;
        entity.persist();
        return applyOverrides(builtInTemplates.get(templateId), entity);
    }

    /**
     * Removes all overrides for a built-in template, restoring its defaults.
     *
     * @param templateId the built-in template identifier
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in
     */
    @Transactional
    public void clearOverrides(String templateId) {
        requireBuiltIn(templateId);
        SessionTemplateOverrideEntity.delete("templateId", templateId);
    }

    private void requireBuiltIn(String templateId) {
        if (isBuiltIn(templateId)) {
            return;
        }
        if (SessionTemplateEntity.find("templateId", templateId).firstResult() != null) {
            throw new IllegalArgumentException(
                    "Overrides are only supported for built-in templates: " + templateId);
        }
        throw new TemplateNotFoundException(templateId);
    }

    private SessionTemplate applyOverrides(SessionTemplate base, SessionTemplateOverrideEntity o) {
        if (o == null) {
            return base;
        }
        Set<String> overridden = new LinkedHashSet<>();
        String model = base.model();
        String engine = base.engine();
        String environment = base.environment();
        if (o.model != null) {
            model = o.model;
            overridden.add("model");
        }
        if (o.engine != null) {
            engine = o.engine;
            overridden.add("engine");
        }
        if (o.environment != null) {
            environment = o.environment;
            overridden.add("environment");
        }
        return new SessionTemplate(base.templateId(), base.name(), base.description(), base.systemPrompt(),
                base.welcomeMessage(), base.initialMessage(), base.workingDirectory(), model, engine,
                base.initScript(), base.initScriptType(), environment, base.mcpServers(),
                base.allowedTools(), base.builtIn(), base.capabilities(), Set.copyOf(overridden));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeEnvironment(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() && node.size() > 0 ? node.toString() : null;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid environment JSON", e);
        }
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -pl app -am test -Dtest=SessionTemplateOverrideServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all 9 tests PASS. (Note: `{"FOO":"bar"}` round-trips unchanged through `normalizeEnvironment`.)

- [ ] **Step 7: Run existing assistant tests for regressions**

Run: `mvn -q -pl app -am test -Dtest='io.apitomy.axiom.app.assistant.*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/resources/db/migration/V3__session_template_override.sql \
    core/src/main/java/io/apitomy/axiom/core/entities/SessionTemplateOverrideEntity.java \
    app/src/main/java/io/apitomy/axiom/app/assistant/SessionTemplateService.java \
    app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
    app/src/test/java/io/apitomy/axiom/app/assistant/SessionTemplateOverrideServiceTest.java \
    app/src/test/java/io/apitomy/axiom/app/assistant/AssistantToolsetResolutionTest.java
git commit -m "feat(assistant): persist and merge overrides for built-in session templates"
```

---

### Task 2: REST API (contract-first)

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/rest/SessionTemplateOverridesResourceTest.java`

**Interfaces:**
- Consumes (Task 1): `SessionTemplateService.getOverrides/setOverrides/clearOverrides`,
  `TemplateOverrides(String model, String engine, String environment)`, `TemplateNotFoundException`,
  `SessionTemplate.overriddenFields()`.
- Produces (for Task 3):
  - `GET /api/v1/assistant/templates/{templateId}/overrides` → `{model?, engine?, environment?}`
  - `PUT` same path, body `{model?, engine?, environment?}` → merged `SessionTemplate`
  - `DELETE` same path → 204
  - `SessionTemplate.overriddenFields: string[]` on all template responses.
  - Generated bean `io.apitomy.axiom.api.beans.SessionTemplateOverrides` and interface methods
    `getSessionTemplateOverrides(String)`, `updateSessionTemplateOverrides(String, SessionTemplateOverrides)`,
    `deleteSessionTemplateOverrides(String)` on the generated Assistant resource interface.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST tests for the built-in session template overrides sub-resource.
 */
@QuarkusTest
class SessionTemplateOverridesResourceTest {

    private static final String BASE = "/api/v1/assistant/templates";
    private static final String BUILT_IN = "general-assistant";

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @AfterEach
    void cleanUp() {
        given().delete(BASE + "/{id}/overrides", BUILT_IN);
    }

    @Test
    void getReturnsEmptyOverridesByDefault() {
        given().get(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200)
                .body("model", nullValue())
                .body("engine", nullValue())
                .body("environment", nullValue());
        given().get(BASE + "/{id}", BUILT_IN)
                .then().statusCode(200).body("overriddenFields", empty());
    }

    @Test
    void putAppliesOverridesAndReportsOverriddenFields() {
        given().contentType(ContentType.JSON)
                .body("{\"model\":\"m1\",\"environment\":{\"FOO\":\"bar\"}}")
                .put(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200)
                .body("model", equalTo("m1"))
                .body("environment.FOO", equalTo("bar"))
                .body("builtIn", equalTo(true))
                .body("overriddenFields", containsInAnyOrder("model", "environment"));

        given().get(BASE + "/{id}/overrides", BUILT_IN)
                .then().statusCode(200).body("model", equalTo("m1"));
        given().get(BASE)
                .then().statusCode(200)
                .body("find { it.templateId == 'general-assistant' }.overriddenFields", hasItem("model"));
    }

    @Test
    void deleteResetsToDefaults() {
        given().contentType(ContentType.JSON).body("{\"model\":\"m1\"}")
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(200);
        given().delete(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(204);
        given().get(BASE + "/{id}", BUILT_IN)
                .then().statusCode(200)
                .body("model", nullValue())
                .body("overriddenFields", empty());
    }

    @Test
    void unknownTemplateReturns404() {
        given().get(BASE + "/{id}/overrides", "nope").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"model\":\"m\"}")
                .put(BASE + "/{id}/overrides", "nope").then().statusCode(404);
        given().delete(BASE + "/{id}/overrides", "nope").then().statusCode(404);
    }

    @Test
    void userTemplateReturns400() {
        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"u\",\"description\":\"d\",\"systemPrompt\":\"p\"}")
                .post(BASE).then().statusCode(201).extract().path("templateId");
        try {
            given().get(BASE + "/{id}/overrides", id).then().statusCode(400);
            given().contentType(ContentType.JSON).body("{\"model\":\"m\"}")
                    .put(BASE + "/{id}/overrides", id).then().statusCode(400);
            given().delete(BASE + "/{id}/overrides", id).then().statusCode(400);
        } finally {
            given().delete(BASE + "/{id}", id);
        }
    }

    @Test
    void existingBuiltInPutStillForbidden() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"p\"}")
                .put(BASE + "/{id}", BUILT_IN).then().statusCode(403);
    }

    @Test
    void sessionCreationUsesOverriddenEngine() throws IOException {
        String engine = given().get("/api/v1/system/config").then().statusCode(200)
                .extract().path("engines.find { it.supportsInteractiveSessions }.type");
        given().contentType(ContentType.JSON).body("{\"engine\":\"" + engine + "\"}")
                .put(BASE + "/{id}/overrides", BUILT_IN).then().statusCode(200);

        when(interactiveSessionDriverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        String sessionId = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"" + BUILT_IN + "\",\"name\":\"override-test\"}")
                .post("/api/v1/assistant/sessions")
                .then().statusCode(200).extract().path("id");
        try {
            ArgumentCaptor<InteractiveSessionDriverFactory.DriverRequest> captor =
                    ArgumentCaptor.forClass(InteractiveSessionDriverFactory.DriverRequest.class);
            verify(interactiveSessionDriverFactory, atLeastOnce()).createDriver(captor.capture());
            assertEquals(engine, captor.getValue().engineType());
        } finally {
            given().delete("/api/v1/assistant/sessions/{id}", sessionId);
        }
    }
}
```

Note: confirm the system config path by checking `fetchSystemConfig` in `ui/src/config/api.ts`; adjust
`/api/v1/system/config` if it differs.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl app -am test -Dtest=SessionTemplateOverridesResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — 404/405 on `/overrides`, missing `overriddenFields`.

- [ ] **Step 3: Update `openapi.json`**

Prefer the apicurio-data-models MCP tools for these edits if available; otherwise edit JSON directly.

3a. Add new path immediately after the `/assistant/templates/{templateId}` path object:

```json
    "/assistant/templates/{templateId}/overrides": {
      "get": {
        "tags": ["Assistant"],
        "summary": "Get user overrides for a built-in session template",
        "operationId": "getSessionTemplateOverrides",
        "responses": {
          "200": {
            "description": "Current overrides (fields are absent when not overridden)",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/SessionTemplateOverrides" }
              }
            }
          },
          "400": { "description": "Template is not a built-in template" },
          "404": { "description": "Template not found" }
        }
      },
      "put": {
        "tags": ["Assistant"],
        "summary": "Replace user overrides for a built-in session template",
        "operationId": "updateSessionTemplateOverrides",
        "requestBody": {
          "content": {
            "application/json": {
              "schema": { "$ref": "#/components/schemas/SessionTemplateOverrides" }
            }
          },
          "required": true
        },
        "responses": {
          "200": {
            "description": "Template with overrides applied",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/SessionTemplate" }
              }
            }
          },
          "400": { "description": "Template is not a built-in template" },
          "404": { "description": "Template not found" }
        }
      },
      "delete": {
        "tags": ["Assistant"],
        "summary": "Remove user overrides, restoring built-in defaults",
        "operationId": "deleteSessionTemplateOverrides",
        "responses": {
          "204": { "description": "Overrides removed" },
          "400": { "description": "Template is not a built-in template" },
          "404": { "description": "Template not found" }
        }
      },
      "parameters": [
        {
          "name": "templateId",
          "in": "path",
          "description": "The built-in template identifier",
          "required": true,
          "schema": { "type": "string" }
        }
      ]
    },
```

3b. Add to `SessionTemplate.properties`:

```json
          "overriddenFields": {
            "description": "Names of fields (model, engine, environment) overridden by the user on a built-in template. Empty for user-defined templates.",
            "type": "array",
            "items": { "type": "string" },
            "readOnly": true
          },
```

3c. Add new schema next to `NewSessionTemplate`:

```json
      "SessionTemplateOverrides": {
        "description": "User-editable overrides for a built-in session template. Absent or null fields use the built-in default.",
        "type": "object",
        "properties": {
          "model": {
            "description": "AI model override",
            "type": "string"
          },
          "engine": {
            "description": "Interactive engine override",
            "type": "string"
          },
          "environment": {
            "description": "Environment variables; replaces the built-in default. Values can reference secrets using ${secret:NAME} syntax.",
            "type": "object",
            "additionalProperties": { "type": "string" }
          }
        }
      },
```

- [ ] **Step 4: Regenerate interfaces/beans**

Run: `mvn -q -pl common/api install -DskipTests`
Expected: BUILD SUCCESS. Then confirm the generated names:
`grep -rn "SessionTemplateOverrides" common/api/target/generated-sources/jaxrs | head`
Note the generated environment type for `SessionTemplateOverrides.environment` (likely a nested class,
e.g. `io.apitomy.axiom.api.beans.Environment__1` or similar) — use the actual generated class name in Step 5.

- [ ] **Step 5: Implement in `AssistantResourceImpl`**

5a. In `toTemplateBean`, add:

```java
        bean.setOverriddenFields(template.overriddenFields().stream().sorted().toList());
```

5b. Add after `deleteSessionTemplate` (import `io.apitomy.axiom.api.beans.SessionTemplateOverrides`):

```java
    /** {@inheritDoc} */
    @Override
    public SessionTemplateOverrides getSessionTemplateOverrides(String templateId) {
        try {
            SessionTemplateService.TemplateOverrides o = templateService.getOverrides(templateId);
            SessionTemplateOverrides bean = new SessionTemplateOverrides();
            bean.setModel(o.model());
            bean.setEngine(o.engine());
            bean.setEnvironment(toOverridesEnvironment(o.environment()));
            return bean;
        } catch (SessionTemplateService.TemplateNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 400);
        }
    }

    /** {@inheritDoc} */
    @Override
    public SessionTemplate updateSessionTemplateOverrides(String templateId,
                                                          SessionTemplateOverrides data) {
        try {
            String env = data.getEnvironment() == null ? null
                    : objectMapper.writeValueAsString(data.getEnvironment().getAdditionalProperties());
            SessionTemplateService.SessionTemplate updated = templateService.setOverrides(templateId,
                    new SessionTemplateService.TemplateOverrides(data.getModel(), data.getEngine(), env));
            return toTemplateBean(updated);
        } catch (SessionTemplateService.TemplateNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 400);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new WebApplicationException("Invalid environment: " + e.getMessage(), 400);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteSessionTemplateOverrides(String templateId) {
        try {
            templateService.clearOverrides(templateId);
        } catch (SessionTemplateService.TemplateNotFoundException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 400);
        }
    }
```

5c. Add helper `toOverridesEnvironment(String json)` mirroring the existing `jsonToEnvironment` but returning
the generated environment type of `SessionTemplateOverrides` (name found in Step 4). If the generator reuses
the `Environment` class, delete this helper and call `jsonToEnvironment` directly.

- [ ] **Step 6: Run tests**

Run: `mvn -q -pl app -am test -Dtest='SessionTemplateOverridesResourceTest,SessionTemplateOverrideServiceTest,SessionCapabilityEndpointTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
    app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java \
    app/src/test/java/io/apitomy/axiom/app/rest/SessionTemplateOverridesResourceTest.java
git commit -m "feat(api): add overrides sub-resource for built-in session templates"
```

---

### Task 3: UI

**Files:**
- Modify: `ui/src/config/api.ts`
- Modify: `ui/src/pages/SessionTemplateDetailPage.tsx`
- Modify: `ui/src/pages/SessionTemplatesPage.tsx`

**Interfaces:**
- Consumes (Task 2): REST endpoints and `overriddenFields`.
- Produces: `SessionTemplateOverrides` TS type; `updateAssistantTemplateOverrides`,
  `deleteAssistantTemplateOverrides` functions.

- [ ] **Step 1: API client (`ui/src/config/api.ts`)**

Add `overriddenFields?: string[];` to `interface SessionTemplate`. After `NewSessionTemplate` add:

```ts
export interface SessionTemplateOverrides {
    model?: string;
    engine?: string;
    environment?: Record<string, string>;
}
```

After `updateAssistantTemplate` add:

```ts
export async function updateAssistantTemplateOverrides(
    templateId: string,
    data: SessionTemplateOverrides
): Promise<SessionTemplate> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}/overrides`,
        {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        }
    );
    if (!response.ok) throw new Error("Failed to update template overrides");
    return response.json();
}

export async function deleteAssistantTemplateOverrides(templateId: string): Promise<void> {
    const response = await fetch(
        `${API}/assistant/templates/${encodeURIComponent(templateId)}/overrides`,
        { method: "DELETE" }
    );
    if (!response.ok) throw new Error("Failed to reset template overrides");
}
```

- [ ] **Step 2: Detail page (`SessionTemplateDetailPage.tsx`)**

2a. Import `updateAssistantTemplateOverrides`, `deleteAssistantTemplateOverrides` from `../config/api`,
and `Modal`, `ModalVariant`, `ModalHeader`, `ModalBody`, `ModalFooter` from `@patternfly/react-core`
(match whatever Modal import style `SessionTemplatesPage.tsx` uses for its delete confirm — copy it).

2b. Add state: `const [confirmReset, setConfirmReset] = useState(false);`

2c. Replace `handleSave` body's save call so built-ins use the overrides endpoint:

```tsx
        const envToSend = Object.keys(envVars).length > 0 ? envVars : undefined;
        const save = template?.builtIn
            ? updateAssistantTemplateOverrides(templateId, {
                model: form.model, engine: form.engine, environment: envToSend,
            })
            : updateAssistantTemplate(templateId, { ...form, environment: envToSend });
        save
            .then((updated) => {
                setTemplate(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
```

2d. Add reset handler:

```tsx
    const handleReset = () => {
        if (!templateId) return;
        setConfirmReset(false);
        deleteAssistantTemplateOverrides(templateId)
            .then(() => loadData())
            .catch(console.error);
    };
```

2e. After `const isReadOnly = template.builtIn;` add:

```tsx
    const overridden = new Set(template.overriddenFields || []);
    const modifiedLabel = (field: string) => overridden.has(field)
        ? <Label color="orange" isCompact style={{ marginLeft: 8 }}>Modified</Label>
        : null;
```

2f. Header: change the built-in label text from `Built-in (read-only)` to `Built-in`. Replace the
`{!isReadOnly && (<FlexItem>…Save…</FlexItem>)}` block with:

```tsx
                {isReadOnly && overridden.size > 0 && (
                    <FlexItem>
                        <Button variant="secondary" onClick={() => setConfirmReset(true)}>
                            Reset to defaults
                        </Button>
                    </FlexItem>
                )}
                <FlexItem>
                    <Button variant="primary" onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving}
                        isLoading={saving}>
                        Save
                    </Button>
                </FlexItem>
```

2g. Below the header `Flex`, for built-ins, add:

```tsx
            {isReadOnly && (
                <Alert variant="info" isInline style={{ marginBottom: 16 }}
                    title="Built-in template: only Engine, Model and Environment can be customized." />
            )}
```

2h. Engine `FormGroup`: change `label="Engine"` to `label={<>Engine{modifiedLabel("engine")}</>}` and
remove `isDisabled={isReadOnly}` from its `FormSelect`.
Model `FormGroup`: `label={<>Model{modifiedLabel("model")}</>}` and remove `isDisabled={isReadOnly}` from its
`FormSelect`.

2i. Environment tab: change title to
`<TabTitleText>Environment{overridden.has("environment") ? " *" : ""}</TabTitleText>` and replace the
`isReadOnly ? (<p>…</p>) : (<EnvironmentTab …/>)` conditional with just:

```tsx
                        {modifiedLabel("environment")}
                        <EnvironmentTab
                            envVars={envVars}
                            onChange={(updated) => {
                                setEnvVars(updated);
                                setDirty(true);
                            }}
                        />
```

2j. Before the closing `</PageSection>`, add the confirm modal:

```tsx
            <Modal variant={ModalVariant.small} isOpen={confirmReset}
                onClose={() => setConfirmReset(false)}>
                <ModalHeader title="Reset to defaults?" />
                <ModalBody>
                    This removes your Engine, Model and Environment customizations for
                    &quot;{template.name}&quot;.
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={handleReset}>Reset</Button>
                    <Button variant="link" onClick={() => setConfirmReset(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
```

- [ ] **Step 3: List page (`SessionTemplatesPage.tsx`)**

Replace the type-column `<Td>` contents with:

```tsx
                                    <Label color={t.builtIn ? "blue" : "green"}>
                                        {t.builtIn ? "Built-in" : "Custom"}
                                    </Label>
                                    {t.builtIn && (t.overriddenFields?.length ?? 0) > 0 && (
                                        <Label color="orange" style={{ marginLeft: 8 }}>
                                            Customized
                                        </Label>
                                    )}
```

- [ ] **Step 4: Type-check, lint, unit tests**

Run (workdir `ui`): `npm run build && npm test`
Expected: build succeeds, tests pass.

- [ ] **Step 5: Manual browser verification**

Start the app (dev mode per project README), open `/session-templates/general-assistant` via Chrome DevTools
MCP, and verify:
1. Info alert shown; Name/Description/System Prompt still read-only; Engine/Model/Environment editable.
2. Change Model → Save → "Modified" label appears next to Model; list page shows "Customized".
3. Add env var → Save → Environment tab title shows `*`.
4. "Reset to defaults" → confirm → fields revert, labels disappear.
5. A custom (non-built-in) template still saves normally via the original endpoint.

- [ ] **Step 6: Commit**

```bash
git add ui/src/config/api.ts ui/src/pages/SessionTemplateDetailPage.tsx ui/src/pages/SessionTemplatesPage.tsx
git commit -m "feat(ui): allow customizing engine, model and environment on built-in templates"
```

---

### Task 4: Full build verification

- [ ] **Step 1:** Run `./build.sh`. Expected: BUILD SUCCESS, all tests pass.
- [ ] **Step 2:** If any docs under `docs/` describe built-in templates as fully read-only
  (`grep -rln "read-only" docs | xargs grep -l -i "template"`), update the wording to describe the
  Engine/Model/Environment customization and reset; wrap markdown at 110 chars. Commit:
  `git commit -am "docs: describe built-in template customization"`.
