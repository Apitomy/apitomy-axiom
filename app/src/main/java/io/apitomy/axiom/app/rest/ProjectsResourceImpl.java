package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.ProjectDeletionService;
import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.api.ProjectsResource;
import io.apitomy.axiom.api.beans.Event;
import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.api.beans.NewProject;
import io.apitomy.axiom.api.beans.NewThreadEntry;
import io.apitomy.axiom.api.beans.ProjectMetrics;
import io.apitomy.axiom.api.beans.NewTask;
import io.apitomy.axiom.api.beans.Project;
import io.apitomy.axiom.api.beans.ProjectSearchResults;
import io.apitomy.axiom.api.beans.Task;
import io.apitomy.axiom.api.beans.ThreadEntry;
import io.apitomy.axiom.api.beans.TriggerWorkflow;
import io.apitomy.axiom.api.beans.UpdateProject;
import io.apitomy.axiom.app.WorkflowExecutionService;
import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.lifecycle.ProjectLifecycle;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.math.BigInteger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the Projects REST API (includes tasks and threads).
 */
@ApplicationScoped
@RunOnVirtualThread
public class ProjectsResourceImpl implements ProjectsResource {

    private static final Logger LOG = Logger.getLogger(ProjectsResourceImpl.class);

    @Inject
    EntityManager entityManager;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    TraceService traceService;

