package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test bean recording every {@code secret-expiry} SSE event together with what a separate transaction could
 * see at the moment the event was sent, proving whether the change was already committed.
 */
@Singleton
public class SecretSseRecorder {

    /**
     * A recorded SSE event.
     *
     * @param event              the SSE event
     * @param secretVisible      whether a new transaction could see the secret row
     * @param warningSentVisible whether a new transaction could see a non-null expiryWarningSentOn
     */
    public record Recorded(SseEvent event, boolean secretVisible, boolean warningSentVisible) {
    }

    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    void onSse(@Observes SseEvent event) {
        if (!"secret-expiry".equals(event.type())) {
            return;
        }
        Long id = Long.valueOf(event.data().replaceAll(".*\"secretId\":(\\d+).*", "$1"));
        SecretEntity seen = QuarkusTransaction.requiringNew().call(() -> SecretEntity.<SecretEntity>findById(id));
        recorded.add(new Recorded(event, seen != null, seen != null && seen.expiryWarningSentOn != null));
    }

    /**
     * Returns recorded events for one secret.
     *
     * @param secretId the secret id
     * @return the recorded events in order
     */
    public List<Recorded> forSecret(long secretId) {
        return recorded.stream().filter(r -> r.event().data().contains("\"secretId\":" + secretId + ",")).toList();
    }

    /**
     * Clears all recorded events.
     */
    public void clear() {
        recorded.clear();
    }
}
