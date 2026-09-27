# Workflow Execution (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make workflow definitions runnable — users trigger a published workflow on a project,
Axiom executes action nodes by creating tasks, and a `WorkflowViewer` shows real-time progress.

**Architecture:** New `workflow_instance` table + entity, a `WorkflowExecutionService` that
wraps the Apitomy Flow engine for async action execution (all nodes return PENDING, tasks
complete them), a thin REST resource delegating to the service, a hook in
`TaskExecutionService.onTaskCompleted()` to advance workflows, and a new Workflow tab on
`ProjectDetailPage` embedding the `WorkflowViewer` from `@apitomy/flow-ui`.

**Tech Stack:** Java 25 / Quarkus 3.33 / Panache / Flyway / H2+PostgreSQL (backend), React 19
/ PatternFly 6 / @apitomy/flow-ui / @xyflow/react (frontend), Apitomy Flow Engine 1.0.1
(execution)

**Spec:** `docs/superpowers/specs/2026-08-26-workflow-execution-phase2-design.md`

**GitHub Issue:** #228

## Global Constraints

- Follow contract-first development: OpenAPI spec changes first, then `mvn install` to
  generate interfaces, then implement.
- REST resource impls must implement generated interfaces — no `@Path` on impl classes.
- Use generated beans from `io.apitomy.axiom.api.beans` for request/response types.
- Entities use Panache active record style (public fields, extend `PanacheEntity`).
- Do not run tests or Maven builds automatically — the user handles compilation and testing.
- Do not include Claude attribution in commit messages.
- Phase 2 supports only start, end, and action node types. Workflows with human-task,
  receive-event, or wait nodes are rejected at trigger time.

## Prerequisites

Before starting, ensure:
1. Apitomy Flow Engine is installed to local Maven repo:
   `cd ~/git/apitomy/apitomy-flow/engine && mvn install -DskipTests`
2. Phase 1 (workflow definitions) is complete and merged — the `apitomy-flow-engine`
   dependency is already in `app/pom.xml`, and the `WorkflowDefinitionEntity` /
   `WorkflowDefinitionVersionEntity` entities exist.

---

### Task 1: OpenAPI Spec — Workflow Instance Schemas and Endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces: Generated `WorkflowInstanceResource` interface,
  `WorkflowInstance` / `TriggerWorkflow` / `HistoryEntry` beans in
  `io.apitomy.axiom.api.beans`; updated `Project` bean with `hasWorkflowInstance` field

- [ ] **Step 1: Add the `WorkflowInstances` tag**

Add to the `tags` array (after `WorkflowDefinitions`):

```json
{
    "name": "WorkflowInstances",
    "description": "Workflow instance execution and monitoring"
}
```

- [ ] **Step 2: Add the `HistoryEntry` schema**

Add to the `components.schemas` section:

```json
"HistoryEntry": {
    "type": "object",
    "properties": {
        "nodeId": {
            "type": "string"
        },
        "nodeName": {
            "type": "string"
        },
        "enteredOn": {
            "format": "date-time",
            "type": "string"
        },
        "completedOn": {
            "format": "date-time",
            "type": "string"
        },
        "output": {
            "type": "object"
        }
    }
}
```

- [ ] **Step 3: Add the `WorkflowInstance` schema**

```json
"WorkflowInstance": {
    "required": [
        "id",
        "projectId",
        "definitionId",
        "definitionVersion",
        "status",
        "startedOn"
    ],
    "type": "object",
    "properties": {
        "id": {
            "format": "int64",
            "type": "integer"
        },
        "projectId": {
            "format": "int64",
            "type": "integer"
        },
        "definitionId": {
            "format": "int64",
            "type": "integer"
        },
        "definitionVersion": {
            "format": "int32",
            "type": "integer"
        },
        "definitionName": {
            "type": "string"
        },
        "status": {
            "type": "string"
        },
        "currentNodeId": {
            "type": "string"
        },
        "currentNodeName": {
            "type": "string"
        },
        "failureReason": {
            "type": "string"
        },
        "workflowContent": {
            "type": "object"
        },
        "context": {
            "type": "object"
        },
        "history": {
            "type": "array",
            "items": {
                "$ref": "#/components/schemas/HistoryEntry"
            }
        },
        "startedOn": {
            "format": "date-time",
            "type": "string"
        },
        "completedOn": {
            "format": "date-time",
            "type": "string"
        }
    }
}
```

- [ ] **Step 4: Add the `TriggerWorkflow` schema**

```json
"TriggerWorkflow": {
    "required": [
        "workflowDefinitionId"
    ],
    "type": "object",
    "properties": {
        "workflowDefinitionId": {
            "format": "int64",
            "type": "integer"
        }
    }
}
```

- [ ] **Step 5: Add `hasWorkflowInstance` to the `Project` schema**

Add to the `properties` of the existing `Project` schema:

```json
"hasWorkflowInstance": {
    "type": "boolean"
}
```

- [ ] **Step 6: Add the path `/projects/{projectId}/workflow`**

Add to the `paths` section. Tag: `"WorkflowInstances"`. Three operations:

