package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A parked receive-event branch of a running {@link WorkflowRunEntity}. Exists
 * from the moment a workflow instance enters a {@code RECEIVE_EVENT} node until
 * a matching event arrives and the dispatcher resumes the branch, at which
 * point the row is deleted. {@code eventType} and {@code projectId} are
 * denormalized (from node config and the owning run respectively) so the
 * dispatcher can prefilter candidates cheaply.
 */
@Entity
@Table(name = "workflow_event_subscription")
public class WorkflowEventSubscriptionEntity extends PanacheEntity {

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "node_id", nullable = false)
    public String nodeId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
