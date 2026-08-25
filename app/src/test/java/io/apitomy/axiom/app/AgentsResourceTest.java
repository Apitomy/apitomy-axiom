package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Agents REST API endpoints.
 */
@QuarkusTest
class AgentsResourceTest {

    private static final String AGENTS_PATH = "/api/v1/agents";

    @Test
    void testListAgents() {
        given()
            .when()
                .get(AGENTS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    void testCreateAndGetAgent() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Claude Code Agent",
                    "description": "AI agent powered by Claude Code",
                    "agentType": "claude-code",
                    "enabled": true,
                    "capabilities": ["action:analyze", "action:implement", "action:review"]
                }
                """)
            .when()
                .post(AGENTS_PATH)
            .then()
                .statusCode(200)
                .body("name", equalTo("Claude Code Agent"))
                .body("description", equalTo("AI agent powered by Claude Code"))
                .body("agentType", equalTo("claude-code"))
                .body("enabled", equalTo(true))
                .body("capabilities", hasItems("action:analyze", "action:implement", "action:review"))
                .body("id", notNullValue())
                .extract().path("id");

        given()
            .when()
                .get(AGENTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Claude Code Agent"))
                .body("id", equalTo(id));
    }

    @Test
    void testUpdateAgent() {
        int id = createAgent("Update Agent", "claude-code");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Updated Agent",
                    "agentType": "claude-code",
                    "capabilities": ["action:analyze", "action:propose"]
                }
                """)
            .when()
                .put(AGENTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Updated Agent"))
                .body("capabilities", hasItems("action:analyze", "action:propose"));
    }

    @Test
    void testDeleteAgent() {
        int id = createAgent("Delete Agent", "opencode");

        given()
            .when()
                .delete(AGENTS_PATH + "/" + id)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(AGENTS_PATH + "/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    void testGetAgentNotFound() {
        given()
            .when()
                .get(AGENTS_PATH + "/999999")
            .then()
                .statusCode(404);
    }

    private int createAgent(String name, String agentType) {
        return given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "name": "%s",
                    "agentType": "%s",
                    "enabled": true
                }
                """, name, agentType))
            .when()
                .post(AGENTS_PATH)
            .then()
                .statusCode(200)
                .extract().path("id");
    }
}
