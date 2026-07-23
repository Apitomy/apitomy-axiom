package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.SystemSettingsEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Provides access to runtime-configurable system settings stored in the database.
 * Caches the single settings row in memory and invalidates on updates.
 */
@ApplicationScoped
public class SystemSettingsService {

    private static final Logger LOG = Logger.getLogger(SystemSettingsService.class);

    private volatile SystemSettingsEntity cached;

    // Fallback defaults from @ConfigProperty (used if DB row is missing)
    @ConfigProperty(name = "axiom.manager.max-turns", defaultValue = "5")
    int defaultManagerMaxTurns;

    @ConfigProperty(name = "axiom.manager.confidence-threshold", defaultValue = "0.7")
    double defaultManagerConfidenceThreshold;

    @ConfigProperty(name = "axiom.manager.timeout-seconds", defaultValue = "120")
    int defaultManagerTimeoutSeconds;

    @ConfigProperty(name = "axiom.manager.model")
    Optional<String> defaultManagerModel;

    @ConfigProperty(name = "axiom.claude-code.max-turns", defaultValue = "50")
    int defaultClaudeCodeMaxTurns;

    @ConfigProperty(name = "axiom.claude-code.max-budget-usd", defaultValue = "5.0")
    double defaultClaudeCodeMaxBudgetUsd;

    @ConfigProperty(name = "axiom.claude-code.timeout-seconds", defaultValue = "600")
    int defaultClaudeCodeTimeoutSeconds;

    @ConfigProperty(name = "axiom.claude-code.model")
    Optional<String> defaultClaudeCodeModel;

    @ConfigProperty(name = "axiom.claude-code.available-models",
            defaultValue = "claude-opus-4-7,claude-sonnet-4-6,claude-opus-4-6,claude-haiku-4-5-20251001,opus,sonnet,haiku")
    String defaultClaudeCodeAvailableModels;

    @ConfigProperty(name = "axiom.opencode.max-steps", defaultValue = "50")
    int defaultOpencodeMaxSteps;

    @ConfigProperty(name = "axiom.opencode.timeout-seconds", defaultValue = "600")
    int defaultOpencodeTimeoutSeconds;

    @ConfigProperty(name = "axiom.opencode.model")
    Optional<String> defaultOpencodeModel;

    @ConfigProperty(name = "axiom.opencode.available-models",
            defaultValue = "anthropic/claude-sonnet-4-6,anthropic/claude-opus-4-6,anthropic/claude-haiku-4-5-20251001,openai/gpt-4o,openai/o3-mini")
    String defaultOpencodeAvailableModels;

    @ConfigProperty(name = "axiom.assistant.max-sessions", defaultValue = "3")
    int defaultAssistantMaxSessions;

    @ConfigProperty(name = "axiom.ai-assistant.timeout-seconds", defaultValue = "300")
    int defaultAssistantTimeoutSeconds;

    @ConfigProperty(name = "axiom.ai-engine", defaultValue = "claude-code")
    String defaultAiEngine;

    @ConfigProperty(name = "axiom.event-source-logs.retention-days", defaultValue = "7")
    int defaultEventSourceLogRetentionDays;

    @ConfigProperty(name = "axiom.script.timeout-seconds", defaultValue = "60")
    int defaultScriptTimeoutSeconds;

    /**
     * Eagerly loads the settings cache at startup.
     *
     * @param event the startup event
     */
    void onStartup(@Observes StartupEvent event) {
        load();
    }

    /**
     * Loads the settings row from the database into the cache.
     * Falls back to {@code @ConfigProperty} defaults if no row exists.
     */
    @Transactional
    public void load() {
        SystemSettingsEntity entity = SystemSettingsEntity.<SystemSettingsEntity>findAll()
                .firstResult();
        if (entity != null) {
            cached = entity;
            LOG.info("System settings loaded from database");
        } else {
            LOG.info("No system settings row found; using @ConfigProperty defaults");
            cached = null;
        }
    }

    /**
     * Updates the settings row in the database and refreshes the cache.
     *
     * @param entity the entity to persist (must have fields set)
     * @return the persisted entity
     */
    @Transactional
    public SystemSettingsEntity update(SystemSettingsEntity entity) {
        entity.persist();
        cached = entity;
        LOG.info("System settings updated");
        return entity;
    }

    /**
     * Returns the current settings entity, loading from DB if the cache is empty.
     *
     * @return the cached entity, or {@code null} if no DB row exists
     */
    public SystemSettingsEntity getCached() {
        return cached;
    }

    // ── Typed getters with fallback ─────────────────────────────────

