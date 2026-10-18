package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Computes the health of event sources from their poll logs. Shared by the system health check and the
 * event source health endpoint.
 * <p>
 * State rules, evaluated in order:
 * <ol>
 *     <li>{@code disabled} — the source is disabled.</li>
 *     <li>{@code never-polled} — no poll has been recorded.</li>
 *     <li>{@code failing} — the most recent poll failed.</li>
 *     <li>{@code baseline} — the most recent poll succeeded but only set the baseline (first poll).</li>
 *     <li>{@code healthy} — otherwise.</li>
 * </ol>
 */
@ApplicationScoped
public class EventSourceHealthService {

    /** Source is enabled and its last poll succeeded. */
    public static final String HEALTHY = "healthy";
    /** Source is enabled and its last poll failed. */
    public static final String FAILING = "failing";
    /** Source is enabled and its last poll only established the baseline. */
    public static final String BASELINE = "baseline";
    /** Source is disabled. */
    public static final String DISABLED = "disabled";
    /** Source is enabled but has never been polled. */
    public static final String NEVER_POLLED = "never-polled";

    static final String POLL_SUCCESS = "success";
    static final String POLL_ERROR = "error";
    private static final String BASELINE_MESSAGE_PREFIX = "Baseline";

    /**
     * Health of one event source.
     *
     * @param id                  source id
     * @param name                source name
     * @param type                source type
     * @param enabled             whether the source is enabled
     * @param state               one of the state constants of this class
     * @param lastAttemptAt       time of the latest poll attempt (nullable)
     * @param lastSuccessAt       time of the latest successful poll (nullable)
     * @param lastErrorAt         time of the latest failed poll (nullable)
     * @param lastError           message of the latest failed poll, only when it is after the latest success
     *                            (nullable)
     * @param consecutiveFailures number of failed polls since the latest success
     * @param eventsLast24h       number of events ingested from the source in the last 24 hours
     */
    public record Health(String id, String name, String type, boolean enabled, String state,
                         Instant lastAttemptAt, Instant lastSuccessAt, Instant lastErrorAt, String lastError,
                         long consecutiveFailures, long eventsLast24h) {
    }

    /**
     * Computes the health of every event source, ordered by name.
     *
     * @return the health of each source
     */
    public List<Health> computeAll() {
        return EventSourceEntity.<EventSourceEntity>listAll(Sort.ascending("name")).stream()
                .map(this::compute)
                .toList();
    }

    /**
     * Computes the health of one event source.
     *
     * @param source the event source
     * @return its health
     */
    public Health compute(EventSourceEntity source) {
        EventSourcePollLogEntity latest = latestLog(source.id, null);
        EventSourcePollLogEntity latestSuccess = latestLog(source.id, POLL_SUCCESS);
        EventSourcePollLogEntity latestError = latestLog(source.id, POLL_ERROR);

        Instant lastSuccessAt = latestSuccess != null ? latestSuccess.createdOn : null;
        Instant lastErrorAt = latestError != null ? latestError.createdOn : null;
        String lastError = latestError != null
                && (lastSuccessAt == null || latestError.createdOn.isAfter(lastSuccessAt))
                ? latestError.message : null;
        long consecutiveFailures = lastSuccessAt == null
                ? EventSourcePollLogEntity.count("eventSourceId = ?1 and status = ?2", source.id, POLL_ERROR)
                : EventSourcePollLogEntity.count("eventSourceId = ?1 and status = ?2 and createdOn > ?3",
                        source.id, POLL_ERROR, lastSuccessAt);
        Instant lastAttemptAt = latest != null ? latest.createdOn : source.lastPolledAt;
        long eventsLast24h = StreamEventEntity.count("eventSourceId = ?1 and createdOn >= ?2",
                source.id, Instant.now().minus(Duration.ofHours(24)));

        return new Health(source.id, source.name, source.sourceType, source.enabled,
                state(source.enabled, latest), lastAttemptAt, lastSuccessAt, lastErrorAt, lastError,
                consecutiveFailures, eventsLast24h);
    }

    /**
     * Derives the state from the enabled flag and the latest poll log.
     *
     * @param enabled whether the source is enabled
     * @param latest  the latest poll log (nullable)
     * @return the state
     */
    static String state(boolean enabled, EventSourcePollLogEntity latest) {
        if (!enabled) {
            return DISABLED;
        }
        if (latest == null) {
            return NEVER_POLLED;
        }
        if (POLL_ERROR.equals(latest.status)) {
            return FAILING;
        }
        if (latest.message != null && latest.message.startsWith(BASELINE_MESSAGE_PREFIX)) {
            return BASELINE;
        }
        return HEALTHY;
    }

    private static EventSourcePollLogEntity latestLog(String sourceId, String status) {
        if (status == null) {
            return EventSourcePollLogEntity.<EventSourcePollLogEntity>find("eventSourceId = ?1",
                    Sort.descending("createdOn", "id"), sourceId).firstResult();
        }
        return EventSourcePollLogEntity.<EventSourcePollLogEntity>find("eventSourceId = ?1 and status = ?2",
                Sort.descending("createdOn", "id"), sourceId, status).firstResult();
    }
}
