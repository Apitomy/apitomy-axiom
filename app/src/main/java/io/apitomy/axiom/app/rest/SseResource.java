package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.events.SseEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE endpoint that streams real-time events to connected UI clients.
 * Listens for CDI {@link SseEvent} events and broadcasts them to all
 * connected subscribers.
 *
 * <p>Uses a set of emitters instead of BroadcastProcessor to avoid
 * back-pressure failures when no clients are connected or clients
 * can't keep up.</p>
 *
 * <p>Sends periodic heartbeat events to keep SSE connections alive
 * through proxies that close idle connections.</p>
 */
@Path("/api/v1/sse")
@ApplicationScoped
public class SseResource {

    private static final Logger LOG = Logger.getLogger(SseResource.class);

    private final Set<MultiEmitter<? super SseEvent>> emitters = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!emitters.isEmpty()) {
                onSseEvent(SseEvent.heartbeat());
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * SSE stream endpoint. Clients connect here and receive real-time events.
     *
     * @return a multi that emits SSE events
     */
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<SseEvent> stream() {
        LOG.info("SSE client connected");
        return Multi.createFrom().emitter(emitter -> {
            emitters.add(emitter);
            emitter.onTermination(() -> {
                emitters.remove(emitter);
                LOG.info("SSE client disconnected");
            });
        });
    }

    /**
     * Observes CDI SseEvent events and broadcasts them to all connected clients.
     * Silently drops events if no clients are connected.
     *
     * @param event the event to broadcast
     */
    void onSseEvent(@Observes SseEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        if (!"heartbeat".equals(event.type())) {
            LOG.debugf("Broadcasting SSE event: %s to %d client(s)",
                    event.type(), emitters.size());
        }
        for (MultiEmitter<? super SseEvent> emitter : emitters) {
            try {
                emitter.emit(event);
            } catch (Exception e) {
                LOG.debugf("Failed to emit SSE event to client: %s", e.getMessage());
            }
        }
    }
}
