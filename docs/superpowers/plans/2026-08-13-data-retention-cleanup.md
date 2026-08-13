# Data Retention Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable, automatic cleanup of stale data (closed projects, traces, events,
event source logs, and processed event queue entries) with a UI settings page for retention
periods.

**Architecture:** A new single-row `RetentionConfigEntity` stores retention periods in the
database, exposed via GET/PUT REST endpoints on the `SystemResource`. Four new `@Scheduled`
cleanup jobs (plus a modified existing one) read from that entity and delete old data hourly.
A new React page under Configuration lets users view and edit the retention periods.

**Tech Stack:** Java 25 / Quarkus 3.33 (Panache, Scheduler), Flyway migrations, React 19 /
PatternFly 6, OpenAPI 3.1 contract-first codegen.

**Spec:** Design approved in conversation on 2026-08-13 (no separate spec file — bounded task).

## Global Constraints

- API-first: all REST changes start in `common/api/src/main/resources/openapi.json`, then
  `mvn install` regenerates interfaces/beans.
- Generated beans live in `io.apitomy.axiom.api.beans`; generated interfaces in
  `io.apitomy.axiom.api`.
- Entity classes go in `core/src/main/java/io/apitomy/axiom/core/entities/`.
- Service and cleanup classes go in `app/src/main/java/io/apitomy/axiom/app/`.
- REST impl classes go in `app/src/main/java/io/apitomy/axiom/app/rest/`.
- Flyway migrations go in `app/src/main/resources/db/migration/` with the next version number
  (`V38__`).
- Java 4-space indent, camelCase variables, PascalCase classes, Javadoc on public methods.
- UI files go in `ui/src/pages/` and `ui/src/config/`.
- Do not run `mvn install` or tests — the developer handles that manually.

---

### Task 1: OpenAPI Spec — Add RetentionConfig Schema and Endpoints

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Consumes: nothing
- Produces: After `mvn install`, generates `RetentionConfig` bean class and
  `getRetentionConfig()` / `updateRetentionConfig(RetentionConfig)` methods on
  `SystemResource` interface.

- [ ] **Step 1: Add the `RetentionConfig` schema to the `components/schemas` section**

Add after the existing `ManagerConfig` schema (around line 5441):

```json
"RetentionConfig": {
  "type": "object",
  "properties": {
    "closedProjectRetentionDays": {
      "description": "Number of days to retain closed projects before automatic deletion.",
      "format": "int32",
      "type": "integer"
    },
    "traceRetentionDays": {
      "description": "Number of days to retain execution traces before automatic deletion.",
      "format": "int32",
      "type": "integer"
    },
    "eventRetentionDays": {
      "description": "Number of days to retain ingested events before automatic deletion.",
      "format": "int32",
      "type": "integer"
    },
    "eventSourceLogRetentionDays": {
      "description": "Number of days to retain event source poll logs before automatic deletion.",
      "format": "int32",
      "type": "integer"
    }
  }
}
```

- [ ] **Step 2: Add `GET /system/retention` endpoint to the `paths` section**

Add after the `/system/engines` path block (around line 120):

```json
"/system/retention": {
  "get": {
    "tags": [
      "System"
    ],
    "summary": "Get data retention configuration",
    "operationId": "getRetentionConfig",
    "responses": {
      "200": {
        "description": "Current retention configuration",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/RetentionConfig"
            }
          }
        }
      }
    }
  },
  "put": {
    "tags": [
      "System"
    ],
    "summary": "Update data retention configuration",
    "operationId": "updateRetentionConfig",
    "requestBody": {
      "content": {
        "application/json": {
          "schema": {
            "$ref": "#/components/schemas/RetentionConfig"
          }
        }
      },
      "required": true
    },
    "responses": {
      "200": {
        "description": "Retention configuration updated",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/RetentionConfig"
            }
          }
        }
      }
    }
  }
}
```

- [ ] **Step 3: Run `mvn install` to regenerate interfaces and beans**

The developer will run this manually. After generation, the following will be available:
- `io.apitomy.axiom.api.beans.RetentionConfig` (with getters/setters for all four fields)
- `getRetentionConfig()` and `updateRetentionConfig(RetentionConfig)` methods on
  `io.apitomy.axiom.api.SystemResource`

