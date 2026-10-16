package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST tests for revisions, validation and publish.
 */
@QuarkusTest
class CompositionsResourceFlowTest {

    static final String BASE = CompositionsResourceTest.BASE;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.list("name like ?1", "crf-%").forEach(e -> e.delete());
            ActionTypeEntity.list("name like ?1", "crf-%").forEach(e -> e.delete());
        });
    }

    @Test
    void listsAndRestoresRevisions() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath tool = CompositionsResourceTest.addItem(id, "tool", "crf-tool");
        long itemId = tool.getLong("id");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "v2");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", tool.getString("contentRevision")))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(200);

        JsonPath revisions = given().get(BASE + "/{id}/items/{itemId}/revisions", id, itemId)
                .then().statusCode(200).body("$", hasSize(2)).extract().jsonPath();
        long first = revisions.getLong("[1].id");
        given().post(BASE + "/{id}/items/{itemId}/revisions/{rev}/restore", id, itemId, first)
                .then().statusCode(200).body("contentRevision", equalTo(tool.getString("contentRevision")));
    }

    @Test
    void validatesUnsavedContentAndWholeComposition() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath workflow = CompositionsResourceTest.addItem(id, "workflow", "crf-wf");
        Map<String, Object> content = workflow.getMap("content");
        given().contentType(ContentType.JSON).body(Map.of("content", content))
                .post(BASE + "/{id}/items/{itemId}/validate", id, workflow.getLong("id"))
                .then().statusCode(200).body("valid", equalTo(true));
        given().post(BASE + "/{id}/validate", id).then().statusCode(200)
                .body("items", hasSize(1)).body("items[0].name", equalTo("crf-wf"));
    }

    @Test
    void publishesAndReportsBlockedItems() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath workflow = CompositionsResourceTest.addItem(id, "workflow", "crf-wf");
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of(workflow.getLong("id"))))
                .post(BASE + "/{id}/publish", id).then().statusCode(200)
                .body("created", equalTo(1)).body("items[0].changed", equalTo(false));

        // Tool names with spaces are valid, so make the tool invalid with an empty scriptTemplate.
        JsonPath badTool = CompositionsResourceTest.addItem(id, "tool", "crf-bad-tool");
        Map<String, Object> badContent = badTool.getMap("content");
        badContent.put("scriptTemplate", "");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", badContent, "baseRevision", badTool.getString("contentRevision")))
                .put(BASE + "/{id}/items/{itemId}", id, badTool.getLong("id")).then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of(badTool.getLong("id"))))
                .post(BASE + "/{id}/publish", id).then().statusCode(422)
                .body("problems", hasKey("tool/crf-bad-tool"));
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of()))
                .post(BASE + "/{id}/publish", id).then().statusCode(400);
    }
}
