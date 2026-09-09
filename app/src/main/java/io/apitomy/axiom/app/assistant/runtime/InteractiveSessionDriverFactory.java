package io.apitomy.axiom.app.assistant.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Factory for selecting and creating interactive session runtime drivers.
 */
public interface InteractiveSessionDriverFactory {

    /**
     * Creates an interactive session driver for the provided engine/runtime context.
     *
     * @param request driver creation request
     * @return configured runtime driver
     * @throws IOException if driver creation requires IO and fails
     */
    InteractiveSessionDriver createDriver(DriverRequest request) throws IOException;

    /**
     * Driver selection/build request.
     *
     * @param engineType interactive engine type (for example, {@code claude-code} or {@code opencode})
     * @param templateId template identifier used to create the session
     * @param sessionDirectory Axiom-managed session directory
     * @param workingDirectory assistant runtime working directory
     * @param command legacy engine command line, when required by the selected runtime
     * @param environment resolved runtime environment variables
     * @param projectId optional project identifier when session is project scoped
     * @param projectName optional project name when session is project scoped
     */
    record DriverRequest(String engineType,
                         String templateId,
                         Path sessionDirectory,
                         Path workingDirectory,
                         List<String> command,
                         Map<String, String> environment,
                         Long projectId,
                         String projectName) {
    }
}