    /**
     * Returns the Manager max turns setting.
     *
     * @return max turns
     */
    public int getManagerMaxTurns() {
        return cached != null ? cached.managerMaxTurns : defaultManagerMaxTurns;
    }

    /**
     * Returns the Manager confidence threshold setting.
     *
     * @return confidence threshold (0.0-1.0)
     */
    public double getManagerConfidenceThreshold() {
        return cached != null ? cached.managerConfidenceThreshold : defaultManagerConfidenceThreshold;
    }

    /**
     * Returns the Manager timeout setting in seconds.
     *
     * @return timeout seconds
     */
    public int getManagerTimeoutSeconds() {
        return cached != null ? cached.managerTimeoutSeconds : defaultManagerTimeoutSeconds;
    }

    /**
     * Returns the Manager model override, or {@code null} if not set.
     *
     * @return model name or null
     */
    public String getManagerModel() {
        if (cached != null) {
            return cached.managerModel;
        }
        return defaultManagerModel.orElse(null);
    }

    /**
     * Returns the Claude Code max turns setting.
     *
     * @return max turns
     */
    public int getClaudeCodeMaxTurns() {
        return cached != null ? cached.claudeCodeMaxTurns : defaultClaudeCodeMaxTurns;
    }

    /**
     * Returns the Claude Code max budget setting in USD.
     *
     * @return max budget
     */
    public double getClaudeCodeMaxBudgetUsd() {
        return cached != null ? cached.claudeCodeMaxBudgetUsd : defaultClaudeCodeMaxBudgetUsd;
    }

    /**
     * Returns the Claude Code timeout setting in seconds.
     *
     * @return timeout seconds
     */
    public int getClaudeCodeTimeoutSeconds() {
        return cached != null ? cached.claudeCodeTimeoutSeconds : defaultClaudeCodeTimeoutSeconds;
    }

    /**
     * Returns the Claude Code model override, or {@code null} if not set.
     *
     * @return model name or null
     */
    public String getClaudeCodeModel() {
        if (cached != null) {
            return cached.claudeCodeModel;
        }
        return defaultClaudeCodeModel.orElse(null);
    }

    /**
     * Returns the comma-separated list of available Claude Code models.
     *
     * @return available models string
     */
    public String getClaudeCodeAvailableModels() {
        return cached != null ? cached.claudeCodeAvailableModels : defaultClaudeCodeAvailableModels;
    }

    /**
     * Returns the OpenCode max steps setting.
     *
     * @return max steps
     */
    public int getOpencodeMaxSteps() {
        return cached != null ? cached.opencodeMaxSteps : defaultOpencodeMaxSteps;
    }

    /**
     * Returns the OpenCode timeout setting in seconds.
     *
     * @return timeout seconds
     */
    public int getOpencodeTimeoutSeconds() {
        return cached != null ? cached.opencodeTimeoutSeconds : defaultOpencodeTimeoutSeconds;
    }

    /**
     * Returns the OpenCode model override, or {@code null} if not set.
     *
     * @return model name or null
     */
    public String getOpencodeModel() {
        if (cached != null) {
            return cached.opencodeModel;
        }
        return defaultOpencodeModel.orElse(null);
    }

    /**
     * Returns the comma-separated list of available OpenCode models.
     *
     * @return available models string
     */
    public String getOpencodeAvailableModels() {
        return cached != null ? cached.opencodeAvailableModels : defaultOpencodeAvailableModels;
    }

    /**
     * Returns the maximum number of concurrent assistant sessions.
     *
     * @return max sessions
     */
    public int getAssistantMaxSessions() {
        return cached != null ? cached.assistantMaxSessions : defaultAssistantMaxSessions;
    }

    /**
     * Returns the assistant timeout setting in seconds.
     *
     * @return timeout seconds
     */
    public int getAssistantTimeoutSeconds() {
        return cached != null ? cached.assistantTimeoutSeconds : defaultAssistantTimeoutSeconds;
    }

    /**
     * Returns the active AI engine identifier.
     *
     * @return engine name (e.g. "claude-code", "opencode")
     */
    public String getAiEngine() {
        return cached != null ? cached.aiEngine : defaultAiEngine;
    }

    /**
     * Returns the event source log retention period in days.
     *
     * @return retention days
     */
    public int getEventSourceLogRetentionDays() {
        return cached != null ? cached.eventSourceLogRetentionDays : defaultEventSourceLogRetentionDays;
    }

    /**
     * Returns the script execution timeout in seconds.
     *
     * @return timeout seconds
     */
    public int getScriptTimeoutSeconds() {
        return cached != null ? cached.scriptTimeoutSeconds : defaultScriptTimeoutSeconds;
    }
}
