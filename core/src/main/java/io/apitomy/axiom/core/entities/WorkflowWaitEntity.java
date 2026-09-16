package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A parked Wait-node branch of a running {@link WorkflowRunEntity}. Exists
 * from the moment a workflow instance enters a {@code WAIT} node until
 * {@code resumeAt} elapses and {@link io.apitomy.axiom.app.WorkflowWaitScheduler}
 * resumes the branch, at which point the row is deleted.
 */
@Entity
@Table(name = "workflow_wait")
public class WorkflowWaitEntity extends PanacheEntity {

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "node_id", nullable = false)
    public String nodeId;

    @Column(name = "resume_at", nullable = false)
    public Instant resumeAt;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
