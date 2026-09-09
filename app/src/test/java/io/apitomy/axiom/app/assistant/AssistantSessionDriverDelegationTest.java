package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantSessionDriverDelegationTest {

    @Test
    void sessionDelegatesRuntimeCallsToDriver() throws Exception {
        RecordingDriver driver = new RecordingDriver();
        AssistantSession session = new AssistantSession(
                "test", "general-assistant", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), null, null, driver);

        session.start();
        session.sendMessage("hello");
        session.respondToPermission("p1", true, JsonNodeFactory.instance.objectNode());
        session.interrupt();
        session.destroy();

        assertEquals(1, driver.startCalls);
        assertEquals("hello", driver.lastMessage);
        assertEquals("p1", driver.lastPermissionId);
        assertEquals(1, driver.interruptCalls);
        assertEquals(1, driver.destroyCalls);
    }

    static class RecordingDriver implements InteractiveSessionDriver {
        int startCalls;
        int interruptCalls;
        int destroyCalls;
        String lastMessage;
        String lastPermissionId;

        @Override
        public void start() {
            startCalls++;
        }

        @Override
        public void sendUserMessage(String message) {
            lastMessage = message;
        }

        @Override
        public void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) {
            lastPermissionId = permissionId;
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
            return AssistantSession.Status.RUNNING;
        }

        @Override
        public String getErrorMessage() {
            return null;
        }
    }
}
