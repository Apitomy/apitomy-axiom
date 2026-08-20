# Workflow Definitions (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add workflow definition CRUD, versioning, and a visual editor to Axiom using Apitomy
Flow.

**Architecture:** New `workflow_definition` and `workflow_definition_version` database tables
with Panache entities, a REST API following the contract-first pattern (OpenAPI spec first,
generated JAX-RS interfaces), and a React UI under the Components section embedding the
`WorkflowEditor` component from `@apitomy/flow-ui`.

**Tech Stack:** Java 25 / Quarkus 3.38 / Panache / Flyway / H2+PostgreSQL (backend), React 19
/ PatternFly 6 / @apitomy/flow-ui / @xyflow/react (frontend), Apitomy Flow Engine 1.0.1
(validation)

**Spec:** `docs/superpowers/specs/2026-08-20-workflow-definitions-phase1-design.md`

**GitHub Issue:** #228

## Global Constraints

- Follow contract-first development: OpenAPI spec changes first, then `mvn install` to
  generate interfaces, then implement.
- REST resource impls must implement generated interfaces — no `@Path` on impl classes.
- Use generated beans from `io.apitomy.axiom.api.beans` for request/response types.
- Entities use Panache active record style (public fields, extend `PanacheEntity`).
- Do not run tests or Maven builds automatically — the user handles compilation and testing.
- Do not include Claude attribution in commit messages.

## Prerequisites

Before starting, ensure:
1. Apitomy Flow Engine is installed to local Maven repo:
   `cd ~/git/apitomy/apitomy-flow/engine && mvn install -DskipTests`
2. Apitomy Flow UI is built and linked:
   `cd ~/git/apitomy/apitomy-flow/ui && npm install && npm run build && npm link`
3. Link the Flow UI into Axiom's UI:
   `cd ~/git/apitomy/apitomy-axiom/ui && npm link @apitomy/flow-ui`

---

### Task 1: OpenAPI Spec — Workflow Definition Schemas and Endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces: Generated `WorkflowDefinitionsResource` interface,
  `WorkflowDefinition` / `NewWorkflowDefinition` / `UpdateWorkflowDefinition` /
  `WorkflowDefinitionVersion` / `WorkflowDefinitionSearchResults` beans in
  `io.apitomy.axiom.api.beans`

- [ ] **Step 1: Add the `WorkflowDefinition` schema**

Add to the `components.schemas` section of `openapi.json`:

```json
"WorkflowDefinition": {
    "required": [
        "name",
        "createdOn",
        "updatedOn"
    ],
    "type": "object",
    "properties": {
        "id": {
            "format": "int64",
            "type": "integer"
        },
        "name": {
            "type": "string"
        },
        "description": {
            "type": "string"
        },
        "content": {
            "type": "object"
        },
        "currentVersion": {
            "format": "int32",
            "type": "integer"
        },
        "createdOn": {
            "format": "date-time",
            "type": "string"
        },
        "updatedOn": {
            "format": "date-time",
            "type": "string"
        }
    }
}
```

- [ ] **Step 2: Add the `NewWorkflowDefinition` schema**

```json
"NewWorkflowDefinition": {
    "required": [
        "name"
    ],
    "type": "object",
    "properties": {
        "name": {
            "type": "string"
        },
        "description": {
            "type": "string"
        }
    }
}
```

- [ ] **Step 3: Add the `UpdateWorkflowDefinition` schema**

```json
"UpdateWorkflowDefinition": {
    "type": "object",
    "properties": {
        "name": {
            "type": "string"
        },
        "description": {
            "type": "string"
        }
    }
}
```

- [ ] **Step 4: Add the `WorkflowDefinitionVersion` schema**

```json
"WorkflowDefinitionVersion": {
    "required": [
        "definitionId",
        "version",
        "createdOn"
    ],
    "type": "object",
    "properties": {
        "id": {
            "format": "int64",
            "type": "integer"
        },
        "definitionId": {
            "format": "int64",
            "type": "integer"
        },
        "version": {
            "format": "int32",
            "type": "integer"
        },
        "content": {
            "type": "object"
        },
        "createdOn": {
            "format": "date-time",
            "type": "string"
        }
    }
}
```

- [ ] **Step 5: Add the `WorkflowDefinitionSearchResults` schema**

