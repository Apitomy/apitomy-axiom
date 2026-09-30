package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.AssistantSessionInfo;
import io.apitomy.axiom.app.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the session-to-bean mapping in {@link AssistantResourceImpl}.
 */
class AssistantResourceImplSessionInfoTest {

    @Test
    void toSessionInfoSetsEngineFromSession() {
        AssistantSession session = new AssistantSession("s", "tpl", Path.of("/tmp/s"), Path.of("/tmp/w"),
                List.of(), Map.of(), "opencode", null, null);

        AssistantSessionInfo info = new AssistantResourceImpl().toSessionInfo(session);

        assertEquals("opencode", info.getEngine());
        assertEquals("s", info.getName());
    }
}
