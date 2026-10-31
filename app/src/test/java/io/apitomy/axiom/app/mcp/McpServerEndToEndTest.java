package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.McpConfigGenerator;
import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end through the REST API: a user stores a token as a secret, adds an MCP server that uses it, tests
 * the connection, selects the server on an action type, and a task of that type gets the server with the
 * resolved token and permission to use its tools.
 */
@QuarkusTest
class McpServerEndToEndTest {

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    McpConfigGenerator generator;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity.delete("name", "e2e-triage");
            McpServerEntity.delete("name", "e2e-fake");
            SecretEntity.delete("name", "E2E_FAKE_TOKEN");
        });
    }

    @Test
    void serverIsConfiguredTestedSelectedAndUsedByATask() throws Exception {
        String script = Path.of(getClass().getResource("/mcp/fake-stdio-server.js").toURI()).toString();

        given().contentType(ContentType.JSON).body("{\"name\":\"E2E_FAKE_TOKEN\",\"value\":\"tok-123\"}")
                .post("/api/v1/secrets").then().statusCode(200);

        int serverId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "e2e-fake", "transport", "stdio", "serverCommand", "node",
                        "serverArgs", List.of(script), "serverEnv", Map.of("FAKE_TOKEN", "${secret:E2E_FAKE_TOKEN}")))
                .post("/api/v1/mcp-servers").then().statusCode(200).extract().path("id");

        // The test resolves the secret: the fake server echoes it in its tool description.
        given().post("/api/v1/mcp-servers/{id}/test", serverId).then().statusCode(200)
                .body("success", equalTo(true))
                .body("tools[0].description", equalTo("Echo tok-123"));
        given().get("/api/v1/mcp-servers/{id}", serverId).then()
                .body("serverEnv.FAKE_TOKEN", equalTo("${secret:E2E_FAKE_TOKEN}"))
                .body("tools.name", contains("echo", "add"));

        given().contentType(ContentType.JSON)
                .body(Map.of("name", "e2e-triage", "executionMode", "agent", "promptTemplate", "Triage",
                        "allowedTools", List.of("Read"), "mcpServers", List.of("e2e-fake")))
                .post("/api/v1/action-types").then().statusCode(200);
        given().get("/api/v1/mcp-servers/{id}/usage", serverId).then()
                .body("name", contains("e2e-triage"));

        List<String> allowed = QuarkusTransaction.requiringNew().call(() ->
                taskExecutionService.effectiveAllowedTools("e2e-triage"));
        assertEquals(List.of("Read", "mcp__e2e-fake__*"), allowed);

        Path config = QuarkusTransaction.requiringNew().call(() ->
                generator.generateMcpConfig(9401L, Map.of("TASK_ONLY", "x"), allowed));
        JsonNode server = mapper.readTree(Files.readString(config)).path("mcpServers").path("e2e-fake");
        assertEquals("node", server.path("command").asText());
        assertEquals("tok-123", server.path("env").path("FAKE_TOKEN").asText());
        assertTrue(server.path("env").path("TASK_ONLY").isMissingNode(), "task env must not leak to the server");

        // A referenced server cannot be deleted.
        given().delete("/api/v1/mcp-servers/{id}", serverId).then().statusCode(409);
    }
}
