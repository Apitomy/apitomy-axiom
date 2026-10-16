package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
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
public class JiraEventSourcePoller {

    private static final Logger LOG = Logger.getLogger(JiraEventSourcePoller.class);
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

        List<EventSourceEntity> eventSources = EventSourceEntity
                .list("sourceType = ?1 and enabled = ?2", "jira", true);

        if (eventSources.isEmpty()) return;

        Instant now = Instant.now();
        for (EventSourceEntity eventSource : eventSources) {
            if (isDue(eventSource, now)) {
                pollEventSource(eventSource);
            }
        }
    }

    private boolean isDue(EventSourceEntity eventSource, Instant now) {
        if (eventSource.lastPolledAt == null) return true;
        int interval = eventSource.pollInterval != null ? eventSource.pollInterval : DEFAULT_POLL_INTERVAL;
        return now.isAfter(eventSource.lastPolledAt.plusSeconds(interval));
    }

    private void pollEventSource(EventSourceEntity eventSource) {
        long startMs = System.currentTimeMillis();

        // First poll: set baseline and skip historical events
        if (eventSource.lastPolledAt == null) {
            LOG.infof("First poll for Jira event source %s — setting baseline to now", eventSource.id);
            updateLastPolledAt(eventSource.id);
            recordPollLog(eventSource.id, "success", "Baseline set (first poll)",
                    null, 0, System.currentTimeMillis() - startMs);
            return;
        }

        String credentials = resolveCredentials(eventSource);
        List<String> projects;
        try {
            JsonNode config = objectMapper.readTree(eventSource.configuration);
            JsonNode projectsNode = config.path("projects");
            if (!projectsNode.isArray() || projectsNode.isEmpty()) {
                LOG.warnf("Event source %s has no projects configured", eventSource.id);
                recordPollLog(eventSource.id, "error", "No projects configured",
                        null, 0, System.currentTimeMillis() - startMs);
                return;
            }
            projects = new ArrayList<>();
            for (JsonNode p : projectsNode) {
                projects.add(p.asText());
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for event source %s", eventSource.id);
            recordPollLog(eventSource.id, "error", "Configuration error",
                    e.getMessage(), 0, System.currentTimeMillis() - startMs);
            return;
        }

        Instant since = eventSource.lastPolledAt;

        // Fetch issues with changelog
        ApiResult result = apiClient.fetchIssuesWithChangelog(
                eventSource.baseUrl, projects, since, credentials);

        if (!result.success()) {
            LOG.warnf("Failed to poll Jira for event source %s: %s", eventSource.id, result.errorMessage());
            updateLastPolledAt(eventSource.id);
            recordPollLog(eventSource.id, "error", "API request failed",
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
                        issue, since, eventSource.id, eventSource.baseUrl);

                for (NormalizedEvent event : events) {
                    boolean persisted = eventStreamService.persistEvent(event);
                    if (persisted) totalIngested++;
                }
            }
        }

        updateLastPolledAt(eventSource.id);
        long duration = System.currentTimeMillis() - startMs;
        recordPollLog(eventSource.id, "success",
                "Polled " + projects.size() + " projects, " + issues.size() + " issues",
                null, totalIngested, duration);
        LOG.infof("Polled Jira event source %s: %d events ingested from %d issues",
                eventSource.id, totalIngested, issues.size());
    }

    /**
     * Resolves Jira credentials (email:api_token format) using the same
     * three-tier fallback as the existing JiraPoller.
     */
    private String resolveCredentials(EventSourceEntity eventSource) {
        // Per-event-source secret
        if (eventSource.secretName != null && !eventSource.secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", eventSource.secretName).firstResult();
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
    void recordPollLog(String eventSourceId, String status, String message,
                        String detail, int eventsIngested, long durationMs) {
        EventSourcePollLogEntity log = new EventSourcePollLogEntity();
        log.eventSourceId = eventSourceId;
        log.status = status;
        log.message = message;
        log.detail = detail;
        log.eventsIngested = eventsIngested;
        log.durationMs = durationMs;
        log.createdOn = Instant.now();
        log.persist();
    }

    @Transactional
    void updateLastPolledAt(String eventSourceId) {
        EventSourceEntity eventSource = EventSourceEntity.findById(eventSourceId);
        if (eventSource != null) {
            eventSource.lastPolledAt = Instant.now();
        }
    }
}
