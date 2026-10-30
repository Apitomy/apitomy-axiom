package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.app.TaskExecutionService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Action types can explicitly select MCP servers; selected servers are loaded and all their tools allowed.
 */
@QuarkusTest
class ActionTypeMcpServersTest {

    private static final String BASE = "/api/v1/action-types";

    @Inject
    TaskExecutionService taskExecutionService;

    @org.junit.jupiter.api.BeforeEach
    void createServers() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String name : List.of("github", "jira")) {
                if (io.apitomy.axiom.core.entities.McpServerEntity.count("name", name) == 0) {
                    io.apitomy.axiom.core.entities.McpServerEntity e = new io.apitomy.axiom.core.entities.McpServerEntity();
                    e.name = name;
                    e.serverCommand = "npx";
                    e.persist();
                }
            }
        });
    }

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity.delete("name like ?1", "atm-%");
            io.apitomy.axiom.core.entities.McpServerEntity.delete("name in ?1", List.of("github", "jira"));
        });
    }

    @Test
    void mcpServersRoundTripThroughRest() {
        int id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"atm-rest\",\"executionMode\":\"agent\",\"promptTemplate\":\"p\","
                        + "\"allowedTools\":[\"Read\"],\"mcpServers\":[\"github\",\"jira\"]}")
                .post(BASE).then().statusCode(200)
                .body("mcpServers", contains("github", "jira"))
                .extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"atm-rest\",\"executionMode\":\"agent\",\"promptTemplate\":\"p\","
                        + "\"mcpServers\":[\"jira\"]}")
                .put(BASE + "/{id}", id).then().statusCode(200)
                .body("mcpServers", contains("jira"));
    }

    @Test
    void selectedServersAddWildcardAllowedTools() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "atm-tools";
            at.executionMode = "agent";
            at.allowedTools = "Read, mcp__github__list_issues";
            at.mcpServers.addAll(List.of("github", "jira"));
            at.persist();
        });
        List<String> tools = QuarkusTransaction.requiringNew().call(() ->
                taskExecutionService.effectiveAllowedTools("atm-tools"));
        assertEquals(List.of("Read", "mcp__github__list_issues", "mcp__github__*", "mcp__jira__*"), tools);
    }

    @Test
    void selectedServersAreAddedToDefaultTools() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = "atm-default";
            at.executionMode = "agent";
            at.mcpServers.add("github");
            at.persist();
        });
        List<String> tools = QuarkusTransaction.requiringNew().call(() ->
                taskExecutionService.effectiveAllowedTools("atm-default"));
        assertEquals(List.of("Read", "Bash(grep *)", "Bash(find *)", "mcp__github__*"), tools);
    }
}