- [ ] **Step 4: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat: add RetentionConfig schema and /system/retention endpoints to OpenAPI spec"
```

---

### Task 2: Database Migration and Entity

**Files:**
- Create: `app/src/main/resources/db/migration/V38__create_retention_config.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/RetentionConfigEntity.java`

**Interfaces:**
- Consumes: nothing
- Produces: `RetentionConfigEntity` — single-row Panache entity with fields
  `closedProjectRetentionDays` (int), `traceRetentionDays` (int),
  `eventRetentionDays` (int), `eventSourceLogRetentionDays` (int).

- [ ] **Step 1: Create the Flyway migration**

Create `app/src/main/resources/db/migration/V38__create_retention_config.sql`:

```sql
CREATE TABLE IF NOT EXISTS retention_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    closed_project_retention_days INT NOT NULL DEFAULT 90,
    trace_retention_days INT NOT NULL DEFAULT 30,
    event_retention_days INT NOT NULL DEFAULT 90,
    event_source_log_retention_days INT NOT NULL DEFAULT 7
);

CREATE SEQUENCE IF NOT EXISTS retention_config_SEQ START WITH 1 INCREMENT BY 50;
```

- [ ] **Step 2: Create the entity class**

Create `core/src/main/java/io/apitomy/axiom/core/entities/RetentionConfigEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Single-row configuration for data retention periods. Each field
 * specifies the number of days to retain data before automatic cleanup.
 */
@Entity
@Table(name = "retention_config")
public class RetentionConfigEntity extends PanacheEntity {

    @Column(name = "closed_project_retention_days", nullable = false)
    public int closedProjectRetentionDays;

    @Column(name = "trace_retention_days", nullable = false)
    public int traceRetentionDays;

    @Column(name = "event_retention_days", nullable = false)
    public int eventRetentionDays;