```json
"/projects/{projectId}/workflow": {
    "get": {
        "tags": [
            "WorkflowInstances"
        ],
        "summary": "Get the workflow instance for a project",
        "operationId": "getProjectWorkflowInstance",
        "responses": {
            "200": {
                "description": "The workflow instance",
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/WorkflowInstance"
                        }
                    }
                }
            },
            "404": {
                "$ref": "#/components/responses/NotFound"
            }
        }
    },
    "post": {
        "tags": [
            "WorkflowInstances"
        ],
        "summary": "Trigger a workflow on a project",
        "operationId": "triggerProjectWorkflow",
        "requestBody": {
            "content": {
                "application/json": {
                    "schema": {
                        "$ref": "#/components/schemas/TriggerWorkflow"
                    }
                }
            },
            "required": true
        },
        "responses": {
            "200": {
                "description": "Workflow triggered",
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/WorkflowInstance"
                        }
                    }
                }
            },
            "400": {
                "description": "Workflow definition not published or contains unsupported node types"
            },
            "404": {
                "$ref": "#/components/responses/NotFound"
            },
            "409": {
                "description": "Project already has a workflow instance"
            }
        }
    },
    "delete": {
        "tags": [
            "WorkflowInstances"
        ],
        "summary": "Cancel a running workflow instance",
        "operationId": "cancelProjectWorkflow",
        "responses": {
            "204": {
                "description": "Workflow cancelled"
            },
            "404": {
                "$ref": "#/components/responses/NotFound"
            },
            "409": {
                "description": "Workflow instance is already in a terminal state"
            }
        }
    },
    "parameters": [
        {
            "$ref": "#/components/parameters/ProjectId"
        }
    ]
}
```

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add workflow instance OpenAPI schemas and endpoints"
```

After this commit, run `mvn install` to generate the Java interfaces and beans.

---

### Task 2: Database Migration and Entities

**Files:**
- Create: `app/src/main/resources/db/migration/V53__create_workflow_instance.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowInstanceEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/TaskEntity.java`

**Interfaces:**
- Produces: `WorkflowInstanceEntity` Panache entity, `TaskEntity.workflowInstanceId` field —
  used by `WorkflowExecutionService` in Task 3 and `WorkflowInstanceResourceImpl` in Task 4

- [ ] **Step 1: Create the Flyway migration**

Create `app/src/main/resources/db/migration/V53__create_workflow_instance.sql`:

```sql
CREATE TABLE workflow_instance (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL UNIQUE REFERENCES project(id),
    definition_id BIGINT NOT NULL REFERENCES workflow_definition(id),
    definition_version INT NOT NULL,
    instance_state TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_node_id VARCHAR(255),
    failure_reason TEXT,
    started_on TIMESTAMP NOT NULL,
    completed_on TIMESTAMP
);

CREATE SEQUENCE IF NOT EXISTS workflow_instance_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_wf_instance_status ON workflow_instance(status);

ALTER TABLE task ADD COLUMN workflow_instance_id BIGINT
    REFERENCES workflow_instance(id);
```

- [ ] **Step 2: Create `WorkflowInstanceEntity`**

Create
`core/src/main/java/io/apitomy/axiom/core/entities/WorkflowInstanceEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_instance")
public class WorkflowInstanceEntity extends PanacheEntity {

    @Column(name = "project_id", nullable = false, unique = true)
    public Long projectId;

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(name = "definition_version", nullable = false)
    public int definitionVersion;

