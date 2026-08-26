package io.apitomy.axiom.agents.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified agent implementation for OpenCode. Manages an {@code opencode serve}
 * server process and communicates via its HTTP API. Merges the functionality of
 * the former {@code OpenCodeActor} and {@code OpenCodeEngine}.
 *
 * <p>Supports structured output via the {@code format} field in prompt requests,
 * multiple LLM providers (Anthropic, OpenAI, Google, etc.), and MCP server
 * management via the server's REST API.</p>
 */
@ApplicationScoped
public class OpenCodeAgent implements Agent {

    private static final Logger LOG = Logger.getLogger(OpenCodeAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "axiom.agent.opencode.server.hostname", defaultValue = "127.0.0.1")
    String hostname;

    @ConfigProperty(name = "axiom.agent.opencode.server.port", defaultValue = "4096")
    int port;

    @ConfigProperty(name = "axiom.agent.opencode.model")
    Optional<String> defaultModel;

    @ConfigProperty(name = "axiom.agent.opencode.max-steps", defaultValue = "50")
    int defaultMaxSteps;

    @ConfigProperty(name = "axiom.agent.opencode.timeout-seconds", defaultValue = "600")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "axiom.agent.opencode.available-models", defaultValue = "")
    String availableModels;

    @Inject
    OpenCodeMcpManager mcpManager;

    private volatile OpenCodeServerManager serverManager;

    /** Tracks running sessions for cancellation support. */
    private final Map<String, String> runningSessions = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public String getType() {
        return "opencode";
    }

    /** {@inheritDoc} */
    @Override
    public String getLabel() {
        return "OpenCode";
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getAvailableModels() {
        if (availableModels == null || availableModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(availableModels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<AgentResult> execute(AgentRequest request) {
        return executeInternal(request, null);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<AgentResult> executeWithSchema(AgentRequest request,
                                                             String jsonSchema) {
        return executeInternal(request, jsonSchema);
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(String executionId) {
        String sessionId = runningSessions.remove(executionId);
        if (sessionId != null) {
            LOG.infof("Cancelling execution %s (session: %s)", executionId, sessionId);
            try {
                OpenCodeClient client = getOrStartServer();
                client.abortSession(sessionId);
            } catch (Exception e) {
                LOG.warnf("Failed to abort session for execution %s: %s",
                        executionId, e.getMessage());
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentCheckResult> healthCheck() {
        List<AgentCheckResult> results = new ArrayList<>();

        // Check CLI availability
        if (!OpenCodeServerManager.isOpenCodeAvailable()) {
            results.add(new AgentCheckResult(
                    "OpenCode CLI",
                    "error",
                    "OpenCode CLI is not installed or not on your PATH. "
                            + "Install OpenCode: curl -fsSL https://opencode.ai/install | bash"
            ));
            return results;
        }

        String version = OpenCodeServerManager.getCliVersion();
        results.add(new AgentCheckResult(
                "OpenCode CLI",
                "ok",
                "OpenCode CLI is available" + (version != null ? " (version: " + version + ")" : "")
        ));

        // Try to start the server and check health
        try {
            OpenCodeClient client = getOrStartServer();
            if (client.isHealthy()) {
                String serverVersion = client.getVersion();
                results.add(new AgentCheckResult(
                        "OpenCode Server",
                        "ok",
                        "OpenCode server is running"
                                + (serverVersion != null ? " (version: " + serverVersion + ")" : "")
                ));
            } else {
                results.add(new AgentCheckResult(
                        "OpenCode Server",
                        "warning",
                        "OpenCode server is not responding. It will be started on first use."
                ));
            }
        } catch (Exception e) {
            results.add(new AgentCheckResult(
                    "OpenCode Server",
                    "warning",
                    "Could not start OpenCode server: " + e.getMessage()
                            + ". It will be started on first use."
            ));
        }

        return results;
    }

    /**
     * Returns the MCP manager for this agent.
     *
     * @return the OpenCode MCP manager
     */
    public OpenCodeMcpManager getMcpManager() {
        return mcpManager;
    }

    // --- Internal ---

    private CompletableFuture<AgentResult> executeInternal(AgentRequest request,
                                                            String jsonSchema) {
        LOG.infof("Executing via OpenCode (executionId=%s)", request.getExecutionId());

        int timeoutSecs = request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() : defaultTimeoutSeconds;

        return CompletableFuture.supplyAsync(() -> {
            try {
                OpenCodeClient client = getOrStartServer();

                // Create session (use the caller's configured timeout so this
                // doesn't spuriously time out under server load -- see
                // OpenCodeClient.createSession javadoc)
                String sessionId = client.createSession(
                        "axiom-" + System.currentTimeMillis(), timeoutSecs);
                LOG.infof("OpenCode session created: %s", sessionId);

                // Track session for cancellation
                if (request.getExecutionId() != null) {
                    runningSessions.put(request.getExecutionId(), sessionId);
                }

                // Build structured output format if schema provided
                JsonNode format = null;
                if (jsonSchema != null) {
                    ObjectNode formatNode = MAPPER.createObjectNode();
                    formatNode.put("type", "json_schema");
                    formatNode.set("schema", MAPPER.readTree(jsonSchema));
                    format = formatNode;
                }

                // Resolve model
                String model = resolveModel(request);

                // Map tool permissions from Axiom format to OpenCode format
                Map<String, Object> permissions = OpenCodePermissionMapper.mapPermissions(
                        request.getAllowedTools(), request.getDisallowedTools());
                if (!permissions.isEmpty()) {
                    LOG.debugf("OpenCode permissions: %s", permissions);
                }

                // Build full prompt with system prompt if provided
                String fullPrompt = request.getPrompt();
                if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
                    fullPrompt = request.getSystemPrompt() + "\n\n---\n\n" + fullPrompt;
                }

                // Send prompt and wait for response
                JsonNode response = client.sendPrompt(
                        sessionId, fullPrompt, model, format, permissions, timeoutSecs);

                // Clean up session tracking
                if (request.getExecutionId() != null) {
                    runningSessions.remove(request.getExecutionId());
                }

                // Parse response
                return parseResponse(response, sessionId, model);

            } catch (Exception e) {
                if (request.getExecutionId() != null) {
                    runningSessions.remove(request.getExecutionId());
                }
                LOG.errorf(e, "OpenCode prompt execution failed");
                return AgentResult.failure("OpenCode error: " + e.getMessage());
            }
        });
    }

    /**
     * Resolves the model to use for a request. The request's model takes priority,
     * then the agent's configured default model.
     */
    private String resolveModel(AgentRequest request) {
        if (request.getModel() != null && !request.getModel().isBlank()) {
            return request.getModel();
        }
        return defaultModel.orElse(null);
    }

    /**
     * Parses the OpenCode server response into an AgentResult.
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
    private AgentResult parseResponse(JsonNode response, String sessionId,
                                      String resolvedModel) {
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

            // Check for structured output. OpenCode's AssistantMessage schema exposes
            // this as "structured" (not "structured_output") -- see /doc OpenAPI schema.
            JsonNode info = response.path("info");
            JsonNode structuredOutput = info.path("structured");
            if (structuredOutput.isMissingNode() || structuredOutput.isNull()) {
                structuredOutput = info.path("structured_output");
            }
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

            String actualModel = info.path("modelID").asText(null);
            if (actualModel == null || actualModel.isBlank()) {
                actualModel = resolvedModel;
            }

            return new AgentResult(true, result, null, null, sessionId,
                    costUsd, inputTokens, outputTokens, getType(), actualModel);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse OpenCode response");
            return new AgentResult(true, response.toString(), null, null, sessionId,
                    null, null, null, getType(), resolvedModel);
        }
    }

    /**
     * Returns the OpenCode client, starting the server process if necessary.
     * Package-private for use by {@link OpenCodeMcpManager}.
     *
     * @return the connected OpenCode client
     */
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
