package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

/**
 * REST tests for the traces API that verify traces and trace nodes can be
 * correlated with stream events by UUID.
 */
@QuarkusTest
class TraceResourceTest {

    private static final String TRACES_PATH = "/api/v1/traces";

    @Inject
    TraceService traceService;

    private UUID eventId;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            TraceNodeEntity.deleteAll();
            TraceEntity.deleteAll();
            if (eventId != null) {
                StreamEventEntity.deleteById(eventId);
            }
        });
    }

    /**
     * Filtering traces by a stream event UUID returns only the traces linked to that event.
     */
    @Test
    void filterTracesByStreamEventId() {
        eventId = createStreamEvent();
        UUID otherEventId = UUID.randomUUID();

        TraceContext matching = traceService.createTrace("manager", "matching trace",
                eventId, null, null, "manager-evaluation", "root", null, null);
        traceService.createTrace("manager", "other trace",
                otherEventId, null, null, "manager-evaluation", "root", null, null);

        given()
            .queryParam("filterEventId", eventId.toString())
            .when()
                .get(TRACES_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].traceId", equalTo(matching.traceId().toString()))
                .body("items.eventId", everyItem(equalTo(eventId.toString())));
    }

    /**
     * A malformed event ID filter is rejected with 400.
     */
    @Test
    void filterTracesByInvalidEventIdReturns400() {
        given()
            .queryParam("filterEventId", "42")
            .when()
                .get(TRACES_PATH)
            .then()
                .statusCode(400);
    }

    /**
     * A trace node referencing a stream event resolves the event as its detail.
     */
    @Test
    void eventTraceNodeDetailResolves() {
        eventId = createStreamEvent();

        TraceContext ctx = traceService.createTrace("manager", "event trace",
                eventId, null, null, "manager-evaluation", "root", null, null);
        Long nodeId = traceService.addUuidEntityNode(ctx, "event-received", "completed",
                "Event received", "event", eventId);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("node.entityType", equalTo("event"))
                .body("node.entityId", equalTo(eventId.toString()))
                .body("detail.id", equalTo(eventId.toString()))
                .body("detail.type", equalTo("issue.opened"));
    }

    /**
     * Numeric entity references continue to resolve after entity_id became a string column.
     */
    @Test
    void numericEntityTraceNodeDetailStillResolves() {
        TraceContext ctx = traceService.createTrace("test", "numeric trace",
                null, null, null, "root", "root", null, null);
        Long nodeId = traceService.addNode(ctx, "task", "in-progress",
                "Task", "task", 987654321L);

        given()
            .when()
                .get(TRACES_PATH + "/" + ctx.traceId() + "/nodes/" + nodeId)
            .then()
                .statusCode(200)
                .body("node.entityId", equalTo("987654321"));
    }

    private UUID createStreamEvent() {
        return QuarkusTransaction.requiringNew().call(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.sourceEventId = "trace-test-" + event.id;
            event.source = "github";
            event.connectionId = "trace-test";
            event.type = "issue.opened";
            event.ref = "https://github.com/example/repo/issues/1";
            event.timestamp = Instant.now();
            event.actor = "{}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();
            return event.id;
        });
    }
}
