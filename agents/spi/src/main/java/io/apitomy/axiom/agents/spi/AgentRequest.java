package io.apitomy.axiom.agents.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unified work description for any AI agent invocation — task, report,
 * scheduled job, or utility call. Replaces both {@code ActorContext} and
 * {@code AiEngineConfig}.
 */
public class AgentRequest {

    private final String prompt;
    private final String systemPrompt;
    private final String model;
    private final int timeoutSeconds;
    private final int maxSteps;
    private final Double maxBudgetUsd;
    private final List<String> allowedTools;
    private final List<String> disallowedTools;
    private final Path mcpConfigFile;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final String executionId;
    private final String sessionId;

    private AgentRequest(Builder builder) {
        this.prompt = builder.prompt;
        this.systemPrompt = builder.systemPrompt;
        this.model = builder.model;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.maxSteps = builder.maxSteps;
        this.maxBudgetUsd = builder.maxBudgetUsd;
        this.allowedTools = builder.allowedTools;
        this.disallowedTools = builder.disallowedTools;
        this.mcpConfigFile = builder.mcpConfigFile;
        this.workingDirectory = builder.workingDirectory;
        this.environment = builder.environment;
        this.executionId = builder.executionId;
        this.sessionId = builder.sessionId;
    }

    /** @return the user prompt text */
    public String getPrompt() {
        return prompt;
    }

    /** @return additional system prompt instructions */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** @return the AI model identifier, or null to use the agent's default */
    public String getModel() {
        return model;
    }

    /** @return timeout in seconds for the AI invocation */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** @return maximum number of agent steps/turns */
    public int getMaxSteps() {
        return maxSteps;
    }

    /** @return maximum budget in USD, or null for no limit */
    public Double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    /** @return the effective set of tools the agent is allowed to use */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    /** @return tools that are explicitly blocked */
    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    /** @return the MCP config file path, or null if no MCP tools are configured */
    public Path getMcpConfigFile() {
        return mcpConfigFile;
    }

    /** @return the working directory for the AI agent, or null */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /** @return additional environment variables for the AI process */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /** @return caller-assigned execution identifier for cancellation tracking */
    public String getExecutionId() {
        return executionId;
    }

    /** @return agent-specific session identifier for resumption, or null */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String prompt;
        private String systemPrompt;
        private String model;
        private int timeoutSeconds = 120;
        private int maxSteps = 50;
        private Double maxBudgetUsd;
        private List<String> allowedTools = List.of();
        private List<String> disallowedTools = List.of();
        private Path mcpConfigFile;
        private Path workingDirectory;
        private Map<String, String> environment = Map.of();
        private String executionId;
        private String sessionId;

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxBudgetUsd(Double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        public Builder allowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        public Builder disallowedTools(List<String> disallowedTools) {
            this.disallowedTools = disallowedTools;
            return this;
        }

        public Builder mcpConfigFile(Path mcpConfigFile) {
            this.mcpConfigFile = mcpConfigFile;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Builds the agent request.
         *
         * @return a new AgentRequest
         */
        public AgentRequest build() {
            return new AgentRequest(this);
        }
    }
}
