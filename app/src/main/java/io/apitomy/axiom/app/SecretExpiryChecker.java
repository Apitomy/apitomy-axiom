package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SseEvent;
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
    Event<SseEvent> sseEvents;

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
     * Checks every secret with an expiration, each in its own transaction.
     *
     * @param now the current time
     * @return the number of events emitted
     */
    public int check(Instant now) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() ->
                SecretEntity.<SecretEntity>list("expiresOn is not null").stream().map(s -> s.id).toList());
        int emitted = 0;
        for (Long id : ids) {
            try {
                emitted += QuarkusTransaction.requiringNew().call(() -> checkOne(id, now));
            } catch (Exception e) {
                LOG.warnf(e, "Secret expiry check failed for secret %d", id);
            }
        }
        return emitted;
    }

    private int checkOne(Long id, Instant now) {
        SecretEntity secret = SecretEntity.findById(id);
        if (secret == null || secret.expiresOn == null) {
            return 0;
        }
        SecretExpiry.Status status = SecretExpiry.status(secret.expiresOn, secret.warnDaysBefore,
                defaultWarnDays, now);
        if (status == SecretExpiry.Status.EXPIRED && secret.expiredEventSentOn == null) {
            emit(secret, EXPIRED, now);
            secret.expiredEventSentOn = now;
            if (secret.expiryWarningSentOn == null) {
                secret.expiryWarningSentOn = now;
            }
            sseEvents.fire(SseEvent.secretExpiry(secret.id, status.name()));
            return 1;
        }
        if (status == SecretExpiry.Status.EXPIRING && secret.expiryWarningSentOn == null) {
            emit(secret, EXPIRING, now);
            secret.expiryWarningSentOn = now;
            sseEvents.fire(SseEvent.secretExpiry(secret.id, status.name()));
            return 1;
        }
        return 0;
    }

    private void emit(SecretEntity secret, String type, Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("secretId", secret.id);
        payload.put("secretName", secret.name);
        payload.put("expiresOn", secret.expiresOn.toString());
        payload.put("daysRemaining", SecretExpiry.daysRemaining(secret.expiresOn, now));
        publisher.publish("system", "axiom", type, secret.name, "{\"login\":\"system\"}",
                payload.toString(), now);
        LOG.infof("Emitted %s for secret '%s' (expires %s)", type, secret.name, secret.expiresOn);
    }
}