    @Column(name = "event_source_log_retention_days", nullable = false)
    public int eventSourceLogRetentionDays;
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migration/V38__create_retention_config.sql
git add core/src/main/java/io/apitomy/axiom/core/entities/RetentionConfigEntity.java
git commit -m "feat: add retention_config table and entity"
```

---

### Task 3: Seed Data and REST Implementation

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SystemResourceImpl.java`

**Interfaces:**
- Consumes: `RetentionConfigEntity` (Task 2), generated `RetentionConfig` bean and
  `SystemResource` interface methods (Task 1)
- Produces: Seeded default row on first startup; `GET /system/retention` and
  `PUT /system/retention` endpoints

- [ ] **Step 1: Add seed method to `SeedDataInitializer`**

Add import for `RetentionConfigEntity` at the top of the file. Add a call to
`seedRetentionConfig()` at the end of the `onStart` method (after the existing
`seedManagerConfig()` call — note: `seedManagerConfig()` is called unconditionally,
not guarded by the early-return at the top). Then add the method:

```java
private void seedRetentionConfig() {
    if (RetentionConfigEntity.count() > 0) {
        LOG.info("Retention config already exists, skipping seed");
        return;
    }

    RetentionConfigEntity config = new RetentionConfigEntity();
    config.closedProjectRetentionDays = 90;
    config.traceRetentionDays = 30;
    config.eventRetentionDays = 90;
    config.eventSourceLogRetentionDays = 7;
    config.persist();

    LOG.info("Seeded default retention configuration");
}
```

- [ ] **Step 2: Implement `getRetentionConfig()` in `SystemResourceImpl`**

Add import for `RetentionConfigEntity` and the generated `RetentionConfig` bean. Add
`@Inject WorkspaceService workspaceService;` is not needed here. Implement:

```java
/**
 * {@inheritDoc}
 */
@Override
public RetentionConfig getRetentionConfig() {
    RetentionConfigEntity entity = RetentionConfigEntity.<RetentionConfigEntity>findAll()
            .firstResult();

    RetentionConfig config = new RetentionConfig();
    if (entity != null) {
        config.setClosedProjectRetentionDays(entity.closedProjectRetentionDays);
        config.setTraceRetentionDays(entity.traceRetentionDays);
        config.setEventRetentionDays(entity.eventRetentionDays);
        config.setEventSourceLogRetentionDays(entity.eventSourceLogRetentionDays);
    } else {
        config.setClosedProjectRetentionDays(90);
        config.setTraceRetentionDays(30);
        config.setEventRetentionDays(90);
        config.setEventSourceLogRetentionDays(7);
    }
    return config;
}
```

- [ ] **Step 3: Implement `updateRetentionConfig()` in `SystemResourceImpl`**

```java
/**
 * {@inheritDoc}
 */
@Override
@Transactional
public RetentionConfig updateRetentionConfig(RetentionConfig data) {
    RetentionConfigEntity entity = RetentionConfigEntity.<RetentionConfigEntity>findAll()
            .firstResult();
    if (entity == null) {
        entity = new RetentionConfigEntity();
    }
    entity.closedProjectRetentionDays = data.getClosedProjectRetentionDays();
    entity.traceRetentionDays = data.getTraceRetentionDays();
    entity.eventRetentionDays = data.getEventRetentionDays();
    entity.eventSourceLogRetentionDays = data.getEventSourceLogRetentionDays();
    entity.persist();

    return data;
}
```

Add `import jakarta.transaction.Transactional;` to the imports if not already present.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java
git add app/src/main/java/io/apitomy/axiom/app/rest/SystemResourceImpl.java
git commit -m "feat: seed retention config defaults and implement REST endpoints"
```

---

### Task 4: Extract `ProjectDeletionService`

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/ProjectDeletionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`

**Interfaces:**
- Consumes: `WorkspaceService`, entity classes (`ThreadEntryEntity`, `AiUsageEntity`,
  `ActivityLogEntity`, `EventEntity`, `TaskEntity`, `ProjectEntity`)
- Produces: `ProjectDeletionService.deleteProject(ProjectEntity)` — performs the full
  cascade deletion of a closed project.

- [ ] **Step 1: Create `ProjectDeletionService`**

Create `app/src/main/java/io/apitomy/axiom/app/ProjectDeletionService.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Handles the full cascade deletion of a project and all its associated data.
 */
@ApplicationScoped
public class ProjectDeletionService {

    @Inject
    WorkspaceService workspaceService;

    /**
     * Deletes a project and all associated data: thread entries, AI usage records,
     * activity log entries, tasks, and the workspace directory. Nullifies the
     * projectId on any linked events rather than deleting them.
     *
     * @param project the project to delete (must already be in Completed status)
     */
    public void deleteProject(ProjectEntity project) {
        long projectId = project.id;
        ThreadEntryEntity.delete("projectId", projectId);
        AiUsageEntity.delete("projectId", projectId);
        ActivityLogEntity.delete("projectId", projectId);
        EventEntity.update("projectId = null where projectId = ?1", projectId);
        TaskEntity.delete("projectId", projectId);
        workspaceService.deleteWorkspace(project);
        project.delete();
    }
}
```

- [ ] **Step 2: Update `ProjectsResourceImpl.deleteProject()` to use the service**

In `ProjectsResourceImpl.java`, add the field:

```java
@Inject
ProjectDeletionService projectDeletionService;
```

Replace the body of `deleteProject(long projectId)` (lines 193–207):

```java
@Override
@Transactional
public void deleteProject(long projectId) {
    ProjectEntity entity = findProjectOrThrow(projectId);
    if (!"Completed".equals(entity.status)) {
        throw new WebApplicationException(
                "Only closed projects can be deleted. Current status: " + entity.status, 409);
    }
    projectDeletionService.deleteProject(entity);
}
```

Remove now-unused imports if any (`ThreadEntryEntity`, `AiUsageEntity`, `ActivityLogEntity`,
`EventEntity`, `TaskEntity`) — but only if they are not used elsewhere in the file. Check
first: `TaskEntity` is likely used by the task-related methods in the same class.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/ProjectDeletionService.java
git add app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java
git commit -m "refactor: extract ProjectDeletionService for reuse by cleanup job"
```

---

### Task 5: Cleanup Jobs — ClosedProjectCleanup and EventQueueCleanup

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/ClosedProjectCleanup.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/EventQueueCleanup.java`

**Interfaces:**
- Consumes: `RetentionConfigEntity` (Task 2), `ProjectDeletionService` (Task 4),
  `ProjectEntity`, `EventQueueEntity`
- Produces: Two `@Scheduled` cleanup jobs that run hourly.

- [ ] **Step 1: Create `ClosedProjectCleanup`**

Create `app/src/main/java/io/apitomy/axiom/app/ClosedProjectCleanup.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically deletes closed projects that have exceeded the configured
 * retention period. Runs once per hour.
 */
@ApplicationScoped
public class ClosedProjectCleanup {

    private static final Logger LOG = Logger.getLogger(ClosedProjectCleanup.class);

    @Inject
    ProjectDeletionService projectDeletionService;

    /**
     * Finds and deletes closed projects older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.closedProjectRetentionDays, ChronoUnit.DAYS);
        List<ProjectEntity> staleProjects = ProjectEntity
                .find("status = 'Completed' and updatedOn < ?1", cutoff)
                .list();

        if (!staleProjects.isEmpty()) {
            for (ProjectEntity project : staleProjects) {
                LOG.infof("Cleaning up closed project %d (%s)", project.id, project.name);
                projectDeletionService.deleteProject(project);
            }
            LOG.infof("Cleaned up %d closed project(s) older than %d days",
                    staleProjects.size(), config.closedProjectRetentionDays);
        }
    }
}
```

- [ ] **Step 2: Create `EventQueueCleanup`**

Create `app/src/main/java/io/apitomy/axiom/app/EventQueueCleanup.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodically deletes processed event queue entries older than one day.
 * This is not configurable — processed queue entries are internal bookkeeping
 * with no user-facing value.
 */
@ApplicationScoped
public class EventQueueCleanup {

    private static final Logger LOG = Logger.getLogger(EventQueueCleanup.class);

    /**
     * Deletes processed event queue entries older than one day.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        long deleted = EventQueueEntity.delete(
                "processedAt is not null and processedAt < ?1", cutoff);
        if (deleted > 0) {
            LOG.infof("Cleaned up %d processed event queue entry/entries older than 1 day",
                    deleted);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/ClosedProjectCleanup.java
git add app/src/main/java/io/apitomy/axiom/app/EventQueueCleanup.java
git commit -m "feat: add cleanup jobs for closed projects and event queue entries"
```

---

### Task 6: Cleanup Jobs — TraceCleanup and EventCleanup

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/TraceCleanup.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/EventCleanup.java`

**Interfaces:**
- Consumes: `RetentionConfigEntity` (Task 2), entity classes (`TraceEntity`,
  `TraceNodeEntity`, `ToolExecutionEntity`, `TaskEntity`, `EventEntity`,
  `ScheduledJobRunEntity`, `ReportEntity`, `EventQueueEntity`, `ActivityLogEntity`,
  `AiUsageEntity`)
- Produces: Two `@Scheduled` cleanup jobs that run hourly.

- [ ] **Step 1: Create `TraceCleanup`**

Create `app/src/main/java/io/apitomy/axiom/app/TraceCleanup.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ToolExecutionEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Periodically deletes execution traces (and their nodes and tool executions)
 * that have exceeded the configured retention period. Nullifies traceId
 * references on related entities to avoid dangling foreign keys.
 */
@ApplicationScoped
public class TraceCleanup {

