package io.apitomy.axiom.engine.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineCheckResult;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineMcpManager;
import io.apitomy.axiom.engine.spi.AiEngineProvider;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenCode implementation of the {@link AiEngine} SPI. Manages an
 * {@code opencode serve} server process and communicates via its HTTP API.
 *
 * <p>Supports structured output via the {@code format} field in prompt requests,
 * multiple LLM providers (Anthropic, OpenAI, Google, etc.), and MCP server
 * management via the server's REST API.</p>
 */
@ApplicationScoped
@Typed({OpenCodeEngine.class, AiEngineProvider.class})
public class OpenCodeEngine implements AiEngine, AiEngineProvider {

    private static final Logger LOG = Logger.getLogger(OpenCodeEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "axiom.opencode.server.hostname", defaultValue = "127.0.0.1")
    String hostname;

    @ConfigProperty(name = "axiom.opencode.server.port", defaultValue = "4096")
    int port;

    @Inject
    OpenCodeMcpManager mcpManager;

    private volatile OpenCodeServerManager serverManager;

    @Override
    public String getType() {
        return "opencode";
    }

    @Override
    public CompletableFuture<AiEngineResult> prompt(AiEngineConfig config, String prompt) {
        return executeInternal(config, prompt, null);
    }

    @Override
    public CompletableFuture<AiEngineResult> promptWithSchema(AiEngineConfig config,
                                                               String prompt,
                                                               String jsonSchema) {
        return executeInternal(config, prompt, jsonSchema);
    }

    @Override
    public List<AiEngineCheckResult> healthCheck() {
        List<AiEngineCheckResult> results = new ArrayList<>();

        // Check CLI availability
        if (!OpenCodeServerManager.isOpenCodeAvailable()) {
            results.add(new AiEngineCheckResult(
                    "OpenCode CLI",
                    "error",
                    "OpenCode CLI is not installed or not on your PATH. "
                            + "Install OpenCode: curl -fsSL https://opencode.ai/install | bash"
            ));
            return results;
        }

        String version = OpenCodeServerManager.getCliVersion();
        results.add(new AiEngineCheckResult(
                "OpenCode CLI",
                "ok",
                "OpenCode CLI is available" + (version != null ? " (version: " + version + ")" : "")
        ));

        // Try to start the server and check health
        try {
            OpenCodeClient client = getOrStartServer();
            if (client.isHealthy()) {
                String serverVersion = client.getVersion();
                results.add(new AiEngineCheckResult(
                        "OpenCode Server",
                        "ok",
                        "OpenCode server is running"
                                + (serverVersion != null ? " (version: " + serverVersion + ")" : "")
                ));
            } else {
                results.add(new AiEngineCheckResult(
                        "OpenCode Server",
                        "warning",
                        "OpenCode server is not responding. It will be started on first use."
                ));
            }
        } catch (Exception e) {
            results.add(new AiEngineCheckResult(
                    "OpenCode Server",
                    "warning",
                    "Could not start OpenCode server: " + e.getMessage()
                            + ". It will be started on first use."
            ));
        }

        return results;
    }

    // --- AiEngineProvider ---

    @Override
    public AiEngine getEngine() {
        return this;
    }

    @Override
    public AiEngineMcpManager getMcpManager() {
        return mcpManager;
    }

    // --- Internal ---

    private CompletableFuture<AiEngineResult> executeInternal(AiEngineConfig config,
                                                               String prompt,
                                                               String jsonSchema) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                OpenCodeClient client = getOrStartServer();

                // Create session (use the caller's configured timeout so this
                // doesn't spuriously time out under server load — see
                // OpenCodeClient.createSession javadoc)
                String sessionId = client.createSession(
                        "axiom-" + System.currentTimeMillis(), config.getTimeoutSeconds());
                LOG.infof("OpenCode session created: %s", sessionId);

                // Build structured output format if schema provided
                JsonNode format = null;
                if (jsonSchema != null) {
                    ObjectNode formatNode = MAPPER.createObjectNode();
                    formatNode.put("type", "json_schema");
                    formatNode.set("schema", MAPPER.readTree(jsonSchema));
                    format = formatNode;
                }

                // Resolve model
                String model = config.getModel();

                // Map tool permissions from Axiom format to OpenCode format
                Map<String, Object> permissions = OpenCodePermissionMapper.mapPermissions(
                        config.getAllowedTools(), config.getDisallowedTools());
                if (!permissions.isEmpty()) {
                    LOG.debugf("OpenCode permissions: %s", permissions);
                }

                // Build full prompt with system prompt if provided
                String fullPrompt = prompt;
                if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
                    fullPrompt = config.getSystemPrompt() + "\n\n---\n\n" + prompt;
                }

                // Send prompt and wait for response
                JsonNode response = client.sendPrompt(
                        sessionId, fullPrompt, model, format, permissions,
                        config.getTimeoutSeconds());

                // Parse response
                return parseResponse(response, sessionId);

            } catch (Exception e) {
                LOG.errorf(e, "OpenCode prompt execution failed");
                return AiEngineResult.failure("OpenCode error: " + e.getMessage());
            }
        });
    }

    /**
     * Parses the OpenCode server response into an AiEngineResult.
     * The response structure is:
     * <pre>
     * {
     *   "info": { "id": "...", "role": "assistant", ... },
     *   "parts": [
     *     { "type": "text", "text": "..." },
     *     { "type": "tool-invocation", ... }
     *   ]
     * }
     * </pre>
     */
    private AiEngineResult parseResponse(JsonNode response, String sessionId) {
        try {
            // Extract text from parts
            StringBuilder resultText = new StringBuilder();
            JsonNode parts = response.path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String type = part.path("type").asText("");
                    if ("text".equals(type)) {
                        if (!resultText.isEmpty()) resultText.append("\n");
                        resultText.append(part.path("text").asText(""));
                    }
                }
            }

            // Check for structured output
            JsonNode info = response.path("info");
            JsonNode structuredOutput = info.path("structured_output");
            String result;
            if (!structuredOutput.isMissingNode() && !structuredOutput.isNull()) {
                result = MAPPER.writeValueAsString(structuredOutput);
            } else if (!resultText.isEmpty()) {
                result = resultText.toString();
            } else {
                result = response.toString();
            }

            // Extract usage/cost info if available
            Double costUsd = null;
            Long inputTokens = null;
            Long outputTokens = null;
            JsonNode usage = info.path("usage");
            if (!usage.isMissingNode()) {
                if (usage.has("inputTokens")) {
                    inputTokens = usage.get("inputTokens").asLong();
                }
                if (usage.has("outputTokens")) {
                    outputTokens = usage.get("outputTokens").asLong();
                }
                if (usage.has("cost")) {
                    costUsd = usage.get("cost").asDouble();
                }
            }

            return new AiEngineResult(result, sessionId, costUsd, inputTokens, outputTokens,
                    true, null);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse OpenCode response");
            return new AiEngineResult(response.toString(), sessionId, null, null, null,
                    true, null);
        }
    }

    OpenCodeClient getOrStartServer() {
        if (serverManager == null) {
            synchronized (this) {
                if (serverManager == null) {
                    serverManager = new OpenCodeServerManager(hostname, port);
                }
            }
        }
        return serverManager.ensureRunning();
    }

    @PreDestroy
    void shutdown() {
        if (serverManager != null) {
            serverManager.stop();
        }
    }
}
