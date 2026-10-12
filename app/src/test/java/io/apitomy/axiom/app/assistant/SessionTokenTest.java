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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the per-session MCP token: generation, hand-off to the MCP server, and enforcement.
 */
@QuarkusTest
class SessionTokenTest {

    private static final String BODY = "{\"type\": \"tool\", \"item\": {\"name\": \"t\"}}";

    @InjectMock
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    @Inject
    AssistantSessionManager manager;

    private String sessionId;

    @BeforeEach
    void create() throws IOException {
        when(interactiveSessionDriverFactory.createDriver(any()))
                .thenAnswer(i -> {
                    InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
                    when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
                    return driver;
                });
        sessionId = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"axiom-config-assistant\",\"name\":\"token-test\"}")
                .post("/api/v1/assistant/sessions").then().statusCode(200).extract().path("id");
    }

    @AfterEach
    void delete() {
        given().delete("/api/v1/assistant/sessions/{id}", sessionId);
    }

    @Test
    void tokenIsPassedToAssistantMcpServer() throws IOException {
        AssistantSession session = manager.getSession(sessionId);
        JsonNode config = new ObjectMapper().readTree(
                Files.readString(session.getSessionDirectory().resolve("mcp-config.json")));
        assertEquals(session.getMcpToken(),
                config.path("mcpServers").path("axiom").path("env").path("AXIOM_SESSION_TOKEN").asText());
    }

    @Test
    void tokensAreUniqueAndVerified() {
        AssistantSession session = manager.getSession(sessionId);
        assertTrue(session.getMcpToken().length() >= 43);
        assertTrue(session.verifyMcpToken(session.getMcpToken()));
        assertFalse(session.verifyMcpToken("wrong"));
        assertFalse(session.verifyMcpToken(null));
        String other = given().contentType(ContentType.JSON)
                .body("{\"templateId\":\"axiom-config-assistant\",\"name\":\"token-test-2\"}")
                .post("/api/v1/assistant/sessions").then().statusCode(200).extract().path("id");
        try {
            assertNotEquals(session.getMcpToken(), manager.getSession(other).getMcpToken());
        } finally {
            given().delete("/api/v1/assistant/sessions/{id}", other);
        }
    }

    @Test
    void validateRequiresToken() {
        given().contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(401);
        given().contentType(ContentType.JSON).header("X-Axiom-Session-Token", "wrong").body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(401);
        given().contentType(ContentType.JSON)
                .header("X-Axiom-Session-Token", manager.getSession(sessionId).getMcpToken()).body(BODY)
                .post("/api/v1/assistant/sessions/{id}/validate", sessionId).then().statusCode(200);
    }

    @Test
    void sessionEventsAreJournaled() throws IOException {
        AssistantSession session = manager.getSession(sessionId);
        com.fasterxml.jackson.databind.node.ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("text", "journal me");
        session.addEvent(new AssistantEventParser.SseEvent("assistant_text", data));
        manager.journal(sessionId).flush();
        java.util.List<AssistantEventParser.SseEvent> loaded = HistoryJournal.load(
                session.getSessionDirectory().resolve(HistoryJournal.FILE_NAME), new ObjectMapper());
        assertEquals("journal me", loaded.get(loaded.size() - 1).data().path("text").asText());
    }
}