    private static final Logger LOG = Logger.getLogger(TraceCleanup.class);

    /**
     * Finds and deletes traces older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.traceRetentionDays, ChronoUnit.DAYS);
        List<TraceEntity> staleTraces = TraceEntity
                .find("startedOn < ?1", cutoff)
                .list();

        if (staleTraces.isEmpty()) {
            return;
        }

        List<UUID> traceIds = staleTraces.stream().map(t -> t.traceId).toList();

        ToolExecutionEntity.delete("traceId in ?1", traceIds);
        TraceNodeEntity.delete("traceId in ?1", traceIds);

        TaskEntity.update("traceId = null where traceId in ?1", traceIds);
        EventEntity.update("traceId = null where traceId in ?1", traceIds);
        ScheduledJobRunEntity.update("traceId = null where traceId in ?1", traceIds);
        ReportEntity.update("traceId = null where traceId in ?1", traceIds);

        TraceEntity.delete("startedOn < ?1", cutoff);

        LOG.infof("Cleaned up %d trace(s) older than %d days",
                staleTraces.size(), config.traceRetentionDays);
    }
}
```

- [ ] **Step 2: Create `EventCleanup`**

Create `app/src/main/java/io/apitomy/axiom/app/EventCleanup.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically deletes ingested events that have exceeded the configured
 * retention period. Cleans up related event queue entries and nullifies
 * eventId references on related entities.
 */
@ApplicationScoped
public class EventCleanup {

    private static final Logger LOG = Logger.getLogger(EventCleanup.class);

