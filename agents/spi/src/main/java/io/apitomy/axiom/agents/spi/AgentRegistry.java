package io.apitomy.axiom.agents.spi;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of all available AI agent implementations. Replaces
 * {@code AiEngineRegistry}, {@code AiEngineProducer}, and
 * {@code AiEngineProvider} with a single discovery and lookup service.
 *
 * <p>Agent implementations are {@code @ApplicationScoped} CDI beans that
 * implement {@link Agent}. This registry discovers them at startup via
 * {@code Instance<Agent>} and provides type-based lookup.</p>
 */
@ApplicationScoped
public class AgentRegistry {

    private static final Logger LOG = Logger.getLogger(AgentRegistry.class);

    @ConfigProperty(name = "axiom.agent.default-type", defaultValue = "claude-code")
    String defaultAgentType;

    @Inject
    Instance<Agent> agents;

    private final Map<String, Agent> agentMap = new LinkedHashMap<>();
    private final Map<String, AgentMcpManager> mcpManagerMap = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        for (Agent agent : agents) {
            agentMap.put(agent.getType(), agent);
            LOG.infof("Registered agent: %s (%s)", agent.getType(),
                    agent.getClass().getSimpleName());
            if (agent instanceof AgentMcpManager mcpManager) {
                mcpManagerMap.put(agent.getType(), mcpManager);
            }
        }
        LOG.infof("Agent registry: %d agent(s) available, default: %s",
                agentMap.size(), defaultAgentType);
    }

    /**
     * Returns the agent for the given type, falling back to the default agent
     * if the type is null, blank, or not found.
     *
     * @param type the agent type (e.g. "opencode"), or null for default
     * @return the resolved agent
     */
    public Agent getAgent(String type) {
        if (type != null && !type.isBlank()) {
            Agent agent = agentMap.get(type);
            if (agent != null) {
                return agent;
            }
            LOG.warnf("Unknown agent type '%s', falling back to default '%s'",
                    type, defaultAgentType);
        }
        return getDefaultAgent();
    }

    /**
     * Returns the default agent.
     *
     * @return the default agent implementation
     * @throws IllegalStateException if the default agent is not found
     */
    public Agent getDefaultAgent() {
        Agent agent = agentMap.get(defaultAgentType);
        if (agent == null) {
            throw new IllegalStateException("Default agent '" + defaultAgentType + "' not found");
        }
        return agent;
    }

    /**
     * Returns the MCP manager for the given agent type, falling back to the
     * default agent's MCP manager.
     *
     * @param agentType the agent type, or null for default
     * @return the MCP manager, or a no-op manager if none is available
     */
    public AgentMcpManager getMcpManager(String agentType) {
        if (agentType != null && !agentType.isBlank()) {
            AgentMcpManager mgr = mcpManagerMap.get(agentType);
            if (mgr != null) {
                return mgr;
            }
        }
        AgentMcpManager defaultMgr = mcpManagerMap.get(defaultAgentType);
        if (defaultMgr != null) {
            return defaultMgr;
        }
        return (workItemId, environment, allowedTools) -> null;
    }

    /**
     * Returns the default agent type identifier.
     *
     * @return the default agent type string
     */
    public String getDefaultAgentType() {
        return defaultAgentType;
    }

    /**
     * Returns the list of available agent type identifiers.
     *
     * @return an unmodifiable list of agent type strings
     */
    public List<String> getAvailableTypes() {
        return List.copyOf(agentMap.keySet());
    }

    /**
     * Returns all registered agent implementations.
     *
     * @return an unmodifiable list of all registered agents
     */
    public List<Agent> getAllAgents() {
        return List.copyOf(agentMap.values());
    }
}
