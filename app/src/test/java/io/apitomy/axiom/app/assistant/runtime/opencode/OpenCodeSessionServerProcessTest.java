package io.apitomy.axiom.app.assistant.runtime.opencode;

import io.apitomy.axiom.agents.opencode.OpenCodeServerManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeSessionServerProcessTest {

    @Test
    void startsAndStopsSessionScopedOpenCodeServer() {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);

        process.start();

        assertTrue(process.isAlive());
        assertTrue(process.baseUrl().startsWith("http://127.0.0.1:"));

        process.stop();

        assertFalse(process.isAlive());
    }
}