    /**
     * Finds and deletes events older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.eventRetentionDays, ChronoUnit.DAYS);
        List<EventEntity> staleEvents = EventEntity
                .find("receivedAt < ?1", cutoff)
                .list();

        if (staleEvents.isEmpty()) {
            return;
        }

        List<Long> eventIds = staleEvents.stream().map(e -> e.id).toList();

        EventQueueEntity.delete("eventId in ?1", eventIds);
        ActivityLogEntity.update("eventId = null where eventId in ?1", eventIds);
        AiUsageEntity.update("eventId = null where eventId in ?1", eventIds);
        TraceEntity.update("eventId = null where eventId in ?1", eventIds);

        long deleted = EventEntity.delete("receivedAt < ?1", cutoff);

        LOG.infof("Cleaned up %d event(s) older than %d days",
                deleted, config.eventRetentionDays);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/TraceCleanup.java
git add app/src/main/java/io/apitomy/axiom/app/EventCleanup.java
git commit -m "feat: add cleanup jobs for traces and events"
```

---

### Task 7: Update EventSourceLogCleanup to Read from RetentionConfigEntity

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/EventSourceLogCleanup.java`

**Interfaces:**
- Consumes: `RetentionConfigEntity` (Task 2)
- Produces: Updated cleanup job that reads retention days from the database instead of
  `@ConfigProperty`.

- [ ] **Step 1: Update `EventSourceLogCleanup` to use `RetentionConfigEntity`**

Replace the entire file content:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventSourceLogEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodically deletes old event source poll logs to prevent unbounded
 * table growth. Runs once per hour and removes entries older than the
 * configured retention period.
 */
@ApplicationScoped
public class EventSourceLogCleanup {

    private static final Logger LOG = Logger.getLogger(EventSourceLogCleanup.class);

