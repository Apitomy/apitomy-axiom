package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.EngineInfo;
import io.apitomy.axiom.api.beans.Features;
import io.apitomy.axiom.api.beans.HealthCheck;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.api.beans.RetentionConfig;
import io.apitomy.axiom.api.beans.StartupCheck;
import io.apitomy.axiom.api.beans.SystemConfig;
import io.apitomy.axiom.api.beans.SystemHealth;
import io.apitomy.axiom.api.beans.UpdateSystemConfigRequest;
import io.apitomy.axiom.api.SystemResource;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.app.EventSourceHealthService;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.StartupCheckService;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.SystemConfigEntity;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import jakarta.transaction.Transactional;
import java.io.InputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the System API endpoints.
 */
@ApplicationScoped
@RunOnVirtualThread
public class SystemResourceImpl implements SystemResource {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "1.0.0-SNAPSHOT")
    String applicationVersion;

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    StartupCheckService startupCheckService;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper packObjectMapper;

    @Inject
    EventSourceHealthService eventSourceHealthService;

    @Inject
    EntityManager entityManager;

    static final String DATABASE_CHECK = "database";
    static final String EVENT_SOURCES_CHECK = "eventSources";
    static final String ENGINES_CHECK = "engines";
    static final String SCHEDULER_CHECK = "scheduler";

    /**
     * {@inheritDoc}
     */
    @Override
    public SystemHealth getSystemHealth() {
        List<HealthCheck> checks = new ArrayList<>();
        checks.add(databaseCheck());
        checks.add(eventSourcesCheck());
        checks.add(enginesCheck());
        checks.add(check(SCHEDULER_CHECK, HealthCheck.Status.UP, "Scheduler started"));

        SystemHealth health = new SystemHealth();
        health.setStatus(SystemHealth.Status.fromValue(worst(checks).value()));
        health.setVersion(applicationVersion);
        health.setTimestamp(new Date());
        health.setChecks(checks);
        return health;
    }

    /**
     * Returns the worst status among the checks (DOWN over DEGRADED over UP).
     *
     * @param checks the checks
     * @return the worst status, UP when there are no checks
     */
    static HealthCheck.Status worst(List<HealthCheck> checks) {
        return checks.stream()
                .map(HealthCheck::getStatus)
                .max(Comparator.comparingInt(SystemResourceImpl::severity))
                .orElse(HealthCheck.Status.UP);
    }

    private static int severity(HealthCheck.Status status) {
        return switch (status) {
            case UP -> 0;
            case DEGRADED -> 1;
            case DOWN -> 2;
        };
    }

    private HealthCheck databaseCheck() {
        try {
            entityManager.createNativeQuery("SELECT 1").getSingleResult();
            return check(DATABASE_CHECK, HealthCheck.Status.UP, null);
        } catch (RuntimeException e) {
            return check(DATABASE_CHECK, HealthCheck.Status.DOWN, "Database query failed: " + e.getMessage());
        }
    }

    private HealthCheck eventSourcesCheck() {
        try {
            List<String> failing = eventSourceHealthService.computeAll(false).stream()
                    .filter(h -> h.enabled() && EventSourceHealthService.FAILING.equals(h.state()))
                    .map(EventSourceHealthService.Health::name)
                    .toList();
            if (failing.isEmpty()) {
                return check(EVENT_SOURCES_CHECK, HealthCheck.Status.UP, null);
            }
            return check(EVENT_SOURCES_CHECK, HealthCheck.Status.DEGRADED,
                    failing.size() + " event source(s) failing: " + String.join(", ", failing));
        } catch (RuntimeException e) {
            return check(EVENT_SOURCES_CHECK, HealthCheck.Status.DOWN,
                    "Could not read event source health: " + e.getMessage());
        }
    }

    private HealthCheck enginesCheck() {
        String defaultType;
        try {
            defaultType = resolveDefaultEngineType();
        } catch (RuntimeException e) {
            defaultType = agentRegistry.getDefaultAgentType();
        }
        List<String> errors = startupCheckService.getResultsForEngine(defaultType).stream()
                .filter(r -> "error".equals(r.status()))
                .map(r -> r.name() + (r.message() != null ? ": " + r.message() : ""))
                .toList();
        if (errors.isEmpty()) {
            return check(ENGINES_CHECK, HealthCheck.Status.UP, null);
        }
        return check(ENGINES_CHECK, HealthCheck.Status.DEGRADED,
                "Default engine '" + defaultType + "' failed startup checks: " + String.join("; ", errors));
    }

    private static HealthCheck check(String name, HealthCheck.Status status, String message) {
        HealthCheck check = new HealthCheck();
        check.setName(name);
        check.setStatus(status);
        check.setMessage(message);
        return check;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SystemConfig getSystemConfig() {
        SystemConfig config = new SystemConfig();
        config.setVersion(applicationVersion);

        String defaultType = resolveDefaultEngineType();
        config.setDefaultEngine(defaultType);
        config.setFeatures(new Features());

        // Build per-engine info
        List<EngineInfo> engineInfos = new ArrayList<>();
        for (Agent agent : agentRegistry.getAllAgents()) {
            EngineInfo info = new EngineInfo();
            info.setType(agent.getType());
            info.setLabel(agent.getLabel());
            info.setSupportsInteractiveSessions(agent.supportsInteractiveSessions());
            info.setModels(agent.getAvailableModels());
            info.setModelSource(EngineInfo.ModelSource.fromValue(agent.getModelSource()));

            List<StartupCheckService.CheckResult> engineChecks =
                    startupCheckService.getResultsForEngine(agent.getType());
            List<StartupCheck> checks = engineChecks.stream()
                    .map(r -> {
                        StartupCheck check = new StartupCheck();
                        check.setName(r.name());
                        check.setStatus(StartupCheck.Status.fromValue(r.status()));
                        check.setMessage(r.message());
                        return check;
                    })
                    .toList();
            info.setChecks(checks);
            info.setAvailable(checks.stream().noneMatch(
                    c -> c.getStatus() == StartupCheck.Status.ERROR));

            engineInfos.add(info);
        }
        config.setEngines(engineInfos);

        // Flat checks list (backwards compat)
        config.setChecks(startupCheckService.getResults().stream()
                .map(r -> {
                    StartupCheck check = new StartupCheck();
                    check.setName(r.name());
                    check.setStatus(StartupCheck.Status.fromValue(r.status()));
                    check.setMessage(r.message());
                    return check;
                })
                .toList());
        return config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public SystemConfig updateSystemConfig(UpdateSystemConfigRequest data) {
        if (data == null || data.getDefaultEngine() == null
                || data.getDefaultEngine().isBlank()) {
            throw new WebApplicationException(
                    "Missing required 'defaultEngine' field", 400);
        }

        String defaultEngine = data.getDefaultEngine().trim();
        Agent resolvedAgent = agentRegistry.getAllAgents().stream()
                .filter(agent -> defaultEngine.equals(agent.getType()))
                .findFirst()
                .orElse(null);
        if (resolvedAgent == null) {
            throw new WebApplicationException(
                    "Unknown engine type: " + defaultEngine, 400);
        }

        SystemConfigEntity entity = SystemConfigEntity.<SystemConfigEntity>findAll().firstResult();
        if (entity == null) {
            entity = new SystemConfigEntity();
        }
        entity.defaultEngine = defaultEngine;
        entity.persist();
        agentRegistry.setDefaultAgentType(defaultEngine);
        return getSystemConfig();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> listEngines() {
        return agentRegistry.getAvailableTypes();
    }

    /**
     * {@inheritDoc}
     *
     * Returns the available models for the specified engine, or the default
     * engine if not specified. Delegates to the Agent SPI's
     * {@code getAvailableModels()} method.
     */
    @Override
    public List<String> listModels(String engine) {
        Agent agent = (engine != null && !engine.isBlank())
                ? agentRegistry.getAgent(engine)
                : agentRegistry.getDefaultAgent();
        return agent.getAvailableModels();
    }

    private String resolveDefaultEngineType() {
        SystemConfigEntity entity = SystemConfigEntity.<SystemConfigEntity>findAll().firstResult();
        if (entity != null && entity.defaultEngine != null && !entity.defaultEngine.isBlank()) {
            return entity.defaultEngine;
        }
        return agentRegistry.getDefaultAgentType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Response exportPack(PackExportRequest data) {
        if (data == null || data.getName() == null || data.getName().isBlank()) {
            throw new WebApplicationException(
                    "Missing required 'name' field", 400);
        }
        JsonNode pack = importExportService.exportPack(data);
        String filename = data.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".json";
        return Response.ok(pack)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ImportResult importPack(InputStream data) {
        try {
            JsonNode pack = packObjectMapper.readTree(data);
            return importExportService.importPack(pack);
        } catch (ConfigValidationException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Configuration pack has invalid items");
            body.put("problems", e.problemsByItem());
            throw new WebApplicationException(
                    Response.status(400).entity(body).build());
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException(
                    "Failed to parse pack JSON: " + e.getMessage(), 400);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RetentionConfig getRetentionConfig() {
        RetentionConfigEntity entity = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();

        if (entity == null) {
            entity = new RetentionConfigEntity();
            entity.inactiveSubjectRetentionDays = 90;
            entity.traceRetentionDays = 30;
            entity.eventRetentionDays = 90;
        }
        return toRetentionBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public RetentionConfig updateRetentionConfig(RetentionConfig data) {
        RetentionConfigEntity entity = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (entity == null) {
            entity = new RetentionConfigEntity();
            entity.inactiveSubjectRetentionDays = 90;
            entity.traceRetentionDays = 30;
            entity.eventRetentionDays = 90;
        }
        validateRetentionDays(data);
        // Every setting is optional: a null field keeps its current value.
        if (data.getInactiveSubjectRetentionDays() != null) {
            entity.inactiveSubjectRetentionDays = data.getInactiveSubjectRetentionDays();
        }
        if (data.getTraceRetentionDays() != null) {
            entity.traceRetentionDays = data.getTraceRetentionDays();
        }
        if (data.getEventRetentionDays() != null) {
            entity.eventRetentionDays = data.getEventRetentionDays();
        }
        if (data.getWorkflowRunRetentionDays() != null) {
            entity.workflowRunRetentionDays = data.getWorkflowRunRetentionDays();
        }
        if (data.getActivityLogRetentionDays() != null) {
            entity.activityLogRetentionDays = data.getActivityLogRetentionDays();
        }
        if (data.getAiUsageRetentionDays() != null) {
            entity.aiUsageRetentionDays = data.getAiUsageRetentionDays();
        }
        entity.persist();

        return toRetentionBean(entity);
    }

    private static RetentionConfig toRetentionBean(RetentionConfigEntity entity) {
        RetentionConfig config = new RetentionConfig();
        config.setInactiveSubjectRetentionDays(entity.inactiveSubjectRetentionDays);
        config.setTraceRetentionDays(entity.traceRetentionDays);
        config.setEventRetentionDays(entity.eventRetentionDays);
        config.setWorkflowRunRetentionDays(entity.workflowRunRetentionDays);
        config.setActivityLogRetentionDays(entity.activityLogRetentionDays);
        config.setAiUsageRetentionDays(entity.aiUsageRetentionDays);
        return config;
    }

    private static void validateRetentionDays(RetentionConfig data) {
        // 0 would delete everything for these settings, so at least 1 day is required.
        Integer[] coreValues = {data.getInactiveSubjectRetentionDays(), data.getTraceRetentionDays(),
            data.getEventRetentionDays()};
        for (Integer value : coreValues) {
            if (value != null && value < 1) {
                throw new WebApplicationException(
                        "Inactive subject, trace and event retention must be at least 1 day", 400);
            }
        }
        Integer[] values = {data.getWorkflowRunRetentionDays(), data.getActivityLogRetentionDays(),
            data.getAiUsageRetentionDays()};
        for (Integer value : values) {
            if (value != null && value < 0) {
                throw new WebApplicationException(
                        "Retention days must be 0 (keep forever) or greater", 400);
            }
        }
    }
}
