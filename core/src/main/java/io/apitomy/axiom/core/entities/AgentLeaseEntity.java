package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tracks which agent is currently executing which work item.
 * Used by the AgentPool for cross-workload busy-state tracking.
 */
@Entity
@Table(name = "agent_lease")
public class AgentLeaseEntity extends PanacheEntity {

    @Column(name = "agent_id", nullable = false)
    public Long agentId;

    @Column(name = "work_type", nullable = false)
    public String workType;

    @Column(name = "work_id", nullable = false)
    public Long workId;

    @Column(name = "leased_at", nullable = false)
    public Instant leasedAt;
}