    /**
     * Deletes event source log entries older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.eventSourceLogRetentionDays, ChronoUnit.DAYS);
        long deleted = EventSourceLogEntity.delete("createdOn < ?1", cutoff);
        if (deleted > 0) {
            LOG.infof("Cleaned up %d event source log(s) older than %d days",
                    deleted, config.eventSourceLogRetentionDays);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/EventSourceLogCleanup.java
git commit -m "refactor: read event source log retention from RetentionConfigEntity"
```

---

### Task 8: UI — API Client and Data Retention Page

**Files:**
- Modify: `ui/src/config/api.ts`
- Create: `ui/src/pages/DataRetentionPage.tsx`
- Modify: `ui/src/App.tsx`
- Modify: `ui/src/components/AppSidebar.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/system/retention`, `PUT /api/v1/system/retention`
- Produces: New "Data Retention" page accessible from the Configuration sidebar section.

- [ ] **Step 1: Add API client functions to `api.ts`**

Add after the existing `// ── Manager Configuration` section (around line 799):

```typescript
// ── Retention Configuration ─────────────────────────────────────

export interface RetentionConfig {
    closedProjectRetentionDays?: number;
    traceRetentionDays?: number;
    eventRetentionDays?: number;
    eventSourceLogRetentionDays?: number;
}

export async function fetchRetentionConfig(): Promise<RetentionConfig> {
    const response = await fetch(`${API}/system/retention`);
    if (!response.ok) throw new Error(`Failed to fetch retention config: ${response.status}`);
    return response.json();
}

export async function updateRetentionConfig(config: RetentionConfig): Promise<RetentionConfig> {
    const response = await fetch(`${API}/system/retention`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config),
    });
    if (!response.ok) throw new Error(`Failed to update retention config: ${response.status}`);
    return response.json();
}
```

- [ ] **Step 2: Create `DataRetentionPage.tsx`**

Create `ui/src/pages/DataRetentionPage.tsx`:

```tsx
import { useState, useEffect, useCallback } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    NumberInput,
    PageSection,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type RetentionConfig,
    fetchRetentionConfig,
    updateRetentionConfig,
} from "../config/api";

export function DataRetentionPage() {
    const [config, setConfig] = useState<RetentionConfig>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);

    const loadConfig = useCallback(() => {
        setLoading(true);
        fetchRetentionConfig()
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { loadConfig(); }, [loadConfig]);

    const handleSave = () => {
        setSaving(true);
        updateRetentionConfig(config)
            .then((c) => { setConfig(c); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const updateField = (field: keyof RetentionConfig, value: number) => {
        const clamped = Math.max(1, Math.round(value));
        setConfig((prev) => ({ ...prev, [field]: clamped }));
        setDirty(true);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading retention configuration...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Data Retention</Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="primary"
                        icon={<SaveIcon />}
                        onClick={handleSave}
                        isDisabled={!dirty || saving}
                        isLoading={saving}
                    >
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <p className="axiom-text-subtle" style={{ marginBottom: "24px" }}>
                Configure how long Axiom retains data before automatic cleanup. A background
                job runs hourly to remove data older than the configured retention period.
                Processed event queue entries are always cleaned up after 1 day.
            </p>

            <Form style={{ maxWidth: "600px" }}>
                <FormGroup label="Closed projects" fieldId="closed-project-retention">
                    <NumberInput
                        id="closed-project-retention"
                        value={config.closedProjectRetentionDays ?? 90}
                        min={1}
                        onMinus={() => updateField("closedProjectRetentionDays",
                            (config.closedProjectRetentionDays ?? 90) - 1)}
                        onPlus={() => updateField("closedProjectRetentionDays",
                            (config.closedProjectRetentionDays ?? 90) + 1)}
                        onChange={(event) => updateField("closedProjectRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain closed projects before automatic deletion.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Traces" fieldId="trace-retention">
                    <NumberInput
                        id="trace-retention"
                        value={config.traceRetentionDays ?? 30}
                        min={1}
                        onMinus={() => updateField("traceRetentionDays",
                            (config.traceRetentionDays ?? 30) - 1)}
                        onPlus={() => updateField("traceRetentionDays",
                            (config.traceRetentionDays ?? 30) + 1)}
                        onChange={(event) => updateField("traceRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain execution traces, trace nodes, and tool execution
                                records.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Events" fieldId="event-retention">
                    <NumberInput
                        id="event-retention"
                        value={config.eventRetentionDays ?? 90}
                        min={1}
                        onMinus={() => updateField("eventRetentionDays",
                            (config.eventRetentionDays ?? 90) - 1)}
                        onPlus={() => updateField("eventRetentionDays",
                            (config.eventRetentionDays ?? 90) + 1)}
                        onChange={(event) => updateField("eventRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain ingested events (GitHub, Jira, internal).
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>

                <FormGroup label="Event source logs" fieldId="event-source-log-retention">
                    <NumberInput
                        id="event-source-log-retention"
                        value={config.eventSourceLogRetentionDays ?? 7}
                        min={1}
                        onMinus={() => updateField("eventSourceLogRetentionDays",
                            (config.eventSourceLogRetentionDays ?? 7) - 1)}
                        onPlus={() => updateField("eventSourceLogRetentionDays",
                            (config.eventSourceLogRetentionDays ?? 7) + 1)}
                        onChange={(event) => updateField("eventSourceLogRetentionDays",
                            Number((event.target as HTMLInputElement).value))}
                        widthChars={4}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Days to retain event source poll log entries.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>
            </Form>
        </PageSection>
    );
}
```

- [ ] **Step 3: Add the route to `App.tsx`**

Add import at the top with the other page imports:

```typescript
import { DataRetentionPage } from "./pages/DataRetentionPage";
```

Add the route inside the `<Routes>` block, after the `/configuration-packs` route
(around line 140):

```tsx
<Route path="/data-retention" element={<DataRetentionPage />} />
```

- [ ] **Step 4: Add sidebar nav item in `AppSidebar.tsx`**

Add `"/data-retention"` to the `CONFIG_PATHS` array (line 15):

```typescript
const CONFIG_PATHS = ["/actors", "/manager", "/action-types", "/tools", "/toolsets", "/mcp-servers", "/secrets", "/event-sources", "/report-definitions", "/engine", "/configuration-packs", "/session-templates", "/scheduled-jobs", "/data-retention"];
```

Add a nav item inside the Configuration `<NavExpandable>`, after the "Configuration Packs"
item and before the closing `</NavExpandable>` (around line 130):

```tsx
<NavItem isActive={location.pathname === "/data-retention"} onClick={() => navigate("/data-retention")}>
    Data Retention
</NavItem>
```

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts
git add ui/src/pages/DataRetentionPage.tsx
git add ui/src/App.tsx
git add ui/src/components/AppSidebar.tsx
git commit -m "feat: add Data Retention configuration page"
```
