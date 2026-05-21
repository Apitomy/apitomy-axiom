package io.apicurio.axiom.app.rest;

import io.apicurio.axiom.api.beans.Features;
import io.apicurio.axiom.api.beans.StartupCheck;
import io.apicurio.axiom.api.beans.SystemConfig;
import io.apicurio.axiom.api.beans.SystemHealth;
import io.apicurio.axiom.api.SystemResource;
import io.apicurio.axiom.app.StartupCheckService;
import io.apicurio.axiom.engine.spi.AiEngine;
import io.apicurio.axiom.engine.spi.AiEngineRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    @ConfigProperty(name = "axiom.bob.available-models",
            defaultValue = "granite,llama,mistral")
    String bobAvailableModels;

    @Inject
    AiEngine aiEngine;

    @Inject
    AiEngineRegistry engineRegistry;

    @Inject
    StartupCheckService startupCheckService;

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
        String models;
        if ("opencode".equals(engineType)) {
            models = openCodeAvailableModels;
        } else if ("bob".equals(engineType)) {
            models = bobAvailableModels;
        } else {
            models = claudeAvailableModels;
        }

        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
