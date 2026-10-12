package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Composition sessions: the workbench MCP server is configured, history can be seeded, and directories survive.
 */
@QuarkusTest
class WorkbenchSessionManagerTest {

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    AssistantSessionManager manager;

    @Inject
    AssistantContextBuilder contextBuilder;

    @Inject
    ObjectMapper mapper;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(manager::destroySession);
    }

    private AssistantSession create(List<SseEvent> seed) throws IOException {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        AssistantSession session = manager.createSession("wb", "axiom-workbench", null, 42L, seed);
        created.add(session.getId());
        return session;
    }

    @Test
    void configuresWorkbenchServerWithCompositionAndToken() throws IOException {
        AssistantSession session = create(List.of());
        assertTrue(session.hasCapability(SessionCapability.COMPOSITION));
        JsonNode config = mapper.readTree(Files.readString(session.getSessionDirectory().resolve("mcp-config.json")));
        JsonNode env = config.path("mcpServers").path("workbench").path("env");
        assertEquals("42", env.path("AXIOM_COMPOSITION_ID").asText());
        assertEquals(session.getMcpToken(), env.path("AXIOM_SESSION_TOKEN").asText());
        assertEquals("42", Files.readString(session.getSessionDirectory().resolve("composition")).trim());
    }

    @Test
    void seededHistoryIsVisibleAndJournaledOnce() throws IOException {
        ObjectNode data = mapper.createObjectNode().put("content", "earlier question");
        AssistantSession session = create(List.of(new SseEvent("user_message", data)));
        assertEquals("earlier question", session.getEventHistory().get(0).data().path("content").asText());
        manager.journal(session.getId()).flush();
        List<SseEvent> journaled = HistoryJournal.load(
                session.getSessionDirectory().resolve(HistoryJournal.FILE_NAME), mapper);
        assertEquals(1, journaled.stream()
                .filter(e -> "earlier question".equals(e.data().path("content").asText())).count());
    }

    @Test
    void destroyCanKeepDirectory() throws IOException {
        AssistantSession session = create(List.of());
        Path dir = session.getSessionDirectory();
        manager.destroySession(session.getId(), true);
        created.remove(session.getId());
        assertTrue(Files.isDirectory(dir));
        manager.destroySession(session.getId(), false);
        contextBuilder.deleteSessionDirectory(dir);
    }
}
