package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@code POST /assistant/sessions/{sessionId}/validate}.
 */
@QuarkusTest
class AssistantValidateEndpointTest {

    private static final String WORKFLOW = """
        {"name": "wf-validate-test", "content": {
          "id": "wf-validate-test", "name": "wf-validate-test",
          "nodes": [
            {"id": "s", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
            {"id": "a", "type": "action", "name": "Do", "config": {"actionType": "at-x"},
             "position": {"x": 200, "y": 0}},
            {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 400, "y": 0}}
          ],
          "edges": [
            {"id": "e1", "source": "s", "target": "a", "priority": 0, "isDefault": true},
            {"id": "e2", "source": "a", "target": "e", "priority": 0, "isDefault": true}
          ]}}
        """;

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @Inject
    AssistantSessionManager assistantSessionManager;

    private String sessionId;

    @BeforeEach
    void createSession() throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any())).thenAnswer(i -> new NoopDriver());
        sessionId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"templateId":"axiom-config-assistant","name":"validate-test"}
                        """)
                .when()
                .post("/api/v1/assistant/sessions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    @AfterEach
    void deleteSession() {
        given().when().delete("/api/v1/assistant/sessions/{id}", sessionId);
    }

    private static String request(String type, String item) {
        return "{\"type\": \"" + type + "\", \"item\": " + item + "}";
    }

    /**
     * Regression test: the MCP server's {@code AXIOM_ASSISTANT_SESSION_ID} must be the session's public id,
     * otherwise {@code axiom_validate_item} calls the validate endpoint with an unknown id and gets 404.
     *
     * @throws IOException if mcp-config.json cannot be read
     */
    @Test
    void mcpServerSessionIdMatchesPublicSessionId() throws IOException {
        Path sessionDir = assistantSessionManager.getSession(sessionId).getSessionDirectory();
        JsonNode mcpConfig = new ObjectMapper()
                .readTree(Files.readString(sessionDir.resolve("mcp-config.json")));
        String mcpSessionId = mcpConfig.path("mcpServers").path("axiom").path("env")
                .path("AXIOM_ASSISTANT_SESSION_ID").asText();
        assertEquals(sessionId, mcpSessionId);

        given()
                .contentType(ContentType.JSON)
                .body(request("workflow", WORKFLOW))
                .when()
                .post("/api/v1/assistant/sessions/{id}/validate", mcpSessionId)
                .then()
                .statusCode(200);
    }

    /**
     * Applying a session with no items fails with a clear message and keeps the session.
     */
    @Test
    void applyWithNothingToApplyFailsAndKeepsSession() {
        given().when().post("/api/v1/assistant/sessions/{id}/apply", sessionId)
                .then().statusCode(422)
                .body("errors[0]", containsString("Nothing to apply"));
        given().when().get("/api/v1/assistant/sessions/{id}", sessionId).then().statusCode(200);
    }

    /**
     * Applying a session whose working directory was removed fails and keeps the session.
     *
     * @throws IOException if the directory cannot be removed
     */
    @Test
    void applyWithMissingWorkingDirectoryFailsAndKeepsSession() throws IOException {
        Path workDir = assistantSessionManager.getSession(sessionId).getWorkingDirectory();
        try (var paths = Files.walk(workDir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        given().when().post("/api/v1/assistant/sessions/{id}/apply", sessionId)
                .then().statusCode(422)
                .body("errors[0]", containsString("working directory"));
        given().when().get("/api/v1/assistant/sessions/{id}", sessionId).then().statusCode(200);
    }

    @Test
    void uppercaseWorkflowIsInvalid() {
        given()
                .contentType(ContentType.JSON)
                .body(request("workflow", WORKFLOW.replace("\"type\": \"start\"", "\"type\": \"START\"")))
                .when()
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId)
                .then()
                .statusCode(200)
                .body("valid", equalTo(false))
                .body("problems[0].code", equalTo("WORKFLOW_PARSE_ERROR"));
    }

    @Test
    void actionTypeWrittenInSessionCountsAsExisting() throws IOException {
        Path workDir = assistantSessionManager.getSession(sessionId).getWorkingDirectory();
        Path actionTypes = workDir.resolve("action-types");
        Files.createDirectories(actionTypes);
        Files.writeString(actionTypes.resolve("at-x.json"), """
                {"name": "at-x", "description": "Script action", "executionMode": "script",
                 "scriptTemplate": "#!/bin/bash\\necho done"}
                """);

        given()
                .contentType(ContentType.JSON)
                .body(request("workflow", WORKFLOW))
                .when()
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId)
                .then()
                .statusCode(200)
                .body("valid", equalTo(true));
    }

    @Test
    void applyWithInvalidWorkflowReturns422WithDetails() throws IOException {
        Path workDir = assistantSessionManager.getSession(sessionId).getWorkingDirectory();
        Path workflows = workDir.resolve("workflows");
        Files.createDirectories(workflows);
        Files.writeString(workflows.resolve("wf-validate-test.json"), WORKFLOW);

        given()
                .when()
                .post("/api/v1/assistant/sessions/{id}/apply", sessionId)
                .then()
                .statusCode(422)
                .body("message", equalTo("Items have validation errors"))
                .body("errors.size()", greaterThan(0))
                .body("errors[0]", containsString("at-x"));
    }

    @Test
    void unknownSessionReturns404() {
        given()
                .contentType(ContentType.JSON)
                .body(request("workflow", WORKFLOW))
                .when()
                .post("/api/v1/assistant/sessions/{id}/validate", "no-such-session")
                .then()
                .statusCode(404);
    }

    private static final class NoopDriver implements InteractiveSessionDriver {

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void sendUserMessage(String message) {
            // no-op
        }

        @Override
        public void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) {
            // no-op
        }

        @Override
        public void interrupt() {
            // no-op
        }

        @Override
        public void destroy() {
            // no-op
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public AssistantSession.Status getStatus() {
            return AssistantSession.Status.RUNNING;
        }

        @Override
        public String getErrorMessage() {
            return null;
        }
    }
}
