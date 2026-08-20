package io.apitomy.axiom.engine.copilot;

import io.apitomy.axiom.actors.spi.ActorContext;

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
     * Creates a builder from an ActorContext and prompt.
     *
     * @param prompt the prompt to send to Copilot
     * @param context the actor context
     * @return a pre-configured builder
     */
    public static CopilotCommandBuilder fromContext(String prompt, ActorContext context) {
        CopilotCommandBuilder builder = new CopilotCommandBuilder();
        builder.prompt = prompt;
        builder.allowedTools = context.getAllowedTools();
        builder.disallowedTools = context.getDisallowedTools();
        builder.mcpConfigFile = context.getMcpConfigFile();
        return builder;
    }

    public CopilotCommandBuilder executable(String executable) {
        this.executable = executable;
        return this;
    }

    public CopilotCommandBuilder prompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public CopilotCommandBuilder model(String model) {
        this.model = model;
        return this;
    }

    public CopilotCommandBuilder allowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
        return this;
    }

    public CopilotCommandBuilder disallowedTools(List<String> disallowedTools) {
        this.disallowedTools = disallowedTools;
        return this;
    }

    public CopilotCommandBuilder sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

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
        // surface predictable/consistent with the other engines.
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
