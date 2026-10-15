package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run's initial context holds exactly the declared standard inputs; required ones must be available. */
@QuarkusTest
class WorkflowTriggerInputGuardTest {

    @Inject
    WorkflowExecutionService service;

    @Inject
    EventStreamOrchestrator orchestrator;

    /** Start → wait (keeps the run active so its context can be read) → end, with the given Start inputs. */
    private static String content(String inputs) {
        return """
            {"id": "in-wf", "name": "Inputs WF",
             "nodes": [
               {"id": "s1", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
               {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"}, "position": {"x": 0, "y": 100}},
               {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}],
             "edges": [
               {"id": "x1", "source": "s1", "target": "w1", "priority": 0, "isDefault": false},
               {"id": "x2", "source": "w1", "target": "e1", "priority": 0, "isDefault": false}]}
            """.formatted(inputs);
    }

    private static final String SUBJECT_REQUIRED = "[{\"name\": \"subject\", \"type\": \"object\", \"required\": true}]";
    private static final String EVENT_REQUIRED = "[{\"name\": \"event\", \"type\": \"object\", \"required\": true}]";
    private static final String EVENT_OPTIONAL = "[{\"name\": \"event\", \"type\": \"object\", \"required\": false}]";

    private static UUID event() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity e = new StreamEventEntity();
            e.id = id;
            e.sourceEventId = "inputs-" + id;
            e.source = "github";
            e.eventSourceId = "conn-inputs";
            e.type = "issue.created";
            e.ref = "test/inputs-event-" + id;
            e.timestamp = Instant.now();
            e.actor = "{\"login\": \"tester\"}";
            e.payload = "{\"n\": 5}";
            e.createdOn = Instant.now();
            e.persist();
        });
        return id;
    }

    @Test
    void contextHoldsOnlyTheDeclaredSubject() {
        long subjectId = TestFixtures.createSubject("inputs-subject-only");
        long defId = TestFixtures.createDefinition("Inputs Subject Only", content(SUBJECT_REQUIRED));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(200)
                .body("context.subject.id", equalTo((int) subjectId))
                .body("context.subject.name", equalTo("inputs-subject-only"))
                .body("context.subject.repository", nullValue())
                .body("context.ref", nullValue())
                .body("context.subjectId", nullValue())
                .body("context.event", nullValue())
                .body("inputs.subject.ref", equalTo("test/inputs-subject-only"));
    }

    @Test
    void noDeclaredInputsGivesAnEmptyContext() {
        long subjectId = TestFixtures.createSubject("inputs-none");
        long defId = TestFixtures.createWaitDefinition("Inputs None");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                service.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));
        given().when().get("/api/v1/workflow/runs/" + run.id)
                .then().statusCode(200).body("context.subject", nullValue()).body("inputs", nullValue());
    }

    @Test
    void requiredEventWithoutAnEventFailsWithNamedInput() {
        long subjectId = TestFixtures.createSubject("inputs-no-event");
        long defId = TestFixtures.createDefinition("Needs Event", content(EVENT_REQUIRED));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(400)
                .body("message", equalTo(
                        "Cannot start workflow \"Needs Event\": required input(s) not available: event"));
        long runs = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.count("subjectId = ?1 and definitionId = ?2", subjectId, defId));
        assertEquals(0, runs);
    }

    @Test
    void failureMessageIsTheExceptionMessage() {
        long subjectId = TestFixtures.createSubject("inputs-no-event-msg");
        long defId = TestFixtures.createDefinition("Needs Event Msg", content(EVENT_REQUIRED));
        WebApplicationException e = assertThrows(WebApplicationException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        service.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null)));
        assertTrue(e.getMessage().contains("required input(s) not available: event"), e.getMessage());
    }

    @Test
    void optionalEventIsAbsentWithoutAnEvent() {
        long subjectId = TestFixtures.createSubject("inputs-optional-event");
        long defId = TestFixtures.createDefinition("Optional Event", content(EVENT_OPTIONAL));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/subjects/" + subjectId + "/workflow")
                .then().statusCode(200).body("context.event", nullValue());
    }

    @Test
    void eventStartedRunGetsTheEventButNotInItsStoredInputs() {
        UUID eventId = event();
        long defId = TestFixtures.createDefinition("Event Run",
                content("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true},"
                        + " {\"name\": \"event\", \"type\": \"object\", \"required\": true}]"));
        given().contentType(ContentType.JSON).body("{\"workflowDefinitionId\": %d}".formatted(defId))
                .when().post("/api/v1/stream/events/" + eventId + "/runs")
                .then().statusCode(200)
                .body("context.event.type", equalTo("issue.created"))
                .body("context.event.payload.n", equalTo(5))
                .body("inputs.event", nullValue())
                .body("inputs.subject.id", org.hamcrest.Matchers.notNullValue());
    }
}
