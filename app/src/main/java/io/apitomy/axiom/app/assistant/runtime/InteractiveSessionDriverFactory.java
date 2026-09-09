package io.apitomy.axiom.app.assistant.runtime;

import java.io.IOException;

/**
 * Factory for creating interactive session runtime drivers.
 */
public interface InteractiveSessionDriverFactory {

    /**
     * Creates an interactive session driver.
     *
     * @return configured runtime driver
     * @throws IOException if driver creation requires IO and fails
     */
    InteractiveSessionDriver createDriver() throws IOException;
}
