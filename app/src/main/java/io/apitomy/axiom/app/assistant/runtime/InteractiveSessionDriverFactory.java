package io.apitomy.axiom.app.assistant.runtime;

import io.apitomy.axiom.app.assistant.AssistantContextBuilder;
import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeCapabilityProbe;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeConfigWriter;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeEventNormalizer;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeInteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeSessionServerProcess;
import io.apitomy.axiom.app.assistant.runtime.opencode.OpenCodeSessionPermissions;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
     * @param mcpServers resolved MCP servers for the session, keyed by name
     * @param systemPrompt final system prompt for the session (template prompt plus project context);
     *        applied by the OpenCode driver. The Claude driver receives it through {@code command}.
     * @param allowedTools resolved allowed tools, in Claude Code format; enforced for OpenCode through its
     *        permission config
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
                         String sessionTitle,
                         Map<String, AssistantContextBuilder.McpServerConfig> mcpServers,
                         String systemPrompt,
                         List<String> allowedTools) {

        /**
         * Normalizes a null MCP server map and a null allowed-tools list to empty collections.
         */
        public DriverRequest {
            mcpServers = mcpServers != null ? mcpServers : Map.of();
            allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        }
    }

    /**
     * Default factory implementation selecting Claude or OpenCode runtime drivers.
     */
    @Unremovable
    @ApplicationScoped
    class DefaultInteractiveSessionDriverFactory implements InteractiveSessionDriverFactory {

        @ConfigProperty(name = "axiom.agent.claude-code.executable", defaultValue = "claude")
        String claudeExecutable;

        @ConfigProperty(name = "axiom.agent.opencode.executable", defaultValue = "opencode")
        String openCodeExecutable;

        @ConfigProperty(name = "axiom.assistant.opencode.executable")
        Optional<String> assistantOpenCodeExecutable;

        @ConfigProperty(name = "axiom.agent.opencode.server.hostname", defaultValue = "127.0.0.1")
        String openCodeServerHostname;

        @ConfigProperty(name = "axiom.agent.opencode.server.port", defaultValue = "0")
        int openCodeServerPort;

        @ConfigProperty(name = "axiom.assistant.opencode.server.port")
        Optional<Integer> assistantOpenCodeServerPort;

        @ConfigProperty(name = "axiom.assistant.opencode.startup-timeout-seconds")
        Optional<Integer> assistantOpenCodeStartupTimeoutSeconds;

        @ConfigProperty(name = "axiom.assistant.opencode.server.startup-timeout-seconds")
        Optional<Integer> legacyAssistantOpenCodeServerStartupTimeoutSeconds;

        @ConfigProperty(name = "axiom.assistant.opencode.startup-timeout-seconds", defaultValue = "30")
        int openCodeServerStartupTimeoutSeconds;

        /**
         * Builds the extra environment for a session's {@code opencode serve} process: the session's
         * template/project environment, overridden by the Axiom-managed {@code OPENCODE_CONFIG} when Axiom wrote
         * a config file. Entries with a null key or value are skipped.
         *
         * @param requestEnvironment resolved session environment; may be null
         * @param openCodeConfig path of the generated OpenCode config, or null if none was written
         * @return environment entries to add to the server process
         */
        static Map<String, String> buildOpenCodeEnvironment(Map<String, String> requestEnvironment,
                                                            Path openCodeConfig) {
            Map<String, String> environment = new LinkedHashMap<>();
            if (requestEnvironment != null) {
                requestEnvironment.forEach((String key, String value) -> {
                    if (key != null && value != null) {
                        environment.put(key, value);
                    }
                });
            }
            if (openCodeConfig != null) {
                environment.put("OPENCODE_CONFIG", openCodeConfig.toString());
            }
            return environment;
        }

        @Override
        public InteractiveSessionDriver createDriver(DriverRequest request) throws IOException {
            Objects.requireNonNull(request, "request");

            String engineType = request.engineType();
            if ("opencode".equalsIgnoreCase(engineType)) {
                Path openCodeConfig = OpenCodeConfigWriter.writeConfig(
                        request.sessionDirectory(), request.mcpServers(),
                        OpenCodeSessionPermissions.fromAllowedTools(request.allowedTools()));
                Map<String, String> serverEnvironment =
                        buildOpenCodeEnvironment(request.environment(), openCodeConfig);
                OpenCodeSessionServerProcess openCodeSessionServerProcess =
                        new OpenCodeSessionServerProcess(resolveOpenCodeExecutable(),
                                openCodeServerHostname,
                                resolveOpenCodeServerPort(),
                                resolveOpenCodeStartupTimeoutSeconds(),
                                serverEnvironment,
                                request.workingDirectory());
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
                        new OpenCodeInteractiveSessionDriver.SessionSettings(
                                sessionTitle,
                                request.model(),
                                null,
                                request.mcpServers().keySet(),
                                request.systemPrompt()));
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

        private String resolveOpenCodeExecutable() {
            return assistantOpenCodeExecutable
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .orElse(openCodeExecutable);
        }

        private int resolveOpenCodeStartupTimeoutSeconds() {
            return assistantOpenCodeStartupTimeoutSeconds
                    .or(() -> legacyAssistantOpenCodeServerStartupTimeoutSeconds)
                    .orElse(openCodeServerStartupTimeoutSeconds);
        }

        private int resolveOpenCodeServerPort() {
            Optional<Integer> configuredAssistantPort =
                    assistantOpenCodeServerPort != null ? assistantOpenCodeServerPort : Optional.empty();
            return configuredAssistantPort.orElse(0);
        }
    }
}
