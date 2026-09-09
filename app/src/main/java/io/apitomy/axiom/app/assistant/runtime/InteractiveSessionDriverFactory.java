package io.apitomy.axiom.app.assistant.runtime;

import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeCapabilityProbe;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeEventNormalizer;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeInteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeSessionServerProcess;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
                         String projectName,
                         Consumer<AssistantEventParser.SseEvent> eventSink,
                         Consumer<AssistantEventParser.SseEvent> autoApprovalSink,
                         String model,
                         com.fasterxml.jackson.databind.JsonNode tools,
                         String sessionTitle) {
    }

    /**
     * Default factory implementation selecting Claude or OpenCode runtime drivers.
     */
    @ApplicationScoped
    class DefaultInteractiveSessionDriverFactory implements InteractiveSessionDriverFactory {

        @ConfigProperty(name = "axiom.agent.claude-code.executable", defaultValue = "claude")
        String claudeExecutable;

        @ConfigProperty(name = "axiom.agent.opencode.executable", defaultValue = "opencode")
        String openCodeExecutable;

        @ConfigProperty(name = "axiom.agent.opencode.server.hostname", defaultValue = "127.0.0.1")
        String openCodeServerHostname;

        @ConfigProperty(name = "axiom.agent.opencode.server.port", defaultValue = "0")
        int openCodeServerPort;

        @ConfigProperty(name = "axiom.assistant.opencode.server.startup-timeout-seconds", defaultValue = "30")
        int openCodeServerStartupTimeoutSeconds;

        @Override
        public InteractiveSessionDriver createDriver(DriverRequest request) throws IOException {
            Objects.requireNonNull(request, "request");

            String engineType = request.engineType();
            if ("opencode".equalsIgnoreCase(engineType)) {
                OpenCodeSessionServerProcess openCodeSessionServerProcess =
                        new OpenCodeSessionServerProcess(openCodeExecutable,
                                openCodeServerHostname,
                                openCodeServerPort,
                                openCodeServerStartupTimeoutSeconds);
                OpenCodeCapabilityProbe capabilityProbe = new OpenCodeCapabilityProbe();
                OpenCodeEventNormalizer normalizer = new OpenCodeEventNormalizer();
                String sessionTitle = request.sessionTitle() != null && !request.sessionTitle().isBlank()
                        ? request.sessionTitle()
                        : "Axiom Assistant Session";
                return new OpenCodeInteractiveSessionDriver(
                        new OpenCodeInteractiveSessionDriver.ServerProcessAdapter(openCodeSessionServerProcess),
                        capabilityProbe::probe,
                        normalizer,
                        request.eventSink(),
                        request.autoApprovalSink(),
                        sessionTitle,
                        request.model(),
                        request.tools()
                );
            }

            return new ClaudeInteractiveSessionDriver(
                    request.workingDirectory(),
                    request.sessionDirectory(),
                    request.command(),
                    request.environment(),
                    new AssistantEventParser(),
                    request.eventSink(),
                    request.autoApprovalSink()
            );
        }
    }
}
