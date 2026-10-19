package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.EventVolumeBucket;
import io.apitomy.axiom.api.beans.ProcessingFailure;
import io.apitomy.axiom.api.beans.SubscriptionProcessingCounts;
import io.apitomy.axiom.api.beans.SubscriptionProcessingSummary;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.WebApplicationException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only operational views over the event pipeline used by dashboards: processing counts per
 * subscription, recent processing failures and the event volume histogram. Every view uses a fixed
 * number of grouped queries, independent of the number of subscriptions or buckets.
 */
@ApplicationScoped
public class EventOperationsService {

    /** Maximum number of buckets a histogram may have. */
    public static final int MAX_BUCKETS = 400;

    /** Ledger statuses that count as a processing failure. */
    static final List<String> ERROR_STATUSES = List.of(EventProcessingLedgerEntity.FAILED,
            EventProcessingLedgerEntity.EXHAUSTED, EventProcessingLedgerEntity.FILTER_ERROR);

    /** When a ledger entry last changed. */
    private static final String UPDATED_AT = "coalesce(l.processedOn, l.lastAttemptAt, l.createdOn)";

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    @Inject
    EntityManager entityManager;

    /**
     * Processing counts per status for every subscription (ordered by name), for ledger entries created at
     * or after {@code since}, plus the most recent error of the window.
     *
     * @param since start of the window; defaults to 24 hours ago
     * @return one summary per subscription
     */
    public List<SubscriptionProcessingSummary> subscriptionSummary(Date since) {
        Instant from = since != null ? since.toInstant() : Instant.now().minus(DEFAULT_WINDOW);
        List<EventSubscriptionEntity> subscriptions = EventSubscriptionEntity.listAll(Sort.ascending("name"));
        if (subscriptions.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<String, Long>> counts = new HashMap<>();
        entityManager.createQuery("select l.subscriptionId, l.status, count(l) from EventProcessingLedgerEntity l"
                        + " where l.createdOn >= :since group by l.subscriptionId, l.status", Object[].class)
                .setParameter("since", from)
                .getResultList()
                .forEach(row -> counts.computeIfAbsent((Long) row[0], k -> new HashMap<>())
                        .put((String) row[1], ((Number) row[2]).longValue()));

        // The latest error entry (highest id) of each subscription in the window.
        Map<Long, Object[]> lastErrors = new HashMap<>();
        entityManager.createQuery("select l.subscriptionId, " + UPDATED_AT + ", l.errorMessage"
                        + " from EventProcessingLedgerEntity l where l.id in (select max(x.id)"
                        + " from EventProcessingLedgerEntity x where x.status in :errors and x.createdOn >= :since"
                        + " group by x.subscriptionId)", Object[].class)
                .setParameter("errors", ERROR_STATUSES)
                .setParameter("since", from)
                .getResultList()
                .forEach(row -> lastErrors.put((Long) row[0], row));

        return subscriptions.stream().map(sub -> {
            Map<String, Long> c = counts.getOrDefault(sub.id, Map.of());
            SubscriptionProcessingCounts beanCounts = new SubscriptionProcessingCounts();
            beanCounts.setPending(c.getOrDefault(EventProcessingLedgerEntity.PENDING, 0L));
            beanCounts.setCompleted(c.getOrDefault(EventProcessingLedgerEntity.COMPLETED, 0L));
            beanCounts.setFailed(c.getOrDefault(EventProcessingLedgerEntity.FAILED, 0L));
            beanCounts.setExhausted(c.getOrDefault(EventProcessingLedgerEntity.EXHAUSTED, 0L));
            beanCounts.setSkipped(c.getOrDefault(EventProcessingLedgerEntity.SKIPPED, 0L));
            beanCounts.setFilterError(c.getOrDefault(EventProcessingLedgerEntity.FILTER_ERROR, 0L));

            SubscriptionProcessingSummary bean = new SubscriptionProcessingSummary();
            bean.setSubscriptionId(sub.id);
            bean.setName(sub.name);
            bean.setEnabled(sub.enabled);
            bean.setCounts(beanCounts);
            Object[] lastError = lastErrors.get(sub.id);
            if (lastError != null) {
                bean.setLastErrorAt(Date.from((Instant) lastError[1]));
                bean.setLastError((String) lastError[2]);
            }
            return bean;
        }).toList();
    }

    /**
     * Ledger entries with status failed, exhausted or filter_error, newest first.
     *
     * @param limit maximum number of entries (1-200, default 20)
     * @param since only entries updated at or after this time (optional)
     * @return the failures
     */
    public List<ProcessingFailure> failures(Integer limit, Date since) {
        int max = limit != null ? Math.clamp(limit, 1, 200) : 20;
        String jpql = "select l.id, l.eventId, e.type, l.subscriptionId, s.name, l.status, l.errorMessage,"
                + " l.attemptCount, l.nextAttemptAt, " + UPDATED_AT
                + " from EventProcessingLedgerEntity l"
                + " join StreamEventEntity e on e.id = l.eventId"
                + " left join EventSubscriptionEntity s on s.id = l.subscriptionId"
                + " where l.status in :errors"
                + (since != null ? " and " + UPDATED_AT + " >= :since" : "")
                + " order by " + UPDATED_AT + " desc, l.id desc";
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class)
                .setParameter("errors", ERROR_STATUSES)
                .setMaxResults(max);
        if (since != null) {
            query.setParameter("since", since.toInstant());
        }
        return query.getResultList().stream().map(row -> {
            ProcessingFailure bean = new ProcessingFailure();
            bean.setLedgerId((Long) row[0]);
            bean.setEventId((UUID) row[1]);
            bean.setEventType((String) row[2]);
            bean.setSubscriptionId((Long) row[3]);
            bean.setSubscriptionName((String) row[4]);
            bean.setStatus(ProcessingFailure.Status.fromValue((String) row[5]));
            bean.setErrorMessage((String) row[6]);
            bean.setAttemptCount(((Number) row[7]).intValue());
            bean.setNextAttemptAt(row[8] != null ? Date.from((Instant) row[8]) : null);
            bean.setUpdatedAt(Date.from((Instant) row[9]));
            return bean;
        }).toList();
    }

