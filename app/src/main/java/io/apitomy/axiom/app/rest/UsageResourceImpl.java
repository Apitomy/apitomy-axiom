package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.UsageResource;
import io.apitomy.axiom.api.beans.AiUsage;
import io.apitomy.axiom.api.beans.AiUsageSearchResults;
import io.apitomy.axiom.api.beans.AiUsageSummary;
import io.apitomy.axiom.api.beans.AiUsageSummaryRow;
import io.apitomy.axiom.api.beans.DiskUsageSubject;
import io.apitomy.axiom.api.beans.DiskUsageSearchResults;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
                                           BigInteger filterSubjectId,
                                           BigInteger filterAgentId,
                                           String filterActionType,
                                           String filterDateFrom,
                                           String filterDateTo,
                                           String filterLabels,
                                           String filterEngine,
                                           String filterModel,
                                           String filterTraceId) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterInvocationType != null && !filterInvocationType.isBlank()) {
            hql.append(" and invocationType = :invocationType");
            params.put("invocationType", filterInvocationType);
        }
        if (filterSubjectId != null) {
            hql.append(" and subjectId = :subjectId");
            params.put("subjectId", filterSubjectId.longValue());
        }
        if (filterAgentId != null) {
            hql.append(" and agentId = :agentId");
            params.put("agentId", filterAgentId.longValue());
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
            hql.append(" and subjectId in (SELECT p.id FROM SubjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }
        if (filterEngine != null && !filterEngine.isBlank()) {
            hql.append(" and engine = :engine");
            params.put("engine", filterEngine);
        }
        if (filterModel != null && !filterModel.isBlank()) {
            hql.append(" and model = :model");
            params.put("model", filterModel);
        }

        if (filterTraceId != null && !filterTraceId.isBlank()) {
            hql.append(" and traceId = :traceId");
            params.put("traceId", TraceResourceImpl.parseUuidParam("filterTraceId", filterTraceId));
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

    /** Maps each supported {@code groupBy} value to the entity attribute it groups on. */
    private static final Map<String, String> SUMMARY_GROUP_ATTRIBUTES = Map.of(
            "subject", "subjectId",
            "workflow", "workflowDefinitionId",
            "engine", "engine",
            "model", "model",
            "actionType", "actionType");

    /** Label used for rows whose group key is not set. */
    static final String NONE_LABEL = "(none)";

    /**
     * {@inheritDoc}
     */
    @Override
    public AiUsageSummary getAiUsageSummary(String groupBy, Date since, String filterLabels, BigInteger limit) {
        String attribute = groupBy != null ? SUMMARY_GROUP_ATTRIBUTES.get(groupBy) : null;
        if (attribute == null) {
            throw new WebApplicationException("Invalid groupBy '" + groupBy
                    + "'; expected one of subject, workflow, engine, model, actionType", 400);
        }
        int maxRows = clampLimit(limit, 10, 100);

        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (since != null) {
            where.append(" and u.createdOn >= :since");
            params.put("since", since.toInstant());
        }
        List<String> labels = filterLabels == null ? List.of() : Arrays.stream(filterLabels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        Double excludedUnscoped = null;
        if (!labels.isEmpty()) {
            Query excludedQuery = entityManager.createQuery("SELECT COALESCE(SUM(u.costUsd), 0) "
                    + "FROM AiUsageEntity u WHERE " + where + " and u.subjectId is null");
            params.forEach(excludedQuery::setParameter);
            excludedUnscoped = ((Number) excludedQuery.getSingleResult()).doubleValue();

            where.append(" and u.subjectId in (SELECT p.id FROM SubjectEntity p"
                    + " JOIN p.labels pl WHERE pl IN :labels"
                    + " GROUP BY p.id HAVING COUNT(DISTINCT pl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        Query totalQuery = entityManager.createQuery("SELECT COALESCE(SUM(u.costUsd), 0) "
                + "FROM AiUsageEntity u WHERE " + where);
        params.forEach(totalQuery::setParameter);
        double totalCost = ((Number) totalQuery.getSingleResult()).doubleValue();

        Query rowsQuery = entityManager.createQuery("SELECT u." + attribute
                + ", COALESCE(SUM(u.costUsd), 0), COALESCE(SUM(u.inputTokens), 0),"
                + " COALESCE(SUM(u.outputTokens), 0), COUNT(u)"
                + " FROM AiUsageEntity u WHERE " + where
                + " GROUP BY u." + attribute
                + " ORDER BY COALESCE(SUM(u.costUsd), 0) DESC, COUNT(u) DESC");
        params.forEach(rowsQuery::setParameter);
        rowsQuery.setMaxResults(maxRows);
        @SuppressWarnings("unchecked")
        List<Object[]> results = rowsQuery.getResultList();

        Map<Long, String> names = resolveGroupNames(groupBy, results);
        List<AiUsageSummaryRow> rows = new ArrayList<>();
        for (Object[] r : results) {
            AiUsageSummaryRow row = new AiUsageSummaryRow();
            String key = r[0] == null ? "" : r[0].toString();
            row.setKey(key);
            String label;
            if (key.isEmpty()) {
                label = NONE_LABEL;
            } else if (r[0] instanceof Long id) {
                label = names.getOrDefault(id, "#" + id);
            } else {
                label = key;
            }
            row.setLabel(label);
            row.setCostUsd(((Number) r[1]).doubleValue());
            row.setInputTokens(((Number) r[2]).longValue());
            row.setOutputTokens(((Number) r[3]).longValue());
            row.setInvocations(((Number) r[4]).longValue());
            rows.add(row);
        }

        AiUsageSummary summary = new AiUsageSummary();
        summary.setGroupBy(AiUsageSummary.GroupBy.fromValue(groupBy));
        summary.setRows(rows);
        summary.setTotalCostUsd(totalCost);
        summary.setExcludedUnscopedCostUsd(excludedUnscoped);
        return summary;
    }

    /**
     * Looks up display names for id-keyed groups (subject names or workflow definition names).
     */
    private Map<Long, String> resolveGroupNames(String groupBy, List<Object[]> results) {
        List<Long> ids = results.stream()
                .map(r -> r[0])
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String entity = "subject".equals(groupBy) ? "SubjectEntity" : "WorkflowDefinitionEntity";
        List<Object[]> pairs = entityManager.createQuery(
                        "SELECT e.id, e.name FROM " + entity + " e WHERE e.id IN :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        Map<Long, String> names = new HashMap<>();
        for (Object[] pair : pairs) {
            names.put((Long) pair[0], (String) pair[1]);
        }
        return names;
    }

    private static int clampLimit(BigInteger value, int defaultValue, int max) {
        if (value == null) {
            return defaultValue;
        }
        return (int) Math.max(1, Math.min(max, value.longValue()));
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

        long totalCount = SubjectEntity.count(hql.toString(), params);

        String sortField = "diskUsageBytes".equals(sortBy) ? "diskUsageBytes" : "name";
        boolean descending = "desc".equalsIgnoreCase(sortOrder);

        PanacheQuery<SubjectEntity> query;
        if ("diskUsageBytes".equals(sortField)) {
            String dir = descending ? "DESC" : "ASC";
            String fullHql = hql + " ORDER BY COALESCE(diskUsageBytes, 0) " + dir;
            query = SubjectEntity.find(fullHql, params);
        } else {
            Sort sort = descending ? Sort.descending("name") : Sort.ascending("name");
            query = SubjectEntity.find(hql.toString(), sort, params);
        }

        List<DiskUsageSubject> items = query
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toDiskUsageBean)
                .toList();

        String aggregateHql = "SELECT COALESCE(SUM(diskUsageBytes), 0) "
                + "FROM SubjectEntity WHERE " + hql;
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
        results.setSubjectCount(totalCount);
        return results;
    }

    private DiskUsageSubject toDiskUsageBean(SubjectEntity entity) {
        DiskUsageSubject subject = new DiskUsageSubject();
        subject.setSubjectId(entity.id);
        subject.setSubjectName(entity.name);
        subject.setDiskUsageBytes(entity.diskUsageBytes != null ? entity.diskUsageBytes : 0L);
        return subject;
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
        usage.setTraceId(entity.traceId);
        usage.setSubjectId(entity.subjectId);
        usage.setAgentId(entity.agentId);
        usage.setActionType(entity.actionType);
        usage.setEngine(entity.engine);
        usage.setModel(entity.model);
        usage.setCostUsd(entity.costUsd);
        usage.setInputTokens(entity.inputTokens);
        usage.setOutputTokens(entity.outputTokens);
        usage.setDurationMs(entity.durationMs);
        usage.setWorkflowRunId(entity.workflowRunId);
        usage.setWorkflowDefinitionId(entity.workflowDefinitionId);
        usage.setCreatedOn(Date.from(entity.createdOn));
        return usage;
    }
}
