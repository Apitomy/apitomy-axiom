package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single execution (run) of a workflow definition against a project.
 * Runs accumulate as history; at most one non-terminal run exists per project.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRunEntity extends PanacheEntity {

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(name = "definition_version", nullable = false)
    public int definitionVersion;

    @Column(name = "instance_state", columnDefinition = "TEXT", nullable = false)
    public String instanceState;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(name = "current_node_id")
    public String currentNodeId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    public String failureReason;

    /** Links this run to its execution trace; null if trace creation failed. */
    @Column(name = "trace_id")
    public UUID traceId;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