```json
"WorkflowDefinitionSearchResults": {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "$ref": "#/components/schemas/WorkflowDefinition"
            }
        },
        "totalCount": {
            "format": "int64",
            "type": "integer"
        },
        "page": {
            "format": "int32",
            "type": "integer"
        },
        "limit": {
            "format": "int32",
            "type": "integer"
        }
    }
}
```

- [ ] **Step 6: Add the collection path `/workflow-definitions`**

Add to the `paths` section. Tag: `"WorkflowDefinitions"`. Operations:

**GET** — List workflow definitions. Query parameters: `page` (int32, default 1), `limit`
(int32, default 20), `filterName` (string, optional). Returns `WorkflowDefinitionSearchResults`.

**POST** — Create a workflow definition. Request body: `NewWorkflowDefinition`. Response: 200
with `WorkflowDefinition`.

- [ ] **Step 7: Add the item path `/workflow-definitions/{workflowDefinitionId}`**

Path parameter: `workflowDefinitionId` (int64). Operations:

**GET** — Get a workflow definition. Response: 200 with `WorkflowDefinition`, 404 `NotFound`.

**PUT** — Update metadata. Request body: `UpdateWorkflowDefinition`. Response: 200 with
`WorkflowDefinition`, 404 `NotFound`.

**DELETE** — Delete a workflow definition. Response: 204, 404 `NotFound`.

- [ ] **Step 8: Add the content path `/workflow-definitions/{workflowDefinitionId}/content`**

**PUT** — Update draft workflow content. Request body: free-form JSON object (`type: object`).
Response: 204 (no content). 404 `NotFound`.

- [ ] **Step 9: Add the publish path `/workflow-definitions/{workflowDefinitionId}/publish`**

**POST** — Publish current draft as a new version. No request body. Response: 200 with
`WorkflowDefinitionVersion`. 400 if validation fails (return array of validation problem
objects). 404 `NotFound`.

- [ ] **Step 10: Add the versions collection path
`/workflow-definitions/{workflowDefinitionId}/versions`**

**GET** — List all published versions. Response: 200 with array of `WorkflowDefinitionVersion`.

- [ ] **Step 11: Add the version item path
`/workflow-definitions/{workflowDefinitionId}/versions/{version}`**

Path parameter: `version` (int32). Operations:

**GET** — Get a specific version. Response: 200 with `WorkflowDefinitionVersion`. 404
`NotFound`.

- [ ] **Step 12: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add workflow definition OpenAPI schemas and endpoints"
```

After this commit, run `mvn install` to generate the Java interfaces and beans. The generated
`WorkflowDefinitionsResource` interface and bean classes in `io.apitomy.axiom.api.beans` will
be used in subsequent tasks.

---

### Task 2: Database Migration and Entities

**Files:**
- Create: `app/src/main/resources/db/migration/V43__create_workflow_definitions.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionEntity.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionVersionEntity.java`

**Interfaces:**
- Produces: `WorkflowDefinitionEntity` and `WorkflowDefinitionVersionEntity` Panache entities
  used by the REST resource in Task 3

- [ ] **Step 1: Create the Flyway migration**

Create `app/src/main/resources/db/migration/V43__create_workflow_definitions.sql`:

```sql
CREATE TABLE workflow_definition (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    content TEXT,
    current_version INT,
    created_on TIMESTAMP NOT NULL,
    updated_on TIMESTAMP NOT NULL
);

CREATE TABLE workflow_definition_version (
    id BIGSERIAL PRIMARY KEY,
    definition_id BIGINT NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    version INT NOT NULL,
    content TEXT NOT NULL,
    created_on TIMESTAMP NOT NULL,
    CONSTRAINT uq_definition_version UNIQUE (definition_id, version)
);

CREATE INDEX idx_wf_def_version_def_id ON workflow_definition_version(definition_id);
```

- [ ] **Step 2: Create `WorkflowDefinitionEntity`**

Create `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinitionEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "current_version")
    public Integer currentVersion;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
```

- [ ] **Step 3: Create `WorkflowDefinitionVersionEntity`**

Create
`core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionVersionEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "workflow_definition_version",
       uniqueConstraints = @UniqueConstraint(columnNames = {"definition_id", "version"}))
