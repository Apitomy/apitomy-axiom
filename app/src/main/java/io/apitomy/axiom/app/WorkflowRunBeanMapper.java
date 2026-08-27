package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.WorkflowContent;
import io.apitomy.axiom.api.beans.WorkflowRunSearchResults;
import io.apitomy.axiom.api.beans.WorkflowRunSummary;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps {@link WorkflowRunEntity} rows to the generated REST beans, shared by the
 * project workflow endpoint and the workflow-runs endpoints.
 */
@ApplicationScoped
public class WorkflowRunBeanMapper {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Builds the enriched WorkflowInstance detail bean for a run.
     *
     * @param entity the workflow run entity
     * @return the workflow instance bean with full details
     */
    public io.apitomy.axiom.api.beans.WorkflowInstance toBean(WorkflowRunEntity entity) {
        io.apitomy.axiom.api.beans.WorkflowInstance bean =
                new io.apitomy.axiom.api.beans.WorkflowInstance();

        bean.setId(entity.id);
        bean.setRunId(entity.id);
        if (entity.traceId != null) {
            bean.setTraceId(entity.traceId);
        }
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
                        version.content, WorkflowContent.class));
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
            bean.setContext(objectMapper.convertValue(
                    flowInstance.context(),
                    io.apitomy.axiom.api.beans.Context.class));
            List<TaskEntity> runTasks = TaskEntity
                    .<TaskEntity>find("workflowRunId", entity.id).list();
            Map<String, TaskEntity> tasksByNode = runTasks.stream()
                    .filter(t -> t.nodeId != null)
                    .collect(java.util.stream.Collectors.toMap(
                            t -> t.nodeId, t -> t, (a, b) -> b));
            bean.setHistory(flowInstance.history().stream()
                    .map(h -> toHistoryEntryBean(h, tasksByNode))
                    .toList());
        } catch (JsonProcessingException e) {
            bean.setHistory(List.of());
        }

        return bean;
    }

    private io.apitomy.axiom.api.beans.HistoryEntry toHistoryEntryBean(
            io.apitomy.flow.model.HistoryEntry entry,
            Map<String, TaskEntity> tasksByNode) {
        io.apitomy.axiom.api.beans.HistoryEntry bean =
                new io.apitomy.axiom.api.beans.HistoryEntry();
        bean.setNodeId(entry.nodeId());
        bean.setNodeName(entry.nodeName());
        if (entry.enteredOn() != null) {
            bean.setEnteredOn(Date.from(entry.enteredOn()));
        }
        if (entry.completedOn() != null) {
            bean.setCompletedOn(Date.from(entry.completedOn()));
        }
        if (entry.output() != null && !entry.output().isEmpty()) {
            bean.setOutput(objectMapper.convertValue(
                    entry.output(),
                    io.apitomy.axiom.api.beans.Output.class));
        }
        TaskEntity task = entry.nodeId() != null
                ? tasksByNode.get(entry.nodeId()) : null;
        if (task != null) {
            bean.setTaskId(task.id);
            bean.setTaskStatus(task.status);
        }
        return bean;
    }

    /**
     * Searches runs with optional project/definition/status filters, newest first.
     *
     * @param projectId    optional project filter (nullable)
     * @param definitionId optional definition filter (nullable; used by the per-definition endpoint)
     * @param status       optional comma-separated status filter (nullable)
     * @param page         1-based page number (nullable → 1)
     * @param limit        page size (nullable → 20)
     * @return paginated run summaries
     */
    public WorkflowRunSearchResults search(Long projectId, Long definitionId, String status,
                                           BigInteger page, BigInteger limit) {
        int pageNum = page != null ? Math.max(1, page.intValue()) : 1;
        int pageSize = limit != null ? Math.max(1, limit.intValue()) : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (projectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", projectId);
        }
        if (definitionId != null) {
            hql.append(" and definitionId = :definitionId");
            params.put("definitionId", definitionId);
        }
        if (status != null && !status.isBlank()) {
            hql.append(" and status in :statuses");
            params.put("statuses", java.util.Arrays.stream(status.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }

        long totalCount = WorkflowRunEntity.count(hql.toString(), params);
        List<WorkflowRunEntity> runs = WorkflowRunEntity
                .<WorkflowRunEntity>find(hql.toString(), Sort.descending("startedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        Map<Long, String> projectNames = resolveProjectNames(runs);
        Map<Long, String> definitionNames = resolveDefinitionNames(runs);

        WorkflowRunSearchResults results = new WorkflowRunSearchResults();
        results.setItems(runs.stream()
                .map(r -> toSummary(r, projectNames, definitionNames))
                .toList());
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    private Map<Long, String> resolveProjectNames(List<WorkflowRunEntity> runs) {
        Set<Long> ids = runs.stream().map(r -> r.projectId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ProjectEntity.<ProjectEntity>find("id in :ids", Map.of("ids", ids)).list().stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
    }

    private Map<Long, String> resolveDefinitionNames(List<WorkflowRunEntity> runs) {
        Set<Long> ids = runs.stream().map(r -> r.definitionId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return WorkflowDefinitionEntity.<WorkflowDefinitionEntity>find("id in :ids", Map.of("ids", ids))
                .list().stream()
                .collect(Collectors.toMap(d -> d.id, d -> d.name));
    }

    private WorkflowRunSummary toSummary(WorkflowRunEntity run, Map<Long, String> projectNames,
                                         Map<Long, String> definitionNames) {
        WorkflowRunSummary summary = new WorkflowRunSummary();
        summary.setRunId(run.id);
        summary.setProjectId(run.projectId);
        summary.setProjectName(projectNames.getOrDefault(run.projectId, "Unknown"));
        summary.setDefinitionId(run.definitionId);
        summary.setDefinitionName(definitionNames.getOrDefault(run.definitionId, "Unknown"));
        summary.setDefinitionVersion(run.definitionVersion);
        summary.setStatus(run.status);
        if (run.traceId != null) {
            summary.setTraceId(run.traceId);
        }
        summary.setStartedOn(Date.from(run.startedOn));
        if (run.completedOn != null) {
            summary.setCompletedOn(Date.from(run.completedOn));
        }
        // currentNodeName is intentionally omitted on the list to avoid an N+1 content
        // read per row; the detail bean (toBean) carries it. Populate later if the UI needs it.
        return summary;
    }
}
