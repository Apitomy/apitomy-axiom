package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ReportsResource;
import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.api.beans.NewReportDefinition;
import io.apitomy.axiom.api.beans.Report;
import io.apitomy.axiom.api.beans.ReportAiEditRequest;
import io.apitomy.axiom.api.beans.ReportAiEditResponse;
import io.apitomy.axiom.api.beans.ReportDefinition;
import io.apitomy.axiom.api.beans.ReportSearchResults;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.Environment;
import io.apitomy.axiom.app.ReportAiService;
import io.apitomy.axiom.app.ReportQueueConsumer;
import io.apitomy.axiom.app.ReportScheduler;
import io.apitomy.axiom.core.SdkToolNames;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.util.SlugUtil;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import io.apitomy.axiom.api.beans.ToolValidationResult;
import io.apitomy.axiom.api.beans.ToolValidationMessage;
import io.apitomy.axiom.core.services.ReportDefinitionValidator;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the Reports REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ReportsResourceImpl implements ReportsResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ReportScheduler reportScheduler;

    @Inject
    ReportQueueConsumer reportQueuePoller;

    @Inject
    ReportAiService reportAiService;

    /**
     * {@inheritDoc}
     */
    @Override
    public ReportAiEditResponse aiEditReportPrompt(ReportAiEditRequest data) {
        return reportAiService.editReportPrompt(data);
    }

    // ── Report Definitions CRUD ──────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ReportDefinition> listReportDefinitions() {
        return ReportDefinitionEntity.<ReportDefinitionEntity>listAll(Sort.ascending("name"))
                .stream().map(this::toDefinitionBean).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ReportDefinition createReportDefinition(NewReportDefinition data) {
        validateOrThrow(data);
        ReportDefinitionEntity entity = new ReportDefinitionEntity();
        applyDefinitionFields(entity, data);
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();
        return toDefinitionBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReportDefinition getReportDefinition(long definitionId) {
        return toDefinitionBean(findDefinitionOrThrow(definitionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ReportDefinition updateReportDefinition(long definitionId, NewReportDefinition data) {
        validateOrThrow(data);
        ReportDefinitionEntity entity = findDefinitionOrThrow(definitionId);
        applyDefinitionFields(entity, data);
        entity.updatedOn = Instant.now();
        return toDefinitionBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolValidationResult validateReportDefinition(NewReportDefinition data) {
        ReportDefinitionValidator.ValidationResult result =
                ReportDefinitionValidator.validate(data, getKnownNames());
        ToolValidationResult response = new ToolValidationResult();
        response.setValid(!result.hasErrors());
        response.setMessages(result.messages().stream().map(m -> {
            ToolValidationMessage msg = new ToolValidationMessage();
            msg.setSeverity(m.severity() == ReportDefinitionValidator.Severity.ERROR
                    ? ToolValidationMessage.Severity.ERROR
                    : ToolValidationMessage.Severity.WARNING);
            msg.setField(m.field());
            msg.setMessage(m.message());
            return msg;
        }).toList());
        return response;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteReportDefinition(long definitionId) {
        ReportDefinitionEntity entity = findDefinitionOrThrow(definitionId);
        // Delete associated reports
        ReportEntity.delete("definitionId", definitionId);
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Report runReportDefinition(long definitionId) {
        // Step 1: create the report (transactional — commits before enqueue)
        Long reportId = createReportForRun(definitionId);

        // Step 2: enqueue after transaction has committed
        reportQueuePoller.enqueue(reportId);

        return getReport(reportId);
    }

    @Transactional
    Long createReportForRun(long definitionId) {
        ReportDefinitionEntity definition = findDefinitionOrThrow(definitionId);
        return reportScheduler.createReportAndScheduleNext(definition);
    }

    // ── Reports (read-only) ──────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ReportSearchResults listReports(BigInteger page, BigInteger limit,
                                            BigInteger filterDefinitionId,
                                            String filterStatus, String filterTitle,
                                            String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterDefinitionId != null) {
            hql.append(" and definitionId = :defId");
            params.put("defId", filterDefinitionId.longValue());
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            java.util.List<String> statuses = java.util.Arrays.stream(filterStatus.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and status in :statuses");
            params.put("statuses", statuses);
        }
        if (filterTitle != null && !filterTitle.isBlank()) {
            hql.append(" and lower(title) like :title");
            params.put("title", "%" + filterTitle.toLowerCase() + "%");
        }
        if (filterLabels != null && !filterLabels.isBlank()) {
            java.util.List<String> labels = java.util.Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and id in (SELECT r.id FROM ReportEntity r"
                    + " JOIN r.labels rl WHERE rl IN :labels"
                    + " GROUP BY r.id HAVING COUNT(DISTINCT rl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        long totalCount = ReportEntity.count(hql.toString(), params);
        List<Report> items = ReportEntity.<ReportEntity>find(hql.toString(),
                        Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toReportBean).toList();

        ReportSearchResults results = new ReportSearchResults();
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
    public String getReportExecutionLog(long reportId) {
        ReportEntity entity = ReportEntity.findById(reportId);
        if (entity == null) {
            throw new WebApplicationException("Report not found: " + reportId, 404);
        }
        if (entity.executionLog == null || entity.executionLog.isEmpty()) {
            throw new WebApplicationException(
                    "No execution log available for report: " + reportId, 404);
        }
        return entity.executionLog;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Report getReport(long reportId) {
        ReportEntity entity = ReportEntity.findById(reportId);
        if (entity == null) {
            throw new WebApplicationException("Report not found: " + reportId, 404);
        }
        return toReportBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteReport(long reportId) {
        ReportEntity entity = ReportEntity.findById(reportId);
        if (entity == null) {
            throw new WebApplicationException("Report not found: " + reportId, 404);
        }
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Report updateReportLabels(long reportId, List<String> labels) {
        ReportEntity entity = ReportEntity.findById(reportId);
        if (entity == null) {
            throw new WebApplicationException("Report not found: " + reportId, 404);
        }
        entity.labels.clear();
        entity.labels.addAll(labels);
        return toReportBean(entity);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void validateOrThrow(NewReportDefinition data) {
        ReportDefinitionValidator.ValidationResult result =
                ReportDefinitionValidator.validate(data, getKnownNames());
        if (result.hasErrors()) {
            var errors = result.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            var warnings = result.warnings().stream()
                    .map(w -> Map.of("field", w.field(), "message", w.message()))
                    .toList();
            var body = Map.of(
                    "message", "Report definition has validation errors.",
                    "errors", errors,
                    "warnings", warnings
            );
            throw new WebApplicationException(
                    Response.status(422).entity(body).build());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Trace> listReportTraces(BigInteger reportId) {
        return TraceEntity.<TraceEntity>list("reportId", Sort.descending("startedOn"),
                reportId.longValue())
                .stream()
                .map(TraceMapper::toTraceBean)
                .toList();
    }

    private ReportDefinitionValidator.KnownNames getKnownNames() {
        Set<String> secrets = SecretEntity.<SecretEntity>listAll().stream()
                .map(s -> s.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> tools = ToolDefinitionEntity.<ToolDefinitionEntity>listAll().stream()
                .map(t -> t.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> toolsets = ToolsetEntity.<ToolsetEntity>listAll().stream()
                .map(t -> t.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sdkTools = SdkToolNames.ALL;
        return new ReportDefinitionValidator.KnownNames(secrets, tools, toolsets, sdkTools);
    }

    private ReportDefinitionEntity findDefinitionOrThrow(long id) {
        ReportDefinitionEntity entity = ReportDefinitionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Report definition not found: " + id, 404);
        }
        return entity;
    }

    private void applyDefinitionFields(ReportDefinitionEntity entity, NewReportDefinition data) {
        entity.name = data.getName();
        entity.slug = (data.getSlug() != null && !data.getSlug().isBlank())
                ? data.getSlug()
                : SlugUtil.slugify(data.getName());
        entity.description = data.getDescription();
        entity.schedule = data.getSchedule();
        entity.scheduleTime = data.getScheduleTime();
        entity.scheduleDayOfWeek = data.getScheduleDayOfWeek();
        entity.timeWindow = data.getTimeWindow();
        entity.promptTemplate = data.getPromptTemplate();
        entity.titleTemplate = data.getTitleTemplate();
        entity.allowedTools = data.getAllowedTools() != null
                ? String.join(",", data.getAllowedTools()) : null;
        entity.enabled = "none".equals(data.getSchedule()) ? false
                : (data.getEnabled() != null ? data.getEnabled() : false);
        entity.timeoutSeconds = data.getTimeoutSeconds();
        entity.environment = environmentToJson(data.getEnvironment());
        entity.initialLabels.clear();
        if (data.getInitialLabels() != null) {
            entity.initialLabels.addAll(data.getInitialLabels());
        }

        // Compute nextRunAt when enabled (or schedule/time changes)
        if (entity.enabled) {
            entity.nextRunAt = reportScheduler.computeInitialNextRunAt(entity);
        } else {
            entity.nextRunAt = null;
        }
    }

    private ReportDefinition toDefinitionBean(ReportDefinitionEntity entity) {
        ReportDefinition def = new ReportDefinition();
        def.setId(entity.id);
        def.setName(entity.name);
        def.setSlug(entity.slug);
        def.setDescription(entity.description);
        def.setSchedule(entity.schedule);
        def.setScheduleTime(entity.scheduleTime);
        def.setScheduleDayOfWeek(entity.scheduleDayOfWeek);
        def.setTimeWindow(entity.timeWindow);
        def.setPromptTemplate(entity.promptTemplate);
        def.setTitleTemplate(entity.titleTemplate);
        if (entity.allowedTools != null && !entity.allowedTools.isBlank()) {
            def.setAllowedTools(java.util.Arrays.stream(entity.allowedTools.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        def.setEnabled(entity.enabled);
        def.setTimeoutSeconds(entity.timeoutSeconds);
        def.setEnvironment(jsonToEnvironment(entity.environment));
        if (entity.nextRunAt != null) def.setNextRunAt(Date.from(entity.nextRunAt));
        if (entity.lastRunAt != null) def.setLastRunAt(Date.from(entity.lastRunAt));
        def.setInitialLabels(entity.initialLabels);
        def.setCreatedOn(Date.from(entity.createdOn));
        def.setUpdatedOn(Date.from(entity.updatedOn));
        return def;
    }

    private Report toReportBean(ReportEntity entity) {
        Report report = new Report();
        report.setId(entity.id);
        report.setDefinitionId(entity.definitionId);
        report.setStatus(entity.status);
        report.setTitle(entity.title);
        report.setContent(entity.content);
        if (entity.timeRangeStart != null) report.setTimeRangeStart(Date.from(entity.timeRangeStart));
        if (entity.timeRangeEnd != null) report.setTimeRangeEnd(Date.from(entity.timeRangeEnd));
        report.setCostUsd(entity.costUsd);
        report.setDurationMs(entity.durationMs);
        report.setCreatedOn(Date.from(entity.createdOn));
        if (entity.completedOn != null) report.setCompletedOn(Date.from(entity.completedOn));
        report.setLabels(entity.labels);
        if (entity.traceId != null) {
            report.setTraceId(entity.traceId);
        }
        return report;
    }

    private String environmentToJson(Environment env) {
        if (env == null || env.getAdditionalProperties().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(env.getAdditionalProperties());
        } catch (Exception e) {
            return null;
        }
    }

    private Environment jsonToEnvironment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {});
            Environment env = new Environment();
            map.forEach(env::setAdditionalProperty);
            return env;
        } catch (Exception e) {
            return null;
        }
    }
}
