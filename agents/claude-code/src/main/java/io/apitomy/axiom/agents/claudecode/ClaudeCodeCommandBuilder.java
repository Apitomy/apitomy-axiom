package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the command line for launching a Claude Code CLI subprocess.
 */
public class ClaudeCodeCommandBuilder {

    private String executable = "claude";
    private String prompt;
    private Path workingDirectory;
    private String model;
    private List<String> allowedTools = List.of();
    private List<String> disallowedTools = List.of();
    private String systemPrompt;
    private Integer maxTurns;
    private Double maxBudgetUsd;
    private String sessionId;
    private Path mcpConfigFile;
    private boolean bare = true;
    private boolean streamJson = true;

    /**
     * Creates a builder from an AgentRequest.
     *
     * @param request the agent request
     * @return a pre-configured builder
     */
    public static ClaudeCodeCommandBuilder fromRequest(AgentRequest request) {
        ClaudeCodeCommandBuilder builder = new ClaudeCodeCommandBuilder();
        builder.prompt = request.getPrompt();
        builder.workingDirectory = request.getWorkingDirectory();
        builder.allowedTools = request.getAllowedTools();
        builder.disallowedTools = request.getDisallowedTools();
        builder.systemPrompt = request.getSystemPrompt();
        builder.mcpConfigFile = request.getMcpConfigFile();
        return builder;
    }

    public ClaudeCodeCommandBuilder executable(String executable) {
        this.executable = executable;
        return this;
    }

    public ClaudeCodeCommandBuilder model(String model) {
        this.model = model;
        return this;
    }

    public ClaudeCodeCommandBuilder maxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
        return this;
    }

    public ClaudeCodeCommandBuilder maxBudgetUsd(Double maxBudgetUsd) {
        this.maxBudgetUsd = maxBudgetUsd;
        return this;
    }

    public ClaudeCodeCommandBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public ClaudeCodeCommandBuilder mcpConfigFile(Path mcpConfigFile) {
        this.mcpConfigFile = mcpConfigFile;
        return this;
    }

    public ClaudeCodeCommandBuilder bare(boolean bare) {
        this.bare = bare;
        return this;
    }

    public ClaudeCodeCommandBuilder streamJson(boolean streamJson) {
        this.streamJson = streamJson;
        return this;
    }

    /**
     * Builds the command line as a list of strings suitable for ProcessBuilder.
     *
     * @return the command line arguments
     */
    public List<String> build() {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-p");
        cmd.add(prompt);

        if (bare) {
            cmd.add("--bare");
        }

        if (streamJson) {
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("--verbose");
        } else {
            cmd.add("--output-format");
            cmd.add("json");
        }

        if (model != null) {
            cmd.add("--model");
            cmd.add(model);
        }

        if (allowedTools != null && !allowedTools.isEmpty()) {
            String baseTools = deriveBaseToolNames(allowedTools);
            cmd.add("--tools");
            cmd.add(baseTools);

            cmd.add("--allowedTools");
            cmd.add(String.join(" ", allowedTools));

            cmd.add("--permission-mode");
            cmd.add("dontAsk");
        } else {
            cmd.add("--permission-mode");
            cmd.add("acceptEdits");
        }

        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            cmd.add("--disallowedTools");
            cmd.add(String.join(",", disallowedTools));
        }

        if (systemPrompt != null) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }

        if (maxTurns != null) {
            cmd.add("--max-turns");
            cmd.add(maxTurns.toString());
        }

        if (maxBudgetUsd != null) {
            cmd.add("--max-budget-usd");
            cmd.add(maxBudgetUsd.toString());
        }

        if (sessionId != null) {
            cmd.add("--session-id");
            cmd.add(sessionId);
        }

        if (mcpConfigFile != null) {
            cmd.add("--mcp-config");
            cmd.add(mcpConfigFile.toAbsolutePath().toString());
        }

        return cmd;
    }

    /**
     * Derives comma-separated base tool names from a list of allowed tool
     * patterns. Strips parenthesized pattern suffixes
     * (e.g. {@code "Bash(git log *)"} becomes {@code "Bash"}) and
     * deduplicates the result.
     *
     * @param allowedTools the list of tool patterns
     * @return comma-separated base tool names
     */
    public static String deriveBaseToolNames(List<String> allowedTools) {
        return allowedTools.stream()
                .map(tool -> {
                    int parenIdx = tool.indexOf('(');
                    return parenIdx > 0 ? tool.substring(0, parenIdx) : tool;
                })
                .distinct()
                .collect(Collectors.joining(","));
    }
}
