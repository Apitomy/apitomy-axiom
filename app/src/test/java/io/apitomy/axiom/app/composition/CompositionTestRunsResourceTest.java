package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST tests for test runs of composition drafts.
 */
@QuarkusTest
class CompositionTestRunsResourceTest {

    static final String BASE = "/api/v1/compositions";

    /**
     * Records the test-run removal SSE events fired in the application.
     */
    @Singleton
    public static class RemovalRecorder {
        final List<SseEvent> events = new CopyOnWriteArrayList<>();

        void onSse(@Observes SseEvent event) {
            if ("composition-test-runs-removed".equals(event.type())) {
                events.add(event);
            }
        }
    }

    @Inject
    RemovalRecorder removals;

    @InjectMock
    WorkbenchSessions sessions;

    @Inject
    EncryptionService encryptionService;

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
            SecretEntity.delete("name", "CTR_TOKEN");
        });
    }

    /** A tool draft whose script prints the TOKEN and CTR_TOKEN variables it was given. */
    static long envTool(long compositionId) {
        long itemId = given().contentType(ContentType.JSON).body(Map.of("type", "tool", "name", "env-tool"))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(200)
                .extract().jsonPath().getLong("id");
        String revision = given().get(BASE + "/{id}/items/{itemId}", compositionId, itemId)
                .then().statusCode(200).extract().jsonPath().getString("contentRevision");
        Map<String, Object> content = Map.of("name", "env-tool", "description", "Prints its environment",
                "parameters", List.of(),
                "scriptTemplate", "echo \"token=${TOKEN:-none} ctr=${CTR_TOKEN:-none}\"");
        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", compositionId, itemId).then().statusCode(200);
        return itemId;
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
    void aToolTestGetsOnlyTheEnvironmentItIsGiven() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity secret = new SecretEntity();
            secret.name = "CTR_TOKEN";
            secret.encryptedValue = encryptionService.encrypt("s3cret");
            secret.persist();
        });
        long id = CompositionsResourceTest.createComposition();
        long itemId = envTool(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of()))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("passed"))
                .body("stdout", containsString("token=none ctr=none"));
        given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of(), "environment", Map.of("TOKEN", "${secret:CTR_TOKEN}")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("passed"))
                .body("stdout", containsString("token=s3cret ctr=none"));
        given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of(), "environment", Map.of("TOKEN", "${secret:CTR_MISSING}")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("error"))
                .body("message", containsString("Secret 'CTR_MISSING' does not exist"));
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

    private static String suggest(long id, String name) {
        return given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", name, "inputs", Map.of("who", "ann")))
                .post(BASE + "/{id}/agent/test-suggestions", id).then().statusCode(200)
                .extract().jsonPath().getString("id");
    }

    @Test
    void runningASuggestionReplacesItInPlace() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        String older = suggest(id, "greet");
        String suggestion = suggest(id, "greet");
        String newer = runTest(id, itemId);
        given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of("who", "edited"), "runId", "client-run-0002",
                        "suggestionId", suggestion))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("id", equalTo(suggestion))
                .body("status", equalTo("passed"))
                .body("author", equalTo("user"))
                .body("outputs.greeting", equalTo("hi edited"));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(3))
                .body("[0].id", equalTo(newer))
                .body("[1].id", equalTo(suggestion)).body("[1].status", equalTo("passed"))
                .body("[2].id", equalTo(older)).body("[2].status", equalTo("suggested"));
        given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/test-runs/{runId}", id, suggestion).then().statusCode(200)
                .body("status", equalTo("passed"));
    }

    @Test
    void runningAnAlreadyRunSuggestionAddsANewRun() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        String suggestion = suggest(id, "greet");
        Map<String, Object> body = Map.of("inputs", Map.of("who", "bob"), "suggestionId", suggestion);
        given().contentType(ContentType.JSON).body(body)
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("id", equalTo(suggestion));
        String rerun = given().contentType(ContentType.JSON).body(body)
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .extract().jsonPath().getString("id");
        assertNotEquals(suggestion, rerun);
        given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of("who", "bob"), "suggestionId", "unknown-suggestion"))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200);
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200).body("$", hasSize(3));
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

    @Test
    void agentTestRunRouteRedactsSecrets() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity secret = new SecretEntity();
            secret.name = "CTR_TOKEN";
            secret.encryptedValue = encryptionService.encrypt("s3cret");
            secret.persist();
        });
        long id = CompositionsResourceTest.createComposition();
        long itemId = envTool(id);
        String runId = given().contentType(ContentType.JSON)
                .body(Map.of("inputs", Map.of(), "environment",
                        Map.of("TOKEN", "${secret:CTR_TOKEN}", "CTR_TOKEN", "literal-value")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("stdout", containsString("token=s3cret ctr=literal-value"))
                .extract().jsonPath().getString("id");
        given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/test-runs/{runId}", id, runId).then().statusCode(200)
                .body("id", equalTo(runId))
                .body("status", equalTo("passed"))
                .body("itemName", equalTo("env-tool"))
                .body("stdout", containsString("token=[redacted]"))
                .body("stdout", not(containsString("s3cret")))
                .body("resolvedScript", notNullValue())
                .body("environment.TOKEN", equalTo("${secret:CTR_TOKEN}"))
                .body("environment.CTR_TOKEN", equalTo("[redacted]"));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("[0]", not(hasKey("environment")));
    }

    @Test
    void agentTestRunRouteRequiresTheSessionTokenAndAKnownRun() {
        long id = CompositionsResourceTest.createComposition();
        given().get(BASE + "/{id}/agent/test-runs/{runId}", id, "nope-1234").then().statusCode(401);
        given().header("X-Axiom-Session-Token", "good")
                .get(BASE + "/{id}/agent/test-runs/{runId}", id, "nope-1234").then().statusCode(404);
    }

    private static String runTest(long id, long itemId) {
        return given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "bob")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .extract().jsonPath().getString("id");
    }

    @Test
    void removesOneRunAndFiresAnSseEvent() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        String first = runTest(id, itemId);
        String second = runTest(id, itemId);
        removals.events.clear();
        given().delete(BASE + "/{id}/test-runs/{runId}", id, first).then().statusCode(204);
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(1)).body("[0].id", equalTo(second));
        assertEquals(1, removals.events.size());
        assertEquals("{\"compositionId\":" + id + ",\"runIds\":[\"" + first + "\"]}",
                removals.events.get(0).data());
    }

    @Test
    void removingAnUnknownRunIs404() {
        long id = CompositionsResourceTest.createComposition();
        given().delete(BASE + "/{id}/test-runs/{runId}", id, "nope-nope-nope").then().statusCode(404);
        given().delete(BASE + "/{id}/test-runs/{runId}", 999_999L, "nope-nope-nope").then().statusCode(404);
    }

    @Test
    void clearsAllRunsAndSuggestions() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        runTest(id, itemId);
        given().header("X-Axiom-Session-Token", "good").contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", "greet", "inputs", Map.of("who", "ai")))
                .post(BASE + "/{id}/agent/test-suggestions", id).then().statusCode(200);
        removals.events.clear();
        given().delete(BASE + "/{id}/test-runs", id).then().statusCode(204);
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200).body("$", hasSize(0));
        assertEquals(1, removals.events.size());
        // Clearing an empty list fires nothing.
        given().delete(BASE + "/{id}/test-runs", id).then().statusCode(204);
        assertEquals(1, removals.events.size());
        given().delete(BASE + "/{id}/test-runs", 999_999L).then().statusCode(404);
    }
}
