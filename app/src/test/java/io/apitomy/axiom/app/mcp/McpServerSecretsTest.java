package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.McpConfigGenerator;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.MissingSecretException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP server env values and headers may be plain values or {@code ${secret:NAME}} references. References are
 * stored verbatim and resolved only when a session or task is launched.
 */
@QuarkusTest
class McpServerSecretsTest {

    @Inject
    EncryptionService encryptionService;

    @Inject
    McpServerResolver resolver;

    @Inject
    McpConfigGenerator generator;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void createSecret() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity secret = new SecretEntity();
            secret.name = "MST_TOKEN";
            secret.encryptedValue = encryptionService.encrypt("s3cr3t");
            secret.persist();
        });
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            SecretEntity.delete("name like ?1", "MST_%");
            McpServerEntity.delete("name like ?1", "mst-%");
        });
    }

    private void persist(String name, String command, String env, String url, String headers) {
        QuarkusTransaction.requiringNew().run(() -> {
            McpServerEntity e = new McpServerEntity();
            e.name = name;
            e.serverCommand = command;
            e.serverEnv = env;
            e.serverUrl = url;
            e.transport = url != null ? "http" : "stdio";
            e.headers = headers;
            e.persist();
        });
    }

    @Test
    void envAndHeaderReferencesAreResolvedAndPlainValuesKept() {
        persist("mst-stdio", "npx", "{\"TOKEN\":\"${secret:MST_TOKEN}\",\"PLAIN\":\"p\"}", null, null);
        persist("mst-http", null, null, "https://x.io/mcp", "{\"Authorization\":\"Bearer ${secret:MST_TOKEN}\"}");

        Map<String, McpServerDefinition> defs = QuarkusTransaction.requiringNew().call(() ->
                resolver.resolveByName(List.of("mst-stdio", "mst-http")));

        assertEquals(Map.of("TOKEN", "s3cr3t", "PLAIN", "p"), defs.get("mst-stdio").env());
        assertEquals(Map.of("Authorization", "Bearer s3cr3t"), defs.get("mst-http").headers());
    }

    @Test
    void missingSecretFailsResolution() {
        persist("mst-missing", "npx", "{\"TOKEN\":\"${secret:MST_NOPE}\"}", null, null);
        MissingSecretException e = assertThrows(MissingSecretException.class, () ->
                QuarkusTransaction.requiringNew().call(() -> resolver.resolveByName(List.of("mst-missing"))));
        assertTrue(e.getMessage().contains("mst-missing"), e.getMessage());
        assertTrue(e.getMessage().contains("MST_NOPE"), e.getMessage());
    }

    @Test
    void taskConfigContainsResolvedValueAndIsOwnerReadable() throws Exception {
        persist("mst-task", "npx", "{\"TOKEN\":\"${secret:MST_TOKEN}\"}", null, null);
        Path file = QuarkusTransaction.requiringNew().call(() ->
                generator.generateMcpConfig(9301L, Map.of(), List.of("mcp__mst-task__x")));
        JsonNode node = mapper.readTree(Files.readString(file)).get("mcpServers").get("mst-task");
        assertEquals("s3cr3t", node.get("env").get("TOKEN").asText());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

    @Test
    void taskConfigGenerationFailsOnMissingSecret() {
        persist("mst-task-missing", "npx", "{\"TOKEN\":\"${secret:MST_NOPE}\"}", null, null);
        assertThrows(MissingSecretException.class, () -> QuarkusTransaction.requiringNew().call(() ->
                generator.generateMcpConfig(9302L, Map.of(), List.of("mcp__mst-task-missing__x"))));
    }

    @Test
    void referencesAreStoredVerbatimAndUnknownSecretsProduceWarnings() {
        int id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"mst-rest\",\"transport\":\"http\",\"serverUrl\":\"https://x.io/mcp\","
                        + "\"headers\":{\"Authorization\":\"Bearer ${secret:MST_TOKEN}\",\"X\":\"${secret:MST_UNKNOWN}\"}}")
                .post("/api/v1/mcp-servers").then().statusCode(200)
                .body("headers.Authorization", equalTo("Bearer ${secret:MST_TOKEN}"))
                .body("warnings", contains("Secret 'MST_UNKNOWN' does not exist"))
                .extract().path("id");
        given().get("/api/v1/mcp-servers/{id}", id).then().statusCode(200)
                .body("headers.X", equalTo("${secret:MST_UNKNOWN}"));

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"mst-rest2\",\"serverCommand\":\"npx\",\"serverEnv\":{\"T\":\"${secret:MST_TOKEN}\"}}")
                .post("/api/v1/mcp-servers").then().statusCode(200)
                .body("warnings", empty());
    }
}