    @Column(name = "instance_state", columnDefinition = "TEXT", nullable = false)
    public String instanceState;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(name = "current_node_id")
    public String currentNodeId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    public String failureReason;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
```

- [ ] **Step 3: Add `workflowInstanceId` to `TaskEntity`**

In `core/src/main/java/io/apitomy/axiom/core/entities/TaskEntity.java`, add after the
`sessionId` field (around line 53):

```java
@Column(name = "workflow_instance_id")
public Long workflowInstanceId;
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V53__create_workflow_instance.sql \
       core/src/main/java/io/apitomy/axiom/core/entities/WorkflowInstanceEntity.java \
       core/src/main/java/io/apitomy/axiom/core/entities/TaskEntity.java
git commit -m "feat: add workflow instance DB migration and entities"
```

---

### Task 3: Workflow Execution Service

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`

**Interfaces:**
- Consumes: `WorkflowInstanceEntity` from Task 2, `TaskEntity.workflowInstanceId` from
  Task 2, `WorkflowDefinitionEntity` and `WorkflowDefinitionVersionEntity` from Phase 1,
  `ProjectEntity` from existing codebase
- Produces: `WorkflowExecutionService` with methods `triggerWorkflow(long, long)`,
  `onTaskCompleted(long)`, `cancelWorkflow(long)` — used by `WorkflowInstanceResourceImpl`
  in Task 4 and `TaskExecutionService` hook in Task 4

- [ ] **Step 1: Add `workflowUpdated` factory method to `SseEvent`**

In `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`, add before the
`heartbeat()` method (around line 172):

```java
/**
 * Fires when a workflow instance changes state.
 */
public static SseEvent workflowUpdated(long projectId) {
    return new SseEvent("workflow-updated",
            "{\"projectId\":" + projectId + "}");
}
```

- [ ] **Step 2: Create `WorkflowExecutionService`**

Create
`app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowInstanceEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.flow.engine.WorkflowEngine;
import io.apitomy.flow.model.InstanceStatus;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.spi.ActionInfo;
import io.apitomy.flow.spi.NodeExecutionContext;
import io.apitomy.flow.spi.NodeExecutor;
import io.apitomy.flow.spi.NodeExecutorProvider;
import io.apitomy.flow.spi.NodeResult;
import io.apitomy.flow.spi.NodeResultStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class WorkflowExecutionService {

    private static final Logger LOG = Logger.getLogger(WorkflowExecutionService.class);
    private static final Set<NodeType> SUPPORTED_NODE_TYPES =
            Set.of(NodeType.START, NodeType.END, NodeType.ACTION);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<SseEvent> sseEvents;

    private WorkflowEngine workflowEngine;

    @PostConstruct
    void init() {
        NodeExecutorProvider provider = actionType -> new NodeExecutor() {
            @Override
            public String actionType() {
                return actionType;
            }

            @Override
            public NodeResult execute(NodeExecutionContext context) {
                return new NodeResult(NodeResultStatus.PENDING, Map.of());
            }
        };
        this.workflowEngine = new WorkflowEngine(provider, List.of(), null);
    }

    /**
     * Triggers a workflow on a project.
     */
    @Transactional
    public WorkflowInstanceEntity triggerWorkflow(long projectId, long definitionId) {
        ProjectEntity project = ProjectEntity.findById(projectId);
        if (project == null) {
            throw new WebApplicationException("Project not found", 404);
        }

        WorkflowInstanceEntity existing = WorkflowInstanceEntity
                .find("projectId", projectId).firstResult();
        if (existing != null) {
            throw new WebApplicationException(
                    "Project already has a workflow instance", 409);
        }

        WorkflowDefinitionEntity definition =
                WorkflowDefinitionEntity.findById(definitionId);
        if (definition == null) {
            throw new WebApplicationException(
                    "Workflow definition not found", 404);
        }
        if (definition.currentVersion == null) {
            throw new WebApplicationException(
                    "Workflow definition has no published version", 400);
        }

        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                definitionId, definition.currentVersion)
                        .firstResult();
        if (version == null) {
            throw new WebApplicationException(
                    "Published version not found", 400);
        }

        Workflow workflow = deserializeWorkflow(version.content);

        validateNodeTypes(workflow);

        Map<String, Object> context = new HashMap<>();
        context.put("projectId", project.id);
        context.put("projectName", project.name);
        context.put("repository", project.repository);
        context.put("ref", project.ref);

        WorkflowInstance instance = workflowEngine.startWorkflow(
                workflow, context);

        WorkflowInstanceEntity entity = new WorkflowInstanceEntity();
        entity.projectId = projectId;
        entity.definitionId = definitionId;
        entity.definitionVersion = definition.currentVersion;
        entity.startedOn = Instant.now();
        persistInstanceState(entity, instance);
        entity.persist();

        if (instance.status() == InstanceStatus.WAITING) {
            createTaskForCurrentNode(entity, workflow, instance);
        }

        logActivity(projectId, "workflow-started",
                "Workflow started: " + definition.name);
        sseEvents.fire(SseEvent.workflowUpdated(projectId));

        return entity;
    }

    /**
     * Called when a workflow-spawned task completes, advancing the workflow.
     */
    @Transactional
    public void onTaskCompleted(long taskId) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null || task.workflowInstanceId == null) {
            return;
        }

        WorkflowInstanceEntity entity =
                WorkflowInstanceEntity.findById(task.workflowInstanceId);
        if (entity == null) {
            LOG.warnf("Workflow instance %d not found for task %d",
                    task.workflowInstanceId, taskId);
            return;
        }

        Workflow workflow = loadWorkflowContent(
                entity.definitionId, entity.definitionVersion);
        WorkflowInstance instance = deserializeInstance(entity.instanceState);

        NodeResult result;
        if ("Completed".equals(task.status)) {
            Map<String, Object> output = parseOutputMap(task.output);
            result = new NodeResult(NodeResultStatus.COMPLETED, output);
        } else {
            result = new NodeResult(NodeResultStatus.FAILED, Map.of());
        }

        WorkflowInstance advanced = workflowEngine.completeCurrentNode(
                workflow, instance, result);

        persistInstanceState(entity, advanced);

        if (advanced.status() == InstanceStatus.WAITING) {
            createTaskForCurrentNode(entity, workflow, advanced);
        } else if (advanced.status() == InstanceStatus.COMPLETED) {
            entity.completedOn = Instant.now();
            logActivity(entity.projectId, "workflow-completed",
                    "Workflow completed");
        } else if (advanced.status() == InstanceStatus.FAILED) {
            entity.completedOn = Instant.now();
            logActivity(entity.projectId, "workflow-failed",
                    "Workflow failed: " + advanced.failureReason());
            sseEvents.fire(SseEvent.notification(
                    "Workflow failed for project", "error"));
        }

        sseEvents.fire(SseEvent.workflowUpdated(entity.projectId));
    }

    /**
     * Cancels a running or waiting workflow instance.
     */
    @Transactional
    public void cancelWorkflow(long projectId) {
        WorkflowInstanceEntity entity = WorkflowInstanceEntity
                .find("projectId", projectId).firstResult();
        if (entity == null) {
            throw new WebApplicationException("No workflow instance found", 404);
        }

        if ("completed".equals(entity.status)
                || "failed".equals(entity.status)
                || "cancelled".equals(entity.status)) {
            throw new WebApplicationException(
                    "Workflow instance is already in a terminal state", 409);
        }

        Workflow workflow = loadWorkflowContent(
                entity.definitionId, entity.definitionVersion);
        WorkflowInstance instance = deserializeInstance(entity.instanceState);

        WorkflowInstance cancelled = workflowEngine.cancelWorkflow(
                workflow, instance);

        persistInstanceState(entity, cancelled);
        entity.completedOn = Instant.now();

        TaskEntity activeTask = TaskEntity
                .find("workflowInstanceId = ?1 and status in ?2",
                        entity.id,
                        List.of("Pending", "InProgress"))
                .firstResult();
        if (activeTask != null) {
            activeTask.status = "Failed";
            activeTask.output = "Cancelled: workflow was cancelled";
            activeTask.completedOn = Instant.now();
        }

        logActivity(projectId, "workflow-cancelled", "Workflow cancelled");
        sseEvents.fire(SseEvent.workflowUpdated(projectId));
    }

    // -- Private helpers --

    private void createTaskForCurrentNode(WorkflowInstanceEntity entity,
            Workflow workflow, WorkflowInstance instance) {
        ActionInfo actionInfo = workflowEngine.getActionInfo(
                workflow, instance);
        if (actionInfo == null) {
            LOG.warnf("No action info for current node in instance %d",
                    entity.id);
            return;
        }

        TaskEntity task = new TaskEntity();
        task.projectId = entity.projectId;
        task.actionType = actionInfo.actionType();
        task.createdBy = "workflow";
        task.status = "Pending";
        task.input = serializeInputs(actionInfo);
        task.workflowInstanceId = entity.id;
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Created task %d for workflow instance %d (action: %s)",
                task.id, entity.id, actionInfo.actionType());

        sseEvents.fire(SseEvent.taskUpdated(
                entity.projectId, task.id, task.status));
    }

    private void persistInstanceState(WorkflowInstanceEntity entity,
            WorkflowInstance instance) {
        try {
            entity.instanceState = objectMapper.writeValueAsString(instance);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Failed to serialize workflow instance state", 500);
        }
        entity.status = instance.status().name().toLowerCase();
        entity.currentNodeId = instance.currentNodeId();
        entity.failureReason = instance.failureReason();
    }

    private void validateNodeTypes(Workflow workflow) {
        List<String> unsupported = workflow.nodes().stream()
                .map(WorkflowNode::type)
                .filter(type -> !SUPPORTED_NODE_TYPES.contains(type))
                .map(NodeType::name)
                .distinct()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new WebApplicationException(
                    "Workflow contains unsupported node types: "
                            + String.join(", ", unsupported)
                            + ". Phase 2 supports only: start, end, action.",
                    400);
        }
    }

    private Workflow deserializeWorkflow(String json) {
        try {
            return objectMapper.readValue(json, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Invalid workflow JSON: " + e.getMessage(), 400);
        }
    }

    private WorkflowInstance deserializeInstance(String json) {
        try {
            return objectMapper.readValue(json, WorkflowInstance.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Invalid workflow instance state: " + e.getMessage(), 500);
        }
    }

    private Workflow loadWorkflowContent(long definitionId,
            int definitionVersion) {
        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                definitionId, definitionVersion)
                        .firstResult();
        if (version == null) {
            throw new WebApplicationException(
                    "Workflow version not found", 500);
        }
        return deserializeWorkflow(version.content);
    }

    private Map<String, Object> parseOutputMap(String output) {
        if (output == null || output.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(
                    output, Map.class);
            return map;
        } catch (JsonProcessingException e) {
            return Map.of("rawOutput", output);
        }
    }

    private String serializeInputs(ActionInfo actionInfo) {
        if (actionInfo.resolvedInputs() == null
                || actionInfo.resolvedInputs().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(
                    actionInfo.resolvedInputs());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void logActivity(Long projectId, String entryType,
            String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.entryType = entryType;
        log.summary = summary;
        log.createdOn = Instant.now();
        log.persist();

        sseEvents.fire(SseEvent.activity(entryType, summary));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java \
       app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java
git commit -m "feat: implement workflow execution service with Flow engine integration"
```

---

### Task 4: REST Resource and Existing Code Hooks

**Files:**
- Create:
  `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowInstanceResourceImpl.java`
- Modify:
  `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`
- Modify:
  `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`

**Interfaces:**
- Consumes: Generated `WorkflowInstanceResource` interface and beans from Task 1,
  `WorkflowInstanceEntity` from Task 2, `WorkflowExecutionService` from Task 3
- Produces: Working REST API at `/api/v1/projects/{projectId}/workflow`, workflow
  advancement hook in `TaskExecutionService`, `hasWorkflowInstance` on project responses

- [ ] **Step 1: Create `WorkflowInstanceResourceImpl`**

Create
`app/src/main/java/io/apitomy/axiom/app/rest/WorkflowInstanceResourceImpl.java`:

```java
package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.WorkflowInstanceResource;
import io.apitomy.axiom.api.beans.HistoryEntry;
import io.apitomy.axiom.api.beans.TriggerWorkflow;
import io.apitomy.axiom.app.WorkflowExecutionService;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowInstanceEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.util.Date;
import java.util.List;

@ApplicationScoped
public class WorkflowInstanceResourceImpl implements WorkflowInstanceResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance triggerProjectWorkflow(
            long projectId, TriggerWorkflow data) {
        WorkflowInstanceEntity entity = workflowExecutionService
                .triggerWorkflow(projectId, data.getWorkflowDefinitionId());
        return toBean(entity);
    }

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance
            getProjectWorkflowInstance(long projectId) {
        WorkflowInstanceEntity entity = WorkflowInstanceEntity
                .find("projectId", projectId).firstResult();
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return toBean(entity);
    }

    @Override
    public void cancelProjectWorkflow(long projectId) {
        workflowExecutionService.cancelWorkflow(projectId);
    }

    private io.apitomy.axiom.api.beans.WorkflowInstance toBean(
            WorkflowInstanceEntity entity) {
        io.apitomy.axiom.api.beans.WorkflowInstance bean =
                new io.apitomy.axiom.api.beans.WorkflowInstance();

        bean.setId(entity.id);
        bean.setProjectId(entity.projectId);
        bean.setDefinitionId(entity.definitionId);
        bean.setDefinitionVersion(entity.definitionVersion);
        bean.setStatus(entity.status);
        bean.setCurrentNodeId(entity.currentNodeId);
        bean.setFailureReason(entity.failureReason);
        bean.setStartedOn(Date.from(entity.startedOn));
        if (entity.completedOn != null) {
            bean.setCompletedOn(Date.from(entity.completedOn));
        }

        WorkflowDefinitionEntity definition =
                WorkflowDefinitionEntity.findById(entity.definitionId);
        if (definition != null) {
            bean.setDefinitionName(definition.name);
        }

        WorkflowDefinitionVersionEntity version =
                WorkflowDefinitionVersionEntity
                        .find("definitionId = ?1 and version = ?2",
                                entity.definitionId,
                                entity.definitionVersion)
                        .firstResult();
        if (version != null) {
            try {
                bean.setWorkflowContent(objectMapper.readValue(
                        version.content, Object.class));
            } catch (JsonProcessingException e) {
                bean.setWorkflowContent(null);
            }

            if (entity.currentNodeId != null) {
                try {
                    Workflow workflow = objectMapper.readValue(
                            version.content, Workflow.class);
                    workflow.findNodeById(entity.currentNodeId)
                            .ifPresent(node ->
                                    bean.setCurrentNodeName(node.name()));
                } catch (JsonProcessingException ignored) {
                }
            }
        }

        try {
            WorkflowInstance flowInstance = objectMapper.readValue(
                    entity.instanceState, WorkflowInstance.class);
            bean.setContext(flowInstance.context());
            bean.setHistory(flowInstance.history().stream()
                    .map(this::toHistoryBean).toList());
        } catch (JsonProcessingException e) {
            bean.setHistory(List.of());
        }

        return bean;
    }

    private HistoryEntry toHistoryBean(
            io.apitomy.flow.model.HistoryEntry entry) {
        HistoryEntry bean = new HistoryEntry();
        bean.setNodeId(entry.nodeId());
        bean.setNodeName(entry.nodeName());
        if (entry.enteredOn() != null) {
            bean.setEnteredOn(Date.from(entry.enteredOn()));
        }
        if (entry.completedOn() != null) {
            bean.setCompletedOn(Date.from(entry.completedOn()));
        }
        if (entry.output() != null && !entry.output().isEmpty()) {
            bean.setOutput(entry.output());
        }
        return bean;
    }
}
```

Note: The exact method signatures on the generated `WorkflowInstanceResource` interface
depend on the OpenAPI spec from Task 1. The parameter names and types above match the spec
design. Adjust if the generated interface differs (e.g., `projectId` may be `long` or
`Long`).

- [ ] **Step 2: Add workflow advancement hook to `TaskExecutionService`**

In `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`:

First, add an inject for the execution service near the other `@Inject` fields:

```java
@Inject
WorkflowExecutionService workflowExecutionService;
```

Then, in the `onTaskCompleted(Long taskId, AgentResult result)` method, add the following
after the `emitInternalEventIfNeeded(task)` call (at the very end of the method, before the
closing brace):

```java
if (task.workflowInstanceId != null) {
    workflowExecutionService.onTaskCompleted(task.id);
}
```

Also add the same check at the end of the `failTask(Long taskId, String reason)` method,
after the `updateProjectStatusAfterTask(task.projectId)` call:

```java
if (task.workflowInstanceId != null) {
    workflowExecutionService.onTaskCompleted(task.id);
}
```

- [ ] **Step 3: Add `hasWorkflowInstance` to project bean mapping**

In `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`, in the
`toProjectBean(ProjectEntity entity)` method (around line 508), add before the `return`
statement:

```java
project.setHasWorkflowInstance(
        WorkflowInstanceEntity.count("projectId", entity.id) > 0);
```

Add the required import at the top of the file:

```java
import io.apitomy.axiom.core.entities.WorkflowInstanceEntity;
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/WorkflowInstanceResourceImpl.java \
       app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java \
       app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java
git commit -m "feat: add workflow instance REST resource and task completion hook"
```

---

### Task 5: Backend Integration Tests

**Files:**
- Create:
  `app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java`

**Interfaces:**
- Consumes: REST API from Tasks 1-4, existing Projects API for test setup

- [ ] **Step 1: Create the integration test**

Create
`app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java`:

```java
package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkflowInstanceResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow-definitions";

    @Test
    void testTriggerAndGetWorkflow() {
        int projectId = createProject("WF Trigger Test Project");
        int definitionId = createAndPublishDefinition(
                "Trigger Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("id", notNullValue())
                    .body("projectId", equalTo(projectId))
                    .body("definitionId", equalTo(definitionId))
                    .body("definitionVersion", equalTo(1))
                    .body("definitionName", equalTo("Trigger Test WF"))
                    .body("status", anyOf(
                            equalTo("running"), equalTo("completed")))
                    .body("startedOn", notNullValue())
                    .body("workflowContent", notNullValue())
                    .body("history", notNullValue());

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("projectId", equalTo(projectId))
                    .body("definitionName", equalTo("Trigger Test WF"));
    }

    @Test
    void testTriggerDuplicateReturns409() {
        int projectId = createProject("WF Dup Test Project");
        int definitionId = createAndPublishDefinition(
                "Dup Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(409);
    }

    @Test
    void testTriggerUnpublishedReturns400() {
        int projectId = createProject("WF Unpub Test Project");
        int definitionId = createDefinition("Unpublished Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(400);
    }

    @Test
    void testTriggerWithUnsupportedNodeTypesReturns400() {
        int projectId = createProject("WF Unsupported Nodes Project");
        int definitionId = createDefinition(
                "Unsupported Nodes WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-unsupported",
                        "name": "Unsupported",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "ht1", "type": "human-task",
                             "name": "Human Task",
                             "config": {"description": "Do something"},
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "ht1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "ht1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + definitionId + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + definitionId + "/publish")
                .then()
                    .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(400);
    }

    @Test
    void testCancelWorkflow() {
        int projectId = createProject("WF Cancel Test Project");
        int definitionId = createAndPublishActionWorkflow(
                "Cancel Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("cancelled"));
    }

    @Test
    void testCancelTerminalReturns409() {
        int projectId = createProject("WF Cancel Term Project");
        int definitionId = createAndPublishDefinition(
                "Cancel Term WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(409);
    }

    @Test
    void testGetNoInstanceReturns404() {
        int projectId = createProject("WF No Instance Project");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(404);
    }

    @Test
    void testHasWorkflowInstanceOnProject() {
        int projectId = createProject(
                "WF HasInstance Test Project");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId)
                .then()
                    .statusCode(200)
                    .body("hasWorkflowInstance", equalTo(false));

        int definitionId = createAndPublishDefinition(
                "HasInstance Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId)
                .then()
                    .statusCode(200)
                    .body("hasWorkflowInstance", equalTo(true));
    }

    // -- Helpers --

    private int createProject(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s",
                        "type": "other",
                        "ref": "%s"
                    }
                    """, name,
                        "test/" + name.toLowerCase().replace(" ", "-")))
                .when()
                    .post(PROJECTS_PATH)
                .then()
                    .statusCode(201)
                    .extract().path("id");
    }

    private int createDefinition(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s"
                    }
                    """, name))
                .when()
                    .post(WORKFLOWS_PATH)
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    /**
     * Creates and publishes a simple start→end workflow (completes
     * immediately on trigger).
     */
    private int createAndPublishDefinition(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Test",
                        "nodes": [
                            {"id": "s1", "type": "start",
                             "name": "Start",
                             "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end",
                             "name": "End",
                             "config": {},
                             "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }

    /**
     * Creates and publishes a start→action→end workflow (stops at the
     * action node with status "waiting").
     */
    private int createAndPublishActionWorkflow(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-action",
                        "name": "Action Test",
                        "nodes": [
                            {"id": "s1", "type": "start",
                             "name": "Start",
                             "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "a1", "type": "action",
                             "name": "Do Something",
                             "config": {
                                 "actionType": "test-action"
                             },
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end",
                             "name": "End",
                             "config": {},
                             "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "a1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "a1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java
git commit -m "test: add workflow instance REST integration tests"
```

---

### Task 6: UI — Types, API Client, and Workflow Tab

**Files:**
- Modify: `ui/src/config/api.ts`
- Create: `ui/src/components/WorkflowTab.tsx`
- Modify: `ui/src/pages/ProjectDetailPage.tsx`

**Interfaces:**
- Consumes: REST API from Tasks 1-4, `WorkflowViewer` and `WorkflowInstance` types from
  `@apitomy/flow-ui`, existing `fetchWorkflowDefinitions` from Phase 1, `useEffectiveTheme`
  hook

- [ ] **Step 1: Add TypeScript interfaces to `ui/src/config/api.ts`**

Add after the existing `WorkflowDefinitionVersion` interface:

```typescript
export interface WorkflowInstanceInfo {
    id: number;
    projectId: number;
    definitionId: number;
    definitionVersion: number;
    definitionName: string;
    status: string;
    currentNodeId?: string;
    currentNodeName?: string;
    failureReason?: string;
    workflowContent: any;
    context: Record<string, any>;
    history: HistoryEntryInfo[];
    startedOn: string;
    completedOn?: string;
}

export interface HistoryEntryInfo {
    nodeId: string;
    nodeName: string;
    enteredOn: string;
    completedOn?: string;
    output?: any;
}

export interface TriggerWorkflowRequest {
    workflowDefinitionId: number;
}
```

- [ ] **Step 2: Add API client functions to `ui/src/config/api.ts`**

Add after the existing workflow definition functions:

```typescript
export async function triggerWorkflow(
    projectId: number, data: TriggerWorkflowRequest
): Promise<WorkflowInstanceInfo> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(data),
        });
    if (!response.ok) {
        throw new Error(
            `Failed to trigger workflow: ${response.status}`);
    }
    return response.json();
}

export async function getWorkflowInstance(
    projectId: number
): Promise<WorkflowInstanceInfo> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`);
    if (!response.ok) {
        if (response.status === 404) return null as any;
        throw new Error(
            `Failed to get workflow instance: ${response.status}`);
    }
    return response.json();
}

