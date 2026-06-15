package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for tool labels: create, update, filter, and pack export.
 */
@QuarkusTest
class ToolLabelsTest {

    private static final String TOOLS_PATH = "/api/v1/tools";

    @Test
    void testCreateToolWithLabels() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "label-test-tool-1",
                        "description": "A tool for label testing",
                        "scriptTemplate": "echo test",
                        "labels": ["github", "automation"]
                    }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("name", equalTo("label-test-tool-1"))
                .body("labels", hasItems("github", "automation"))
                .body("labels.size()", equalTo(2))
                .extract().path("id");

        // Verify labels persist on GET
        given()
                .when()
                .get(TOOLS_PATH + "/" + toolId)
                .then()
                .statusCode(200)
                .body("labels", hasItems("github", "automation"));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
    }

    @Test
    void testUpdateToolLabels() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    { "name": "label-test-tool-2", "scriptTemplate": "echo test", "labels": ["alpha"] }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("labels", hasItems("alpha"))
                .extract().path("id");

        // Update labels
        given()
                .contentType(ContentType.JSON)
                .body("""
                    { "name": "label-test-tool-2", "scriptTemplate": "echo test", "labels": ["beta", "gamma"] }
                    """)
                .when()
                .put(TOOLS_PATH + "/" + toolId)
                .then()
                .statusCode(200)
                .body("labels", hasItems("beta", "gamma"))
                .body("labels", not(hasItem("alpha")));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
    }

    @Test
    void testFilterToolsBySingleLabel() {
        // Create two tools with different labels
        int id1 = createToolWithLabels("filter-test-a", "github", "ci");
        int id2 = createToolWithLabels("filter-test-b", "jira", "reporting");

        // Filter by "github" — should find only tool a
        given()
                .queryParam("filterLabels", "github")
                .when()
                .get(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("items.name", hasItem("filter-test-a"))
                .body("items.name", not(hasItem("filter-test-b")));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + id1).then().statusCode(204);
        given().when().delete(TOOLS_PATH + "/" + id2).then().statusCode(204);
    }

    @Test
    void testFilterToolsByMultipleLabelsAndLogic() {
        // Create tools: tool-x has [github, ci], tool-y has [github, reporting]
        int idX = createToolWithLabels("and-test-x", "github", "ci");
        int idY = createToolWithLabels("and-test-y", "github", "reporting");

        // Filter by "github,ci" (AND) — should find only tool-x
        given()
                .queryParam("filterLabels", "github,ci")
                .when()
                .get(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("items.name", hasItem("and-test-x"))
                .body("items.name", not(hasItem("and-test-y")));

        // Filter by "github" alone — should find both
        given()
                .queryParam("filterLabels", "github")
                .when()
                .get(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("items.name", hasItems("and-test-x", "and-test-y"));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + idX).then().statusCode(204);
        given().when().delete(TOOLS_PATH + "/" + idY).then().statusCode(204);
    }

    @Test
    void testNoLabelFilterReturnsAll() {
        int id = createToolWithLabels("no-filter-test", "misc");

        given()
                .when()
                .get(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("items.name", hasItem("no-filter-test"));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + id).then().statusCode(204);
    }

    @Test
    void testLabelsIncludedInPackExport() {
        int id = createToolWithLabels("pack-label-test", "export", "pack");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "Label Test Pack",
                        "toolIds": [%d]
                    }
                    """.formatted(id))
                .when()
                .post("/api/v1/system/packs/export")
                .then()
                .statusCode(200)
                .body("tools[0].name", equalTo("pack-label-test"))
                .body("tools[0].labels", hasItems("export", "pack"));

        // Cleanup
        given().when().delete(TOOLS_PATH + "/" + id).then().statusCode(204);
    }

    private int createToolWithLabels(String name, String... labels) {
        String labelsJson = "[" + String.join(",",
                java.util.Arrays.stream(labels).map(l -> "\"" + l + "\"").toList()) + "]";
        return given()
                .contentType(ContentType.JSON)
                .body("""
                    { "name": "%s", "scriptTemplate": "echo test", "labels": %s }
                    """.formatted(name, labelsJson))
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");
    }
}
