package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a kind of work that can be performed within the system.
 */
@Entity
@Table(name = "action_type")
public class ActionTypeEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "execution_mode", nullable = false)
    public String executionMode;

    @Column(name = "user_triggerable", nullable = false)
    public boolean userTriggerable;

    @Column(name = "workflow_enabled", nullable = false)
    public boolean workflowEnabled;

    @Column(name = "allowed_tools", columnDefinition = "TEXT")
    public String allowedTools;

    @Column(name = "prompt_template", columnDefinition = "TEXT")
    public String promptTemplate;

    /**
     * Bash script template for script-mode action types.
     * Supports placeholders like {{projectId}}, {{apiBaseUrl}}, etc.
     */
    @Column(name = "script_template", columnDefinition = "TEXT")
    public String scriptTemplate;

    /**
     * Optional AI model override (e.g. "claude-sonnet-4-6").
     * When null, the global default model is used.
     */
    @Column(name = "model")
    public String model;

    /**
     * Optional AI engine override (e.g. "opencode", "claude-code").
     * When null, the global default engine ({@code axiom.ai-engine}) is used.
     * This allows different action types to use different AI engines.
     */
    @Column(name = "engine")
    public String engine;

    /**
     * Optional max steps/turns override for this action type.
     * When null, the global default max-steps is used.
     */
    @Column(name = "max_steps")
    public Integer maxSteps;

    /**
     * Optional max budget (USD) override for this action type.
     * When null, the global default max-budget-usd is used.
     */
    @Column(name = "max_budget_usd")
    public Double maxBudgetUsd;

    /**
     * Optional timeout override in seconds for agent execution.
     * When null, the global default (120s) is used.
     */
    @Column(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Column(name = "manager_triggerable", nullable = false)
    public boolean managerTriggerable;

    /**
     * Optional JSON object of environment variables for the subprocess.
     * Values can reference secrets using ${secret:NAME} syntax.
     * When set, replaces the default all-secrets injection.
     */
    @Column(columnDefinition = "TEXT")
    public String environment;

    @Column(name = "emits_event", nullable = false)
    public boolean emitsEvent;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_type_label", joinColumns = @JoinColumn(name = "action_type_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_type_input", joinColumns = @JoinColumn(name = "action_type_id"))
    @OrderColumn(name = "ordinal")
    public List<ActionTypeField> inputs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_type_output", joinColumns = @JoinColumn(name = "action_type_id"))
    @OrderColumn(name = "ordinal")
    public List<ActionTypeField> outputs = new ArrayList<>();
}
