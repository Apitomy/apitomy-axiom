package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Safety cap on the number of search pages fetched in one poll window. */
    int maxSearchPages = 50;

    /**
     * In-memory record of the last poll attempt per event source. Used for scheduling
     * only, so that a failed poll (which does not move the persisted checkpoint) is not
     * retried on every tick.
     */
    private final Map<String, Instant> lastAttemptAt = new ConcurrentHashMap<>();

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

    /**
     * Determines whether an event source is due for polling, based on the later of its
     * persisted checkpoint and the last in-memory poll attempt.
     *
     * @param eventSource the event source
     * @param now         the current time
     * @return true if the poll interval has elapsed
     */
    boolean isDue(EventSourceEntity eventSource, Instant now) {
        if (eventSource.lastPolledAt == null) return true;
        int interval = eventSource.pollInterval != null ? eventSource.pollInterval : DEFAULT_POLL_INTERVAL;
        Instant reference = eventSource.lastPolledAt;
        Instant attempt = lastAttemptAt.get(eventSource.id);
        if (attempt != null && attempt.isAfter(reference)) {
            reference = attempt;
        }
        return now.isAfter(reference.plusSeconds(interval));
    }

    /**
     * Records the time of a poll attempt for scheduling purposes.
     *
     * @param eventSourceId the event source id
     * @param attemptAt     the attempt time
     */
    void recordAttempt(String eventSourceId, Instant attemptAt) {
        lastAttemptAt.put(eventSourceId, attemptAt);
    }

    private void pollEventSource(EventSourceEntity eventSource) {
        long startMs = System.currentTimeMillis();

        // First poll: set baseline and skip historical events
        if (eventSource.lastPolledAt == null) {
            LOG.infof("First poll for Jira event source %s — setting baseline to now", eventSource.id);
            updateLastPolledAt(eventSource.id, Instant.now());
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
        // Window start is captured before fetching so changes made during the poll are
        // picked up by the next window rather than skipped.
        Instant windowStart = Instant.now();
        recordAttempt(eventSource.id, windowStart);

        WindowResult result = ingestWindow(eventSource.id, eventSource.baseUrl, projects,
                since, credentials, windowStart);
        long duration = System.currentTimeMillis() - startMs;

        if (result.checkpoint() != null) {
            updateLastPolledAt(eventSource.id, result.checkpoint());
        }

        if (result.complete()) {
            recordPollLog(eventSource.id, "success",
                    "Polled " + projects.size() + " projects, " + result.issuesProcessed() + " issues",
                    null, result.eventsIngested(), duration);
            LOG.infof("Polled Jira event source %s: %d events ingested from %d issues",
                    eventSource.id, result.eventsIngested(), result.issuesProcessed());
        } else {
            LOG.warnf("Incomplete Jira poll for event source %s: %s (checkpoint %s)",
                    eventSource.id, result.errorMessage(),
                    result.checkpoint() != null ? "advanced to " + result.checkpoint() : "unchanged");
            recordPollLog(eventSource.id, "error", "Poll incomplete",
                    result.errorMessage(), result.eventsIngested(), duration);
        }
    }

    /**
     * Outcome of ingesting one poll window.
     *
     * @param complete        true if every page and issue in the window was fetched and ingested
     * @param checkpoint      the new checkpoint to persist, or {@code null} to keep the previous one
     * @param issuesProcessed number of issues fully processed
     * @param eventsIngested  number of new (non-duplicate) events persisted
     * @param errorMessage    reason the window is incomplete, or {@code null}
     */
    record WindowResult(boolean complete, Instant checkpoint, int issuesProcessed,
                        int eventsIngested, String errorMessage) {
    }

    /**
     * Fetches all pages of changed issues since {@code since} and ingests their events.
     * The checkpoint is only advanced (to {@code windowStart}) when the whole window was
     * ingested. On API failure, persistence failure or shutdown, the checkpoint is left
     * unchanged so the next poll re-fetches; re-ingestion is safe because events are
     * de-duplicated by their deterministic source event id. If the page safety cap is hit,
     * the checkpoint advances only to the latest {@code updated} time of the processed
     * issues (results are ordered by {@code updated ASC}).
     *
     * @param eventSourceId the event source id
     * @param baseUrl       Jira base URL
     * @param projects      project keys
     * @param since         the current (last successful) checkpoint
     * @param credentials   email:api_token credentials
     * @param windowStart   time captured before the first request of this window
     * @return the window outcome
     */
    WindowResult ingestWindow(String eventSourceId, String baseUrl, List<String> projects,
                              Instant since, String credentials, Instant windowStart) {
        // Normalizer cutoff overlaps the previous window to tolerate clock skew and
        // boundary timestamps; duplicates are dropped by source-event de-duplication.
        Instant normalizeSince = since.minus(JiraEventsApiClient.QUERY_OVERLAP);
        int issuesProcessed = 0;
        int totalIngested = 0;
        Instant maxUpdated = null;
        String nextPageToken = null;

        for (int pageNum = 0; pageNum < maxSearchPages; pageNum++) {
            ApiResult page = apiClient.fetchIssuesPage(baseUrl, projects, since, credentials, nextPageToken);
            if (page == null || !page.success()) {
                String msg = page != null ? page.errorMessage() : "no response";
                return new WindowResult(false, null, issuesProcessed, totalIngested,
                        "API request failed: " + msg);
            }

            JsonNode issues = page.data().path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    if (shuttingDown) {
                        return new WindowResult(false, null, issuesProcessed, totalIngested,
                                "Interrupted by shutdown");
                    }
                    try {
                        JsonNode completeIssue = withFullChangelog(baseUrl, issue, credentials);
                        if (completeIssue == null) {
                            return new WindowResult(false, null, issuesProcessed, totalIngested,
                                    "Failed to fetch changelog for " + issue.path("key").asText());
                        }
                        List<NormalizedEvent> events = normalizer.normalizeIssue(
                                completeIssue, normalizeSince, eventSourceId, baseUrl);
                        for (NormalizedEvent event : events) {
                            if (eventStreamService.persistEvent(event)) {
                                totalIngested++;
                            }
                        }
                    } catch (RuntimeException e) {
                        LOG.warnf(e, "Failed to ingest Jira issue %s", issue.path("key").asText());
                        return new WindowResult(false, null, issuesProcessed, totalIngested,
                                "Ingestion failed: " + e.getMessage());
                    }
                    issuesProcessed++;
                    Instant updated = JiraEventNormalizerV2.parseJiraTimestamp(
                            issue.path("fields").path("updated").asText(null));
                    if (updated != null && (maxUpdated == null || updated.isAfter(maxUpdated))) {
                        maxUpdated = updated;
                    }
                }
            }

            JsonNode tokenNode = page.data().path("nextPageToken");
            nextPageToken = tokenNode.isTextual() && !tokenNode.asText().isBlank() ? tokenNode.asText() : null;
            if (nextPageToken == null || page.data().path("isLast").asBoolean(false)) {
                return new WindowResult(true, windowStart, issuesProcessed, totalIngested, null);
            }
        }

        LOG.warnf("Jira event source %s hit the page cap (%d pages); remaining issues will be "
                + "fetched in the next poll", eventSourceId, maxSearchPages);
        Instant partial = maxUpdated != null && maxUpdated.isAfter(since) ? maxUpdated : null;
        return new WindowResult(false, partial, issuesProcessed, totalIngested,
                "Page cap of " + maxSearchPages + " reached");
    }

    /**
     * Returns the issue with its complete changelog, fetching remaining changelog pages
     * when the embedded changelog is truncated.
     *
     * @return the (possibly augmented) issue, or {@code null} if the changelog fetch failed
     */
    private JsonNode withFullChangelog(String baseUrl, JsonNode issue, String credentials) {
        JsonNode changelog = issue.path("changelog");
        JsonNode histories = changelog.path("histories");
        int total = changelog.path("total").asInt(0);
        int embedded = histories.isArray() ? histories.size() : 0;
        if (total <= embedded) {
            return issue;
        }
        ApiResult full = apiClient.fetchFullChangelog(baseUrl, issue.path("key").asText(), credentials);
        if (full == null || !full.success()) {
            return null;
        }
        ObjectNode copy = ((ObjectNode) issue).deepCopy();
        ObjectNode newChangelog = copy.putObject("changelog");
        newChangelog.put("startAt", 0);
        newChangelog.put("maxResults", full.data().size());
        newChangelog.put("total", full.data().size());
        newChangelog.set("histories", full.data());
        return copy;
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
    void updateLastPolledAt(String eventSourceId, Instant checkpoint) {
        EventSourceEntity eventSource = EventSourceEntity.findById(eventSourceId);
        if (eventSource != null) {
            eventSource.lastPolledAt = checkpoint;
        }
    }
}
