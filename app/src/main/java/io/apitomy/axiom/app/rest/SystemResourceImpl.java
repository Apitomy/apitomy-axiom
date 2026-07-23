package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.Features;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.api.beans.StartupCheck;
import io.apitomy.axiom.api.beans.SystemConfig;
import io.apitomy.axiom.api.beans.SystemHealth;
import io.apitomy.axiom.api.beans.SystemSettings;
import io.apitomy.axiom.api.SystemResource;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.StartupCheckService;
import io.apitomy.axiom.core.entities.SystemSettingsEntity;
import io.apitomy.axiom.core.services.SystemSettingsService;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
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
    SystemSettingsService settingsService;

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
                ? settingsService.getOpencodeAvailableModels()
                : settingsService.getClaudeCodeAvailableModels();

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
    public SystemSettings getSystemSettings() {
        return toBean(settingsService);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public SystemSettings updateSystemSettings(SystemSettings data) {
        validateSettings(data);

        SystemSettingsEntity entity = SystemSettingsEntity.<SystemSettingsEntity>findAll()
                .firstResult();
        if (entity == null) {
            entity = new SystemSettingsEntity();
        }

        if (data.getManagerMaxTurns() != null) {
            entity.managerMaxTurns = data.getManagerMaxTurns().intValue();
        }
        if (data.getManagerConfidenceThreshold() != null) {
            entity.managerConfidenceThreshold = data.getManagerConfidenceThreshold().doubleValue();
        }
        if (data.getManagerTimeoutSeconds() != null) {
            entity.managerTimeoutSeconds = data.getManagerTimeoutSeconds().intValue();
        }
        if (data.getManagerModel() != null) {
            entity.managerModel = data.getManagerModel().isBlank() ? null : data.getManagerModel();
        }
        if (data.getClaudeCodeMaxTurns() != null) {
            entity.claudeCodeMaxTurns = data.getClaudeCodeMaxTurns().intValue();
        }
        if (data.getClaudeCodeMaxBudgetUsd() != null) {
            entity.claudeCodeMaxBudgetUsd = data.getClaudeCodeMaxBudgetUsd().doubleValue();
        }
        if (data.getClaudeCodeTimeoutSeconds() != null) {
            entity.claudeCodeTimeoutSeconds = data.getClaudeCodeTimeoutSeconds().intValue();
        }
        if (data.getClaudeCodeModel() != null) {
            entity.claudeCodeModel = data.getClaudeCodeModel().isBlank() ? null : data.getClaudeCodeModel();
        }
        if (data.getClaudeCodeAvailableModels() != null) {
            entity.claudeCodeAvailableModels = data.getClaudeCodeAvailableModels();
        }
        if (data.getOpencodeMaxSteps() != null) {
            entity.opencodeMaxSteps = data.getOpencodeMaxSteps().intValue();
        }
        if (data.getOpencodeTimeoutSeconds() != null) {
            entity.opencodeTimeoutSeconds = data.getOpencodeTimeoutSeconds().intValue();
        }
        if (data.getOpencodeModel() != null) {
            entity.opencodeModel = data.getOpencodeModel().isBlank() ? null : data.getOpencodeModel();
        }
        if (data.getOpencodeAvailableModels() != null) {
            entity.opencodeAvailableModels = data.getOpencodeAvailableModels();
        }
        if (data.getAssistantMaxSessions() != null) {
            entity.assistantMaxSessions = data.getAssistantMaxSessions().intValue();
        }
        if (data.getAssistantTimeoutSeconds() != null) {
            entity.assistantTimeoutSeconds = data.getAssistantTimeoutSeconds().intValue();
        }
        if (data.getAiEngine() != null) {
            entity.aiEngine = data.getAiEngine();
        }
        if (data.getEventSourceLogRetentionDays() != null) {
            entity.eventSourceLogRetentionDays = data.getEventSourceLogRetentionDays().intValue();
        }
        if (data.getScriptTimeoutSeconds() != null) {
            entity.scriptTimeoutSeconds = data.getScriptTimeoutSeconds().intValue();
        }

        settingsService.update(entity);
        return toBean(settingsService);
    }

    private SystemSettings toBean(SystemSettingsService svc) {
        SystemSettings bean = new SystemSettings();
        bean.setManagerMaxTurns(svc.getManagerMaxTurns());
        bean.setManagerConfidenceThreshold(svc.getManagerConfidenceThreshold());
        bean.setManagerTimeoutSeconds(svc.getManagerTimeoutSeconds());
        bean.setManagerModel(svc.getManagerModel());
        bean.setClaudeCodeMaxTurns(svc.getClaudeCodeMaxTurns());
        bean.setClaudeCodeMaxBudgetUsd(svc.getClaudeCodeMaxBudgetUsd());
        bean.setClaudeCodeTimeoutSeconds(svc.getClaudeCodeTimeoutSeconds());
        bean.setClaudeCodeModel(svc.getClaudeCodeModel());
        bean.setClaudeCodeAvailableModels(svc.getClaudeCodeAvailableModels());
        bean.setOpencodeMaxSteps(svc.getOpencodeMaxSteps());
        bean.setOpencodeTimeoutSeconds(svc.getOpencodeTimeoutSeconds());
        bean.setOpencodeModel(svc.getOpencodeModel());
        bean.setOpencodeAvailableModels(svc.getOpencodeAvailableModels());
        bean.setAssistantMaxSessions(svc.getAssistantMaxSessions());
        bean.setAssistantTimeoutSeconds(svc.getAssistantTimeoutSeconds());
        bean.setAiEngine(svc.getAiEngine());
        bean.setEventSourceLogRetentionDays(svc.getEventSourceLogRetentionDays());
        bean.setScriptTimeoutSeconds(svc.getScriptTimeoutSeconds());
        return bean;
    }

    private void validateSettings(SystemSettings data) {
        if (data.getManagerMaxTurns() != null && data.getManagerMaxTurns() < 1) {
            throw new WebApplicationException("managerMaxTurns must be >= 1", 400);
        }
        if (data.getManagerConfidenceThreshold() != null
                && (data.getManagerConfidenceThreshold() < 0 || data.getManagerConfidenceThreshold() > 1)) {
            throw new WebApplicationException("managerConfidenceThreshold must be between 0 and 1", 400);
        }
        if (data.getManagerTimeoutSeconds() != null && data.getManagerTimeoutSeconds() < 1) {
            throw new WebApplicationException("managerTimeoutSeconds must be >= 1", 400);
        }
        if (data.getClaudeCodeMaxTurns() != null && data.getClaudeCodeMaxTurns() < 1) {
            throw new WebApplicationException("claudeCodeMaxTurns must be >= 1", 400);
        }
        if (data.getClaudeCodeMaxBudgetUsd() != null && data.getClaudeCodeMaxBudgetUsd() < 0) {
            throw new WebApplicationException("claudeCodeMaxBudgetUsd must be >= 0", 400);
        }
        if (data.getClaudeCodeTimeoutSeconds() != null && data.getClaudeCodeTimeoutSeconds() < 1) {
            throw new WebApplicationException("claudeCodeTimeoutSeconds must be >= 1", 400);
        }
        if (data.getOpencodeMaxSteps() != null && data.getOpencodeMaxSteps() < 1) {
            throw new WebApplicationException("opencodeMaxSteps must be >= 1", 400);
        }
        if (data.getOpencodeTimeoutSeconds() != null && data.getOpencodeTimeoutSeconds() < 1) {
            throw new WebApplicationException("opencodeTimeoutSeconds must be >= 1", 400);
        }
        if (data.getAssistantMaxSessions() != null && data.getAssistantMaxSessions() < 1) {
            throw new WebApplicationException("assistantMaxSessions must be >= 1", 400);
        }
        if (data.getAssistantTimeoutSeconds() != null && data.getAssistantTimeoutSeconds() < 1) {
            throw new WebApplicationException("assistantTimeoutSeconds must be >= 1", 400);
        }
        if (data.getEventSourceLogRetentionDays() != null && data.getEventSourceLogRetentionDays() < 1) {
            throw new WebApplicationException("eventSourceLogRetentionDays must be >= 1", 400);
        }
        if (data.getScriptTimeoutSeconds() != null && data.getScriptTimeoutSeconds() < 1) {
            throw new WebApplicationException("scriptTimeoutSeconds must be >= 1", 400);
        }
    }
}
