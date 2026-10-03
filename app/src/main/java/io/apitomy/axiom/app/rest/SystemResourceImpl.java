package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.EngineInfo;
import io.apitomy.axiom.api.beans.Features;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.api.beans.RetentionConfig;
import io.apitomy.axiom.api.beans.StartupCheck;
import io.apitomy.axiom.api.beans.SystemConfig;
import io.apitomy.axiom.api.beans.SystemHealth;
import io.apitomy.axiom.api.beans.UpdateSystemConfigRequest;
import io.apitomy.axiom.api.SystemResource;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.StartupCheckService;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.SystemConfigEntity;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import jakarta.transaction.Transactional;
import java.io.InputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

    /**
     * {@inheritDoc}
     */
    @Override
    public SystemHealth getSystemHealth() {
        SystemHealth health = new SystemHealth();
        health.setStatus(SystemHealth.Status.UP);
        health.setVersion(applicationVersion);
        health.setTimestamp(new Date());
        return health;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SystemConfig getSystemConfig() {
        SystemConfig config = new SystemConfig();
        config.setVersion(applicationVersion);

        String defaultType = resolveDefaultEngineType();
        config.setEngine(defaultType);
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
            throw new jakarta.ws.rs.WebApplicationException(
                    "Missing required 'defaultEngine' field", 400);
        }

        String defaultEngine = data.getDefaultEngine().trim();
        Agent resolvedAgent = agentRegistry.getAllAgents().stream()
                .filter(agent -> defaultEngine.equals(agent.getType()))
                .findFirst()
                .orElse(null);
        if (resolvedAgent == null) {
            throw new jakarta.ws.rs.WebApplicationException(
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
            throw new jakarta.ws.rs.WebApplicationException(
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
        } catch (jakarta.ws.rs.WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new jakarta.ws.rs.WebApplicationException(
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
            entity.closedProjectRetentionDays = 90;
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
            entity.closedProjectRetentionDays = 90;
            entity.traceRetentionDays = 30;
            entity.eventRetentionDays = 90;
        }
        validateRetentionDays(data);
        // Every setting is optional: a null field keeps its current value.
        if (data.getClosedProjectRetentionDays() != null) {
            entity.closedProjectRetentionDays = data.getClosedProjectRetentionDays();
        }
        if (data.getTraceRetentionDays() != null) {
            entity.traceRetentionDays = data.getTraceRetentionDays();
        }
        if (data.getEventRetentionDays() != null) {
            entity.eventRetentionDays = data.getEventRetentionDays();
        }
        if (data.getScheduledJobRunRetentionDays() != null) {
            entity.scheduledJobRunRetentionDays = data.getScheduledJobRunRetentionDays();
        }
        if (data.getReportRetentionDays() != null) {
            entity.reportRetentionDays = data.getReportRetentionDays();
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
        config.setClosedProjectRetentionDays(entity.closedProjectRetentionDays);
        config.setTraceRetentionDays(entity.traceRetentionDays);
        config.setEventRetentionDays(entity.eventRetentionDays);
        config.setScheduledJobRunRetentionDays(entity.scheduledJobRunRetentionDays);
        config.setReportRetentionDays(entity.reportRetentionDays);
        config.setWorkflowRunRetentionDays(entity.workflowRunRetentionDays);
        config.setActivityLogRetentionDays(entity.activityLogRetentionDays);
        config.setAiUsageRetentionDays(entity.aiUsageRetentionDays);
        return config;
    }

    private static void validateRetentionDays(RetentionConfig data) {
        // 0 would delete everything for these settings, so at least 1 day is required.
        Integer[] coreValues = {data.getClosedProjectRetentionDays(), data.getTraceRetentionDays(),
            data.getEventRetentionDays()};
        for (Integer value : coreValues) {
            if (value != null && value < 1) {
                throw new WebApplicationException(
                        "Closed project, trace and event retention must be at least 1 day", 400);
            }
        }
        Integer[] values = {data.getScheduledJobRunRetentionDays(), data.getReportRetentionDays(),
            data.getWorkflowRunRetentionDays(), data.getActivityLogRetentionDays(),
            data.getAiUsageRetentionDays()};
        for (Integer value : values) {
            if (value != null && value < 0) {
                throw new WebApplicationException(
                        "Retention days must be 0 (keep forever) or greater", 400);
            }
        }
    }
}
