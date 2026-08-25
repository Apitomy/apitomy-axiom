package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Records a single AI invocation (Claude Code subprocess execution) with
 * cost, token, and metadata for aggregation and reporting.
 *
 * <p>Every time Claude Code is invoked — whether for a task execution or
 * a Manager evaluation — a row is written to this table. The metadata
 * columns allow querying and aggregating by agent, project, action type,
 * invocation type, and time range.</p>
 */
@Entity
@Table(name = "ai_usage")
public class AiUsageEntity extends PanacheEntity {

    /**
     * Type of invocation: "task" or "manager".
     */
    @Column(name = "invocation_type", nullable = false)
    public String invocationType;

    /**
     * Task ID (set for task executions, null for manager evaluations).
     */
    @Column(name = "task_id")
    public Long taskId;

    /**
     * Event ID (set for manager evaluations, may also be set for tasks).
     */
    @Column(name = "event_id")
    public Long eventId;

    /**
     * Project ID (if associated with a project).
     */
    @Column(name = "project_id")
    public Long projectId;

    /**
     * Agent ID (the AI agent that performed the invocation).
     */
    @Column(name = "agent_id")
    public Long agentId;

    /**
     * Action type name (e.g. "analyze", "answer-question", "manager-evaluate").
     */
    @Column(name = "action_type")
    public String actionType;

    /**
     * The AI model used (e.g. "claude-sonnet-4-6").
     */
    public String model;

    /**
     * Cost of the invocation in USD.
     */
    @Column(name = "cost_usd")
    public Double costUsd;

    /**
     * Number of input tokens consumed.
     */
    @Column(name = "input_tokens")
    public Long inputTokens;

    /**
     * Number of output tokens produced.
     */
    @Column(name = "output_tokens")
    public Long outputTokens;

    /**
     * Duration of the invocation in milliseconds.
     */
    @Column(name = "duration_ms")
    public Long durationMs;

    /**
     * When the invocation was recorded.
     */
    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
