package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "agent_capability")
@IdClass(AgentCapabilityEntity.AgentCapabilityId.class)
public class AgentCapabilityEntity extends PanacheEntityBase {

    @Id
    @Column(name = "agent_id")
    public Long agentId;

    @Id
    public String capability;

    /**
     * Returns all capability strings for the given agent.
     */
    public static List<String> findCapabilities(Long agentId) {
        return find("agentId", agentId)
                .stream()
                .map(e -> ((AgentCapabilityEntity) e).capability)
                .toList();
    }

    /**
     * Replaces all capabilities for the given agent.
     */
    public static void setCapabilities(Long agentId, List<String> capabilities) {
        delete("agentId", agentId);
        if (capabilities != null) {
            for (String cap : capabilities) {
                AgentCapabilityEntity entity = new AgentCapabilityEntity();
                entity.agentId = agentId;
                entity.capability = cap.trim();
                entity.persist();
            }
        }
    }

    public static class AgentCapabilityId implements Serializable {
        public Long agentId;
        public String capability;

        public AgentCapabilityId() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgentCapabilityId that)) return false;
            return Objects.equals(agentId, that.agentId)
                    && Objects.equals(capability, that.capability);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentId, capability);
        }
    }
}
