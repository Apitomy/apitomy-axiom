package io.apitomy.axiom.core.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/**
 * Sends the {@code secret-expiry} SSE notification once a secret expiry change has been committed, so
 * clients never refetch stale data (and are never notified of rolled-back changes).
 */
@ApplicationScoped
public class SecretExpirySseNotifier {

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Fires the {@code secret-expiry} SSE event after the changing transaction committed. Without an
     * active transaction the observer runs immediately.
     *
     * @param changed the committed change
     */
    void onSecretExpiryChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) SecretExpiryChanged changed) {
        sseEvents.fire(SseEvent.secretExpiry(changed.secretId(), changed.status()));
    }
}
