package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST tests for sending messages, agent routes (token-guarded), change sets, resolution and undo.
 */
@QuarkusTest
class CompositionTurnsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @InjectMock
    WorkbenchSessions sessions;

    @Inject
    CompositionService service;

    @Inject
    CompositionTurns turns;

    private long compositionId;

    @BeforeEach
    void setUp() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "ctr-wf");
        compositionId = composition.id;
        AssistantSession session = mock(AssistantSession.class);
        when(session.verifyMcpToken("good")).thenReturn(true);
        when(sessions.live(anyLong())).thenReturn(session);
    }

    @AfterEach
    void cleanUp() {
        turns.onSessionEvent(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.FAILED, "cleanup"));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void unavailableAgentIs503() throws Exception {
        org.mockito.Mockito.doThrow(new AgentUnavailableException("no agent", null)).when(sessions)
                .nextEventIndex(compositionId);
        given().contentType(ContentType.JSON).body(Map.of("message", "hi"))
                .post(BASE + "/{id}/messages", compositionId).then().statusCode(503)
                .body("message", equalTo("no agent"));
        given().get(BASE + "/{id}", compositionId).then().body("turnState", equalTo("idle"));
    }

    @Test
    void agentRoutesRequireToken() {
        given().get(BASE + "/{id}/agent/composition", compositionId).then().statusCode(401);
        given().header("X-Axiom-Session-Token", "bad").get(BASE + "/{id}/agent/composition", compositionId)
                .then().statusCode(401);
        given().header("X-Axiom-Session-Token", "good").get(BASE + "/{id}/agent/composition", compositionId)
                .then().statusCode(200).body("workflowRevision", notNullValue()).body("items", hasSize(1));
    }

    @Test
    void fullTurnThroughRest() {
        given().contentType(ContentType.JSON).body(Map.of("message", "rename start", "nodeIds", List.of("start-1")))
                .post(BASE + "/{id}/messages", compositionId).then().statusCode(200)
                .body("turnState", equalTo("ai-working"));
        given().contentType(ContentType.JSON).body(Map.of("message", "again"))
                .post(BASE + "/{id}/messages", compositionId).then().statusCode(409);

        String revision = given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/composition", compositionId).then().extract().path("workflowRevision");
        Map<String, Object> changeSet = Map.of("id", "cs1", "baseRevision", revision,
                "author", "agent:axiom-workbench", "summary", "rename",
                "ops", List.of(Map.of("op", "updateNode", "id", "start-1", "patch", Map.of("name", "Begin"))));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(changeSet)
                .post(BASE + "/{id}/agent/workflow/changesets", compositionId).then().statusCode(200)
                .body("applied", equalTo(true));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(changeSet)
                .post(BASE + "/{id}/agent/workflow/changesets", compositionId).then().statusCode(200)
                .body("applied", equalTo(false)).body("error.code", equalTo("stale"));

        Map<String, Object> tool = Map.of("type", "tool", "content",
                Map.of("name", "ctr-tool", "description", "d", "parameters", List.of(), "scriptTemplate", "echo hi"));
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON).body(tool)
                .put(BASE + "/{id}/agent/items", compositionId).then().statusCode(200).body("name", equalTo("ctr-tool"));
        String turnId = given().get(BASE + "/{id}", compositionId).then().extract().path("currentTurnId");

        turns.onSessionEvent(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        given().get(BASE + "/{id}", compositionId).then().statusCode(200)
                .body("turnState", equalTo("idle")).body("pendingTurnId", equalTo(turnId))
                .body("pendingChangeSet.ops", hasSize(1));

        given().contentType(ContentType.JSON).body(Map.of("turnId", turnId, "outcome", "applied"))
                .post(BASE + "/{id}/changeset-resolution", compositionId).then().statusCode(204);
        given().post(BASE + "/{id}/turns/{turnId}/undo", compositionId, turnId).then().statusCode(200)
                .body("removed", equalTo(List.of("tool/ctr-tool")));
    }

    @Test
    void staleResolutionFromPendingDoesNotRetry() throws Exception {
        String revision = given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/composition", compositionId).then().extract().path("workflowRevision");
        String turnId = turns.start(compositionId, "rename", List.of(), List.of());
        turns.applyChangeSet(compositionId, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                "id", "cs1", "baseRevision", revision, "author", "agent:axiom-workbench", "summary", "rename",
                "ops", List.of(Map.of("op", "updateNode", "id", "start-1", "patch", Map.of("name", "Begin"))))));
        turns.onSessionEvent(new WorkbenchSessionEvent(compositionId, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        given().contentType(ContentType.JSON).body(Map.of("turnId", turnId, "outcome", "stale", "fromPending", true))
                .post(BASE + "/{id}/changeset-resolution", compositionId).then().statusCode(204);
        given().get(BASE + "/{id}", compositionId).then().statusCode(200)
                .body("turnState", equalTo("idle")).body("pendingTurnId", org.hamcrest.Matchers.nullValue());
        org.mockito.Mockito.verify(sessions).notice(compositionId, CompositionTurns.STALE_PENDING_NOTICE);
    }
}
