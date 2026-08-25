package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.UsageResource;
import io.apitomy.axiom.api.beans.AiUsage;
import io.apitomy.axiom.api.beans.AiUsageSearchResults;
import io.apitomy.axiom.api.beans.DiskUsageProject;
import io.apitomy.axiom.api.beans.DiskUsageSearchResults;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Usage REST API, providing both AI usage
 * and disk usage endpoints.
 */
@ApplicationScoped
@RunOnVirtualThread
public class UsageResourceImpl implements UsageResource {

    @Inject
    EntityManager entityManager;

    /**
     * {@inheritDoc}
     */
    @Override
    public AiUsageSearchResults listAiUsage(BigInteger page, BigInteger limit,
                                           String filterInvocationType,
                                           BigInteger filterProjectId,
                                           BigInteger filterActorId,
                                           String filterActionType,
                                           String filterDateFrom,
                                           String filterDateTo,
                                           String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterInvocationType != null && !filterInvocationType.isBlank()) {
            hql.append(" and invocationType = :invocationType");
            params.put("invocationType", filterInvocationType);
        }
        if (filterProjectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", filterProjectId.longValue());
        }
        if (filterActorId != null) {
            hql.append(" and actorId = :actorId");
            params.put("actorId", filterActorId.longValue());
        }
        if (filterActionType != null && !filterActionType.isBlank()) {
            hql.append(" and lower(actionType) like :actionType");
            params.put("actionType", "%" + filterActionType.toLowerCase() + "%");
        }
        if (filterDateFrom != null && !filterDateFrom.isBlank()) {
            Instant fromInstant = parseInstant(filterDateFrom);
            hql.append(" and createdOn >= :dateFrom");
            params.put("dateFrom", fromInstant);
        }
        if (filterDateTo != null && !filterDateTo.isBlank()) {
            Instant toInstant = parseInstant(filterDateTo);
            if (!filterDateTo.contains("T")) {
                toInstant = toInstant.plus(1, ChronoUnit.DAYS);
            }
            hql.append(" and createdOn < :dateTo");
            params.put("dateTo", toInstant);
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

        long totalCount = AiUsageEntity.count(hql.toString(), params);
        List<AiUsage> items = AiUsageEntity.<AiUsageEntity>find(hql.toString(),
                        Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toBean)
                .toList();

        // Compute aggregates across ALL matching records (not just the current page)
        String aggregateHql = "SELECT COALESCE(SUM(costUsd), 0), "
                + "COALESCE(SUM(inputTokens), 0), "
                + "COALESCE(SUM(outputTokens), 0) "
                + "FROM AiUsageEntity WHERE " + hql;
        Query aggregateQuery = entityManager.createQuery(aggregateHql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            aggregateQuery.setParameter(entry.getKey(), entry.getValue());
        }
        Object[] aggregates = (Object[]) aggregateQuery.getSingleResult();

        AiUsageSearchResults results = new AiUsageSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        results.setTotalCostUsd(((Number) aggregates[0]).doubleValue());
        results.setTotalInputTokens(((Number) aggregates[1]).longValue());
        results.setTotalOutputTokens(((Number) aggregates[2]).longValue());
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DiskUsageSearchResults listDiskUsage(BigInteger page, BigInteger limit,
                                                 String filterName, String sortBy,
                                                 String sortOrder) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and lower(name) like :name");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        long totalCount = ProjectEntity.count(hql.toString(), params);

        String sortField = "diskUsageBytes".equals(sortBy) ? "diskUsageBytes" : "name";
        boolean descending = "desc".equalsIgnoreCase(sortOrder);

        PanacheQuery<ProjectEntity> query;
        if ("diskUsageBytes".equals(sortField)) {
            String dir = descending ? "DESC" : "ASC";
            String fullHql = hql + " ORDER BY COALESCE(diskUsageBytes, 0) " + dir;
            query = ProjectEntity.find(fullHql, params);
        } else {
            Sort sort = descending ? Sort.descending("name") : Sort.ascending("name");
            query = ProjectEntity.find(hql.toString(), sort, params);
        }

        List<DiskUsageProject> items = query
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toDiskUsageBean)
                .toList();

        String aggregateHql = "SELECT COALESCE(SUM(diskUsageBytes), 0) "
                + "FROM ProjectEntity WHERE " + hql;
        Query aggregateQuery = entityManager.createQuery(aggregateHql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            aggregateQuery.setParameter(entry.getKey(), entry.getValue());
        }
        long totalDiskUsageBytes = ((Number) aggregateQuery.getSingleResult()).longValue();

        DiskUsageSearchResults results = new DiskUsageSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        results.setTotalDiskUsageBytes(totalDiskUsageBytes);
        results.setProjectCount(totalCount);
        return results;
    }

    private DiskUsageProject toDiskUsageBean(ProjectEntity entity) {
        DiskUsageProject project = new DiskUsageProject();
        project.setProjectId(entity.id);
        project.setProjectName(entity.name);
        project.setDiskUsageBytes(entity.diskUsageBytes != null ? entity.diskUsageBytes : 0L);
        return project;
    }

    /**
     * Parses a date string that may be either ISO-8601 datetime (e.g. "2026-07-30T12:00:00.000Z")
     * or date-only (e.g. "2026-07-30"), returning an {@link Instant}.
     */
    private Instant parseInstant(String value) {
        if (value.contains("T")) {
            return Instant.parse(value);
        }
        return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private AiUsage toBean(AiUsageEntity entity) {
        AiUsage usage = new AiUsage();
        usage.setId(entity.id);
        usage.setInvocationType(entity.invocationType);
        usage.setTaskId(entity.taskId);
        usage.setEventId(entity.eventId);
        usage.setProjectId(entity.projectId);
        usage.setAgentId(entity.agentId);
        usage.setActionType(entity.actionType);
        usage.setModel(entity.model);
        usage.setCostUsd(entity.costUsd);
        usage.setInputTokens(entity.inputTokens);
        usage.setOutputTokens(entity.outputTokens);
        usage.setDurationMs(entity.durationMs);
        usage.setCreatedOn(Date.from(entity.createdOn));
        return usage;
    }
}
