package io.apitomy.axiom.app.assistant;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the working directory structure for an assistant session, including
 * the {@code CLAUDE.md} system prompt and {@code mcp-config.json} for the
 * Axiom Assistant MCP server.
 */
@ApplicationScoped
public class AssistantContextBuilder {

    private static final Logger LOG = Logger.getLogger(AssistantContextBuilder.class);

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    /**
     * Creates the full working directory for an assistant session.
     *
     * @param sessionId the unique session identifier
     * @param mcpServerDir path to the installed assistant MCP server, or null if
     *                     not yet installed
     * @return the path to the created working directory
     * @throws IOException if directory creation fails
     */
    public Path createWorkingDirectory(String sessionId, Path mcpServerDir) throws IOException {
        Path axiomHome = Path.of(System.getProperty("user.home"), ".axiom");
        Path sessionsRoot = axiomHome.resolve("assistant-sessions");
        Path sessionDir = sessionsRoot.resolve(sessionId);

        Files.createDirectories(sessionDir.resolve("tools"));
        Files.createDirectories(sessionDir.resolve("action-types"));
        Files.createDirectories(sessionDir.resolve("report-definitions"));

        Files.writeString(sessionDir.resolve("CLAUDE.md"), buildClaudeMd());

        if (mcpServerDir != null) {
            Files.writeString(sessionDir.resolve("mcp-config.json"),
                    buildMcpConfig(mcpServerDir));
        }

        LOG.infof("Created assistant working directory: %s", sessionDir);
        return sessionDir;
    }

    /**
     * Deletes the working directory and all its contents.
     *
     * @param workingDirectory the directory to delete
     */
    public void deleteWorkingDirectory(Path workingDirectory) {
        if (workingDirectory == null || !Files.exists(workingDirectory)) {
            return;
        }
        try {
            try (var walk = Files.walk(workingDirectory)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                LOG.warnf("Failed to delete: %s", p);
                            }
                        });
            }
            LOG.infof("Deleted assistant working directory: %s", workingDirectory);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up working directory: %s", workingDirectory);
        }
    }

    private String buildClaudeMd() {
        return """
                # Axiom Configuration Assistant

                You are the **Axiom Configuration Assistant**. Your job is to help the user
                create and refine Axiom configuration items — **Tools**, **Action Types**, and
                **Report Definitions** — by writing well-formed JSON files in this working
                directory.

                ## What You Can Create

                ### Tools
                Script-based tools that AI agents can invoke. Each tool runs a bash script with
                parameter substitution.

                Write each tool as a JSON file in the `tools/` subdirectory.

                **Schema:**
                ```json
                {
                  "name": "tool-name",
                  "description": "What the tool does",
                  "parameters": [
                    {
                      "name": "paramName",
                      "type": "string",
                      "description": "Parameter description",
                      "required": true
                    }
                  ],
                  "scriptTemplate": "#!/bin/bash\\n# Use {{paramName}} for parameter substitution\\necho {{paramName}}",
                  "labels": ["optional", "labels"]
                }
                ```

                **Template placeholders:** Use `{{paramName}}` in the script template. For
                parameters that contain multi-line content, use `{{paramName_file}}` — a temp
                file path containing the value will be substituted instead.

                ### Action Types
                Define kinds of work that AI agents or scripts can perform.

                Write each action type as a JSON file in the `action-types/` subdirectory.

                **Schema:**
                ```json
                {
                  "name": "action-type-name",
                  "description": "What this action does",
                  "executionMode": "actor",
                  "userTriggerable": true,
                  "managerTriggerable": true,
                  "emitsEvent": false,
                  "allowedTools": ["@Read-Only Tools", "mcp__axiom-tools__my-tool", "mcp__axiom-sdk__axiom_create_task"],
                  "promptTemplate": "You are performing...\\n\\nContext: {{input}}",
                  "scriptTemplate": null,
                  "model": null,
                  "engine": null,
                  "inputSchema": null,
                  "environment": null
                }
                ```

                **Execution modes:** `actor` (AI agent), `script` (bash script).

                **Allowed tools pattern:** Comma-separated list. Axiom has two built-in MCP
                servers with different prefixes:
                - `mcp__axiom-tools__<name>` — user-defined **script tools** (e.g.
                  `mcp__axiom-tools__post_github_comment`)
                - `mcp__axiom-sdk__axiom_<name>` — built-in **Axiom SDK tools** for
                  project/task management (e.g. `mcp__axiom-sdk__axiom_create_task`)
                - `@ToolsetName` — reference a named toolset (e.g. `@Read-Only Tools`,
                  `@Axiom SDK`)

                **Prompt template placeholders:** `{{input}}`, `{{projectId}}`,
                `{{projectName}}`, `{{repository}}`, `{{issueRef}}`.

                ### Report Definitions
                Recurring or on-demand reports generated by AI agents.

                Write each report definition as a JSON file in the `report-definitions/`
                subdirectory.

                **Schema:**
                ```json
                {
                  "name": "report-name",
                  "description": "What this report covers",
                  "schedule": "weekly",
                  "scheduleTime": "08:00",
                  "scheduleDayOfWeek": "monday",
                  "timeWindow": "last-7d",
                  "promptTemplate": "Generate a report...\\n\\nRepositories: {{repositories}}\\nTime range: {{timeRangeStart}} to {{timeRangeEnd}}",
                  "allowedTools": ["mcp__axiom-tools__my-tool"]
                }
                ```

                **Schedule values:** `none`, `daily`, `weekly`, `monthly`, or a cron expression.

                **Time window values:** `since-last-run`, `last-24h`, `last-7d`, `last-30d`.

                **Prompt template placeholders:** `{{repositories}}`, `{{timeRangeStart}}`,
                `{{timeRangeEnd}}`, `{{timeWindow}}`.

                **Optional fields** (omit to use defaults):
                - `environment` — JSON object of environment variables. Omit if not needed.
                - `timeoutSeconds` — per-report timeout override. **Do not include this field**
                  unless the user explicitly requests a custom timeout. The system default
                  (600 seconds) is used when this field is absent.

                ## Guidelines

                - **One file per item.** Name files descriptively (e.g. `tools/fetch-prs.json`).
                - **Use the MCP tools** (`axiom_list_tools`, `axiom_get_tool`, etc.) to discover
                  existing configuration before creating new items.
                - **Naming conventions:** Use lowercase kebab-case for names (e.g. `fetch-prs`,
                  `weekly-status`).
                - **Secret references:** Use `${secret:SECRET_NAME}` in script templates and
                  environment variables to reference secrets stored in Axiom.
                - **Validate your output.** Make sure JSON is well-formed and all required fields
                  are present.
                - **Allowed tools must match the prompt.** If a prompt template or script template
                  references specific tools (e.g. "use the fetch-github-notifications tool"),
                  those tools **must** be listed in `allowedTools`. An empty `allowedTools` means
                  the agent gets no tools at all. Always include every tool the agent will need.
                - When the user asks to modify an item, read the existing file, update it, and
                  write it back.
                """;
    }

    private String buildMcpConfig(Path mcpServerDir) {
        String serverJsPath = mcpServerDir.resolve("server.js")
                .toAbsolutePath().toString().replace("\\", "\\\\");
        String apiUrl = "http://localhost:" + httpPort + "/api/v1";

        return """
                {
                  "mcpServers": {
                    "axiom": {
                      "command": "node",
                      "args": ["%s"],
                      "env": {
                        "AXIOM_API_URL": "%s"
                      }
                    }
                  }
                }
                """.formatted(serverJsPath, apiUrl);
    }
}
