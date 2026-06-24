package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Detailed record of an MCP tool invocation, storing the full JSON input and
 * output. Referenced by a {@link TraceNodeEntity} via
 * {@code entityType="tool-execution"}.
 */
@Entity
@Table(name = "tool_execution")
public class ToolExecutionEntity extends PanacheEntity {

    @Column(name = "trace_id", nullable = false)
    public UUID traceId;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    @Column(name = "tool_input", columnDefinition = "TEXT")
    public String toolInput;

    @Column(name = "tool_output", columnDefinition = "TEXT")
    public String toolOutput;

    @Column(nullable = false)
    public String status;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
