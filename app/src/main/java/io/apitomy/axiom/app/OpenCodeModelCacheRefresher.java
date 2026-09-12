package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Warms and periodically refreshes the OpenCode model discovery cache.
 */
@ApplicationScoped
public class OpenCodeModelCacheRefresher {

    private static final Logger LOG = Logger.getLogger(OpenCodeModelCacheRefresher.class);

    private final AgentRegistry agentRegistry;

    @Inject
    public OpenCodeModelCacheRefresher(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    void refreshOnStartup(@Observes StartupEvent event) {
        refreshOpenCodeModels("startup");
    }

    @Scheduled(
            every = "${axiom.agent.opencode.model-discovery.refresh-interval:24h}",
            delayed = "5m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    void refreshDaily() {
        refreshOpenCodeModels("scheduled");
    }

    private void refreshOpenCodeModels(String trigger) {
        for (Agent agent : agentRegistry.getAllAgents()) {
            if (!"opencode".equals(agent.getType())) {
                continue;
            }
            try {
                agent.refreshAvailableModels();
                LOG.infof("Refreshed OpenCode model cache (%s)", trigger);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to refresh OpenCode model cache (%s)", trigger);
            }
        }
    }
}
