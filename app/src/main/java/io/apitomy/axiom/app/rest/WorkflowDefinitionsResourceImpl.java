package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.WorkflowResource;
import io.apitomy.axiom.api.beans.Content;
import io.apitomy.axiom.api.beans.NewScheduleTrigger;
import io.apitomy.axiom.api.beans.NewWorkflowDefinition;
import io.apitomy.axiom.api.beans.ScheduleTrigger;
import io.apitomy.axiom.api.beans.UpdateWorkflowDefinition;
import io.apitomy.axiom.api.beans.WorkflowDefinition;
import io.apitomy.axiom.api.beans.WorkflowDefinitionSearchResults;
import io.apitomy.axiom.api.beans.WorkflowDefinitionVersion;
import io.apitomy.axiom.app.CronSchedules;
import io.apitomy.axiom.app.WorkflowDashboardService;
import io.apitomy.axiom.app.WorkflowExecutionService;
import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.axiom.app.validation.WorkflowPublisher;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.apitomy.flow.model.Workflow;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the Workflow Definitions REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class WorkflowDefinitionsResourceImpl implements WorkflowResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkflowPublisher workflowPublisher;

    @Inject
    io.apitomy.axiom.app.EventStreamOrchestrator orchestrator;

    @Inject
    WorkflowDashboardService dashboardService;

    // ── List ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowDefinitionSearchResults listWorkflowDefinitions(
            Integer page, Integer limit, String filterName) {
        int pageNum = page != null ? page : 1;
        int pageSize = limit != null ? limit : 20;

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

    // ── Create ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
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

    // ── Get ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowDefinition getWorkflowDefinition(long workflowDefinitionId) {
        return toBean(findOrThrow(workflowDefinitionId));
    }

    // ── Update metadata ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
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

    // ── Delete ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);

        // Cascade delete everything that references this workflow definition. Deletes are
        // performed explicitly (rather than relying on database ON DELETE CASCADE) so the
        // behavior is deterministic across database engines.
        List<Long> runIds = WorkflowRunEntity
                .<WorkflowRunEntity>list("definitionId", workflowDefinitionId)
                .stream()
                .map(run -> run.id)
                .toList();

        if (!runIds.isEmpty()) {
            // Delete human tasks belonging to the runs first (task.workflow_run_id FK).
            TaskEntity.delete("workflowRunId in ?1", runIds);
            // And the rows that hang off the runs (resume records, parked waits/subscriptions).
            WorkflowRunResumeEntity.delete("runId in ?1", runIds);
            WorkflowWaitEntity.delete("runId in ?1", runIds);
            WorkflowEventSubscriptionEntity.delete("runId in ?1", runIds);
            // Then delete the runs themselves.
            WorkflowRunEntity.delete("definitionId", workflowDefinitionId);
        }

        // Delete the schedule triggers of the definition.
        WorkflowScheduleTriggerEntity.delete("definitionId", workflowDefinitionId);

        // Delete the published versions of the definition.
        WorkflowDefinitionVersionEntity.delete("definitionId", workflowDefinitionId);

        // Finally delete the definition itself.
        entity.delete();
    }

    // ── Schedule triggers ───────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ScheduleTrigger> listWorkflowScheduleTriggers(long workflowDefinitionId) {
        findOrThrow(workflowDefinitionId);
        return WorkflowScheduleTriggerEntity
                .<WorkflowScheduleTriggerEntity>list("definitionId", Sort.ascending("id"), workflowDefinitionId)
                .stream()
                .map(this::toTriggerBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ScheduleTrigger createWorkflowScheduleTrigger(long workflowDefinitionId, NewScheduleTrigger data) {
        findOrThrow(workflowDefinitionId);
        WorkflowScheduleTriggerEntity trigger = new WorkflowScheduleTriggerEntity();
        trigger.definitionId = workflowDefinitionId;
        trigger.createdOn = Instant.now();
        applyTrigger(trigger, data);
        trigger.persist();
        return toTriggerBean(trigger);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ScheduleTrigger updateWorkflowScheduleTrigger(long workflowDefinitionId, long triggerId,
                                                         NewScheduleTrigger data) {
        WorkflowScheduleTriggerEntity trigger = findTriggerOrThrow(workflowDefinitionId, triggerId);
        applyTrigger(trigger, data);
        return toTriggerBean(trigger);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteWorkflowScheduleTrigger(long workflowDefinitionId, long triggerId) {
        findTriggerOrThrow(workflowDefinitionId, triggerId).delete();
    }

    private WorkflowScheduleTriggerEntity findTriggerOrThrow(long workflowDefinitionId, long triggerId) {
        WorkflowScheduleTriggerEntity trigger = WorkflowScheduleTriggerEntity.findById(triggerId);
        if (trigger == null || trigger.definitionId != workflowDefinitionId) {
            throw new WebApplicationException("Schedule trigger not found", 404);
        }
        return trigger;
    }

    /**
     * Validates the request and copies it onto the trigger, recomputing the next run time (null when
     * disabled).
     */
    private void applyTrigger(WorkflowScheduleTriggerEntity trigger, NewScheduleTrigger data) {
        String cron = data.getCron() != null ? data.getCron().trim() : "";
        String subjectName = data.getSubjectName() != null ? data.getSubjectName().trim() : "";
        if (subjectName.isEmpty() || subjectName.length() > SubjectEntity.SCHEDULE_NAME_MAX_LENGTH) {
            throw new WebApplicationException(
                    "subjectName must be 1-" + SubjectEntity.SCHEDULE_NAME_MAX_LENGTH + " characters", 400);
        }
        Instant nextRunAt;
        try {
            if (cron.isEmpty() || cron.length() > 100) {
                throw new IllegalArgumentException("cron must be 1-100 characters");
            }
            nextRunAt = CronSchedules.nextAfter(cron, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid cron expression: " + e.getMessage(), 400);
        }
        trigger.cron = cron;
        trigger.subjectName = subjectName;
        trigger.enabled = data.getEnabled() == null || data.getEnabled();
        trigger.nextRunAt = trigger.enabled ? nextRunAt : null;
        trigger.updatedOn = Instant.now();
    }

    private ScheduleTrigger toTriggerBean(WorkflowScheduleTriggerEntity trigger) {
        ScheduleTrigger bean = new ScheduleTrigger();
        bean.setId(trigger.id);
        bean.setDefinitionId(trigger.definitionId);
        bean.setCron(trigger.cron);
        bean.setSubjectName(trigger.subjectName);
        bean.setEnabled(trigger.enabled);
        bean.setNextRunAt(trigger.nextRunAt != null ? Date.from(trigger.nextRunAt) : null);
        bean.setLastRunAt(trigger.lastRunAt != null ? Date.from(trigger.lastRunAt) : null);
        bean.setLastOutcome(trigger.lastOutcome);
        bean.setCreatedOn(Date.from(trigger.createdOn));
        bean.setUpdatedOn(Date.from(trigger.updatedOn));
        return bean;
    }

    // ── Update content ──────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void updateWorkflowDefinitionContent(long workflowDefinitionId, InputStream data) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);
        try {
            entity.content = new String(data.readAllBytes());
        } catch (Exception e) {
            throw new WebApplicationException("Invalid workflow content", 400);
        }
        entity.updatedOn = Instant.now();
    }

    // ── Publish ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public WorkflowDefinitionVersion publishWorkflowDefinition(long workflowDefinitionId) {
        WorkflowDefinitionEntity entity = findOrThrow(workflowDefinitionId);
        WorkflowDefinitionVersionEntity version = workflowPublisher.publish(entity);
        return toVersionBean(version);
    }

    // ── List versions ───────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<WorkflowDefinitionVersion> listWorkflowDefinitionVersions(
            long workflowDefinitionId) {
        findOrThrow(workflowDefinitionId);
        List<WorkflowDefinitionVersionEntity> versions = WorkflowDefinitionVersionEntity
                .find("definitionId", Sort.descending("version"), workflowDefinitionId)
                .list();
        return versions.stream().map(this::toVersionBean).toList();
    }

    // ── Get version ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
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

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Finds a workflow definition by ID or throws a 404 exception.
     */
    private WorkflowDefinitionEntity findOrThrow(long id) {
        WorkflowDefinitionEntity entity = WorkflowDefinitionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return entity;
    }

    /**
     * Checks if a workflow definition name is already in use.
     */
    private void checkDuplicateName(String name, Long excludeId) {
        WorkflowDefinitionEntity existing = WorkflowDefinitionEntity
                .find("name", name).firstResult();
        if (existing != null && (excludeId == null || !existing.id.equals(excludeId))) {
            throw new WebApplicationException("Workflow definition name already exists", 409);
        }
    }

    /**
     * Converts a WorkflowDefinitionEntity to a response bean.
     */
    private WorkflowDefinition toBean(WorkflowDefinitionEntity entity) {
        WorkflowDefinition bean = new WorkflowDefinition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Content.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCurrentVersion(entity.currentVersion);
        bean.setCreatedOn(Date.from(entity.createdOn));
        bean.setUpdatedOn(Date.from(entity.updatedOn));
        return bean;
    }

    /**
     * Converts a WorkflowDefinitionVersionEntity to a response bean.
     */
    private WorkflowDefinitionVersion toVersionBean(WorkflowDefinitionVersionEntity entity) {
        WorkflowDefinitionVersion bean = new WorkflowDefinitionVersion();
        bean.setId(entity.id);
        bean.setDefinitionId(entity.definitionId);
        bean.setVersion(entity.version);
        if (entity.content != null) {
            try {
                bean.setContent(objectMapper.readValue(entity.content, Content.class));
            } catch (JsonProcessingException e) {
                bean.setContent(null);
            }
        }
        bean.setCreatedOn(Date.from(entity.createdOn));
        return bean;
    }

    /**
     * Creates an empty workflow JSON with start and end nodes.
     */
    private String createEmptyWorkflowContent(String name) {
        return WorkflowTemplates.emptyWorkflow(name, objectMapper).toString();
    }

    // ── Workflow Runs ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowRunStatusSummary getWorkflowRunStatusSummary(
            String filterLabels, String since, java.math.BigInteger failedLimit) {
        Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.OffsetDateTime.parse(since.trim()).toInstant();
            } catch (java.time.format.DateTimeParseException e) {
                throw new WebApplicationException("Invalid since (expected ISO-8601 date-time): " + since,
                        Response.Status.BAD_REQUEST);
            }
        }
        return dashboardService.summarize(WorkflowDashboardService.parseLabels(filterLabels), sinceInstant,
                clamp(failedLimit, 10, 50));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<io.apitomy.axiom.api.beans.WaitingWorkflowRun> listWaitingWorkflowRuns(
            String filterLabels, java.math.BigInteger limit) {
        return dashboardService.listWaiting(WorkflowDashboardService.parseLabels(filterLabels),
                clamp(limit, 20, 200));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<io.apitomy.axiom.api.beans.WorkflowScheduleSummary> listWorkflowSchedules(
            java.math.BigInteger limit) {
        return dashboardService.listSchedules(clamp(limit, 50, 500));
    }

    private static int clamp(java.math.BigInteger value, int defaultValue, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(max, value.intValue()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowRunSearchResults listWorkflowRuns(
            Long subjectId, String status, Long definitionId, java.util.List<String> label,
            String triggerEventId, java.math.BigInteger page, java.math.BigInteger limit) {
        java.util.UUID eventId = null;
        if (triggerEventId != null && !triggerEventId.isBlank()) {
            try {
                eventId = java.util.UUID.fromString(triggerEventId);
            } catch (IllegalArgumentException e) {
                throw new WebApplicationException("Invalid triggerEventId: " + triggerEventId,
                        Response.Status.BAD_REQUEST);
            }
        }
        return runBeanMapper.search(subjectId, definitionId, status, label, eventId, page, limit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance getWorkflowRun(long runId) {
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Workflow run not found: " + runId,
                    Response.Status.NOT_FOUND);
        }
        return runBeanMapper.toBean(run);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancelWorkflowRun(long runId) {
        workflowExecutionService.cancelWorkflowRun(runId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWorkflowRunTitle(long runId, io.apitomy.axiom.api.beans.WorkflowRunTitle data) {
        workflowExecutionService.setRunTitle(runId, data.getTitle());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance rerunWorkflowRun(long runId) {
        io.apitomy.axiom.core.entities.WorkflowRunEntity original =
                io.apitomy.axiom.core.entities.WorkflowRunEntity.findById(runId);
        if (original == null) {
            throw new jakarta.ws.rs.NotFoundException("Workflow run not found: " + runId);
        }
        if (!"failed".equals(original.status)) {
            throw new jakarta.ws.rs.WebApplicationException(jakarta.ws.rs.core.Response.status(409)
                    .entity(java.util.Map.of("message", "Only failed runs can be re-run; run " + runId + " is "
                            + original.status + "."))
                    .build());
        }
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>(readMap(original.labels, String.class));
        labels.put("rerunOf", String.valueOf(runId));
        io.apitomy.axiom.app.RunRequest request = new io.apitomy.axiom.app.RunRequest(original.title, labels);
        io.apitomy.axiom.core.entities.StreamEventEntity event = original.triggerEventId == null ? null
                : io.apitomy.axiom.core.entities.StreamEventEntity.findById(original.triggerEventId);
        // A run started by a stream event is re-run from that event, so it gets the same context.event.
        io.apitomy.axiom.core.entities.WorkflowRunEntity rerun = event != null
                ? orchestrator.startRunFromEvent(event, original.definitionId, request, null)
                : workflowExecutionService.triggerWorkflow(original.subjectId, original.definitionId, request, null);
        return runBeanMapper.toBean(rerun);
    }

    /** Reads a JSON object column into a map; null or invalid JSON gives an empty map. */
    private <V> java.util.Map<String, V> readMap(String json, Class<V> valueType) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(java.util.LinkedHashMap.class, String.class, valueType));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return java.util.Map.of();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.apitomy.axiom.api.beans.WorkflowRunSearchResults listWorkflowDefinitionRuns(
            long workflowDefinitionId, String status, java.math.BigInteger page, java.math.BigInteger limit) {
        findOrThrow(workflowDefinitionId);
        return runBeanMapper.search(null, workflowDefinitionId, status, page, limit);
    }
}