export async function cancelWorkflow(
    projectId: number
): Promise<void> {
    const response = await fetch(
        `${API}/projects/${projectId}/workflow`, {
            method: "DELETE",
        });
    if (!response.ok) {
        throw new Error(
            `Failed to cancel workflow: ${response.status}`);
    }
}
```

- [ ] **Step 3: Create `WorkflowTab` component**

Create `ui/src/components/WorkflowTab.tsx`:

```tsx
import { useState, useEffect, useCallback, useMemo } from "react";
import {
    Button, EmptyState, EmptyStateBody,
    Flex, FlexItem, Label, Modal, ModalBody,
    ModalFooter, ModalHeader, Form, FormGroup,
    FormSelect, FormSelectOption,
} from "@patternfly/react-core";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type { Workflow, WorkflowInstance } from "@apitomy/flow-ui";
import { useEffectiveTheme } from "../hooks/useTheme";
import { ConfirmDeleteModal } from "./ConfirmDeleteModal";
import {
    type WorkflowDefinition, type WorkflowInstanceInfo,
    fetchWorkflowDefinitions, triggerWorkflow,
    getWorkflowInstance, cancelWorkflow,
} from "../config/api";

interface WorkflowTabProps {
    projectId: number;
    hasWorkflowInstance: boolean;
    onRefresh: () => void;
}

