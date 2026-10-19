package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the health of event sources from their poll logs. Shared by the system health check and the
 * event source health endpoint.
 * <p>
 * All sources are computed together with a fixed number of grouped queries (independent of the number of
 * sources), so the system health check stays cheap.
 * <p>
 * State rules, evaluated in order:
 * <ol>
 *     <li>{@code disabled} — the source is disabled.</li>
 *     <li>{@code never-polled} — no poll has been recorded.</li>
 *     <li>{@code failing} — the latest failed poll is more recent than the latest successful poll.</li>
 *     <li>{@code baseline} — the latest successful poll only established the baseline (first poll).</li>
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

    @Inject
    EntityManager entityManager;

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
     * @param eventsLast24h       events ingested from the source in the last 24 hours; null when not
     *                            requested
     */
    public record Health(String id, String name, String type, boolean enabled, String state,
                         Instant lastAttemptAt, Instant lastSuccessAt, Instant lastErrorAt, String lastError,
                         long consecutiveFailures, Long eventsLast24h) {
    }

    /** Aggregated poll log facts of one source. */
    private static final class PollFacts {
        private Instant lastAttemptAt;
        private Instant lastSuccessAt;
        private Instant lastErrorAt;
        private String lastSuccessMessage;
        private String lastErrorMessage;
        private long errorsAfterSuccess;
    }

    /**
     * Computes the health of every event source, ordered by name.
     *
     * @param includeEventCounts whether to also count the events ingested per source in the last 24 hours
     *                           (one extra grouped query); when false {@code eventsLast24h} is null
     * @return the health of each source
     */
    public List<Health> computeAll(boolean includeEventCounts) {
        List<EventSourceEntity> sources = EventSourceEntity.listAll(Sort.ascending("name"));
        if (sources.isEmpty()) {
            return List.of();
        }
        Map<String, PollFacts> facts = loadPollFacts(null);
        Map<String, Long> eventCounts = includeEventCounts ? loadEventCounts() : Map.of();
        return sources.stream()
                .map(source -> toHealth(source, facts.get(source.id),
                        includeEventCounts ? eventCounts.getOrDefault(source.id, 0L) : null))
                .toList();
    }

    /**
     * Computes the health of a single event source with the same grouped queries as {@link #computeAll},
     * restricted to that source. {@code eventsLast24h} is not computed (null).
     *
     * @param source the event source
     * @return its health
     */
    public Health compute(EventSourceEntity source) {
        return toHealth(source, loadPollFacts(source.id).get(source.id), null);
    }

    /**
     * Loads aggregated poll log facts per source.
     *
     * @param sourceId restricts the queries to one source when not null
     * @return facts keyed by source id
     */
    private Map<String, PollFacts> loadPollFacts(String sourceId) {
        Map<String, PollFacts> facts = new HashMap<>();
        String only = sourceId != null ? " and l.eventSourceId = :sourceId" : "";
        query("select l.eventSourceId, max(l.createdOn),"
                        + " max(case when l.status = :success then l.createdOn end),"
                        + " max(case when l.status = :error then l.createdOn end)"
                        + " from EventSourcePollLogEntity l where 1 = 1" + only + " group by l.eventSourceId",
                sourceId)
                .getResultList()
                .forEach(row -> {
                    PollFacts f = facts.computeIfAbsent((String) row[0], k -> new PollFacts());
                    f.lastAttemptAt = (Instant) row[1];
                    f.lastSuccessAt = (Instant) row[2];
                    f.lastErrorAt = (Instant) row[3];
                });

        // Messages of the latest success and latest error per source (highest id wins on equal times).
        query("select l.eventSourceId, l.status, l.message from EventSourcePollLogEntity l"
                        + " where l.status in (:success, :error)" + only + " and l.id = (select max(x.id)"
                        + " from EventSourcePollLogEntity x where x.eventSourceId = l.eventSourceId"
                        + " and x.status = l.status and x.createdOn = (select max(y.createdOn)"
                        + " from EventSourcePollLogEntity y where y.eventSourceId = l.eventSourceId"
                        + " and y.status = l.status))", sourceId)
                .getResultList()
                .forEach(row -> {
                    PollFacts f = facts.computeIfAbsent((String) row[0], k -> new PollFacts());
                    if (EventSourcePollLogEntity.STATUS_SUCCESS.equals(row[1])) {
                        f.lastSuccessMessage = (String) row[2];
                    } else {
                        f.lastErrorMessage = (String) row[2];
                    }
                });

        // Failed polls after the latest success, per source.
        query("select l.eventSourceId, count(l) from EventSourcePollLogEntity l"
                        + " where l.status = :error" + only + " and not exists (select 1 from EventSourcePollLogEntity s"
                        + " where s.eventSourceId = l.eventSourceId and s.status = :success"
                        + " and s.createdOn >= l.createdOn) group by l.eventSourceId", sourceId)
                .getResultList()
                .forEach(row -> facts.computeIfAbsent((String) row[0], k -> new PollFacts())
                        .errorsAfterSuccess = ((Number) row[1]).longValue());
        return facts;
    }

    private TypedQuery<Object[]> query(String jpql, String sourceId) {
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("success", EventSourcePollLogEntity.STATUS_SUCCESS)
                .setParameter("error", EventSourcePollLogEntity.STATUS_ERROR);
        if (sourceId != null) {
            query.setParameter("sourceId", sourceId);
        }
        return query;
    }

    private Map<String, Long> loadEventCounts() {
        Map<String, Long> counts = new HashMap<>();
        entityManager.createQuery("select e.eventSourceId, count(e) from StreamEventEntity e"
                        + " where e.createdOn >= :since and e.eventSourceId is not null"
                        + " group by e.eventSourceId", Object[].class)
                .setParameter("since", Instant.now().minus(Duration.ofHours(24)))
                .getResultList()
                .forEach(row -> counts.put((String) row[0], ((Number) row[1]).longValue()));
        return counts;
    }

    private static Health toHealth(EventSourceEntity source, PollFacts facts, Long eventsLast24h) {
        PollFacts f = facts != null ? facts : new PollFacts();
        boolean errorIsLatest = f.lastErrorAt != null
                && (f.lastSuccessAt == null || f.lastErrorAt.isAfter(f.lastSuccessAt));
        Instant lastAttemptAt = f.lastAttemptAt != null ? f.lastAttemptAt : source.lastPolledAt;
        return new Health(source.id, source.name, source.sourceType, source.enabled,
                state(source.enabled, f.lastAttemptAt != null, errorIsLatest, f.lastSuccessMessage),
                lastAttemptAt, f.lastSuccessAt, f.lastErrorAt, errorIsLatest ? f.lastErrorMessage : null,
                f.errorsAfterSuccess, eventsLast24h);
    }

    /**
     * Derives the state of a source.
     *
     * @param enabled            whether the source is enabled
     * @param polled             whether any poll was recorded
     * @param errorIsLatest      whether the latest error is more recent than the latest success
     * @param lastSuccessMessage message of the latest successful poll (nullable)
     * @return the state
     */
    static String state(boolean enabled, boolean polled, boolean errorIsLatest, String lastSuccessMessage) {
        if (!enabled) {
            return DISABLED;
        }
        if (!polled) {
            return NEVER_POLLED;
        }
        if (errorIsLatest) {
            return FAILING;
        }
        if (lastSuccessMessage != null
                && lastSuccessMessage.startsWith(EventSourcePollLogEntity.BASELINE_MESSAGE_PREFIX)) {
            return BASELINE;
        }
        return HEALTHY;
    }
}
