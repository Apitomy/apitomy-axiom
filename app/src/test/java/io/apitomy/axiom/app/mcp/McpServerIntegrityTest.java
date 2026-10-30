package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Referential integrity for MCP servers: usage, blocked deletes, rename cascades, validation, and missing
 * servers.
 */
@QuarkusTest
class McpServerIntegrityTest {

    private static final String BASE = "/api/v1/mcp-servers";

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ValidationContextFactory contextFactory;

    @Inject
    McpServerResolver resolver;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity.delete("name like ?1", "mi-%");
            SessionTemplateEntity.<SessionTemplateEntity>list("name like ?1", "mi-%").forEach(t -> t.delete());
            ToolsetEntity.delete("name like ?1", "mi-%");
            McpServerEntity.delete("name like ?1", "mi-%");
        });
    }

    private static int createServer(String name) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"serverCommand\":\"npx\"}")
                .post(BASE).then().statusCode(200).extract().path("id");
    }

    /** Creates references to "mi-gh" from every kind of item, plus a near-miss to "mi-gh-ent". */
    private void createReferences() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity selects = new ActionTypeEntity();
            selects.name = "mi-at-selects";
            selects.executionMode = "agent";
            selects.mcpServers.add("mi-gh");
            selects.persist();

            ActionTypeEntity allows = new ActionTypeEntity();
            allows.name = "mi-at-allows";
            allows.executionMode = "agent";
            allows.allowedTools = "Read, mcp__mi-gh__list_issues, mcp__mi-gh-ent__x";
            allows.persist();

            SessionTemplateEntity template = new SessionTemplateEntity();
            template.templateId = "mi-template";
            template.name = "mi-template";
            template.description = "d";
            template.systemPrompt = "s";
            template.mcpServers.add("mi-gh");
            template.allowedTools.add("mcp__mi-gh__*");
            template.persist();

            ToolsetEntity toolset = new ToolsetEntity();
            toolset.name = "mi-toolset";
            toolset.tools = "Read,mcp__mi-gh__get_issue";
            toolset.persist();
        });
    }

    @Test
    void usageListsEveryReference() {
        int id = createServer("mi-gh");
        createServer("mi-gh-ent");
        createReferences();
        given().get(BASE + "/{id}/usage", id).then().statusCode(200)
                .body("", hasSize(5))
                .body("collect { it.type + ':' + it.name + ':' + it.field }", containsInAnyOrder(
                        "actionType:mi-at-selects:mcpServers",
                        "actionType:mi-at-allows:allowedTools",
                        "sessionTemplate:mi-template:mcpServers",
                        "sessionTemplate:mi-template:allowedTools",
                        "toolset:mi-toolset:tools"));
    }

    @Test
    void deletingAServerInUseIsBlocked() {
        int id = createServer("mi-gh");
        createReferences();
        given().delete(BASE + "/{id}", id).then().statusCode(409)
                .body("message", containsString("in use"))
                .body("usages", hasSize(5));
        given().get(BASE + "/{id}", id).then().statusCode(200);
    }

    @Test
    void deletingAnUnusedServerSucceeds() {
        int id = createServer("mi-unused");
        given().get(BASE + "/{id}/usage", id).then().statusCode(200).body("", empty());
        given().delete(BASE + "/{id}", id).then().statusCode(204);
    }

    @Test
    void renameCascadesToEveryReference() {
        int id = createServer("mi-gh");
        createServer("mi-gh-ent");
        createReferences();
        given().contentType(ContentType.JSON).body("{\"name\":\"mi-github\",\"serverCommand\":\"npx\"}")
                .put(BASE + "/{id}", id).then().statusCode(200);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(List.of("mi-github"),
                    ActionTypeEntity.<ActionTypeEntity>find("name", "mi-at-selects").firstResult().mcpServers);
            assertEquals("Read, mcp__mi-github__list_issues, mcp__mi-gh-ent__x",
                    ActionTypeEntity.<ActionTypeEntity>find("name", "mi-at-allows").firstResult().allowedTools);
            SessionTemplateEntity t = SessionTemplateEntity.find("name", "mi-template").firstResult();
            assertEquals(List.of("mi-github"), t.mcpServers);
            assertEquals(List.of("mcp__mi-github__*"), t.allowedTools);
            assertEquals("Read,mcp__mi-github__get_issue",
                    ToolsetEntity.<ToolsetEntity>find("name", "mi-toolset").firstResult().tools);
        });
    }

    @Test
    void validationRejectsReferencesToUnknownServers() throws Exception {
        createServer("mi-known");
        List<ConfigProblem> problems = QuarkusTransaction.requiringNew().call(() -> validationService.validate(
                ConfigItemType.ACTION_TYPE, mapper.readTree("""
                        {"name":"mi-v","executionMode":"agent","promptTemplate":"p",
                         "allowedTools":["mcp__mi-known__a","mcp__mi-nope__b"],"mcpServers":["mi-known","mi-gone"]}
                        """), contextFactory.databaseOnly()));
        List<String> errors = problems.stream().filter(ConfigProblem::isError).map(ConfigProblem::message).toList();
        assertTrue(errors.stream().anyMatch(m -> m.contains("mi-nope")), errors.toString());
        assertTrue(errors.stream().anyMatch(m -> m.contains("mi-gone")), errors.toString());
        assertFalse(errors.stream().anyMatch(m -> m.contains("mi-known")), errors.toString());
    }

    @Test
    void validationRejectsSessionTemplatesSelectingUnknownServers() throws Exception {
        List<ConfigProblem> problems = QuarkusTransaction.requiringNew().call(() -> validationService.validate(
                ConfigItemType.SESSION_TEMPLATE, mapper.readTree("""
                        {"name":"mi-st","description":"d","systemPrompt":"s","templateId":"x",
                         "allowedTools":["Read"],"mcpServers":["@axiom-tools","mi-gone"]}
                        """), contextFactory.databaseOnly()));
        List<String> errors = problems.stream().filter(ConfigProblem::isError).map(ConfigProblem::message).toList();
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("mi-gone"));
    }

    @Test
    void findsMissingServers() {
        createServer("mi-here");
        List<String> missing = QuarkusTransaction.requiringNew().call(() ->
                resolver.findMissing(List.of("mi-here", "mi-absent")));
        assertEquals(List.of("mi-absent"), missing);
    }
}