export function WorkflowTab({
    projectId, hasWorkflowInstance, onRefresh,
}: WorkflowTabProps) {
    const effectiveTheme = useEffectiveTheme();
    const [instance, setInstance] =
        useState<WorkflowInstanceInfo | null>(null);
    const [loading, setLoading] = useState(true);
    const [isTriggerOpen, setIsTriggerOpen] = useState(false);
    const [isCancelOpen, setIsCancelOpen] = useState(false);
    const [definitions, setDefinitions] =
        useState<WorkflowDefinition[]>([]);
    const [selectedDefId, setSelectedDefId] = useState("");
    const [submitting, setSubmitting] = useState(false);

    const loadInstance = useCallback(() => {
        if (!hasWorkflowInstance) {
            setInstance(null);
            setLoading(false);
            return;
        }
        setLoading(true);
        getWorkflowInstance(projectId)
            .then(setInstance)
            .catch(() => setInstance(null))
            .finally(() => setLoading(false));
    }, [projectId, hasWorkflowInstance]);

    useEffect(() => {
        loadInstance();
    }, [loadInstance]);

    useEffect(() => {
        const eventSource = new EventSource("/api/v1/sse");
        const handler = (event: MessageEvent) => {
            try {
                const data = JSON.parse(event.data);
                if (data.projectId === projectId) {
                    loadInstance();
                    onRefresh();
                }
            } catch {
                // ignore
            }
        };
        eventSource.addEventListener(
            "workflow-updated", handler);
        return () => {
            eventSource.removeEventListener(
                "workflow-updated", handler);
            eventSource.close();
        };
    }, [projectId, loadInstance, onRefresh]);

    const openTriggerModal = useCallback(() => {
        fetchWorkflowDefinitions(1, 1000)
            .then((results) => {
                const published = results.items.filter(
                    (d) => d.currentVersion != null);
                setDefinitions(published);
                if (published.length > 0) {
                    setSelectedDefId(String(published[0].id));
                }
                setIsTriggerOpen(true);
            })
            .catch(console.error);
    }, []);

    const handleTrigger = useCallback(() => {
        if (!selectedDefId) return;
        setSubmitting(true);
        triggerWorkflow(projectId, {
            workflowDefinitionId: Number(selectedDefId),
        })
            .then(() => {
                setIsTriggerOpen(false);
                onRefresh();
                loadInstance();
            })
            .catch(console.error)
            .finally(() => setSubmitting(false));
    }, [projectId, selectedDefId, onRefresh, loadInstance]);

    const handleCancel = useCallback(() => {
        cancelWorkflow(projectId)
            .then(() => {
                setIsCancelOpen(false);
                onRefresh();
                loadInstance();
            })
            .catch(console.error);
    }, [projectId, onRefresh, loadInstance]);

    const workflowContent = useMemo<Workflow | null>(() => {
        if (!instance?.workflowContent) return null;
        return instance.workflowContent as Workflow;
    }, [instance]);

    const viewerInstance = useMemo<WorkflowInstance | null>(() => {
        if (!instance) return null;
        return {
            id: String(instance.id),
            workflowId: String(instance.definitionId),
            currentNodeId: instance.currentNodeId || "",
            status: instance.status as any,
            context: instance.context || {},
            history: (instance.history || []).map((h) => ({
                nodeId: h.nodeId,
                nodeName: h.nodeName,
                edgeId: "",
                edgeCondition: "",
                enteredOn: h.enteredOn,
                completedOn: h.completedOn || "",
                output: h.output || {},
            })),
            failureReason: instance.failureReason,
            createdOn: instance.startedOn,
            updatedOn: instance.completedOn || instance.startedOn,
        } as WorkflowInstance;
    }, [instance]);

    if (loading) {
        return <EmptyState><EmptyStateBody>
            Loading...
        </EmptyStateBody></EmptyState>;
    }

    if (!instance) {
        return (
            <>
                <EmptyState>
                    <EmptyStateBody>
                        No workflow is running on this project.
                    </EmptyStateBody>
                    <Button variant="primary"
                        onClick={openTriggerModal}>
                        Run Workflow
                    </Button>
                </EmptyState>

                <Modal isOpen={isTriggerOpen}
                    onClose={() => setIsTriggerOpen(false)}
                    variant="medium">
                    <ModalHeader title="Run Workflow" />
                    <ModalBody>
                        {definitions.length === 0 ? (
                            <EmptyState>
                                <EmptyStateBody>
                                    No published workflow definitions
                                    available.
                                </EmptyStateBody>
                            </EmptyState>
                        ) : (
                            <Form>
                                <FormGroup label="Workflow Definition"
                                    isRequired fieldId="wf-def">
                                    <FormSelect id="wf-def"
                                        value={selectedDefId}
                                        onChange={(_e, v) =>
                                            setSelectedDefId(v)}>
                                        {definitions.map((d) => (
                                            <FormSelectOption
                                                key={d.id}
                                                value={String(d.id)}
                                                label={`${d.name} (v${d.currentVersion})`}
                                            />
                                        ))}
                                    </FormSelect>
                                </FormGroup>
                            </Form>
                        )}
                    </ModalBody>
                    <ModalFooter>
                        <Button variant="primary"
                            onClick={handleTrigger}
                            isDisabled={
                                !selectedDefId
                                || definitions.length === 0
                                || submitting}
                            isLoading={submitting}>
                            Run Workflow
                        </Button>
                        <Button variant="link"
                            onClick={
                                () => setIsTriggerOpen(false)}>
                            Cancel
                        </Button>
                    </ModalFooter>
                </Modal>
            </>
        );
    }

    const isActive = instance.status === "running"
        || instance.status === "waiting";

    return (
        <>
            <Flex justifyContent={{
                default: "justifyContentSpaceBetween" }}
                alignItems={{
                    default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Flex alignItems={{
                        default: "alignItemsCenter" }}
                        spaceItems={{
                            default: "spaceItemsSm" }}>
                        <FlexItem>
                            <strong>
                                {instance.definitionName}
                            </strong>
                            {" v"}
                            {instance.definitionVersion}
                        </FlexItem>
                        <FlexItem>
                            <Label color={
                                instance.status === "completed"
                                    ? "green"
                                    : instance.status === "failed"
                                        ? "red"
                                        : instance.status === "cancelled"
                                            ? "grey"
                                            : "blue"}>
                                {instance.status}
                            </Label>
                        </FlexItem>
                        {instance.currentNodeName && isActive && (
                            <FlexItem>
                                <Label color="cyan">
                                    {instance.currentNodeName}
                                </Label>
                            </FlexItem>
                        )}
                    </Flex>
                </FlexItem>
                {isActive && (
                    <FlexItem>
                        <Button variant="danger"
                            onClick={() => setIsCancelOpen(true)}>
                            Cancel Workflow
                        </Button>
                    </FlexItem>
                )}
            </Flex>

            {instance.failureReason && (
                <div style={{
                    padding: "8px 16px",
                    marginBottom: "16px",
                    backgroundColor:
                        "var(--pf-t--global--color--status--danger--default)",
                    color: "white",
                    borderRadius: "4px" }}>
                    {instance.failureReason}
                </div>
            )}

            {workflowContent && viewerInstance && (
                <div style={{ height: "500px" }}>
                    <WorkflowViewer
                        workflow={workflowContent}
                        instance={viewerInstance}
                        theme={effectiveTheme === "dark"
                            ? "dark" : "light"}
                    />
                </div>
            )}

            <ConfirmDeleteModal
                isOpen={isCancelOpen}
                title="Cancel Workflow"
                onConfirm={handleCancel}
                onCancel={() => setIsCancelOpen(false)}
                confirmLabel="Cancel Workflow">
                Are you sure you want to cancel this workflow?
                Any in-progress tasks will be stopped.
            </ConfirmDeleteModal>
        </>
    );
}
```

- [ ] **Step 4: Add `WorkflowTab` to `ProjectDetailPage`**

In `ui/src/pages/ProjectDetailPage.tsx`:

First, add the import near the other component imports (around line 74):

```typescript
import { WorkflowTab } from "../components/WorkflowTab";
```

Then, add the new tab after the existing Metrics tab (eventKey 3). Add inside the `<Tabs>`
component, after the Metrics `<Tab>` closing tag:

```tsx
<Tab eventKey={4} title={<TabTitleText>Workflow{project?.hasWorkflowInstance ? " ●" : ""}</TabTitleText>}>
    <TabContent id="workflow-tab" eventKey={4} activeKey={activeTab} style={{ marginTop: "16px" }}>
        {project && (
            <WorkflowTab
                projectId={project.id}
                hasWorkflowInstance={project.hasWorkflowInstance ?? false}
                onRefresh={loadData}
            />
        )}
    </TabContent>
</Tab>
```

Also update the `Project` interface import in `api.ts` if it is imported with destructuring
— the `hasWorkflowInstance` field was already added to the OpenAPI `Project` schema in Task 1,
so the generated bean and TypeScript interface should already include it after the spec
change.

Add `hasWorkflowInstance` to the `Project` interface in `ui/src/config/api.ts` (around
line 57):

```typescript
hasWorkflowInstance?: boolean;
```

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts \
       ui/src/components/WorkflowTab.tsx \
       ui/src/pages/ProjectDetailPage.tsx
git commit -m "feat(ui): add workflow tab with viewer and trigger modal to project detail page"
```
