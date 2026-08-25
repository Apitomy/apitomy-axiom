package io.apitomy.axiom.agents.opencode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Axiom tool names (as used in ActionType.allowedTools and Claude Code's
 * {@code --tools}/{@code --allowedTools} flags) to OpenCode's permission
 * configuration format.
 *
 * <h3>Axiom tool name formats</h3>
 * <ul>
 *   <li>{@code Read}, {@code Glob}, {@code Grep} -- simple tool names</li>
 *   <li>{@code Bash(git log *)} -- parameterized Bash with glob patterns</li>
 *   <li>{@code mcp__serverName__toolName} -- MCP server tools</li>
 *   <li>{@code StructuredOutput} -- Claude-specific, not a real tool in OpenCode</li>
 *   <li>{@code Write}, {@code Edit} -- file modification tools</li>
 * </ul>
 *
 * <h3>OpenCode permission format</h3>
 * OpenCode uses a JSON permission config where each tool key maps to either
 * a simple action ({@code "allow"}/{@code "deny"}/{@code "ask"}) or an object
 * with glob patterns:
 * <pre>
 * {
 *   "read": "allow",
 *   "bash": { "*": "deny", "git log*": "allow", "gh issue*": "allow" },
 *   "edit": "allow",
 *   "mymcp_*": "allow"
 * }
 * </pre>
 *
 * @see <a href="https://opencode.ai/docs/permissions/">OpenCode Permissions Docs</a>
 */
public class OpenCodePermissionMapper {

    /** Axiom tool names that map directly to OpenCode permission keys. */
    private static final Map<String, String> DIRECT_MAPPINGS = Map.of(
            "Read", "read",
            "Glob", "glob",
            "Grep", "grep",
            "Bash", "bash",
            "Write", "edit",
            "Edit", "edit"
    );

    /** Axiom tool names that are Claude-specific and have no OpenCode equivalent. */
    private static final List<String> IGNORED_TOOLS = List.of(
            "StructuredOutput"
    );

    /**
     * Converts a list of Axiom tool names to an OpenCode permission configuration.
     * The result is a map suitable for serialization into OpenCode's
     * {@code permission} JSON config.
     *
     * <p>When the allowed tools list is null or empty, returns an empty map
     * (no restrictions -- OpenCode defaults to allowing all tools).</p>
     *
     * @param allowedTools    the Axiom allowed tool names (may be null)
     * @param disallowedTools the Axiom disallowed tool names (may be null)
     * @return a map of OpenCode permission keys to their values
     */
    public static Map<String, Object> mapPermissions(List<String> allowedTools,
                                                      List<String> disallowedTools) {
        Map<String, Object> permissions = new LinkedHashMap<>();

        // Process allowed tools
        if (allowedTools != null && !allowedTools.isEmpty()) {
            // Collect bash patterns separately since they need object syntax
            Map<String, String> bashPatterns = new LinkedHashMap<>();
            boolean hasBashWildcard = false;

            for (String tool : allowedTools) {
                if (IGNORED_TOOLS.contains(tool)) {
                    continue;
                }

                if (tool.startsWith("Bash(") && tool.endsWith(")")) {
                    // Parameterized Bash: Bash(git log *) -> bash: { "git log*": "allow" }
                    String pattern = tool.substring(5, tool.length() - 1).trim();
                    bashPatterns.put(pattern, "allow");
                } else if ("Bash".equals(tool)) {
                    // Unrestricted Bash
                    hasBashWildcard = true;
                } else if (tool.startsWith("mcp__")) {
                    // MCP tool: mcp__serverName__toolName -> serverName_toolName: allow
                    String mapped = mapMcpToolName(tool);
                    permissions.put(mapped, "allow");
                } else {
                    // Direct mapping
                    String mapped = DIRECT_MAPPINGS.get(tool);
                    if (mapped != null) {
                        permissions.put(mapped, "allow");
                    }
                    // Unknown tools are passed through as-is (lowercase)
                    else {
                        permissions.put(tool.toLowerCase(), "allow");
                    }
                }
            }

            // Build bash permission entry
            if (hasBashWildcard) {
                permissions.put("bash", "allow");
            } else if (!bashPatterns.isEmpty()) {
                // Object syntax: { "*": "deny", "pattern1": "allow", ... }
                Map<String, String> bashConfig = new LinkedHashMap<>();
                bashConfig.put("*", "deny");
                bashConfig.putAll(bashPatterns);
                permissions.put("bash", bashConfig);
            }
        }

        // Process disallowed tools
        if (disallowedTools != null) {
            for (String tool : disallowedTools) {
                if (tool.startsWith("mcp__")) {
                    permissions.put(mapMcpToolName(tool), "deny");
                } else {
                    String mapped = DIRECT_MAPPINGS.getOrDefault(tool, tool.toLowerCase());
                    permissions.put(mapped, "deny");
                }
            }
        }

        return permissions;
    }

    /**
     * Translates an Axiom MCP tool name to OpenCode's format.
     *
     * <p>Axiom uses double-underscore: {@code mcp__serverName__toolName}<br>
     * OpenCode uses single-underscore: {@code serverName_toolName}</p>
     *
     * @param axiomMcpTool the Axiom MCP tool name
     * @return the OpenCode tool permission key
     */
    public static String mapMcpToolName(String axiomMcpTool) {
        if (axiomMcpTool == null) return "";

        // Strip "mcp__" prefix, then replace remaining "__" with "_"
        if (axiomMcpTool.startsWith("mcp__")) {
            String remainder = axiomMcpTool.substring(5); // after "mcp__"
            return remainder.replace("__", "_");
        }

        return axiomMcpTool;
    }

    /**
     * Translates an OpenCode MCP tool name back to Axiom's format.
     *
     * <p>OpenCode: {@code serverName_toolName}<br>
     * Axiom: {@code mcp__serverName__toolName}</p>
     *
     * <p>Note: This is a best-effort reverse mapping. If the server name itself
     * contains underscores, the mapping may be ambiguous.</p>
     *
     * @param openCodeTool the OpenCode tool name
     * @param serverName   the known MCP server name (to resolve ambiguity)
     * @return the Axiom MCP tool name
     */
    public static String toAxiomMcpToolName(String openCodeTool, String serverName) {
        if (openCodeTool == null || serverName == null) return openCodeTool;

        // If it starts with serverName_, reconstruct
        String prefix = serverName + "_";
        if (openCodeTool.startsWith(prefix)) {
            String toolName = openCodeTool.substring(prefix.length());
            return "mcp__" + serverName + "__" + toolName;
        }

        return openCodeTool;
    }
}
