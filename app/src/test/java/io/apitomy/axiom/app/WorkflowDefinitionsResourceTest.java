package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkflowDefinitionsResourceTest {

    private static final String BASE_PATH = "/api/v1/workflow-definitions";

    @Test
    void testCreateAndGetWorkflowDefinition() {
        int id = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Test Workflow",
                        "description": "A test workflow"
                    }
                    """)
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Test Workflow"))
                    .body("description", equalTo("A test workflow"))
                    .body("id", notNullValue())
                    .body("content", notNullValue())
                    .body("currentVersion", nullValue())
                    .body("createdOn", notNullValue())
                    .body("updatedOn", notNullValue())
                    .extract().path("id");

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Test Workflow"))
                    .body("content.nodes.size()", equalTo(2))
                    .body("content.edges.size()", equalTo(1));
    }

    @Test
    void testListWorkflowDefinitions() {
        createDefinition("List Test WF 1");
        createDefinition("List Test WF 2");

        given()
                .when()
                    .get(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("items.size()", greaterThanOrEqualTo(2))
                    .body("totalCount", greaterThanOrEqualTo(2))
                    .body("page", equalTo(1))
                    .body("limit", equalTo(20));
    }

    @Test
    void testFilterByName() {
        createDefinition("Unique Filter WF Name");

        given()
                .queryParam("filterName", "Unique Filter WF")
                .when()
                    .get(BASE_PATH)
                .then()
                    .statusCode(200)
                    .body("items.size()", greaterThanOrEqualTo(1))
                    .body("items.name", hasItem(containsString("Unique Filter WF")));
    }

    @Test
    void testUpdateMetadata() {
        int id = createDefinition("Update Meta WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Updated WF Name",
                        "description": "Updated description"
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("name", equalTo("Updated WF Name"))
                    .body("description", equalTo("Updated description"));
    }

    @Test
    void testUpdateContent() {
        int id = createDefinition("Content Update WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Content Update WF",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("content.id", equalTo("wf-test"));
    }

    @Test
    void testPublishValidWorkflow() {
        int id = createDefinition("Publish WF");

        // Update with valid content
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-pub",
                        "name": "Publish WF",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        // Publish
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("definitionId", equalTo(id))
                    .body("content", notNullValue())
                    .body("createdOn", notNullValue());

        // Verify currentVersion updated
        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("currentVersion", equalTo(1));

        // Publish again — version increments
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(2));
    }

    @Test
    void testPublishInvalidWorkflowReturns400() {
        int id = createDefinition("Invalid Publish WF");

        // Update with invalid content (no start node)
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-invalid",
                        "name": "Invalid",
                        "nodes": [
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": []
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        // Publish should fail
        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(400);
    }

    @Test
    void testListVersions() {
        int id = createAndPublishDefinition("Versions List WF");

        given()
                .when()
                    .get(BASE_PATH + "/" + id + "/versions")
                .then()
                    .statusCode(200)
                    .body("size()", equalTo(1))
                    .body("[0].version", equalTo(1));
    }

    @Test
    void testGetSpecificVersion() {
        int id = createAndPublishDefinition("Version Get WF");

        given()
                .when()
                    .get(BASE_PATH + "/" + id + "/versions/1")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("content", notNullValue());
    }

    @Test
    void testDeleteWorkflowDefinition() {
        int id = createDefinition("Delete WF");

        given()
                .when()
                    .delete(BASE_PATH + "/" + id)
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(404);
    }

    @Test
    void testGetNotFound() {
        given()
                .when()
                    .get(BASE_PATH + "/99999")
                .then()
                    .statusCode(404);
    }

    @Test
    void testDuplicateNameReturns409() {
        createDefinition("Dup Name WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Dup Name WF"
                    }
                    """)
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(409);
    }

    // -- Helpers --

    private int createDefinition(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s"
                    }
                    """, name))
                .when()
                    .post(BASE_PATH)
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    private int createAndPublishDefinition(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Test",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }
}
