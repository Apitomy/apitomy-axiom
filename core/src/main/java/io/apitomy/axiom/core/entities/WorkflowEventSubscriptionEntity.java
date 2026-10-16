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
 * point the row is deleted. {@code eventType} and {@code subjectId} are
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

    @Column(name = "subject_id", nullable = false)
    public Long subjectId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    /**
     * The node's evaluated correlation subscription key ({@code correlationKey.subscriptionKey}
     * evaluated against the context when the branch parked); null when the node has no
     * correlation key. Only events whose evaluated event key equals it can match.
     */
    @Column(name = "correlation_key", length = 1024)
    public String correlationKey;

    /** When the branch times out and follows its timeout edge; null when the node has no timeout. */
    @Column(name = "timeout_at")
    public Instant timeoutAt;

    /**
     * The start of the look-back window for events that arrived before the branch parked; null
     * once the look-back has run, or when the node's lookback is {@code none}.
     */
    @Column(name = "lookback_from")
    public Instant lookbackFrom;
}
