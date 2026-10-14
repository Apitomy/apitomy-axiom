package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST tests for test runs of composition drafts.
 */
@QuarkusTest
class CompositionTestRunsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @InjectMock
    WorkbenchSessions sessions;

    @BeforeEach
    void setUp() {
        AssistantSession session = mock(AssistantSession.class);
        when(session.verifyMcpToken("good")).thenReturn(true);
        when(sessions.live(anyLong())).thenReturn(session);
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    static long scriptActionType(long compositionId) {
        long itemId = given().contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", "greet", "executionMode", "script"))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(200)
                .extract().jsonPath().getLong("id");
        String revision = given().get(BASE + "/{id}/items/{itemId}", compositionId, itemId)
                .then().statusCode(200).extract().jsonPath().getString("contentRevision");
        Map<String, Object> content = Map.of("name", "greet", "description", "", "executionMode", "script",
                "inputs", List.of(Map.of("name", "who", "type", "string", "required", true)),
                "outputs", List.of(Map.of("name", "greeting", "type", "string", "required", true)),
                "scriptTemplate", "printf '{\"greeting\":\"hi %s\"}\\n' {{inputs.who}}");
        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", compositionId, itemId).then().statusCode(200);
        return itemId;
    }

    @Test
    void runsADraftAndListsTheRun() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "bob")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("passed"))
                .body("author", equalTo("user"))
                .body("itemName", equalTo("greet"))
                .body("outputs.greeting", equalTo("hi bob"))
                .body("executionMode", equalTo("script"));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(1)).body("[0].status", equalTo("passed"));
    }

    @Test
    void usesTheCallersRunIdOnlyWhenValidAndUnused() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "a"), "runId", "client-run-0001"))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("id", equalTo("client-run-0001"));
        String second = given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of("who", "a"), "runId", "client-run-0001"))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .extract().jsonPath().getString("id");
        org.junit.jupiter.api.Assertions.assertNotEquals("client-run-0001", second);
        String invalid = given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "a"), "runId", "x y"))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .extract().jsonPath().getString("id");
        org.junit.jupiter.api.Assertions.assertNotEquals("x y", invalid);
    }

    @Test
    void rejectsTheWorkflow() {
        long id = CompositionsResourceTest.createComposition();
        long workflowId = CompositionsResourceTest.addItem(id, "workflow", "test-wf").getLong("id");
        given().contentType(ContentType.JSON).body(Map.of())
                .post(BASE + "/{id}/items/{itemId}/test", id, workflowId).then().statusCode(400);
    }

    @Test
    void suggestionRouteRequiresTheSessionToken() {
        long id = CompositionsResourceTest.createComposition();
        given().contentType(ContentType.JSON).body(Map.of("type", "action-type", "name", "greet"))
                .post(BASE + "/{id}/agent/test-suggestions", id).then().statusCode(401);
    }

    @Test
    void keepsOnlyTheMostRecentRuns() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        for (int i = 0; i < CompositionTestRuns.MAX_RUNS + 2; i++) {
            given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of())).post(
                    BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200);
        }
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(CompositionTestRuns.MAX_RUNS));
    }

    @Test
    void suggestionRouteRecordsASuggestionWithoutRunning() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        String revision = given().get(BASE + "/{id}/items/{itemId}", id, itemId)
                .then().statusCode(200).extract().jsonPath().getString("contentRevision");
        Map<String, Object> content = Map.of("name", "greet", "description", "", "executionMode", "script",
                "inputs", List.of(Map.of("name", "who", "type", "string", "required", true)),
                "scriptTemplate", "exit 7");
        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(200);
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", "greet", "inputs", Map.of("who", "ann")))
                .post(BASE + "/{id}/agent/test-suggestions", id).then().statusCode(200)
                .body("status", equalTo("suggested"))
                .body("author", equalTo("ai"))
                .body("itemName", equalTo("greet"))
                .body("inputs.who", equalTo("ann"))
                .body("startedAt", notNullValue())
                .body("$", not(hasKey("exitCode")))
                .body("$", not(hasKey("durationMs")))
                .body("$", not(hasKey("stdout")));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(1)).body("[0].status", equalTo("suggested"))
                .body("[0]", not(hasKey("exitCode")));
    }

    @Test
    void suggestionRouteUnknownDraftIs404() {
        long id = CompositionsResourceTest.createComposition();
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", "nope"))
                .post(BASE + "/{id}/agent/test-suggestions", id).then().statusCode(404);
    }

    @Test
    void unknownSubjectIs400() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "bob"), "subjectId", 999999))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(400);
    }
}
