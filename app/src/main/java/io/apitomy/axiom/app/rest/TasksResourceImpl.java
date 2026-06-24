package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.TasksResource;
import io.apitomy.axiom.api.beans.Task;
import io.apitomy.axiom.api.beans.TaskSearchResults;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the global Tasks REST API. Provides paginated,
 * filterable access to all tasks across all projects.
 */
@ApplicationScoped
@RunOnVirtualThread
public class TasksResourceImpl implements TasksResource {

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskSearchResults listAllTasks(BigInteger page, BigInteger limit,
                                           String filterActionType, String filterStatus,
                                           BigInteger filterProjectId) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterActionType != null && !filterActionType.isBlank()) {
            hql.append(" and lower(actionType) like :actionType");
            params.put("actionType", "%" + filterActionType.toLowerCase() + "%");
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            List<String> statuses = List.of(filterStatus.split(","));
            hql.append(" and status in :statuses");
            params.put("statuses", statuses);
        }
        if (filterProjectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", filterProjectId.longValue());
        }

        long totalCount = TaskEntity.count(hql.toString(), params);
        List<Task> items = TaskEntity.<TaskEntity>find(
                        hql.toString(), Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toBean)
                .toList();

        TaskSearchResults results = new TaskSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    private Task toBean(TaskEntity entity) {
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
}
