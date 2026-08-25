package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents a configured AI agent slot in the agent pool.
 */
@Entity
@Table(name = "agent")
public class AgentEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "agent_type", nullable = false)
    public String agentType;

    @Column(columnDefinition = "TEXT")
    public String configuration;

    @Column(nullable = false)
    public boolean enabled = true;
}
