package io.apitomy.axiom.app;

import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;

@QuarkusTest
class EventSchemasResourceTest {

    @Inject
    EventStreamService service;

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.delete("eventSourceId", "schema-rest"));
    }

    @Test
    void listsAllEventTypes() {
        given().when().get("/api/v1/schemas/events").then()
                .statusCode(200)
                .body("$", hasSize(26))
                .body("type", hasItem("issue.closed"));
    }

    @Test
    void returnsAssembledSchema() {
        given().when().get("/api/v1/schemas/events/issue.closed").then()
                .statusCode(200)
                .body("title", equalTo("issue.closed"))
                .body("properties.payload.properties.type.const", equalTo("issue.closed"));
    }

    @Test
    void unknownTypeIs404() {
        given().when().get("/api/v1/schemas/events/nope").then().statusCode(404);
    }

    @Test
    void streamEventExposesValidationErrors() {
        NormalizedEvent event = EventStreamValidationTest.event("schema-rest", "P-7-1",
                "2026-10-21T14:53:07.835+0000");
        service.persistEvent(event);
        given().when().get("/api/v1/stream/events/" + event.id()).then()
                .statusCode(200)
                .body("validationErrors", not(empty()));
    }
}
