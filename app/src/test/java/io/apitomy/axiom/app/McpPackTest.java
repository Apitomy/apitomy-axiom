package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration packs and MCP servers: validation, conflict modes and round trips.
 */
@QuarkusTest
class McpPackTest {

    private static final String IMPORT = "/api/v1/system/packs/import";

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity.delete("name like ?1", "mp-%");
            SessionTemplateEntity.<SessionTemplateEntity>list("name like ?1", "mp-%").forEach(t -> t.delete());
            McpServerEntity.delete("name like ?1", "mp-%");
        });
    }

    private void createServer(String name, String command) {
        QuarkusTransaction.requiringNew().run(() -> {
            McpServerEntity e = new McpServerEntity();
            e.name = name;
            e.serverCommand = command;
            e.persist();
        });
    }

    @Test
    void exportedActionTypeWithAllowedToolsCanBeReImported() {
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "mp-at";
            at.executionMode = "agent";
            at.promptTemplate = "p";
            at.allowedTools = "Read, Bash(git log *)";
            at.persist();
            return at.id;
        });
        JsonNode item = QuarkusTransaction.requiringNew().call(() ->
                importExportService.serializeActionType(ActionTypeEntity.findById(id)));
        assertTrue(item.path("allowedTools").isArray(), item.toString());
        ObjectNode pack = mapper.createObjectNode();
        pack.putArray("actionTypes").add(item);
        importExportService.importOrUpdatePack(pack);
        QuarkusTransaction.requiringNew().run(() -> assertEquals("Read,Bash(git log *)",
                ActionTypeEntity.<ActionTypeEntity>find("name", "mp-at").firstResult().allowedTools));
    }

    @Test
    void legacyPacksWithCommaSeparatedAllowedToolsStillImport() throws Exception {
        JsonNode pack = mapper.readTree("""
                {"actionTypes": [{"name": "mp-legacy", "executionMode": "agent", "promptTemplate": "p",
                  "allowedTools": "Read, Edit"}]}
                """);
        importExportService.importPack(pack);
        QuarkusTransaction.requiringNew().run(() -> assertEquals("Read,Edit",
                ActionTypeEntity.<ActionTypeEntity>find("name", "mp-legacy").firstResult().allowedTools));
    }

    @Test
    void invalidMcpServersAreRejected() throws Exception {
        for (String server : List.of(
                "{\"name\": \"mp bad\", \"serverCommand\": \"npx\"}",
                "{\"name\": \"mp-both\", \"serverCommand\": \"npx\", \"serverUrl\": \"https://x.io\"}",
                "{\"name\": \"mp-hdr\", \"serverCommand\": \"npx\", \"headers\": {\"A\": \"b\"}}")) {
            JsonNode pack = mapper.readTree("{\"mcpServers\": [" + server + "]}");
            ConfigValidationException e = assertThrows(ConfigValidationException.class,
                    () -> importExportService.importPack(pack), server);
            assertTrue(e.problemsByItem().keySet().stream().anyMatch(k -> k.startsWith("mcp-server/")),
                    e.problemsByItem().toString());
        }
    }

    @Test
    void templatesReferencingUnknownServersAreRejected() throws Exception {
        JsonNode pack = mapper.readTree("""
                {"sessionTemplates": [{"templateId": "mp-t", "name": "mp-t", "description": "d",
                  "systemPrompt": "s", "allowedTools": ["Read"], "mcpServers": ["mp-missing"]}]}
                """);
        assertThrows(ConfigValidationException.class, () -> importExportService.importPack(pack));
    }

    private static final String CONFLICT_PACK = """
            {"mcpServers": [{"name": "mp-existing", "serverCommand": "uvx"},
                            {"name": "mp-new", "serverCommand": "npx"}]}
            """;

    @Test
    void conflictsFailByDefault() {
        createServer("mp-existing", "npx");
        given().contentType(ContentType.JSON).body(CONFLICT_PACK).post(IMPORT)
                .then().statusCode(409).body("conflicts[0].name", equalTo("mp-existing"));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(0, McpServerEntity.count("name", "mp-new")));
    }

    @Test
    void conflictsCanBeSkipped() {
        createServer("mp-existing", "npx");
        given().contentType(ContentType.JSON).body(CONFLICT_PACK).queryParam("onConflict", "skip").post(IMPORT)
                .then().statusCode(200).body("mcpServers", equalTo(1));
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals("npx", McpServerEntity.<McpServerEntity>find("name", "mp-existing").firstResult().serverCommand);
            assertEquals(1, McpServerEntity.count("name", "mp-new"));
        });
    }

    @Test
    void conflictsCanBeOverwritten() {
        createServer("mp-existing", "npx");
        given().contentType(ContentType.JSON).body(CONFLICT_PACK).queryParam("onConflict", "overwrite").post(IMPORT)
                .then().statusCode(200).body("mcpServers", equalTo(2));
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals("uvx", McpServerEntity.<McpServerEntity>find("name", "mp-existing").firstResult().serverCommand));
    }
}
