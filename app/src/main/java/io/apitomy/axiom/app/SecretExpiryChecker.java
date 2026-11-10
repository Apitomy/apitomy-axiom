package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SecretExpiryChanged;
import io.apitomy.axiom.core.services.SecretExpiry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Periodically emits {@code secret.expiring} and {@code secret.expired} stream events. Each event fires at
 * most once per expiry; updating a secret resets the bookkeeping.
 */
@ApplicationScoped
public class SecretExpiryChecker {

    private static final Logger LOG = Logger.getLogger(SecretExpiryChecker.class);
    static final String EXPIRING = "secret.expiring";
    static final String EXPIRED = "secret.expired";

    @Inject
    SystemEventPublisher publisher;

    @Inject
    Event<SecretExpiryChanged> expiryChanges;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "axiom.secrets.expiry-warning-days", defaultValue = "7")
    int defaultWarnDays;

    @Scheduled(every = "${axiom.secrets.expiry-check-interval:15m}", delayed = "1m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledCheck() {
        check(Instant.now());
    }

    /**
     * Immutable copy of the expiry-related columns of a secret, read without keeping a managed entity so
     * the checker never writes stale columns back.
     *
     * @param id                  the secret id
     * @param name                the secret name
     * @param expiresOn           the expiration seen when the snapshot was taken
     * @param warnDaysBefore      the per-secret warning override, or {@code null}
     * @param expiryWarningSentOn when the warning was sent, or {@code null}
     * @param expiredEventSentOn  when the expired event was sent, or {@code null}
     */
    record Snapshot(Long id, String name, Instant expiresOn, Integer warnDaysBefore,
                    Instant expiryWarningSentOn, Instant expiredEventSentOn) {
    }

    /**
     * Checks every secret with an expiration, each in its own transaction.
     *
     * @param now the current time
     * @return the number of events emitted
     */
    public int check(Instant now) {
        int emitted = 0;
        for (Snapshot snapshot : snapshots()) {
            try {
                emitted += checkOne(snapshot, now);
            } catch (Exception e) {
                LOG.warnf(e, "Secret expiry check failed for secret %d", snapshot.id());
            }
        }
        return emitted;
    }

    /**
     * Reads snapshots of every secret with an expiration in a read-only transaction.
     *
     * @return the snapshots
     */
    List<Snapshot> snapshots() {
        return QuarkusTransaction.requiringNew().call(() ->
                SecretEntity.<SecretEntity>list("expiresOn is not null").stream()
                        .map(s -> new Snapshot(s.id, s.name, s.expiresOn, s.warnDaysBefore,
                                s.expiryWarningSentOn, s.expiredEventSentOn))
                        .toList());
    }

    /**
     * Emits the due event for one secret in its own transaction, but only if this checker wins the claim.
     * The claim is a conditional bulk update that also requires the expiration to be unchanged since the
     * snapshot, so a concurrent rotation is never overwritten and each event is emitted at most once.
     *
     * @param snapshot the secret snapshot
     * @param now      the current time
     * @return 1 if an event was emitted, otherwise 0
     */
    int checkOne(Snapshot snapshot, Instant now) {
        SecretExpiry.Status status = SecretExpiry.status(snapshot.expiresOn(), snapshot.warnDaysBefore(),
                defaultWarnDays, now);
        String type;
        if (status == SecretExpiry.Status.EXPIRED && snapshot.expiredEventSentOn() == null) {
            type = EXPIRED;
        } else if (status == SecretExpiry.Status.EXPIRING && snapshot.expiryWarningSentOn() == null) {
            type = EXPIRING;
        } else {
            return 0;
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            int claimed = EXPIRED.equals(type)
                    ? claimExpired(snapshot.id(), snapshot.expiresOn(), now)
                    : claimWarning(snapshot.id(), snapshot.expiresOn(), now);
            if (claimed != 1) {
                return 0;
            }
            emit(snapshot, type, now);
            expiryChanges.fire(new SecretExpiryChanged(snapshot.id(), status.name()));
            return 1;
        });
    }

    /**
     * Claims the expired event (and the warning, if not yet sent) for a secret whose expiration is still
     * {@code expiresOn}. Must run in a transaction.
     *
     * @param id        the secret id
     * @param expiresOn the expected expiration
     * @param now       the current time
     * @return the number of rows claimed (0 or 1)
     */
    int claimExpired(Long id, Instant expiresOn, Instant now) {
        return SecretEntity.update("expiredEventSentOn = ?1, expiryWarningSentOn = coalesce(expiryWarningSentOn, ?1)"
                + " where id = ?2 and expiresOn = ?3 and expiredEventSentOn is null", now, id, expiresOn);
    }

    /**
     * Claims the expiring warning for a secret whose expiration is still {@code expiresOn}. Must run in a
     * transaction.
     *
     * @param id        the secret id
     * @param expiresOn the expected expiration
     * @param now       the current time
     * @return the number of rows claimed (0 or 1)
     */
    int claimWarning(Long id, Instant expiresOn, Instant now) {
        return SecretEntity.update("expiryWarningSentOn = ?1"
                + " where id = ?2 and expiresOn = ?3 and expiryWarningSentOn is null", now, id, expiresOn);
    }

    private void emit(Snapshot secret, String type, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("secretId", secret.id());
        payload.put("secretName", secret.name());
        payload.put("expiresOn", secret.expiresOn().toString());
        payload.put("daysRemaining", SecretExpiry.daysRemaining(secret.expiresOn(), now));
        publisher.publish("system", "axiom", type, secret.name(), "{\"login\":\"system\"}",
                payload.toString(), now);
        LOG.infof("Emitted %s for secret '%s' (expires %s)", type, secret.name(), secret.expiresOn());
    }
}