public class WorkflowDefinitionVersionEntity extends PanacheEntity {

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(nullable = false)
    public int version;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V43__create_workflow_definitions.sql \
       core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionEntity.java \
       core/src/main/java/io/apitomy/axiom/core/entities/WorkflowDefinitionVersionEntity.java
git commit -m "feat: add workflow definition DB migration and entities"
```

---

### Task 3: REST Resource Implementation

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`
- Modify: `app/pom.xml` (add `apitomy-flow-engine` dependency)

**Interfaces:**
- Consumes: Generated `WorkflowDefinitionsResource` interface and beans from Task 1;
  `WorkflowDefinitionEntity` and `WorkflowDefinitionVersionEntity` from Task 2
- Produces: Working REST API at `/api/v1/workflow-definitions`

- [ ] **Step 1: Add the Apitomy Flow Engine dependency to `app/pom.xml`**

Add to the `<dependencies>` section of `app/pom.xml`:

```xml
<dependency>
    <groupId>io.apitomy</groupId>
    <artifactId>apitomy-flow-engine</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

- [ ] **Step 2: Create `WorkflowDefinitionsResourceImpl`**

Create
`app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`.

This is a large file. The key elements:

```java
package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.WorkflowDefinitionsResource;
import io.apitomy.axiom.api.beans.*;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.ValidationSeverity;
import io.apitomy.flow.validation.WorkflowValidator;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.common.NotImplementedYet;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class WorkflowDefinitionsResourceImpl implements WorkflowDefinitionsResource {

    @Inject
    ObjectMapper objectMapper;

    // -- List --
    @Override
    public WorkflowDefinitionSearchResults listWorkflowDefinitions(
            BigInteger page, BigInteger limit, String filterName) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and lower(name) like :name");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        PanacheQuery<WorkflowDefinitionEntity> query = WorkflowDefinitionEntity
                .find(hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize));

        WorkflowDefinitionSearchResults results = new WorkflowDefinitionSearchResults();
        results.setItems(query.list().stream().map(this::toBean).toList());
        results.setTotalCount(query.count());
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    // -- Create --
    @Override
    @Transactional
    public WorkflowDefinition createWorkflowDefinition(NewWorkflowDefinition data) {
        checkDuplicateName(data.getName(), null);

        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.content = createEmptyWorkflowContent(data.getName());
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();

        return toBean(entity);
    }

    // -- Get --
    @Override
    public WorkflowDefinition getWorkflowDefinition(long workflowDefinitionId) {
        return toBean(findOrThrow(workflowDefinitionId));
    }

    // -- Update metadata --
    @Override
    @Transactional
    public WorkflowDefinition updateWorkflowDefinition(
            long workflowDefinitionId, UpdateWorkflowDefinition data) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        if (data.getName() != null) {
            checkDuplicateName(data.getName(), entity.id);
            entity.name = data.getName();
        }
        if (data.getDescription() != null) {
            entity.description = data.getDescription();
        }
        entity.updatedOn = Instant.now();

