package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.Features;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.api.beans.RetentionConfig;
import io.apitomy.axiom.api.beans.StartupCheck;
import io.apitomy.axiom.api.beans.SystemConfig;
import io.apitomy.axiom.api.beans.SystemHealth;
import io.apitomy.axiom.api.SystemResource;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.StartupCheckService;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import jakarta.transaction.Transactional;
import java.io.InputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of the System API endpoints.
 */
@ApplicationScoped
@RunOnVirtualThread
public class SystemResourceImpl implements SystemResource {

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "1.0.0-SNAPSHOT")
    String applicationVersion;

    @ConfigProperty(name = "axiom.claude-code.available-models",
            defaultValue = "claude-opus-4-7,claude-sonnet-4-6,claude-opus-4-6,claude-haiku-4-5-20251001,opus,sonnet,haiku")
    String claudeAvailableModels;

    @ConfigProperty(name = "axiom.opencode.available-models",
            defaultValue = "anthropic/claude-sonnet-4-6,anthropic/claude-opus-4-6,anthropic/claude-haiku-4-5-20251001,openai/gpt-4o,openai/o3-mini")
    String openCodeAvailableModels;

    @Inject
    AiEngine aiEngine;

    @Inject
    AiEngineRegistry engineRegistry;

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
        config.setEngine(aiEngine.getType());
        config.setFeatures(new Features());
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
    public List<String> listEngines() {
        return engineRegistry.getAvailableEngineTypes();
    }

    /**
     * {@inheritDoc}
     *
     * Returns the available models for the specified engine, or the default
     * engine if not specified. Claude Code returns short model names;
     * OpenCode returns provider/model format.
     */
    @Override
    public List<String> listModels(String engine) {
        String engineType = (engine != null && !engine.isBlank()) ? engine : aiEngine.getType();
        String models = "opencode".equals(engineType)
                ? openCodeAvailableModels
                : claudeAvailableModels;

        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Response exportPack(PackExportRequest data) {
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

        RetentionConfig config = new RetentionConfig();
        if (entity != null) {
            config.setClosedProjectRetentionDays(entity.closedProjectRetentionDays);
            config.setTraceRetentionDays(entity.traceRetentionDays);
            config.setEventRetentionDays(entity.eventRetentionDays);
            config.setEventSourceLogRetentionDays(entity.eventSourceLogRetentionDays);
        } else {
            config.setClosedProjectRetentionDays(90);
            config.setTraceRetentionDays(30);
            config.setEventRetentionDays(90);
            config.setEventSourceLogRetentionDays(7);
        }
        return config;
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
        }
        entity.closedProjectRetentionDays = data.getClosedProjectRetentionDays();
        entity.traceRetentionDays = data.getTraceRetentionDays();
        entity.eventRetentionDays = data.getEventRetentionDays();
        entity.eventSourceLogRetentionDays = data.getEventSourceLogRetentionDays();
        entity.persist();

        return data;
    }
}
