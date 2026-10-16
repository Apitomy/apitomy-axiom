package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.SubjectDeletionService;
import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.api.SubjectsResource;
import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.api.beans.NewSubject;
import io.apitomy.axiom.api.beans.NewThreadEntry;
import io.apitomy.axiom.api.beans.SubjectMetrics;
import io.apitomy.axiom.api.beans.Subject;
import io.apitomy.axiom.api.beans.SubjectSearchResults;
import io.apitomy.axiom.api.beans.Task;
import io.apitomy.axiom.api.beans.ThreadEntry;
import io.apitomy.axiom.api.beans.UpdateSubject;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the Subjects REST API (includes tasks and threads).
 */
@ApplicationScoped
@RunOnVirtualThread
public class SubjectsResourceImpl implements SubjectsResource {

    private static final Logger LOG = Logger.getLogger(SubjectsResourceImpl.class);

    @Inject
    EntityManager entityManager;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TraceService traceService;

    @Inject
    SubjectDeletionService subjectDeletionService;

    @Inject
    TaskExecutionService taskExecutionService;

    // ── Subjects ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public SubjectSearchResults listSubjects(BigInteger page, BigInteger limit,
                                              String filterName, Boolean filterActive,
                                              String filterLabels, String filterRef) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(ref) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }
        if (filterActive != null) {
            hql.append(filterActive ? " and id in" : " and id not in");
            hql.append(" (select r.subjectId from WorkflowRunEntity r where r.status in :activeStatuses)");
            params.put("activeStatuses", List.of("running", "waiting"));
        }
        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and id in (SELECT p.id FROM SubjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }
        if (filterRef != null && !filterRef.isBlank()) {
            hql.append(" and ref = :ref");
            params.put("ref", filterRef);
        }

        long totalCount = SubjectEntity.count(hql.toString(), params);
        Set<Long> activeIds = SubjectEntity.findActiveSubjectIds();
        List<Subject> items = SubjectEntity.<SubjectEntity>find(hql.toString(),
                        Sort.descending("updatedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(entity -> toSubjectBean(entity, activeIds.contains(entity.id)))
                .toList();

        SubjectSearchResults results = new SubjectSearchResults();
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
    public Subject createSubject(NewSubject data) {
        validateRequired(data.getName(), "name");
        validateRequired(data.getType(), "type");
        validateRequired(data.getRef(), "ref");

        SubjectEntity entity = new SubjectEntity();
        entity.name = data.getName();
        entity.body = data.getBody();
        entity.type = data.getType();
        entity.refSource = data.getRefSource();
        entity.ref = data.getRef();
        entity.repository = data.getRepository();
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();
        return toSubjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Subject getSubject(long subjectId) {
        return toSubjectBean(findSubjectOrThrow(subjectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Subject updateSubject(long subjectId, UpdateSubject data) {
        SubjectEntity entity = findSubjectOrThrow(subjectId);
        if (data.getName() != null) {
            entity.name = data.getName();
        }
        if (data.getBody() != null) {
            entity.body = data.getBody();
        }
        if (data.getType() != null) {
            entity.type = data.getType();
        }
        if (data.getLabels() != null) {
            entity.labels.clear();
            entity.labels.addAll(data.getLabels());
        }
        entity.updatedOn = Instant.now();
        return toSubjectBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSubject(long subjectId) {
        SubjectEntity entity = findSubjectOrThrow(subjectId);
        if (SubjectEntity.isActive(subjectId)) {
            throw new WebApplicationException(
                    "Subjects with a running or waiting workflow run cannot be deleted", 409);
        }
        subjectDeletionService.deleteSubject(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void updateSubjectBody(long subjectId, String body) {
        SubjectEntity entity = findSubjectOrThrow(subjectId);
        entity.body = body;
        entity.updatedOn = Instant.now();
    }

    // ── Tasks ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Task> listSubjectTasks(long subjectId) {
        findSubjectOrThrow(subjectId);
        return TaskEntity.<TaskEntity>list("subjectId", subjectId)
                .stream()
                .map(this::toTaskBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Task getTask(long subjectId, long taskId) {
        findSubjectOrThrow(subjectId);
        TaskEntity entity = TaskEntity.findById(taskId);
        if (entity == null || entity.subjectId != subjectId) {
            throw new WebApplicationException("Task not found: " + taskId, 404);
        }
        return toTaskBean(entity);
    }

    // ── Threads ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ThreadEntry> listThreadEntries(long subjectId) {
        findSubjectOrThrow(subjectId);
        return ThreadEntryEntity.<ThreadEntryEntity>list("subjectId", subjectId)
                .stream()
                .map(this::toThreadEntryBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void addThreadEntry(long subjectId, NewThreadEntry data) {
        findSubjectOrThrow(subjectId);
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.subjectId = subjectId;
        entry.authorType = "agent";
        entry.entryType = "message";
        entry.content = data.getContent();
        entry.createdOn = Instant.now();
        entry.persist();
    }

    // ── Subject Metrics ────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public SubjectMetrics getSubjectMetrics(long subjectId) {
        SubjectEntity subject = findSubjectOrThrow(subjectId);

        // Aggregate AI usage for this subject
        Object[] aggregates = (Object[]) entityManager.createQuery(
                "SELECT COUNT(id), COALESCE(SUM(costUsd), 0), "
                        + "COALESCE(SUM(inputTokens), 0), COALESCE(SUM(outputTokens), 0) "
                        + "FROM AiUsageEntity WHERE subjectId = :pid")
                .setParameter("pid", subjectId)
                .getSingleResult();

        SubjectMetrics metrics = new SubjectMetrics();
        metrics.setSubjectId(subject.id);
        metrics.setDiskUsageBytes(subject.diskUsageBytes != null ? subject.diskUsageBytes : 0L);
        metrics.setInvocationCount(((Number) aggregates[0]).longValue());
        metrics.setTotalCostUsd(((Number) aggregates[1]).doubleValue());
        metrics.setTotalInputTokens(((Number) aggregates[2]).longValue());
        metrics.setTotalOutputTokens(((Number) aggregates[3]).longValue());
        return metrics;
    }

    // ── Task Response ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Task cancelTask(long subjectId, long taskId) {
        findSubjectOrThrow(subjectId);

        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null || task.subjectId != subjectId) {
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
                        task.traceId, String.valueOf(task.id)).firstResult();
                if (taskNode != null) {
                    traceService.completeNode(taskNode.id, "failed");
                }
                traceService.completeTrace(task.traceId, "failed");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for cancelled task %d", taskId);
            }
        }

        return toTaskBean(task);
    }

    // ── Task Execution Log ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTaskExecutionLog(BigInteger subjectId, BigInteger taskId) {
        long pid = subjectId.longValue();
        long tid = taskId.longValue();

        findSubjectOrThrow(pid);

        TaskEntity entity = TaskEntity.findById(tid);
        if (entity == null || entity.subjectId != pid) {
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
    public List<Trace> listSubjectTraces(BigInteger subjectId) {
        return TraceEntity.<TraceEntity>list("subjectId", Sort.descending("startedOn"),
                subjectId.longValue())
                .stream()
                .map(TraceMapper::toTraceBean)
                .toList();
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException("Missing required '" + fieldName + "' field", 400);
        }
    }

    private SubjectEntity findSubjectOrThrow(long id) {
        SubjectEntity entity = SubjectEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Subject not found: " + id, 404);
        }
        return entity;
    }

    private Subject toSubjectBean(SubjectEntity entity) {
        return toSubjectBean(entity, SubjectEntity.isActive(entity.id));
    }

    private Subject toSubjectBean(SubjectEntity entity, boolean active) {
        Subject subject = new Subject();
        subject.setId(entity.id);
        subject.setName(entity.name);
        subject.setBody(entity.body);
        subject.setType(entity.type);
        subject.setRefSource(entity.refSource);
        subject.setRef(entity.ref);
        subject.setRepository(entity.repository);
        subject.setCreatedOn(Date.from(entity.createdOn));
        subject.setUpdatedOn(Date.from(entity.updatedOn));
        subject.setLabels(entity.labels);
        subject.setHasWorkflowInstance(
                WorkflowRunEntity.count("subjectId", entity.id) > 0);
        subject.setActive(active);
        return subject;
    }

    private Task toTaskBean(TaskEntity entity) {
        Task task = new Task();
        task.setId(entity.id);
        task.setSubjectId(entity.subjectId);
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
        entry.setSubjectId(entity.subjectId);
        String authorType = "actor".equals(entity.authorType) ? "agent" : entity.authorType;
        entry.setAuthorType(ThreadEntry.AuthorType.fromValue(authorType));
        entry.setAuthorId(entity.authorId);
        entry.setEntryType(ThreadEntry.EntryType.fromValue(entity.entryType));
        entry.setContent(entity.content);
        entry.setCreatedOn(Date.from(entity.createdOn));
        return entry;
    }

}
