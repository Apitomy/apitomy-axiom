package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSourceConnectionEntity;
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

GitHubConnectionPoller {

    private static final Logger LOG = Logger.getLogger(GitHubConnectionPoller.class);
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

    // Track ETag per connection+repo for conditional polling
    // Key: "connectionId:owner/repo"
    private final Map<String, String> etagCache = new ConcurrentHashMap<>();

    // Track last-seen event IDs per connection+repo for dedup
    private final Map<String, Set<String>> seenEventIds = new ConcurrentHashMap<>();

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.github-v2.tick-interval:" + TICK_INTERVAL + "s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown) return;

        List<EventSourceConnectionEntity> connections = EventSourceConnectionEntity
                .list("sourceType = ?1 and enabled = ?2", "github", true);

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
        String token = resolveToken(conn);
        List<String> repositories;
        try {
            JsonNode config = objectMapper.readTree(conn.configuration);
            JsonNode reposNode = config.path("repositories");
            if (!reposNode.isArray() || reposNode.isEmpty()) {
                LOG.warnf("Connection %s has no repositories configured", conn.id);
                return;
            }
            repositories = new java.util.ArrayList<>();
            for (JsonNode repo : reposNode) {
                repositories.add(repo.asText());
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for connection %s", conn.id);
            return;
        }

        // The stored baseUrl is the human-readable URL (e.g. https://github.com).
        // Derive the API base URL from it for API calls.
        String apiBaseUrl = deriveApiBaseUrl(conn.baseUrl);
        String htmlBaseUrl = conn.baseUrl;

        int totalIngested = 0;
        for (String repoFullName : repositories) {
            if (shuttingDown) break;
            String[] parts = repoFullName.split("/");
            if (parts.length != 2) {
                LOG.warnf("Invalid repository format '%s' in connection %s, expected 'owner/repo'",
                        repoFullName, conn.id);
                continue;
            }
            totalIngested += pollRepository(conn, parts[0], parts[1], token, apiBaseUrl, htmlBaseUrl);
        }

        updateLastPolledAt(conn.id);
        LOG.infof("Polled connection %s: %d events ingested across %d repositories",
                conn.id, totalIngested, repositories.size());
    }

    private int pollRepository(EventSourceConnectionEntity conn, String owner, String repo,
                                String token, String apiBaseUrl, String htmlBaseUrl) {
        String cacheKey = conn.id + ":" + owner + "/" + repo;
        String etag = etagCache.get(cacheKey);

        GitHubEventsApiClient.EventsPollResult result = apiClient.fetchRepositoryEvents(
                apiBaseUrl, owner, repo, token, etag);

        if (!result.success()) {
            LOG.warnf("Failed to poll %s/%s for connection %s: %s",
                    owner, repo, conn.id, result.errorMessage());
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

            NormalizedEvent normalized = normalizer.normalize(event, conn.id, htmlBaseUrl, fullPr);
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

    /**
     * Derives the API base URL from the human-readable URL.
     * "https://github.com" -> "https://api.github.com"
     * "https://github.example.com" -> "https://github.example.com/api/v3"
     */
    String deriveApiBaseUrl(String htmlBaseUrl) {
        if (htmlBaseUrl == null) return "https://api.github.com";
        String url = htmlBaseUrl.replaceAll("/+$", "");
        if (url.equals("https://github.com") || url.equals("http://github.com")) {
            return "https://api.github.com";
        }
        // GHE: append /api/v3 if not already present
        if (url.endsWith("/api/v3")) {
            return url;
        }
        return url + "/api/v3";
    }

    private String resolveToken(EventSourceConnectionEntity conn) {
        // Per-connection secret
        if (conn.secretName != null && !conn.secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", conn.secretName).firstResult();
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
    void updateLastPolledAt(String connectionId) {
        EventSourceConnectionEntity conn = EventSourceConnectionEntity.findById(connectionId);
        if (conn != null) {
            conn.lastPolledAt = Instant.now();
        }
    }
}
