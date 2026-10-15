package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * REST tests for compositions and their draft items.
 */
@QuarkusTest
class CompositionsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    static long createComposition() {
        return given().contentType(ContentType.JSON).body("{\"name\":\"rest-c\",\"goal\":\"triage\"}")
                .post(BASE).then().statusCode(200)
                .body("turnState", equalTo("idle")).body("applyMode", equalTo("live"))
                .extract().jsonPath().getLong("id");
    }

    static JsonPath addItem(long compositionId, String type, String name) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("type", type, "name", name))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(200)
                .body("content", notNullValue()).extract().jsonPath();
    }

    @Test
    void createsListsUpdatesAndDeletes() {
        long id = createComposition();
        given().get(BASE).then().statusCode(200).body("$", hasSize(1));
        given().contentType(ContentType.JSON).body("{\"applyMode\":\"review\"}")
                .patch(BASE + "/{id}", id).then().statusCode(200).body("applyMode", equalTo("review"));
        given().delete(BASE + "/{id}", id).then().statusCode(204);
        given().get(BASE + "/{id}", id).then().statusCode(404);
    }

    @Test
    void addsItemsAndRejectsSecondWorkflow() {
        long id = createComposition();
        addItem(id, "workflow", "rest-wf");
        addItem(id, "tool", "rest-tool");
        given().get(BASE + "/{id}/items", id).then().statusCode(200)
                .body("$", hasSize(2)).body("[0].type", equalTo("workflow"))
                .body("[0].content", nullValue()).body("[1].changed", equalTo(true));
        given().contentType(ContentType.JSON).body("{\"type\":\"workflow\",\"name\":\"other\"}")
                .post(BASE + "/{id}/items", id).then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"type\":\"toolset\",\"name\":\"x\"}")
                .post(BASE + "/{id}/items", id).then().statusCode(400);
    }

    @Test
    void savesWithBaseRevisionAndReportsStale() {
        long id = createComposition();
        JsonPath tool = addItem(id, "tool", "rest-tool");
        long itemId = tool.getLong("id");
        String revision = tool.getString("contentRevision");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "edited");

        String newRevision = given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(200)
                .body("lastAuthor", equalTo("user")).body("content.description", equalTo("edited"))
                .extract().path("contentRevision");

        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(409)
                .body("currentRevision", equalTo(newRevision));
        given().get(BASE + "/{id}/items/{itemId}", id, 999_999L).then().statusCode(404);
    }

    @Test
    void deletesItem() {
        long id = createComposition();
        long itemId = addItem(id, "tool", "rest-tool").getLong("id");
        given().delete(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(204);
        given().get(BASE + "/{id}/items", id).then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void saveWithTurnIdIsRecordedAsAi() {
        long id = createComposition();
        JsonPath tool = addItem(id, "tool", "rest-ai-tool");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "applied from AI change set");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", tool.getString("contentRevision"), "turnId", "turn-9"))
                .put(BASE + "/{id}/items/{itemId}", id, tool.getLong("id")).then().statusCode(200)
                .body("lastAuthor", equalTo("ai")).body("lastTurnId", equalTo("turn-9"));
        given().get(BASE + "/{id}", id).then().statusCode(200)
                .body("pendingChangeSet", nullValue()).body("currentTurnId", nullValue());
    }

    @Test
    void saveWithBlankTurnIdIsRecordedAsUser() {
        long id = createComposition();
        JsonPath tool = addItem(id, "tool", "rest-blank-turn-tool");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "edited");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", tool.getString("contentRevision"), "turnId", " "))
                .put(BASE + "/{id}/items/{itemId}", id, tool.getLong("id")).then().statusCode(200)
                .body("lastAuthor", equalTo("user")).body("lastTurnId", nullValue());
    }

    @Test
    void addsABlankSubscriptionItem() {
        long compositionId = createComposition();
        JsonPath item = addItem(compositionId, "subscription", "on-pr");
        assertEquals("subscription", item.getString("type"));
        assertEquals("on-pr", item.getString("name"));
        assertEquals(0, item.getList("content.routing").size());
        given().get(BASE + "/{id}/items", compositionId).then().statusCode(200).body("type", hasItem("subscription"));
    }

    @Test
    void existingSubscriptionsCannotBeAdded() {
        long compositionId = createComposition();
        given().contentType(ContentType.JSON).body(Map.of("type", "subscription", "existingItemId", 1))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(400);
    }

    @Test
    void enabledEndpointRejectsUnpublishedAndNonSubscriptionItems() {
        long compositionId = createComposition();
        long subscriptionId = addItem(compositionId, "subscription", "en-sub").getLong("id");
        long toolId = addItem(compositionId, "tool", "en-tool").getLong("id");
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/" + subscriptionId + "/enabled")
                .then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/" + toolId + "/enabled")
                .then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"enabled\": true}")
                .when().put("/api/v1/compositions/" + compositionId + "/items/999999999/enabled")
                .then().statusCode(404);
    }
}
