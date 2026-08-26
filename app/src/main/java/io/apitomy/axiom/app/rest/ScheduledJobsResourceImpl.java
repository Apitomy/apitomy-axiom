package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ScheduledResource;
import io.apitomy.axiom.api.beans.Environment;
import io.apitomy.axiom.api.beans.NewScheduledJob;
import io.apitomy.axiom.api.beans.ScheduledJob;
import io.apitomy.axiom.api.beans.ScheduledJobRun;
import io.apitomy.axiom.api.beans.ScheduledJobRunSearchResults;
import io.apitomy.axiom.api.beans.ToolValidationMessage;
import io.apitomy.axiom.api.beans.ToolValidationResult;
import io.apitomy.axiom.app.ScheduledJobQueueConsumer;
import io.apitomy.axiom.app.ScheduledJobScheduler;
import io.apitomy.axiom.core.SdkToolNames;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.services.ScheduledJobValidator;
import io.apitomy.axiom.core.util.SlugUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the Scheduled Jobs REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ScheduledJobsResourceImpl implements ScheduledResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ScheduledJobScheduler scheduler;

    @Inject
    ScheduledJobQueueConsumer queueConsumer;

    // ── Scheduled Jobs CRUD ─────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ScheduledJob> listScheduledJobs() {
        return ScheduledJobEntity.<ScheduledJobEntity>listAll(Sort.ascending("name"))
                .stream().map(this::toBean).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ScheduledJob createScheduledJob(NewScheduledJob data) {
        validateOrThrow(data);
        checkDuplicateName(data.getName(), null);
        ScheduledJobEntity entity = new ScheduledJobEntity();
        applyFields(entity, data);
        entity.createdOn = Instant.now();
        entity.updatedOn = Instant.now();
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduledJob getScheduledJob(long jobId) {
        return toBean(findOrThrow(jobId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ScheduledJob updateScheduledJob(long jobId, NewScheduledJob data) {
        validateOrThrow(data);
        checkDuplicateName(data.getName(), jobId);
        ScheduledJobEntity entity = findOrThrow(jobId);
        applyFields(entity, data);
        entity.updatedOn = Instant.now();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteScheduledJob(long jobId) {
        ScheduledJobEntity entity = findOrThrow(jobId);
        ScheduledJobRunEntity.delete("jobId", jobId);
        entity.delete();
    }

    // ── Manual Trigger ──────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduledJobRun runScheduledJob(long jobId) {
        Long runId = createRunForManualTrigger(jobId);
        queueConsumer.enqueue(runId);

        return toRunBean(findRunOrThrow(runId));
    }

    private ScheduledJobRunEntity findRunOrThrow(long runId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Scheduled job run not found: " + runId, 404);
        }
        return run;
    }

    @Transactional
    Long createRunForManualTrigger(long jobId) {
        ScheduledJobEntity job = findOrThrow(jobId);
        return scheduler.createRunForManualTrigger(job);
    }

    // ── Cross-Job Run Listing ───────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduledJobRunSearchResults listAllScheduledJobRuns(BigInteger page,
                                                                 BigInteger limit,
                                                                 String filterJobName,
                                                                 String filterStatus,
                                                                 String filterTrigger) {
        int pageNum = page != null ? Math.max(1, page.intValue()) : 1;
        int pageSize = limit != null ? Math.max(1, limit.intValue()) : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterJobName != null && !filterJobName.isBlank()) {
            List<Long> matchingJobIds = ScheduledJobEntity.<ScheduledJobEntity>find(
                            "lower(name) like :name", Map.of("name", "%" + filterJobName.toLowerCase() + "%"))
                    .list().stream().map(j -> j.id).toList();
            if (matchingJobIds.isEmpty()) {
                return emptyResults(pageNum, pageSize);
            }
            hql.append(" and jobId in :jobIds");
            params.put("jobIds", matchingJobIds);
        }
        if (filterStatus != null && !filterStatus.isBlank()) {
            List<String> statuses = List.of(filterStatus.split(","));
            hql.append(" and status in :statuses");
            params.put("statuses", statuses);
        }
        if (filterTrigger != null && !filterTrigger.isBlank()) {
            hql.append(" and trigger = :trigger");
            params.put("trigger", filterTrigger);
        }

        long totalCount = ScheduledJobRunEntity.count(hql.toString(), params);
        List<ScheduledJobRunEntity> runs = ScheduledJobRunEntity.<ScheduledJobRunEntity>find(
                        hql.toString(), Sort.descending("createdOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        Map<Long, String> jobNames = resolveJobNames(runs);

        ScheduledJobRunSearchResults results = new ScheduledJobRunSearchResults();
        results.setItems(runs.stream().map(r -> toRunBean(r, jobNames)).toList());
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduledJobRun getScheduledJobRun(long runId) {
        ScheduledJobRunEntity run = findRunOrThrow(runId);
        Map<Long, String> jobNames = resolveJobNames(List.of(run));
        return toRunBean(run, jobNames);
    }

    // ── Per-Job Run History ─────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduledJobRunSearchResults listScheduledJobRuns(long jobId,
                                                              BigInteger page,
                                                              BigInteger limit) {
        findOrThrow(jobId);

        int pageNum = page != null ? Math.max(1, page.intValue()) : 1;
        int pageSize = limit != null ? Math.max(1, limit.intValue()) : 20;

        long totalCount = ScheduledJobRunEntity.count("jobId", jobId);
        List<ScheduledJobRunEntity> runs = ScheduledJobRunEntity
                .find("jobId", Sort.descending("createdOn"), jobId)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        ScheduledJobRunSearchResults results = new ScheduledJobRunSearchResults();
        results.setItems(runs.stream().map(this::toRunBean).toList());
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    // ── Validation ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolValidationResult validateScheduledJob(NewScheduledJob data) {
        ScheduledJobValidator.ValidationResult result =
                ScheduledJobValidator.validate(data, getKnownNames());
        ToolValidationResult response = new ToolValidationResult();
        response.setValid(!result.hasErrors());
        response.setMessages(result.messages().stream().map(m -> {
            ToolValidationMessage msg = new ToolValidationMessage();
            msg.setSeverity(m.severity() == ScheduledJobValidator.Severity.ERROR
                    ? ToolValidationMessage.Severity.ERROR
                    : ToolValidationMessage.Severity.WARNING);
            msg.setField(m.field());
            msg.setMessage(m.message());
            return msg;
        }).toList());
        return response;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void validateOrThrow(NewScheduledJob data) {
        ScheduledJobValidator.ValidationResult result =
                ScheduledJobValidator.validate(data, getKnownNames());
        if (result.hasErrors()) {
            var errors = result.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            var warnings = result.warnings().stream()
                    .map(w -> Map.of("field", w.field(), "message", w.message()))
                    .toList();
            var body = Map.of(
                    "message", "Scheduled job has validation errors.",
                    "errors", errors,
                    "warnings", warnings
            );
            throw new WebApplicationException(
                    Response.status(422).entity(body).build());
        }
    }

    private ScheduledJobValidator.KnownNames getKnownNames() {
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
        return new ScheduledJobValidator.KnownNames(secrets, tools, toolsets, sdkTools);
    }

    private void checkDuplicateName(String name, Long excludeId) {
        if (name == null || name.isBlank()) return;
        ScheduledJobEntity existing = ScheduledJobEntity.find("name", name).firstResult();
        if (existing != null && (excludeId == null || !excludeId.equals(existing.id))) {
            throw new WebApplicationException(
                    Response.status(409)
                            .entity(Map.of("message",
                                    "A scheduled job named '" + name + "' already exists."))
                            .build());
        }
    }

    private ScheduledJobEntity findOrThrow(long id) {
        ScheduledJobEntity entity = ScheduledJobEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Scheduled job not found: " + id, 404);
        }
        return entity;
    }

    private void applyFields(ScheduledJobEntity entity, NewScheduledJob data) {
        entity.name = data.getName();
        entity.slug = (data.getSlug() != null && !data.getSlug().isBlank())
                ? data.getSlug()
                : SlugUtil.slugify(data.getName());
        entity.description = data.getDescription();
        entity.schedule = data.getSchedule();
        entity.scheduleTime = data.getScheduleTime();
        entity.scheduleDayOfWeek = data.getScheduleDayOfWeek();
        entity.executionMode = data.getExecutionMode();
        entity.promptTemplate = data.getPromptTemplate();
        entity.scriptTemplate = data.getScriptTemplate();
        entity.model = data.getModel();
        entity.engine = data.getEngine();
        entity.allowedTools = data.getAllowedTools() != null
                ? String.join(",", data.getAllowedTools()) : null;
        entity.maxSteps = data.getMaxSteps();
        entity.maxBudgetUsd = data.getMaxBudgetUsd();
        entity.timeoutSeconds = data.getTimeoutSeconds();
        entity.enabled = "none".equals(data.getSchedule()) ? false
                : (data.getEnabled() != null ? data.getEnabled() : false);
        entity.environment = environmentToJson(data.getEnvironment());
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }

        if (entity.enabled) {
            entity.nextRunAt = scheduler.computeInitialNextRunAt(entity);
        } else {
            entity.nextRunAt = null;
        }
    }

    private ScheduledJob toBean(ScheduledJobEntity entity) {
        ScheduledJob bean = new ScheduledJob();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setSlug(entity.slug);
        bean.setDescription(entity.description);
        bean.setLabels(entity.labels);
        bean.setEnabled(entity.enabled);
        bean.setSchedule(entity.schedule);
        bean.setScheduleTime(entity.scheduleTime);
        bean.setScheduleDayOfWeek(entity.scheduleDayOfWeek);
        if (entity.nextRunAt != null) bean.setNextRunAt(Date.from(entity.nextRunAt));
        if (entity.lastRunAt != null) bean.setLastRunAt(Date.from(entity.lastRunAt));
        bean.setExecutionMode(entity.executionMode);
        bean.setPromptTemplate(entity.promptTemplate);
        bean.setScriptTemplate(entity.scriptTemplate);
        bean.setModel(entity.model);
        bean.setEngine(entity.engine);
        if (entity.allowedTools != null && !entity.allowedTools.isBlank()) {
            bean.setAllowedTools(java.util.Arrays.stream(entity.allowedTools.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        bean.setMaxSteps(entity.maxSteps);
        bean.setMaxBudgetUsd(entity.maxBudgetUsd);
        bean.setTimeoutSeconds(entity.timeoutSeconds);
        bean.setEnvironment(jsonToEnvironment(entity.environment));
        bean.setCreatedOn(Date.from(entity.createdOn));
        bean.setUpdatedOn(Date.from(entity.updatedOn));
        return bean;
    }

    private ScheduledJobRunSearchResults emptyResults(int pageNum, int pageSize) {
        ScheduledJobRunSearchResults results = new ScheduledJobRunSearchResults();
        results.setItems(List.of());
        results.setTotalCount(0L);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * Batch-resolves job names for a list of run entities.
     */
    private Map<Long, String> resolveJobNames(List<ScheduledJobRunEntity> runs) {
        Set<Long> jobIds = runs.stream().map(r -> r.jobId).collect(Collectors.toSet());
        if (jobIds.isEmpty()) return Map.of();
        return ScheduledJobEntity.<ScheduledJobEntity>find("id in :ids", Map.of("ids", jobIds))
                .list().stream()
                .collect(Collectors.toMap(j -> j.id, j -> j.name));
    }

    private ScheduledJobRun toRunBean(ScheduledJobRunEntity entity, Map<Long, String> jobNames) {
        ScheduledJobRun bean = toRunBean(entity);
        bean.setJobName(jobNames.getOrDefault(entity.jobId, "Unknown"));
        return bean;
    }

    private ScheduledJobRun toRunBean(ScheduledJobRunEntity entity) {
        ScheduledJobRun bean = new ScheduledJobRun();
        bean.setId(entity.id);
        bean.setJobId(entity.jobId);
        bean.setStatus(entity.status);
        bean.setTrigger(entity.trigger);
        if (entity.startedAt != null) bean.setStartedAt(Date.from(entity.startedAt));
        if (entity.completedAt != null) bean.setCompletedAt(Date.from(entity.completedAt));
        bean.setOutput(entity.output);
        bean.setError(entity.error);
        bean.setExecutionLog(entity.executionLog);
        bean.setCostUsd(entity.costUsd);
        bean.setDurationMs(entity.durationMs);
        if (entity.traceId != null) bean.setTraceId(entity.traceId);
        bean.setCreatedOn(Date.from(entity.createdOn));
        return bean;
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