        return toBean(entity);
    }

    // -- Delete --
    @Override
    @Transactional
    public void deleteWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);
        // Versions cascade-deleted via FK ON DELETE CASCADE
        entity.delete();
    }

    // -- Update content --
    @Override
    @Transactional
    public void updateWorkflowDefinitionContent(long workflowDefinitionId, Object content) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);
        try {
            entity.content = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Invalid workflow content", 400);
        }
        entity.updatedOn = Instant.now();
    }

    // -- Publish --
    @Override
    @Transactional
    public WorkflowDefinitionVersion publishWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        if (entity.content == null || entity.content.isBlank()) {
            throw new WebApplicationException("No draft content to publish", 400);
        }

        // Deserialize and validate
        Workflow workflow;
        try {
            workflow = objectMapper.readValue(entity.content, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Invalid workflow JSON: " + e.getMessage(), 400);
        }

        List<ValidationProblem> problems = WorkflowValidator.validate(workflow);
        List<ValidationProblem> errors = problems.stream()
                .filter(p -> p.severity() == ValidationSeverity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new WebApplicationException(
                    Response.status(400).entity(errors).build());
        }

        // Create version
        int newVersion = entity.currentVersion != null ? entity.currentVersion + 1 : 1;

        WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
        version.definitionId = entity.id;
        version.version = newVersion;
        version.content = entity.content;
        version.createdOn = Instant.now();
        version.persist();

        entity.currentVersion = newVersion;
        entity.updatedOn = Instant.now();

        return toVersionBean(version);
    }

    // -- List versions --
    @Override
    public List<WorkflowDefinitionVersion> listWorkflowDefinitionVersions(
            long workflowDefinitionId) {
        findOrThrow(workflowDefinitionId);
        List<WorkflowDefinitionVersionEntity> versions = WorkflowDefinitionVersionEntity
                .find("definitionId", Sort.descending("version"), workflowDefinitionId)
                .list();
        return versions.stream().map(this::toVersionBean).toList();
    }

    // -- Get version --
    @Override
    public WorkflowDefinitionVersion getWorkflowDefinitionVersion(
            long workflowDefinitionId, int version) {
        findOrThrow(workflowDefinitionId);
        WorkflowDefinitionVersionEntity entity = WorkflowDefinitionVersionEntity
                .find("definitionId = ?1 and version = ?2", workflowDefinitionId, version)
                .firstResult();
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return toVersionBean(entity);
    }

    // -- Helpers --

    private WorkflowDefinitionEntity findOrThrow(long id) {
        WorkflowDefinitionEntity entity = WorkflowDefinitionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return entity;
    }

    private void checkDuplicateName(String name, Long excludeId) {
        WorkflowDefinitionEntity existing = WorkflowDefinitionEntity
                .find("name", name).firstResult();
        if (existing != null && (excludeId == null || !existing.id.equals(excludeId))) {
            throw new WebApplicationException("Workflow definition name already exists", 409);
        }
    }

    private WorkflowDefinition toBean(WorkflowDefinitionEntity entity) {
        WorkflowDefinition bean = new WorkflowDefinition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Object.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCurrentVersion(entity.currentVersion);
        bean.setCreatedOn(Date.from(entity.createdOn));
        bean.setUpdatedOn(Date.from(entity.updatedOn));
        return bean;
    }

    private WorkflowDefinitionVersion toVersionBean(WorkflowDefinitionVersionEntity entity) {
        WorkflowDefinitionVersion bean = new WorkflowDefinitionVersion();
        bean.setId(entity.id);
        bean.setDefinitionId(entity.definitionId);
        bean.setVersion(entity.version);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Object.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCreatedOn(Date.from(entity.createdOn));
        return bean;
    }

    private String createEmptyWorkflowContent(String name) {
        Map<String, Object> startNode = Map.of(
                "id", "start-1",
                "type", "start",
                "name", "Start",
                "config", Map.of(),
                "position", Map.of("x", 250, "y", 100));
        Map<String, Object> endNode = Map.of(
                "id", "end-1",
                "type", "end",
                "name", "End",
                "config", Map.of(),
                "position", Map.of("x", 250, "y", 400));
        Map<String, Object> edge = Map.of(
                "id", "edge-1",
                "source", "start-1",
                "target", "end-1",
                "priority", 0,
                "isDefault", true);
        Map<String, Object> workflow = Map.of(
                "id", UUID.randomUUID().toString(),
                "name", name,
                "nodes", List.of(startNode, endNode),
                "edges", List.of(edge));
        try {
            return objectMapper.writeValueAsString(workflow);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
```

Note: The exact method signatures on the generated `WorkflowDefinitionsResource` interface
depend on the OpenAPI spec from Task 1. The parameter names and types above match the spec
design. Adjust if the generated interface differs slightly (e.g., parameter types may be
`BigInteger` for numeric query params, `long` for path params).

- [ ] **Step 3: Commit**

```bash
git add app/pom.xml \
       app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java
git commit -m "feat: implement workflow definitions REST resource"
```

---

### Task 4: Backend Integration Tests

**Files:**
- Create: `app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java`

**Interfaces:**
- Consumes: REST API from Task 3

- [ ] **Step 1: Create the integration test**

Create
`app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java`:

```java
package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkflowDefinitionsResourceTest {

    private static final String BASE_PATH = "/api/v1/workflow-definitions";

    @Test
    void testCreateAndGetWorkflowDefinition() {
        int id = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Test Workflow",
                        "description": "A test workflow"
                    }
                    """)
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Test Workflow"))
                    .body("description", equalTo("A test workflow"))
                    .body("id", notNullValue())
                    .body("content", notNullValue())
                    .body("currentVersion", nullValue())
                    .body("createdOn", notNullValue())
                    .body("updatedOn", notNullValue())
                    .extract().path("id");

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Test Workflow"))
                    .body("content.nodes.size()", equalTo(2))
                    .body("content.edges.size()", equalTo(1));
    }

    @Test
    void testListWorkflowDefinitions() {
        createDefinition("List Test WF 1");
        createDefinition("List Test WF 2");

        given()
                .when()
                    .get(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("items.size()", greaterThanOrEqualTo(2))
                    .body("totalCount", greaterThanOrEqualTo(2))
                    .body("page", equalTo(1))
                    .body("limit", equalTo(20));
    }

    @Test
    void testFilterByName() {
        createDefinition("Unique Filter WF Name");

        given()
                .queryParam("filterName", "Unique Filter WF")
                .when()
                    .get(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("items.size()", greaterThanOrEqualTo(1))
                    .body("items.name", hasItem(containsString("Unique Filter WF")));
    }

    @Test
    void testUpdateMetadata() {
        int id = createDefinition("Update Meta WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Updated WF Name",
                        "description": "Updated description"
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Updated WF Name"))
                    .body("description", equalTo("Updated description"));
    }

    @Test
    void testUpdateContent() {
        int id = createDefinition("Content Update WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Content Update WF",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("content.id", equalTo("wf-test"));
    }

    @Test
    void testPublishValidWorkflow() {
        int id = createDefinition("Publish WF");

        // Update with valid content
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-pub",
                        "name": "Publish WF",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        // Publish
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("definitionId", equalTo(id))
                    .body("content", notNullValue())
                    .body("createdOn", notNullValue());

        // Verify currentVersion updated
        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("currentVersion", equalTo(1));

        // Publish again — version increments
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(2));
    }

    @Test
    void testPublishInvalidWorkflowReturns400() {
        int id = createDefinition("Invalid Publish WF");

        // Update with invalid content (no start node)
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-invalid",
                        "name": "Invalid",
                        "nodes": [
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": []
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        // Publish should fail
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(400);
    }

    @Test
    void testListVersions() {
        int id = createAndPublishDefinition("Versions List WF");

        given()
                .when()
                    .get(BASE_PATH + "/" + id + "/versions")
                .then()
                    .statusCode(200)
                    .body("size()", equalTo(1))
                    .body("[0].version", equalTo(1));
    }

    @Test
    void testGetSpecificVersion() {
        int id = createAndPublishDefinition("Version Get WF");

        given()
                .when()
                    .get(BASE_PATH + "/" + id + "/versions/1")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("content", notNullValue());
    }

    @Test
    void testDeleteWorkflowDefinition() {
        int id = createDefinition("Delete WF");

        given()
                .when()
                    .delete(BASE_PATH + "/" + id)
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(404);
    }

    @Test
    void testGetNotFound() {
        given()
                .when()
                    .get(BASE_PATH + "/99999")
                .then()
                    .statusCode(404);
    }

    @Test
    void testDuplicateNameReturns409() {
        createDefinition("Dup Name WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Dup Name WF"
                    }
                    """)
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(409);
    }

    // -- Helpers --

    private int createDefinition(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s"
                    }
                    """, name))
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    private int createAndPublishDefinition(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Test",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java
git commit -m "test: add workflow definitions REST integration tests"
```

---

### Task 5: UI Foundation — Types, API Client, Navigation, Routes

**Files:**
- Modify: `ui/package.json` (add `@apitomy/flow-ui` dependency)
- Modify: `ui/src/config/api.ts` (add types and fetch functions)
- Modify: `ui/src/components/AppSidebar.tsx` (add Workflows nav item)
- Modify: `ui/src/App.tsx` (add routes)

**Interfaces:**
- Produces: TypeScript types `WorkflowDefinition`, `NewWorkflowDefinition`,
  `WorkflowDefinitionVersion`; fetch functions `fetchWorkflowDefinitions`,
  `createWorkflowDefinition`, `getWorkflowDefinition`,
  `updateWorkflowDefinition`, `updateWorkflowDefinitionContent`,
  `publishWorkflowDefinition`, `deleteWorkflowDefinition`,
  `listWorkflowDefinitionVersions`, `getWorkflowDefinitionVersion`;
  navigation and routes for `/components/workflows` and
  `/components/workflows/:workflowDefinitionId`

- [ ] **Step 1: Add `@apitomy/flow-ui` to `ui/package.json`**

If using npm link (from prerequisites), the dependency is already linked. Otherwise add:

```json
"@apitomy/flow-ui": "1.0.1-SNAPSHOT"
```

The peer dependency `@xyflow/react` is already present at version 12.11.3.

- [ ] **Step 2: Add TypeScript interfaces to `ui/src/config/api.ts`**

Add after the existing interface definitions:

```typescript
export interface WorkflowDefinition {
    id: number;
    name: string;
    description?: string;
    content?: any;
    currentVersion?: number;
    createdOn: string;
    updatedOn: string;
}

export interface NewWorkflowDefinition {
    name: string;
    description?: string;
}

export interface UpdateWorkflowDefinition {
    name?: string;
    description?: string;
}

export interface WorkflowDefinitionVersion {
    id: number;
    definitionId: number;
    version: number;
    content?: any;
    createdOn: string;
}
```

- [ ] **Step 3: Add API client functions to `ui/src/config/api.ts`**

Add after the existing fetch functions:

```typescript
export async function fetchWorkflowDefinitions(
    page = 1, limit = 20, filterName?: string
): Promise<SearchResults<WorkflowDefinition>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    const response = await fetch(`${API}/workflow-definitions?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow definitions: ${response.status}`);
    return response.json();
}

export async function createWorkflowDefinition(
    data: NewWorkflowDefinition
): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow-definitions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to create workflow definition: ${response.status}`);
    return response.json();
}

export async function getWorkflowDefinition(id: number): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow-definitions/${id}`);
    if (!response.ok) throw new Error(`Failed to get workflow definition: ${response.status}`);
    return response.json();
}

export async function updateWorkflowDefinition(
    id: number, data: UpdateWorkflowDefinition
): Promise<WorkflowDefinition> {
    const response = await fetch(`${API}/workflow-definitions/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error(`Failed to update workflow definition: ${response.status}`);
    return response.json();
}

export async function updateWorkflowDefinitionContent(
    id: number, content: any
): Promise<void> {
    const response = await fetch(`${API}/workflow-definitions/${id}/content`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(content),
    });
    if (!response.ok) throw new Error(`Failed to update workflow content: ${response.status}`);
}

export async function publishWorkflowDefinition(
    id: number
): Promise<WorkflowDefinitionVersion> {
    const response = await fetch(`${API}/workflow-definitions/${id}/publish`, {
        method: "POST",
    });
    if (!response.ok) throw new Error(`Failed to publish workflow definition: ${response.status}`);
    return response.json();
}

export async function deleteWorkflowDefinition(id: number): Promise<void> {
    const response = await fetch(`${API}/workflow-definitions/${id}`, {
        method: "DELETE",
    });
    if (!response.ok) throw new Error(`Failed to delete workflow definition: ${response.status}`);
}

export async function listWorkflowDefinitionVersions(
    id: number
): Promise<WorkflowDefinitionVersion[]> {
    const response = await fetch(`${API}/workflow-definitions/${id}/versions`);
    if (!response.ok) throw new Error(`Failed to list versions: ${response.status}`);
    return response.json();
}

export async function getWorkflowDefinitionVersion(
    id: number, version: number
): Promise<WorkflowDefinitionVersion> {
    const response = await fetch(`${API}/workflow-definitions/${id}/versions/${version}`);
    if (!response.ok) throw new Error(`Failed to get version: ${response.status}`);
    return response.json();
}
```

- [ ] **Step 4: Add "Workflows" to `AppSidebar.tsx`**

In `ui/src/components/AppSidebar.tsx`:

1. Add `"/components/workflows"` to the `COMPONENT_PATHS` array.
2. Add a `NavItem` inside the Components `NavExpandable`, positioned alphabetically (after
   "Toolsets"):

```tsx
<NavItem
    isActive={location.pathname.startsWith("/components/workflows")}
    onClick={() => navigate("/components/workflows")}
>
    Workflows
</NavItem>
```

- [ ] **Step 5: Add routes to `App.tsx`**

In `ui/src/App.tsx`:

1. Import the new page components:

```typescript
import { WorkflowDefinitionsPage } from "./pages/WorkflowDefinitionsPage";
import { WorkflowDefinitionDetailPage } from "./pages/WorkflowDefinitionDetailPage";
```

2. Add routes alongside the other Components routes:

```tsx
<Route path="/components/workflows" element={<WorkflowDefinitionsPage />} />
<Route path="/components/workflows/:workflowDefinitionId"
       element={<WorkflowDefinitionDetailPage />} />
```

Note: The page components will be created in Tasks 6 and 7. For now, create placeholder files
so the imports don't break:

Create `ui/src/pages/WorkflowDefinitionsPage.tsx`:
```tsx
export function WorkflowDefinitionsPage() {
    return <div>Workflow Definitions - Coming Soon</div>;
}
```

Create `ui/src/pages/WorkflowDefinitionDetailPage.tsx`:
```tsx
export function WorkflowDefinitionDetailPage() {
    return <div>Workflow Definition Detail - Coming Soon</div>;
}
```

- [ ] **Step 6: Commit**

```bash
git add ui/src/config/api.ts \
       ui/src/components/AppSidebar.tsx \
       ui/src/App.tsx \
       ui/src/pages/WorkflowDefinitionsPage.tsx \
       ui/src/pages/WorkflowDefinitionDetailPage.tsx
git commit -m "feat(ui): add workflow definitions types, API client, navigation, and routes"
```

---

### Task 6: UI — Workflow Definitions List Page

**Files:**
- Modify: `ui/src/pages/WorkflowDefinitionsPage.tsx` (replace placeholder)

**Interfaces:**
- Consumes: `fetchWorkflowDefinitions`, `createWorkflowDefinition`,
  `deleteWorkflowDefinition`, `WorkflowDefinition`, `NewWorkflowDefinition` from Task 5

- [ ] **Step 1: Implement `WorkflowDefinitionsPage.tsx`**

Replace the placeholder with the full implementation. Follow the pattern from
`ActionTypesPage.tsx`:

- State: `items` (WorkflowDefinition[]), `totalCount`, `isCreateOpen`, `deleteTarget`,
  pagination state, filter state.
- `load()` callback calls `fetchWorkflowDefinitions()` with current page/limit/filters.
- Table columns: Name, Description, Version (show `currentVersion` or "Draft"), Updated.
- Rows are clickable — navigate to `/components/workflows/${id}`.
- Create modal with Name and Description fields. On create success, navigate to the new
  definition's detail page.
- Delete button per row with `ConfirmDeleteModal`.
- Search by name using `ChipFilterInput`.

Key UI elements to use (all from existing imports in the codebase):
- `PageSection`, `Title`, `Toolbar`, `ToolbarContent`, `ToolbarItem` from PatternFly
- `ChipFilterInput`, `FilterChips`, `ChipFilterCriteria` from `@apitomy/common-ui-components`
- `Table`, `Thead`, `Tbody`, `Tr`, `Th`, `Td` from `@patternfly/react-table`
- `Modal`, `ModalHeader`, `ModalBody`, `ModalFooter` from PatternFly
- `Button`, `TextInput`, `TextArea`, `FormGroup`, `Form`, `Label` from PatternFly
- `ConfirmDeleteModal` from `../components/ConfirmDeleteModal`

The Version column should display:
- `v{currentVersion}` with a PatternFly `<Label>` if published
- "Draft" in a grey `<Label>` if `currentVersion` is null/undefined

- [ ] **Step 2: Commit**

```bash
git add ui/src/pages/WorkflowDefinitionsPage.tsx
git commit -m "feat(ui): implement workflow definitions list page"
```

---

### Task 7: UI — Workflow Definition Detail Page with Editor

**Files:**
- Modify: `ui/src/pages/WorkflowDefinitionDetailPage.tsx` (replace placeholder)
- Modify: `ui/src/main.tsx` or the app's CSS entry point (add Flow CSS imports)

**Interfaces:**
- Consumes: `getWorkflowDefinition`, `updateWorkflowDefinition`,
  `updateWorkflowDefinitionContent`, `publishWorkflowDefinition`,
  `deleteWorkflowDefinition`, `listWorkflowDefinitionVersions`,
  `fetchActionTypes` from Task 5;
  `WorkflowEditor`, `EditorSpi`, `ActionTypeDescriptor`, `Workflow`,
  `ValidationProblem` from `@apitomy/flow-ui`

- [ ] **Step 1: Add CSS imports for Flow UI**

In the app's CSS entry point (check where PatternFly CSS is imported — likely `ui/src/main.tsx`
or `ui/src/index.css`), add:

```typescript
import "@xyflow/react/dist/style.css";
import "@apitomy/flow-ui/style.css";
```

Note: `@xyflow/react/dist/style.css` may already be imported if Axiom uses React Flow
elsewhere. Check before adding a duplicate.

- [ ] **Step 2: Implement `WorkflowDefinitionDetailPage.tsx`**

Replace the placeholder with the full implementation. Key structure:

```tsx
import { useParams, useNavigate } from "react-router-dom";
import { useState, useEffect, useCallback, useRef } from "react";
import { WorkflowEditor } from "@apitomy/flow-ui";
import type { Workflow, ValidationProblem, EditorSpi,
              ActionTypeDescriptor } from "@apitomy/flow-ui";
import {
    getWorkflowDefinition, updateWorkflowDefinition,
    updateWorkflowDefinitionContent, publishWorkflowDefinition,
    deleteWorkflowDefinition, listWorkflowDefinitionVersions,
    fetchActionTypes,
} from "../config/api";
import type { WorkflowDefinition, WorkflowDefinitionVersion } from "../config/api";
// PatternFly imports: PageSection, Breadcrumb, BreadcrumbItem, Title,
// Button, Flex, FlexItem, Label, Modal, TextInput, TextArea, FormGroup,
// Form, ExpandableSection, DescriptionList, etc.
```

**State:**
- `definition: WorkflowDefinition | null` — loaded from API
- `editorContent: Workflow | null` — current editor state (may differ from saved)
- `dirty: boolean` — true when editor content differs from last save
- `saving: boolean` — true during save operation
- `publishing: boolean` — true during publish operation
- `validationErrors: ValidationProblem[]` — from the editor's `onValidationChange`
- `versions: WorkflowDefinitionVersion[]` — published version list
- `useTheme` — match Axiom's current theme (check how other pages detect dark mode)

**EditorSpi setup:**

```tsx
const spi: EditorSpi = {
    actionTypes: async () => {
        const results = await fetchActionTypes(1, 1000);
        return results.items.map((at): ActionTypeDescriptor => ({
            value: at.name,
            label: at.name,
            description: at.description,
        }));
    },
};
```

**Editor integration:**

```tsx
<div style={{ flex: 1, minHeight: 0 }}>
    <WorkflowEditor
        workflow={editorContent}
        onChange={(updated) => {
            setEditorContent(updated);
            setDirty(true);
        }}
        onValidationChange={(problems) => {
            setValidationErrors(problems.filter(p => p.severity === "error"));
        }}
        theme={isDarkTheme ? "dark" : "light"}
        spi={spi}
    />
</div>
```

The outer container must fill the viewport height. Use a flex column layout:

```tsx
<PageSection isFilled style={{ display: "flex", flexDirection: "column", padding: 0 }}>
    {/* Header bar with breadcrumb, buttons */}
    <div style={{ padding: "16px 24px", borderBottom: "1px solid var(--pf-t--global--border--color--default)" }}>
        {/* Breadcrumb, title, Save/Publish/Delete buttons */}
    </div>
    {/* Editor fills remaining space */}
    <div style={{ flex: 1, minHeight: 0 }}>
        <WorkflowEditor ... />
    </div>
</PageSection>
```

**Save button logic:**
- Disabled when: `!dirty || saving`
- On click: call `updateWorkflowDefinitionContent(id, editorContent)`, then set
  `dirty = false`
- Visual: show "Unsaved changes" text or a dot indicator when dirty

**Publish button logic:**
- Disabled when: `dirty || publishing || validationErrors.length > 0`
- On click: call `publishWorkflowDefinition(id)`, then reload definition and versions
- On success, show a brief success alert

**Metadata editing:**
- Edit name/description via a modal (similar to how other detail pages handle it)
- Call `updateWorkflowDefinition(id, { name, description })`

**Version history:**
- `ExpandableSection` below the header or as a sidebar panel
- List versions with version number and timestamp
- Load via `listWorkflowDefinitionVersions(id)` on page load and after publish

**beforeunload guard:**

```tsx
useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
        if (dirty) {
            e.preventDefault();
        }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
}, [dirty]);
```

**Loading/not-found states:**
- Show spinner while loading
- Show 404 empty state if definition not found

- [ ] **Step 3: Commit**

```bash
git add ui/src/pages/WorkflowDefinitionDetailPage.tsx \
       ui/src/main.tsx
git commit -m "feat(ui): implement workflow definition detail page with visual editor"
```
