package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.actors.human.HumanActor;
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
import io.apitomy.axiom.api.beans.TaskResponse;
import io.apitomy.axiom.api.beans.ThreadEntry;
import io.apitomy.axiom.api.beans.UpdateProject;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.lifecycle.ProjectLifecycle;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Projects REST API (includes tasks and threads).
 */
@ApplicationScoped
@RunOnVirtualThread
public class ProjectsResourceImpl implements ProjectsResource {

    @Inject
    HumanActor humanActor;

    @Inject
    EntityManager entityManager;

    @Inject
    WorkspaceService workspaceService;

    // ── Projects ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ProjectSearchResults listProjects(BigInteger page, BigInteger limit,
                                              String filterName, String filterStatus,
                                              String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(issueRef) like :name)");
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
        ProjectEntity entity = new ProjectEntity();
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.type = data.getType().value();
        entity.status = ProjectStatus.Created.name();
        entity.issueSource = data.getIssueSource().value();
        entity.issueRef = data.getIssueRef();
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
        if (data.getDescription() != null) {
            entity.description = data.getDescription();
        }
        if (data.getType() != null) {
            entity.type = data.getType().value();
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

        ThreadEntryEntity.delete("projectId", projectId);
        AiUsageEntity.delete("projectId", projectId);
        ActivityLogEntity.delete("projectId", projectId);
        EventEntity.update("projectId = null where projectId = ?1", projectId);
        TaskEntity.delete("projectId", projectId);
        workspaceService.deleteWorkspace(entity);
        entity.delete();
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
        TaskEntity entity = new TaskEntity();
        entity.projectId = projectId;
        entity.actionType = data.getActionType();
        entity.createdBy = "user";
        entity.assignedActor = data.getAssignedActor();
        entity.status = "Pending";
        entity.input = data.getInput();
        entity.createdOn = Instant.now();
        entity.persist();
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
        entry.authorType = "actor";
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
        return EventEntity.<EventEntity>list("issueRef", project.issueRef)
                .stream()
                .map(this::toEventBean)
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void respondToTask(BigInteger projectId, BigInteger taskId, TaskResponse data) {
        long pid = projectId.longValue();
        long tid = taskId.longValue();

        findProjectOrThrow(pid);

        TaskEntity task = TaskEntity.findById(tid);
        if (task == null || task.projectId != pid) {
            throw new WebApplicationException("Task not found: " + tid, 404);
        }
        if (!"AwaitingInput".equals(task.status)) {
            throw new WebApplicationException(
                    "Task is not awaiting input (status: " + task.status + ")", 409);
        }

        boolean accepted = humanActor.submitResponse(tid, data.getResponse());
        if (!accepted) {
            throw new WebApplicationException("Task is not pending a human response", 409);
        }
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
        project.setDescription(entity.description);
        project.setType(Project.Type.fromValue(entity.type));
        project.setStatus(Project.Status.fromValue(entity.status));
        project.setIssueSource(Project.IssueSource.fromValue(entity.issueSource));
        project.setIssueRef(entity.issueRef);
        project.setRepository(entity.repository);
        project.setCreatedOn(Date.from(entity.createdOn));
        project.setUpdatedOn(Date.from(entity.updatedOn));
        project.setLabels(entity.labels);
        return project;
    }

    private Task toTaskBean(TaskEntity entity) {
        Task task = new Task();
        task.setId(entity.id);
        task.setProjectId(entity.projectId);
        task.setEventId(entity.eventId);
        task.setActionType(entity.actionType);
        task.setCreatedBy(Task.CreatedBy.fromValue(entity.createdBy));
        task.setAssignedActor(entity.assignedActor);
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
        return task;
    }

    private ThreadEntry toThreadEntryBean(ThreadEntryEntity entity) {
        ThreadEntry entry = new ThreadEntry();
        entry.setId(entity.id);
        entry.setProjectId(entity.projectId);
        entry.setAuthorType(ThreadEntry.AuthorType.fromValue(entity.authorType));
        entry.setAuthorId(entity.authorId);
        entry.setEntryType(ThreadEntry.EntryType.fromValue(entity.entryType));
        entry.setContent(entity.content);
        entry.setCreatedOn(Date.from(entity.createdOn));
        return entry;
    }

    private Event toEventBean(EventEntity entity) {
        Event event = new Event();
        event.setId(entity.id);
        event.setSource(entity.source);
        event.setEventType(entity.eventType);
        event.setIssueRef(entity.issueRef);
        event.setRepository(entity.repository);
        event.setProjectId(entity.projectId);
        event.setTaskId(entity.taskId);
        event.setReceivedAt(Date.from(entity.receivedAt));
        if (entity.traceId != null) {
            event.setTraceId(entity.traceId);
        }
        return event;
    }
}
