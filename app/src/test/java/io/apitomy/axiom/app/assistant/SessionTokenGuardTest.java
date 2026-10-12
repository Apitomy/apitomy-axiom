package io.apitomy.axiom.app.assistant;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the shared MCP session-token check.
 */
class SessionTokenGuardTest {

    private final SessionTokenGuard guard = new SessionTokenGuard();

    private static AssistantSession session(String token) {
        AssistantSession session = new AssistantSession("s", "n", "t", null, null, java.util.List.of(),
                java.util.Map.of(), "claude-code", null, null, org.mockito.Mockito.mock(
                        io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver.class));
        session.setMcpToken(token);
        return session;
    }

    @Test
    void acceptsMatchingToken() {
        assertDoesNotThrow(() -> guard.require(session("secret"), "secret"));
    }

    @Test
    void rejectsMissingWrongTokenOrSession() {
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(session("secret"), null)).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(session("secret"), "nope")).getResponse().getStatus());
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> guard.require(null, "secret")).getResponse().getStatus());
    }
}
