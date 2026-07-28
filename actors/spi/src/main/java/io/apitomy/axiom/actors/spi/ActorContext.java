package io.apitomy.axiom.actors.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Context provided to an Actor when executing a task. Contains all the
 * information the actor needs to perform its work.
 */
public class ActorContext {

    private final Path workingDirectory;
    private final List<String> allowedTools;
    private final List<String> disallowedTools;
    private final String systemPrompt;
    private final String promptTemplate;
    private final Path mcpConfigFile;
    private final Map<String, String> environment;
    private final String model;
    private final Integer maxSteps;
    private final Double maxBudgetUsd;

    private ActorContext(Builder builder) {
        this.workingDirectory = builder.workingDirectory;
        this.allowedTools = builder.allowedTools;
        this.disallowedTools = builder.disallowedTools;
        this.systemPrompt = builder.systemPrompt;
        this.promptTemplate = builder.promptTemplate;
        this.mcpConfigFile = builder.mcpConfigFile;
        this.environment = builder.environment;
        this.model = builder.model;
        this.maxSteps = builder.maxSteps;
        this.maxBudgetUsd = builder.maxBudgetUsd;
    }

    /**
     * @return the project's git clone directory
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * @return the effective set of tools the actor is allowed to use
     */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    /**
     * @return tools that are explicitly blocked
     */
    public List<String> getDisallowedTools() {
        return disallowedTools;
    }

    /**
     * @return additional system prompt instructions for the task
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * @return the MCP config file path, or null if no MCP tools are configured
     */
    public Path getMcpConfigFile() {
        return mcpConfigFile;
    }

    /**
     * @return the prompt template for this action type, or null if not configured
     */
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * @return additional environment variables for the subprocess
     */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * @return the AI model override for this action type, or null to use the global default
     */
    public String getModel() {
        return model;
    }

    /**
     * @return the max steps/turns override for this action type, or null to use the global default
     */
    public Integer getMaxSteps() {
        return maxSteps;
    }

    /**
     * @return the max budget (USD) override for this action type, or null to use the global default
     */
    public Double getMaxBudgetUsd() {
        return maxBudgetUsd;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path workingDirectory;
        private List<String> allowedTools = List.of();
        private List<String> disallowedTools = List.of();
        private String systemPrompt;
        private String promptTemplate;
        private Path mcpConfigFile;
        private Map<String, String> environment = Map.of();
        private String model;
        private Integer maxSteps;
        private Double maxBudgetUsd;

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
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

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder mcpConfigFile(Path mcpConfigFile) {
            this.mcpConfigFile = mcpConfigFile;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder maxSteps(Integer maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxBudgetUsd(Double maxBudgetUsd) {
            this.maxBudgetUsd = maxBudgetUsd;
            return this;
        }

        public ActorContext build() {
            return new ActorContext(this);
        }
    }
}