    @Inject
    ProjectDeletionService projectDeletionService;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    // ── Projects ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ProjectSearchResults listProjects(BigInteger page, BigInteger limit,
                                              String filterName, String filterStatus,
                                              String filterLabels, String filterRef) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(ref) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            List<String> statuses = Arrays.stream(filterStatus.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and status in :statuses");
            params.put("statuses", statuses);
        }
        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and id in (SELECT p.id FROM ProjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }
        if (filterRef != null && !filterRef.isBlank()) {
            hql.append(" and ref = :ref");
            params.put("ref", filterRef);
        }

        long totalCount = ProjectEntity.count(hql.toString(), params);
        List<Project> items = ProjectEntity.<ProjectEntity>find(hql.toString(),
                        Sort.descending("updatedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toProjectBean)
                .toList();

        ProjectSearchResults results = new ProjectSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Project createProject(NewProject data) {
        validateRequired(data.getName(), "name");
        validateRequired(data.getType(), "type");
        validateRequired(data.getRef(), "ref");

        ProjectEntity entity = new ProjectEntity();
        entity.name = data.getName();
        entity.body = data.getBody();
        entity.type = data.getType();
        entity.status = ProjectStatus.Created.name();
        entity.refSource = data.getRefSource();
        entity.ref = data.getRef();
        entity.repository = data.getRepository();
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();
        return toProjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Project getProject(long projectId) {
        return toProjectBean(findProjectOrThrow(projectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Project updateProject(long projectId, UpdateProject data) {
        ProjectEntity entity = findProjectOrThrow(projectId);
        if (data.getName() != null) {
            entity.name = data.getName();
        }
        if (data.getBody() != null) {
            entity.body = data.getBody();
        }
        if (data.getType() != null) {
            entity.type = data.getType();
        }
        if (data.getStatus() != null) {
            ProjectStatus currentStatus = ProjectStatus.fromValue(entity.status);
            ProjectStatus newStatus = ProjectStatus.fromValue(data.getStatus().value());
            try {
                ProjectLifecycle.validateTransition(currentStatus, newStatus);
            } catch (Exception e) {
                throw new WebApplicationException(e.getMessage(), 409);
            }
            entity.status = newStatus.name();
        }
        if (data.getLabels() != null) {
            entity.labels.clear();
            entity.labels.addAll(data.getLabels());
        }
        entity.updatedOn = Instant.now();
        return toProjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Project closeProject(long projectId) {
        ProjectEntity entity = findProjectOrThrow(projectId);
        entity.status = "Completed";
        entity.updatedOn = Instant.now();
        return toProjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Project reopenProject(long projectId) {
        ProjectEntity entity = findProjectOrThrow(projectId);
        entity.status = "InProgress";
        entity.updatedOn = Instant.now();
        return toProjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void updateProjectBody(long projectId, String body) {
        ProjectEntity entity = findProjectOrThrow(projectId);
        entity.body = body;
        entity.updatedOn = Instant.now();
    }

    // ── Tasks ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Task> listProjectTasks(long projectId) {
        findProjectOrThrow(projectId);
        return TaskEntity.<TaskEntity>list("projectId", projectId)
                .stream()
                .map(this::toTaskBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Task createTask(long projectId, NewTask data) {
        findProjectOrThrow(projectId);

        TraceContext traceCtx = null;
        try {
            traceCtx = traceService.createTrace("user-action",
                    "User action: " + data.getActionType(),
                    null, projectId, null,
                    "user-action-triggered", "User triggered action: " + data.getActionType(),
                    null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for user action on project %d", projectId);
        }

        TaskEntity entity = new TaskEntity();
        entity.projectId = projectId;
        entity.actionType = data.getActionType();
        entity.createdBy = "user";
        entity.assignedAgent = data.getAssignedAgent();
        entity.status = "Pending";
        entity.input = data.getInput();
        entity.createdOn = Instant.now();
        if (traceCtx != null) {
            entity.traceId = traceCtx.traceId();
        }
        entity.persist();

        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Task: " + entity.actionType, "task", entity.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add task trace node for task %d", entity.id);
            }
        }

        return toTaskBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Task getTask(long projectId, long taskId) {
        findProjectOrThrow(projectId);
        TaskEntity entity = TaskEntity.findById(taskId);
        if (entity == null || entity.projectId != projectId) {
            throw new WebApplicationException("Task not found: " + taskId, 404);
        }
        return toTaskBean(entity);
    }

    // ── Threads ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ThreadEntry> listThreadEntries(long projectId) {
        findProjectOrThrow(projectId);
        return ThreadEntryEntity.<ThreadEntryEntity>list("projectId", projectId)
                .stream()
                .map(this::toThreadEntryBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void addThreadEntry(long projectId, NewThreadEntry data) {
        findProjectOrThrow(projectId);
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.projectId = projectId;
        entry.authorType = "agent";
        entry.entryType = "message";
        entry.content = data.getContent();
        entry.createdOn = Instant.now();
        entry.persist();
    }

    // ── Project Metrics ────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ProjectMetrics getProjectMetrics(long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);

        // Aggregate AI usage for this project
        Object[] aggregates = (Object[]) entityManager.createQuery(
                "SELECT COUNT(id), COALESCE(SUM(costUsd), 0), "
                        + "COALESCE(SUM(inputTokens), 0), COALESCE(SUM(outputTokens), 0) "
                        + "FROM AiUsageEntity WHERE projectId = :pid")
                .setParameter("pid", projectId)
                .getSingleResult();

        ProjectMetrics metrics = new ProjectMetrics();
        metrics.setProjectId(project.id);
        metrics.setDiskUsageBytes(project.diskUsageBytes != null ? project.diskUsageBytes : 0L);
        metrics.setInvocationCount(((Number) aggregates[0]).longValue());
        metrics.setTotalCostUsd(((Number) aggregates[1]).doubleValue());
        metrics.setTotalInputTokens(((Number) aggregates[2]).longValue());
        metrics.setTotalOutputTokens(((Number) aggregates[3]).longValue());
        return metrics;
    }

    // ── Project Events ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Event> listProjectEvents(long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        List<EventEntity> entities = EventEntity.list("issueRef", project.ref);
        Map<Long, List<String>> labelsMap = loadEventSourceLabels(entities);
        return entities.stream()
                .map(e -> toEventBean(e, labelsMap.getOrDefault(e.eventSourceId, Collections.emptyList())))
                .toList();
    }

    // ── Task Response ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Task cancelTask(long projectId, long taskId) {
        findProjectOrThrow(projectId);

        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null || task.projectId != projectId) {
            throw new WebApplicationException("Task not found: " + taskId, 404);
        }
        if ("Completed".equals(task.status) || "Failed".equals(task.status)) {
            throw new WebApplicationException(
                    "Task is already " + task.status + " and cannot be cancelled", 409);
        }

        task.status = "Failed";
        task.output = "Cancelled by user";
        task.completedOn = Instant.now();

        if (task.traceId != null) {
            try {
                TraceNodeEntity taskNode = TraceNodeEntity.find(
                        "traceId = ?1 and nodeType = 'task' and entityType = 'task' and entityId = ?2",
                        task.traceId, task.id).firstResult();
                if (taskNode != null) {
                    traceService.completeNode(taskNode.id, "failed");
                }
                traceService.completeTrace(task.traceId, "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for cancelled task %d", taskId);
            }
        }

        // Reset project status if no other active tasks remain
        long activeTasks = TaskEntity.count(
                "projectId = ?1 and id != ?2 and (status = 'InProgress' or status = 'AwaitingInput')",
                projectId, taskId);
        if (activeTasks == 0) {
            ProjectEntity project = ProjectEntity.findById(projectId);
            if (project != null && "InProgress".equals(project.status)) {
                project.status = "Idle";
                project.updatedOn = Instant.now();
            }
        }

        return toTaskBean(task);
    }

    // ── Task Execution Log ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTaskExecutionLog(BigInteger projectId, BigInteger taskId) {
        long pid = projectId.longValue();
        long tid = taskId.longValue();

        findProjectOrThrow(pid);

        TaskEntity entity = TaskEntity.findById(tid);
        if (entity == null || entity.projectId != pid) {
            throw new WebApplicationException("Task not found: " + tid, 404);
        }
        if (entity.executionLog == null || entity.executionLog.isEmpty()) {
            throw new WebApplicationException(
                    "No execution log available for task: " + tid, 404);
        }
        return entity.executionLog;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Trace> listProjectTraces(BigInteger projectId) {
        return TraceEntity.<TraceEntity>list("projectId", Sort.descending("startedOn"),
                projectId.longValue())
                .stream()
                .map(TraceMapper::toTraceBean)
                .toList();
    }

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance triggerProjectWorkflow(
            long projectId, TriggerWorkflow data) {
        WorkflowRunEntity entity = workflowExecutionService
                .triggerWorkflow(projectId, data.getWorkflowDefinitionId());
        return toWorkflowInstanceBean(entity);
    }

    @Override
    public io.apitomy.axiom.api.beans.WorkflowInstance getProjectWorkflowInstance(
            long projectId) {
        WorkflowRunEntity entity = WorkflowRunEntity
                .find("projectId", io.quarkus.panache.common.Sort.descending("startedOn"),
                        projectId)
                .firstResult();
        if (entity == null) {
            throw new WebApplicationException(404);
        }
        return toWorkflowInstanceBean(entity);
    }

    @Override
    public void cancelProjectWorkflow(long projectId) {
        workflowExecutionService.cancelWorkflow(projectId);
    }

    private io.apitomy.axiom.api.beans.WorkflowInstance toWorkflowInstanceBean(
            WorkflowRunEntity entity) {
        return runBeanMapper.toBean(entity);
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException("Missing required '" + fieldName + "' field", 400);
        }
    }

    private ProjectEntity findProjectOrThrow(long id) {
        ProjectEntity entity = ProjectEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Project not found: " + id, 404);
        }
        return entity;
    }

    private Project toProjectBean(ProjectEntity entity) {
        Project project = new Project();
        project.setId(entity.id);
        project.setName(entity.name);
        project.setBody(entity.body);
        project.setType(entity.type);
        project.setStatus(Project.Status.fromValue(entity.status));
        project.setRefSource(entity.refSource);
        project.setRef(entity.ref);
        project.setRepository(entity.repository);
        project.setCreatedOn(Date.from(entity.createdOn));
        project.setUpdatedOn(Date.from(entity.updatedOn));
        project.setLabels(entity.labels);
        project.setHasWorkflowInstance(
                WorkflowRunEntity.count("projectId", entity.id) > 0);
        return project;
    }

    private Task toTaskBean(TaskEntity entity) {
        Task task = new Task();
        task.setId(entity.id);
        task.setProjectId(entity.projectId);
        task.setEventId(entity.eventId);
        task.setActionType(entity.actionType);
        task.setCreatedBy(Task.CreatedBy.fromValue(entity.createdBy));
        task.setAssignedAgent(entity.assignedAgent);
        task.setStatus(Task.Status.fromValue(entity.status));
        task.setInput(entity.input);
        task.setOutput(entity.output);
        task.setCreatedOn(Date.from(entity.createdOn));
        if (entity.completedOn != null) {
            task.setCompletedOn(Date.from(entity.completedOn));
        }
        task.setSessionId(entity.sessionId);
        if (entity.traceId != null) {
            task.setTraceId(entity.traceId);
        }
        task.setHumanContext(entity.humanContext);
        task.setOutputSchema(entity.outputSchema);
        task.setWorkflowRunId(entity.workflowRunId);
        task.setNodeId(entity.nodeId);
        return task;
    }

    private ThreadEntry toThreadEntryBean(ThreadEntryEntity entity) {
        ThreadEntry entry = new ThreadEntry();
        entry.setId(entity.id);
        entry.setProjectId(entity.projectId);
        String authorType = "actor".equals(entity.authorType) ? "agent" : entity.authorType;
        entry.setAuthorType(ThreadEntry.AuthorType.fromValue(authorType));
        entry.setAuthorId(entity.authorId);
        entry.setEntryType(ThreadEntry.EntryType.fromValue(entity.entryType));
        entry.setContent(entity.content);
        entry.setCreatedOn(Date.from(entity.createdOn));
        return entry;
    }

    /**
     * Batch-loads Event Source labels for a list of event entities.
     */
    private Map<Long, List<String>> loadEventSourceLabels(List<EventEntity> entities) {
        Set<Long> sourceIds = entities.stream()
                .map(e -> e.eventSourceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (sourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return EventSourceEntity.<EventSourceEntity>list("id in ?1", List.copyOf(sourceIds))
                .stream()
                .collect(Collectors.toMap(es -> es.id, es -> es.labels));
    }

    private Event toEventBean(EventEntity entity, List<String> labels) {
        Event event = new Event();
        event.setId(entity.id);
        event.setSource(entity.source);
        event.setEventType(entity.eventType);
        event.setIssueRef(entity.issueRef);
        event.setRepository(entity.repository);
        event.setProjectId(entity.projectId);
        event.setTaskId(entity.taskId);
        event.setReceivedAt(Date.from(entity.receivedAt));
        event.setLabels(labels);
        if (entity.traceId != null) {
            event.setTraceId(entity.traceId);
        }
        return event;
    }
}
