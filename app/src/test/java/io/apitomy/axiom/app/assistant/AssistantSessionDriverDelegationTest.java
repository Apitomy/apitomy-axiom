package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSessionDriverDelegationTest {

    @Test
    void sessionDelegatesRuntimeCallsToDriver() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "claude-code", null, null, driver);

        session.start();
        session.sendMessage("hello");
        JsonNode toolInput = JsonNodeFactory.instance.objectNode().put("field", "value");
        session.respondToPermission("p1", true, toolInput);
        boolean alive = session.isAlive();
        AssistantSession.Status status = session.getStatus();
        String errorMessage = session.getErrorMessage();
        session.interrupt();
        session.destroy();

        assertEquals(1, driver.startCalls);
        assertEquals("hello", driver.lastMessage);
        assertEquals("p1", driver.lastPermissionId);
        assertTrue(driver.lastPermissionAllow);
        assertSame(toolInput, driver.lastPermissionToolInput);
        assertTrue(alive);
        assertEquals(AssistantSession.Status.ERROR, status);
        assertEquals("driver-error", errorMessage);
        assertEquals(1, driver.interruptCalls);
        assertEquals(1, driver.destroyCalls);
    }

    @Test
    void failedDeliveryRecordsMessageNotDeliveredAfterUserMessage() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        driver.sendFailure = new IOException("connection refused");
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "opencode", null, null, driver);
        session.start();

        IOException thrown = assertThrows(IOException.class, () -> session.sendMessage("hello"));

        assertSame(driver.sendFailure, thrown);
        List<SseEvent> history = session.getEventHistory();
        SseEvent userMessage = history.get(history.size() - 2);
        SseEvent error = history.get(history.size() - 1);
        assertEquals("user_message", userMessage.type());
        assertEquals("hello", userMessage.data().path("content").asText());
        assertEquals("session_error", error.type());
        assertEquals("MessageNotDelivered", error.data().path("name").asText());
        assertEquals("Message was not delivered: connection refused", error.data().path("message").asText());
    }

    @Test
    void failedDeliveryWithoutMessageUsesExceptionType() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        driver.sendFailure = new IllegalStateException();
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "opencode", null, null, driver);
        session.start();

        assertThrows(IllegalStateException.class, () -> session.sendMessage("hello"));

        List<SseEvent> history = session.getEventHistory();
        assertEquals("Message was not delivered: IllegalStateException",
                history.get(history.size() - 1).data().path("message").asText());
    }

    static class RecordingDriver implements InteractiveSessionDriver {
        int startCalls;
        int interruptCalls;
        int destroyCalls;
        String lastMessage;
        String lastPermissionId;
        boolean lastPermissionAllow;
        JsonNode lastPermissionToolInput;
        Exception sendFailure;

        @Override
        public void start() {
            startCalls++;
        }

        @Override
        public void sendUserMessage(String message) throws IOException {
            lastMessage = message;
            if (sendFailure instanceof IOException ioException) {
                throw ioException;
            }
            if (sendFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
        }

        @Override
        public void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) {
            lastPermissionId = permissionId;
            lastPermissionAllow = allow;
            lastPermissionToolInput = toolInput;
        }

        @Override
        public void interrupt() {
            interruptCalls++;
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public AssistantSession.Status getStatus() {
            return AssistantSession.Status.ERROR;
        }

        @Override
        public String getErrorMessage() {
            return "driver-error";
        }
    }
}
