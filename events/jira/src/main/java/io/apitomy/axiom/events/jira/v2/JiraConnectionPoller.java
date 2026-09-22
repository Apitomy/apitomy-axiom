package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ConnectionPollLogEntity;
import io.apitomy.axiom.core.entities.EventSourceConnectionEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.events.core.ApiResult;
import io.apitomy.axiom.events.core.EventStreamService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JiraConnectionPoller {

    private static final Logger LOG = Logger.getLogger(JiraConnectionPoller.class);
    private static final int DEFAULT_POLL_INTERVAL = 60;
    private static final int TICK_INTERVAL = 10;

    @Inject
    JiraEventsApiClient apiClient;

    @Inject
    JiraEventNormalizerV2 normalizer;

    @Inject
    EventStreamService eventStreamService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EncryptionService encryptionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.jira-v2.tick-interval:" + TICK_INTERVAL + "s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown) return;

        List<EventSourceConnectionEntity> connections = EventSourceConnectionEntity
                .list("sourceType = ?1 and enabled = ?2", "jira", true);

        if (connections.isEmpty()) return;

        Instant now = Instant.now();
        for (EventSourceConnectionEntity conn : connections) {
            if (isDue(conn, now)) {
                pollConnection(conn);
            }
        }
    }

    private boolean isDue(EventSourceConnectionEntity conn, Instant now) {
        if (conn.lastPolledAt == null) return true;
        int interval = conn.pollInterval != null ? conn.pollInterval : DEFAULT_POLL_INTERVAL;
        return now.isAfter(conn.lastPolledAt.plusSeconds(interval));
    }

    private void pollConnection(EventSourceConnectionEntity conn) {
        long startMs = System.currentTimeMillis();

        // First poll: set baseline and skip historical events
        if (conn.lastPolledAt == null) {
            LOG.infof("First poll for Jira connection %s — setting baseline to now", conn.id);
            updateLastPolledAt(conn.id);
            recordPollLog(conn.id, "success", "Baseline set (first poll)",
                    null, 0, System.currentTimeMillis() - startMs);
            return;
        }

        String credentials = resolveCredentials(conn);
        List<String> projects;
        try {
            JsonNode config = objectMapper.readTree(conn.configuration);
            JsonNode projectsNode = config.path("projects");
            if (!projectsNode.isArray() || projectsNode.isEmpty()) {
                LOG.warnf("Connection %s has no projects configured", conn.id);
                recordPollLog(conn.id, "error", "No projects configured",
                        null, 0, System.currentTimeMillis() - startMs);
                return;
            }
            projects = new ArrayList<>();
            for (JsonNode p : projectsNode) {
                projects.add(p.asText());
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for connection %s", conn.id);
            recordPollLog(conn.id, "error", "Configuration error",
                    e.getMessage(), 0, System.currentTimeMillis() - startMs);
            return;
        }

        Instant since = conn.lastPolledAt;

        // Fetch issues with changelog
        ApiResult result = apiClient.fetchIssuesWithChangelog(
                conn.baseUrl, projects, since, credentials);

        if (!result.success()) {
            LOG.warnf("Failed to poll Jira for connection %s: %s", conn.id, result.errorMessage());
            updateLastPolledAt(conn.id);
            recordPollLog(conn.id, "error", "API request failed",
                    result.errorMessage(), 0, System.currentTimeMillis() - startMs);
            return;
        }

        // Process each issue
        JsonNode issues = result.data().path("issues");
        int totalIngested = 0;

        if (issues.isArray()) {
            for (JsonNode issue : issues) {
                if (shuttingDown) break;

                List<NormalizedEvent> events = normalizer.normalizeIssue(
                        issue, since, conn.id, conn.baseUrl);

                for (NormalizedEvent event : events) {
                    boolean persisted = eventStreamService.persistEvent(event);
                    if (persisted) totalIngested++;
                }
            }
        }

        updateLastPolledAt(conn.id);
        long duration = System.currentTimeMillis() - startMs;
        recordPollLog(conn.id, "success",
                "Polled " + projects.size() + " projects, " + issues.size() + " issues",
                null, totalIngested, duration);
        LOG.infof("Polled Jira connection %s: %d events ingested from %d issues",
                conn.id, totalIngested, issues.size());
    }

    /**
     * Resolves Jira credentials (email:api_token format) using the same
     * three-tier fallback as the existing JiraPoller.
     */
    private String resolveCredentials(EventSourceConnectionEntity conn) {
        // Per-connection secret
        if (conn.secretName != null && !conn.secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", conn.secretName).firstResult();
            if (secret != null) {
                return encryptionService.decrypt(secret.encryptedValue);
            }
        }
        // Default provider secret
        SecretEntity secret = SecretEntity.find("name", "JIRA_API_TOKEN").firstResult();
        if (secret != null) {
            return encryptionService.decrypt(secret.encryptedValue);
        }
        // Environment variable fallback
        return System.getenv("JIRA_API_TOKEN");
    }

    @Transactional
    void recordPollLog(String connectionId, String status, String message,
                        String detail, int eventsIngested, long durationMs) {
        ConnectionPollLogEntity log = new ConnectionPollLogEntity();
        log.connectionId = connectionId;
        log.status = status;
        log.message = message;
        log.detail = detail;
        log.eventsIngested = eventsIngested;
        log.durationMs = durationMs;
        log.createdOn = Instant.now();
        log.persist();
    }

    @Transactional
    void updateLastPolledAt(String connectionId) {
        EventSourceConnectionEntity conn = EventSourceConnectionEntity.findById(connectionId);
        if (conn != null) {
            conn.lastPolledAt = Instant.now();
        }
    }
}