    /**
     * Number of ingested events per UTC hour or day from {@code since} until now, including empty buckets.
     *
     * @param since         start of the histogram; defaults to 24 hours ago
     * @param bucket        "hour" (default) or "day"
     * @param eventSourceId only count events of this source (optional)
     * @param now           the current time (end of the histogram)
     * @return buckets ordered ascending
     * @throws WebApplicationException 400 when the bucket is invalid, since is in the future or the range
     *                                 needs more than {@link #MAX_BUCKETS} buckets
     */
    public List<EventVolumeBucket> histogram(Date since, String bucket, String eventSourceId, Instant now) {
        ChronoUnit unit = switch (bucket == null || bucket.isBlank() ? "hour" : bucket) {
            case "hour" -> ChronoUnit.HOURS;
            case "day" -> ChronoUnit.DAYS;
            default -> throw new WebApplicationException("Invalid bucket: must be hour or day", 400);
        };
        Instant from = since != null ? since.toInstant() : now.minus(DEFAULT_WINDOW);
        if (from.isAfter(now)) {
            throw new WebApplicationException("since must not be in the future", 400);
        }
        Instant first = from.truncatedTo(unit);
        Instant last = now.truncatedTo(unit);
        long bucketCount = unit.between(first, last) + 1;
        if (bucketCount > MAX_BUCKETS) {
            throw new WebApplicationException("Too many buckets (" + bucketCount + "); at most " + MAX_BUCKETS
                    + " are allowed: use a later since or a larger bucket", 400);
        }

        // Group by hour in the database (hour boundaries are the same in every whole-hour time zone), then
        // fold into UTC buckets here so day buckets do not depend on the database time zone.
        String jpql = "select truncate(e.createdOn, hour), count(e) from StreamEventEntity e"
                + " where e.createdOn >= :from"
                + (eventSourceId != null && !eventSourceId.isBlank() ? " and e.eventSourceId = :source" : "")
                + " group by truncate(e.createdOn, hour)";
        TypedQuery<Object[]> query = entityManager.createQuery(jpql, Object[].class).setParameter("from", from);
        if (eventSourceId != null && !eventSourceId.isBlank()) {
            query.setParameter("source", eventSourceId);
        }
        Map<Instant, Long> counts = new HashMap<>();
        query.getResultList().forEach(row -> counts.merge(toInstant(row[0]).truncatedTo(unit),
                ((Number) row[1]).longValue(), Long::sum));

        List<EventVolumeBucket> buckets = new ArrayList<>();
        for (Instant start = first; !start.isAfter(last); start = start.plus(1, unit)) {
            EventVolumeBucket b = new EventVolumeBucket();
            b.setBucketStart(Date.from(start));
            b.setCount(counts.getOrDefault(start, 0L));
            buckets.add(b);
        }
        return buckets;
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case Timestamp t -> t.toInstant();
            case LocalDateTime l -> l.atZone(ZoneId.systemDefault()).toInstant();
            case Date d -> d.toInstant();
            default -> throw new IllegalStateException("Unexpected time value: " + value.getClass());
        };
    }
}
