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
import java.util.ArrayList;
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
            int ingested = pollRepository(eventSource.id, parts[0], parts[1], token, apiBaseUrl, htmlBaseUrl,
                    errors);
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

    /**
     * Polls one repository and ingests the events that are not yet in the event stream.
     *
     * <p>All event IDs of the page are looked up in the stream with one indexed query, and events already
     * stored are skipped before normalization and PR backfill. After a restart (which loses the in-memory
     * ETag) this keeps the first poll cheap. The full PR is only fetched for PR events whose action is
     * supported, so unsupported events cost no API calls either.</p>
     *
     * <p>If storing an event fails, the remaining events are left for the next poll, and the ETag is not
     * updated so that the next request is not answered with a 304.</p>
     *
     * @param eventSourceId the event source identifier
     * @param owner the repository owner
     * @param repo the repository name
     * @param token the GitHub token, or null
     * @param apiBaseUrl the GitHub REST API base URL
     * @param htmlBaseUrl the GitHub web base URL (for event refs)
     * @param errors collects error descriptions for the poll log
     * @return the number of events stored
     */
    int pollRepository(String eventSourceId, String owner, String repo,
                       String token, String apiBaseUrl, String htmlBaseUrl,
                       StringBuilder errors) {
        String repository = owner + "/" + repo;
        String cacheKey = eventSourceId + ":" + repository;
        String etag = etagCache.get(cacheKey);

        GitHubEventsApiClient.EventsPollResult result = apiClient.fetchRepositoryEvents(
                apiBaseUrl, owner, repo, token, etag);

        if (!result.success()) {
            LOG.warnf("Failed to poll %s for event source %s: %s",
                    repository, eventSourceId, result.errorMessage());
            appendError(errors, repository + ": " + result.errorMessage());
            return 0;
        }

        if (result.notModified()) {
            LOG.debugf("No changes for %s (304 Not Modified)", repository);
            return 0;
        }

        JsonNode events = result.events();
        if (events == null || !events.isArray()) return 0;

        List<String> pageIds = new ArrayList<>();
        for (JsonNode event : events) {
            String id = event.path("id").asText(null);
            if (id != null) pageIds.add(id);
        }
        Set<String> alreadyStored = eventStreamService.existingSourceEventIds(pageIds);

        int ingested = 0;
        boolean complete = true;

        // Events API returns newest first; process in reverse for chronological order
        for (int i = events.size() - 1; i >= 0; i--) {
            JsonNode event = events.get(i);
            String eventId = event.path("id").asText(null);
            if (eventId != null && alreadyStored.contains(eventId)) continue;

            try {
                JsonNode fullPr = normalizer.isSupportedPullRequestEvent(event)
                        ? backfillPr(apiBaseUrl, owner, repo, event, token)
                        : null;
                NormalizedEvent normalized = normalizer.normalize(event, eventSourceId, htmlBaseUrl, fullPr);
                if (normalized != null && eventStreamService.persistEvent(normalized)) {
                    ingested++;
                }
            } catch (RuntimeException e) {
                LOG.warnf(e, "Failed to ingest GitHub event %s from %s for event source %s; "
                        + "will retry on the next poll", eventId, repository, eventSourceId);
                appendError(errors, repository + ": failed to ingest event " + eventId + ": " + e.getMessage());
                complete = false;
                break;
            }
        }

        if (complete && result.etag() != null) {
            etagCache.put(cacheKey, result.etag());
        }

        LOG.debugf("Polled %s: %d events, %d already stored, %d ingested",
                repository, events.size(), alreadyStored.size(), ingested);
        return ingested;
    }

    private static void appendError(StringBuilder errors, String message) {
        if (!errors.isEmpty()) errors.append("; ");
        errors.append(message);
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
