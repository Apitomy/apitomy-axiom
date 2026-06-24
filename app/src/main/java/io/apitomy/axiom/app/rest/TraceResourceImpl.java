package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.TracesResource;
import io.apitomy.axiom.api.beans.Detail;
import io.apitomy.axiom.api.beans.ToolCallCompletion;
import io.apitomy.axiom.api.beans.ToolCallCreated;
import io.apitomy.axiom.api.beans.ToolCallRequest;
import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.api.beans.TraceDetail;
import io.apitomy.axiom.api.beans.TraceNode;
import io.apitomy.axiom.api.beans.TraceNodeDetail;
import io.apitomy.axiom.api.beans.TraceSearchResults;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ToolExecutionEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST resource for querying and managing execution traces.
 */
@ApplicationScoped
@RunOnVirtualThread
public class TraceResourceImpl implements TracesResource {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public TraceSearchResults listTraces(BigInteger page, BigInteger limit,
            String filterTraceType, String filterStatus,
            BigInteger filterEventId, BigInteger filterProjectId,
            BigInteger filterReportId) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterTraceType != null && !filterTraceType.isBlank()) {
            hql.append(" and traceType = :traceType");
            params.put("traceType", filterTraceType);
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            List<String> statuses = Arrays.stream(filterStatus.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (statuses.size() == 1) {
                hql.append(" and status = :status");
                params.put("status", statuses.getFirst());
            } else {
                hql.append(" and status in :statuses");
                params.put("statuses", statuses);
            }
        }
        if (filterEventId != null) {
            hql.append(" and eventId = :eventId");
            params.put("eventId", filterEventId.longValue());
        }
        if (filterProjectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", filterProjectId.longValue());
        }
        if (filterReportId != null) {
            hql.append(" and reportId = :reportId");
            params.put("reportId", filterReportId.longValue());
        }

        long totalCount = TraceEntity.count(hql.toString(), params);
        List<Trace> items = TraceEntity
                .<TraceEntity>find(hql.toString(), Sort.descending("startedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list()
                .stream()
                .map(this::toTraceBean)
                .toList();

        TraceSearchResults results = new TraceSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    @Override
    public TraceDetail getTrace(String traceId) {
        UUID uuid = parseUuid(traceId);
        TraceEntity trace = TraceEntity.findById(uuid);
        if (trace == null) {
            throw new WebApplicationException("Trace not found: " + traceId, 404);
        }

        List<TraceNode> nodes = TraceNodeEntity
                .<TraceNodeEntity>find("traceId = ?1 order by startedOn asc", uuid)
                .list()
                .stream()
                .map(this::toNodeBean)
                .toList();

        TraceDetail detail = new TraceDetail();
        detail.setTrace(toTraceBean(trace));
        detail.setNodes(nodes);
        return detail;
    }

    @Override
    public TraceNodeDetail getTraceNodeDetail(String traceId, BigInteger nodeId) {
        TraceNodeEntity node = TraceNodeEntity.findById(nodeId.longValue());
        if (node == null || !node.traceId.toString().equals(traceId)) {
            throw new WebApplicationException("Trace node not found: " + nodeId, 404);
        }

        TraceNodeDetail result = new TraceNodeDetail();
        result.setNode(toNodeBean(node));
        result.setDetail(resolveDetail(node.entityType, node.entityId));
        return result;
    }

    @Override
    public ToolCallCreated createToolCall(ToolCallRequest data) {
        UUID traceUuid = data.getTraceId();
        TraceEntity trace = TraceEntity.findById(traceUuid);
        if (trace == null) {
            throw new WebApplicationException("Trace not found: " + traceUuid, 404);
        }

        ToolExecutionEntity toolExec = new ToolExecutionEntity();
        toolExec.traceId = traceUuid;
        toolExec.toolName = data.getToolName();
        toolExec.toolInput = data.getToolInput();
        toolExec.status = "in-progress";
        toolExec.createdOn = java.time.Instant.now();
        toolExec.persist();

        TraceNodeEntity node = new TraceNodeEntity();
        node.traceId = traceUuid;
        node.parentNodeId = data.getParentNodeId();
        node.nodeType = "tool-execution";
        node.status = "in-progress";
        node.summary = "Tool: " + data.getToolName();
        node.startedOn = java.time.Instant.now();
        node.entityType = "tool-execution";
        node.entityId = toolExec.id;
        node.persist();

        ToolCallCreated response = new ToolCallCreated();
        response.setNodeId(node.id);
        return response;
    }

    @Override
    public void completeToolCall(BigInteger nodeId, ToolCallCompletion data) {
        TraceNodeEntity node = TraceNodeEntity.findById(nodeId.longValue());
        if (node == null) {
            throw new WebApplicationException("Trace node not found: " + nodeId, 404);
        }

        java.time.Instant now = java.time.Instant.now();
        node.completedOn = now;
        node.durationMs = data.getDurationMs();
        node.status = data.getStatus() != null ? data.getStatus() : "completed";

        if (node.entityType != null && "tool-execution".equals(node.entityType)
                && node.entityId != null) {
            ToolExecutionEntity toolExec = ToolExecutionEntity.findById(node.entityId);
            if (toolExec != null) {
                toolExec.toolOutput = data.getToolOutput();
                toolExec.status = node.status;
                toolExec.durationMs = data.getDurationMs();
            }
        }
    }

    /**
     * Resolves the detail entity for a trace node based on its entityType.
     */
    @SuppressWarnings("unchecked")
    private Detail resolveDetail(String entityType, Long entityId) {
        if (entityType == null || entityId == null) {
            return null;
        }

        Object entity = switch (entityType) {
            case "activity-log" -> ActivityLogEntity.findById(entityId);
            case "event" -> EventEntity.findById(entityId);
            case "ai-usage" -> AiUsageEntity.findById(entityId);
            case "tool-execution" -> ToolExecutionEntity.findById(entityId);
            case "task" -> TaskEntity.findById(entityId);
            case "report" -> ReportEntity.findById(entityId);
            default -> null;
        };

        if (entity == null) {
            return null;
        }

        Map<String, Object> properties = objectMapper.convertValue(entity, Map.class);
        return new DynamicDetail(properties);
    }

    private Trace toTraceBean(TraceEntity entity) {
        Trace bean = new Trace();
        bean.setTraceId(entity.traceId);
        bean.setTraceType(entity.traceType);
        bean.setStatus(entity.status);
        bean.setSummary(entity.summary);
        bean.setEventId(entity.eventId);
        bean.setProjectId(entity.projectId);
        bean.setReportId(entity.reportId);
        bean.setStartedOn(Date.from(entity.startedOn));
        if (entity.completedOn != null) {
            bean.setCompletedOn(Date.from(entity.completedOn));
        }
        return bean;
    }

    private TraceNode toNodeBean(TraceNodeEntity entity) {
        TraceNode bean = new TraceNode();
        bean.setId(entity.id);
        bean.setTraceId(entity.traceId);
        bean.setParentNodeId(entity.parentNodeId);
        bean.setNodeType(entity.nodeType);
        bean.setStatus(entity.status);
        bean.setSummary(entity.summary);
        bean.setStartedOn(Date.from(entity.startedOn));
        if (entity.completedOn != null) {
            bean.setCompletedOn(Date.from(entity.completedOn));
        }
        bean.setDurationMs(entity.durationMs);
        bean.setEntityType(entity.entityType);
        bean.setEntityId(entity.entityId);
        return bean;
    }

    private UUID parseUuid(String traceId) {
        try {
            return UUID.fromString(traceId);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid trace ID format: " + traceId, 400);
        }
    }

    /**
     * Extends the generated Detail bean to carry dynamic properties via
     * Jackson's {@code @JsonAnyGetter}, allowing arbitrary entity fields
     * to be serialized into the detail object.
     */
    private static class DynamicDetail extends Detail {

        private final Map<String, Object> properties;

        DynamicDetail(Map<String, Object> properties) {
            this.properties = properties;
        }

        /**
         * Exposes all entity fields as top-level JSON properties.
         */
        @JsonAnyGetter
        public Map<String, Object> getProperties() {
            return properties;
        }
    }
}
