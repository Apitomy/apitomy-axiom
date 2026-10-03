package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ActivityResource;
import io.apitomy.axiom.api.beans.ActivityLogEntry;
import io.apitomy.axiom.api.beans.ActivityLogSearchResults;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Activity Log REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ActivityResourceImpl implements ActivityResource {

    /**
     * {@inheritDoc}
     */
    @Override
    public ActivityLogSearchResults listActivityLog(BigInteger page, BigInteger limit,
                                                     String filterEventId, String filterSummary,
                                                     BigInteger filterProjectId, String filterEntryType,
                                                     String filterLabels, String filterTraceId,
                                                     String filterScheduledJobRunId,
                                                     String filterReportId) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterEventId != null && !filterEventId.isBlank()) {
            hql.append(" and eventId = :eventId");
            params.put("eventId", TraceResourceImpl.parseEventId(filterEventId));
        }
        if (filterSummary != null && !filterSummary.isBlank()) {
            hql.append(" and lower(summary) like :summary");
            params.put("summary", "%" + filterSummary.toLowerCase() + "%");
        }
        if (filterProjectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", filterProjectId.longValue());
        }
        if (filterEntryType != null && !filterEntryType.isBlank()) {
            List<String> types = Arrays.stream(filterEntryType.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and entryType in :types");
            params.put("types", types);
        }
        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and projectId in (SELECT p.id FROM ProjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        if (filterTraceId != null && !filterTraceId.isBlank()) {
            hql.append(" and traceId = :traceId");
            params.put("traceId", TraceResourceImpl.parseUuidParam("filterTraceId", filterTraceId));
        }

        if (filterScheduledJobRunId != null && !filterScheduledJobRunId.isBlank()) {
            hql.append(" and scheduledJobRunId = :scheduledJobRunId");
            params.put("scheduledJobRunId",
                    TraceResourceImpl.parseIdParam("filterScheduledJobRunId", filterScheduledJobRunId));
        }
        if (filterReportId != null && !filterReportId.isBlank()) {
            hql.append(" and reportId = :reportId");
            params.put("reportId",
                    TraceResourceImpl.parseIdParam("filterReportId", filterReportId));
        }

        long totalCount = ActivityLogEntity.count(hql.toString(), params);
        List<ActivityLogEntry> items = ActivityLogEntity
                .<ActivityLogEntity>find(hql.toString(), Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toBean)
                .toList();

        ActivityLogSearchResults results = new ActivityLogSearchResults();
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
    public String getActivityLogDetails(long activityId) {
        ActivityLogEntity entity = ActivityLogEntity.findById(activityId);
        if (entity == null) {
            throw new WebApplicationException("Activity entry not found: " + activityId, 404);
        }
        if (entity.details == null || entity.details.isEmpty()) {
            throw new WebApplicationException(
                    "No execution log available for activity entry: " + activityId, 404);
        }
        return entity.details;
    }

    private ActivityLogEntry toBean(ActivityLogEntity entity) {
        ActivityLogEntry entry = new ActivityLogEntry();
        entry.setId(entity.id);
        entry.setProjectId(entity.projectId);
        entry.setTaskId(entity.taskId);
        entry.setEventId(entity.eventId);
        entry.setTraceId(entity.traceId);
        entry.setScheduledJobRunId(entity.scheduledJobRunId);
        entry.setReportId(entity.reportId);
        entry.setReportDefinitionId(entity.reportDefinitionId);
        entry.setEntryType(entity.entryType);
        entry.setSummary(entity.summary);
        entry.setDetails(entity.details);
        entry.setCreatedOn(Date.from(entity.createdOn));
        return entry;
    }
}
