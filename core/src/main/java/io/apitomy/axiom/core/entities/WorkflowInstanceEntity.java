package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_instance")
public class WorkflowInstanceEntity extends PanacheEntity {

    @Column(name = "project_id", nullable = false, unique = true)
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

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
