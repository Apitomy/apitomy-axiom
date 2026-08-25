package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.AgentRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the command line for launching a GitHub Copilot CLI ({@code copilot})
 * subprocess in non-interactive mode.
 *
 * <p>Note: Copilot CLI's built-in tool names (e.g. {@code view}, {@code bash},
 * {@code edit}) do not match the Claude Code-style tool identifiers used
 * elsewhere in Axiom (e.g. {@code Read}, {@code Bash(git log *)}). Passing an
 * unrecognized name to {@code --allow-tool}/{@code --available-tools} is
 * harmless (Copilot logs a warning and ignores it), so when an action type
 * restricts tools we still forward the base names best-effort, but we always
 * additionally allow all tools so that the subprocess never blocks waiting
 * for interactive confirmation. See
 * https://github.com/Apitomy/apitomy-axiom/issues/224 for the tracked
 * follow-up on first-class Copilot tool-name mapping.</p>
 */
public class CopilotCommandBuilder {

    private String executable = "copilot";
    private String prompt;
    private String model;
    private List<String> allowedTools = List.of();
    private List<String> disallowedTools = List.of();
    private String sessionId;
    private Path mcpConfigFile;

    /**
     * Creates a builder pre-configured from an {@link AgentRequest}.
     *
     * @param request the agent request containing prompt, tools, and MCP config
     * @return a pre-configured builder
     */
    public static CopilotCommandBuilder fromRequest(AgentRequest request) {
        CopilotCommandBuilder builder = new CopilotCommandBuilder();
        builder.prompt = request.getPrompt();
        builder.allowedTools = request.getAllowedTools();
        builder.disallowedTools = request.getDisallowedTools();
        builder.mcpConfigFile = request.getMcpConfigFile();
        return builder;
    }

    /**
     * Sets the Copilot CLI executable name or path.
     *
     * @param executable the executable name or path
     * @return this builder
     */
    public CopilotCommandBuilder executable(String executable) {
        this.executable = executable;
        return this;
    }

    /**
     * Sets the prompt text to send to Copilot.
     *
     * @param prompt the prompt text
     * @return this builder
     */
    public CopilotCommandBuilder prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    /**
     * Sets the AI model identifier for the Copilot invocation.
     *
     * @param model the model identifier
     * @return this builder
     */
    public CopilotCommandBuilder model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Sets the list of allowed tool names.
     *
     * @param allowedTools the allowed tool names
     * @return this builder
     */
    public CopilotCommandBuilder allowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
        return this;
    }

    /**
     * Sets the list of disallowed tool names.
     *
     * @param disallowedTools the disallowed tool names
     * @return this builder
     */
    public CopilotCommandBuilder disallowedTools(List<String> disallowedTools) {
        this.disallowedTools = disallowedTools;
        return this;
    }

    /**
     * Sets the session identifier for resuming a previous Copilot session.
     *
     * @param sessionId the session identifier
     * @return this builder
     */
    public CopilotCommandBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Sets the MCP configuration file path.
     *
     * @param mcpConfigFile the path to the MCP config file
     * @return this builder
     */
    public CopilotCommandBuilder mcpConfigFile(Path mcpConfigFile) {
        this.mcpConfigFile = mcpConfigFile;
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

        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--no-color");

        // Disable Copilot's own built-in GitHub MCP server — Axiom manages
        // GitHub integration separately and this keeps the available tool
        // surface predictable/consistent with the other agents.
        cmd.add("--disable-builtin-mcps");

        if (model != null) {
            cmd.add("--model");
            cmd.add(model);
        }

        if (allowedTools != null && !allowedTools.isEmpty()) {
            String baseTools = deriveBaseToolNames(allowedTools);
            for (String tool : baseTools.split(",")) {
                cmd.add("--allow-tool");
                cmd.add(tool);
            }
        }
        // Non-interactive mode requires blanket tool approval; fine-grained
        // restriction above is best-effort (see class Javadoc).
        cmd.add("--allow-all-tools");

        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            for (String tool : disallowedTools) {
                cmd.add("--deny-tool");
                cmd.add(tool);
            }
        }

        if (sessionId != null) {
            cmd.add("--session-id");
            cmd.add(sessionId);
        }

        if (mcpConfigFile != null) {
            cmd.add("--additional-mcp-config");
            cmd.add("@" + mcpConfigFile.toAbsolutePath());
        }

        return cmd;
    }

    /**
     * Derives comma-separated base tool names from a list of allowed tool
     * patterns, stripping any parenthesized pattern suffix
     * (e.g. {@code "Bash(git log *)"} becomes {@code "Bash"}) and
     * deduplicating the result.
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
