package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Stores runtime-configurable system settings. This is a single-row table.
 */
@Entity
@Table(name = "system_settings")
public class SystemSettingsEntity extends PanacheEntity {

    @Column(name = "manager_max_turns", nullable = false)
    public int managerMaxTurns;

    @Column(name = "manager_confidence_threshold", nullable = false)
    public double managerConfidenceThreshold;

    @Column(name = "manager_timeout_seconds", nullable = false)
    public int managerTimeoutSeconds;

    @Column(name = "manager_model")
    public String managerModel;

    @Column(name = "claude_code_max_turns", nullable = false)
    public int claudeCodeMaxTurns;

    @Column(name = "claude_code_max_budget_usd", nullable = false)
    public double claudeCodeMaxBudgetUsd;

    @Column(name = "claude_code_timeout_seconds", nullable = false)
    public int claudeCodeTimeoutSeconds;

    @Column(name = "claude_code_model")
    public String claudeCodeModel;

    @Column(name = "claude_code_available_models", columnDefinition = "TEXT", nullable = false)
    public String claudeCodeAvailableModels;

    @Column(name = "opencode_max_steps", nullable = false)
    public int opencodeMaxSteps;

    @Column(name = "opencode_timeout_seconds", nullable = false)
    public int opencodeTimeoutSeconds;

    @Column(name = "opencode_model")
    public String opencodeModel;

    @Column(name = "opencode_available_models", columnDefinition = "TEXT", nullable = false)
    public String opencodeAvailableModels;

    @Column(name = "assistant_max_sessions", nullable = false)
    public int assistantMaxSessions;

    @Column(name = "assistant_timeout_seconds", nullable = false)
    public int assistantTimeoutSeconds;

    @Column(name = "ai_engine", nullable = false)
    public String aiEngine;

    @Column(name = "event_source_log_retention_days", nullable = false)
    public int eventSourceLogRetentionDays;

    @Column(name = "script_timeout_seconds", nullable = false)
    public int scriptTimeoutSeconds;
}
