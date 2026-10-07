package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.events.core.EventStreamService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class

GitHubEventSourcePoller {

    private static final Logger LOG = Logger.getLogger(GitHubEventSourcePoller.class);
    private static final int DEFAULT_POLL_INTERVAL = 60;
    private static final int TICK_INTERVAL = 10;

    @Inject
    GitHubEventsApiClient apiClient;

    @Inject
    GitHubEventNormalizerV2 normalizer;

    @Inject
    EventStreamService eventStreamService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EncryptionService encryptionService;

    private volatile boolean shuttingDown = false;

    // Track ETag per event source+repo for conditional polling
    // Key: "eventSourceId:owner/repo"
    private final Map<String, String> etagCache = new ConcurrentHashMap<>();

    // Track last-seen event IDs per event source+repo for dedup
    private final Map<String, Set<String>> seenEventIds = new ConcurrentHashMap<>();

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.github-v2.tick-interval:" + TICK_INTERVAL + "s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown) return;

        List<EventSourceEntity> eventSources = EventSourceEntity
                .list("sourceType = ?1 and enabled = ?2", "github", true);

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
        String token = resolveToken(eventSource);
        List<String> repositories;
        try {
            JsonNode config = objectMapper.readTree(eventSource.configuration);
            JsonNode reposNode = config.path("repositories");
            if (!reposNode.isArray() || reposNode.isEmpty()) {
                LOG.warnf("Event source %s has no repositories configured", eventSource.id);
                recordPollLog(eventSource.id, "error", "No repositories configured",
                        null, 0, System.currentTimeMillis() - startMs);
                return;
            }
            repositories = new java.util.ArrayList<>();
            for (JsonNode repo : reposNode) {
                repositories.add(repo.asText());
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for event source %s", eventSource.id);
            recordPollLog(eventSource.id, "error", "Configuration error",
                    e.getMessage(), 0, System.currentTimeMillis() - startMs);
            return;
        }

        // The stored baseUrl may be the web URL, a repository page, the API URL, etc.
        // Resolve it to the web URL (for event refs) and the REST API URL (for polling).
        GitHubUrls urls;
        try {
            urls = GitHubUrls.resolve(eventSource.baseUrl);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid base URL for event source %s: %s", eventSource.id, e.getMessage());
            recordPollLog(eventSource.id, "error", "Configuration error",
                    e.getMessage(), 0, System.currentTimeMillis() - startMs);
            return;
        }
        String apiBaseUrl = urls.apiBaseUrl();
        String htmlBaseUrl = urls.webBaseUrl();

        int totalIngested = 0;
        StringBuilder errors = new StringBuilder();
        for (String repoFullName : repositories) {
            if (shuttingDown) break;
            String[] parts = repoFullName.split("/");
            if (parts.length != 2) {
                LOG.warnf("Invalid repository format '%s' in event source %s, expected 'owner/repo'",
                        repoFullName, eventSource.id);
                if (!errors.isEmpty()) errors.append("; ");
                errors.append("Invalid repo format: ").append(repoFullName);
                continue;
            }
            int ingested = pollRepository(eventSource, parts[0], parts[1], token, apiBaseUrl, htmlBaseUrl, errors);
            totalIngested += ingested;
        }

        updateLastPolledAt(eventSource.id);
        long duration = System.currentTimeMillis() - startMs;

        if (errors.isEmpty()) {
            recordPollLog(eventSource.id, "success",
                    "Polled " + repositories.size() + " repositories",
                    null, totalIngested, duration);
        } else {
            recordPollLog(eventSource.id, "error",
                    "Polled " + repositories.size() + " repositories with errors",
                    errors.toString(), totalIngested, duration);
        }

        LOG.infof("Polled event source %s: %d events ingested across %d repositories",
                eventSource.id, totalIngested, repositories.size());
    }

    private int pollRepository(EventSourceEntity eventSource, String owner, String repo,
                                String token, String apiBaseUrl, String htmlBaseUrl,
                                StringBuilder errors) {
        String cacheKey = eventSource.id + ":" + owner + "/" + repo;
        String etag = etagCache.get(cacheKey);

        GitHubEventsApiClient.EventsPollResult result = apiClient.fetchRepositoryEvents(
                apiBaseUrl, owner, repo, token, etag);

        if (!result.success()) {
            LOG.warnf("Failed to poll %s/%s for event source %s: %s",
                    owner, repo, eventSource.id, result.errorMessage());
            if (!errors.isEmpty()) errors.append("; ");
            errors.append(owner).append("/").append(repo).append(": ").append(result.errorMessage());
            return 0;
        }

        // Update cached ETag
        if (result.etag() != null) {
            etagCache.put(cacheKey, result.etag());
        }

        if (result.notModified()) {
            LOG.debugf("No changes for %s/%s (304 Not Modified)", owner, repo);
            return 0;
        }

        // Process events (newest first in API, we want oldest first for ordering)
        JsonNode events = result.events();
        if (events == null || !events.isArray()) return 0;

        Set<String> seen = seenEventIds.computeIfAbsent(cacheKey, k -> new HashSet<>());
        int ingested = 0;

        // Events API returns newest first; process in reverse for chronological order
        for (int i = events.size() - 1; i >= 0; i--) {
            JsonNode event = events.get(i);
            String eventId = event.path("id").asText(null);

            // Skip if already seen (dedup in memory)
            if (eventId != null && seen.contains(eventId)) continue;

            // Backfill PR data if needed
            JsonNode fullPr = null;
            String eventType = event.path("type").asText("");
            if (needsPrBackfill(eventType)) {
                fullPr = backfillPr(apiBaseUrl, owner, repo, event, token);
            }

            NormalizedEvent normalized = normalizer.normalize(event, eventSource.id, htmlBaseUrl, fullPr);
            if (normalized != null) {
                boolean persisted = eventStreamService.persistEvent(normalized);
                if (persisted) ingested++;
            }

            if (eventId != null) seen.add(eventId);
        }

        // Keep seen set bounded (only keep last 1000 IDs per repo)
        if (seen.size() > 1000) {
            // Clear and let dedup in EventStreamService handle duplicates
            seen.clear();
        }

        return ingested;
    }

    private boolean needsPrBackfill(String eventType) {
        return "PullRequestEvent".equals(eventType)
                || "PullRequestReviewEvent".equals(eventType);
    }

    private JsonNode backfillPr(String apiBaseUrl, String owner, String repo,
                                 JsonNode event, String token) {
        JsonNode payload = event.path("payload");
        JsonNode prNode = payload.path("pull_request");
        int number = prNode.path("number").asInt(0);
        if (number == 0) return null;

        var result = apiClient.fetchPullRequest(apiBaseUrl, owner, repo, number, token);
        if (result.success()) {
            return result.data();
        }
        LOG.warnf("Failed to backfill PR #%d for %s/%s: %s", number, owner, repo, result.errorMessage());
        return null;
    }

    private String resolveToken(EventSourceEntity eventSource) {
        // Per-event-source secret
        if (eventSource.secretName != null && !eventSource.secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", eventSource.secretName).firstResult();
            if (secret != null) {
                return encryptionService.decrypt(secret.encryptedValue);
            }
        }
        // Default provider secrets
        for (String defaultName : List.of("GH_TOKEN", "GITHUB_TOKEN")) {
            SecretEntity secret = SecretEntity.find("name", defaultName).firstResult();
            if (secret != null) {
                return encryptionService.decrypt(secret.encryptedValue);
            }
        }
        // Environment variable fallback
        String envToken = System.getenv("GH_TOKEN");
        if (envToken == null) envToken = System.getenv("GITHUB_TOKEN");
        return envToken;
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
