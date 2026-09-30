package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.agents.opencode.OpenCodePermissionMapper;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Converts an interactive session template's allowed tools (Claude Code format, e.g. {@code Read(*)},
 * {@code Bash(ls *)}, {@code mcp__axiom__axiom_list_tools}) into an OpenCode {@code permission} config block.
 *
 * <p>Listed tools are allowed without prompting and every other tool asks the user, mirroring Claude Code's
 * {@code --allowedTools}. Unlike the unattended task path ({@link OpenCodePermissionMapper}), nothing is denied.
 *
 * <p>A whole-server MCP entry such as {@code mcp__github} becomes the wildcard key {@code github_*}. OpenCode
 * matches such keys by prefix, so {@code github_*} also matches tools of a server named
 * {@code github_enterprise}.
 */
public final class OpenCodeSessionPermissions {

    private static final Logger LOG = Logger.getLogger(OpenCodeSessionPermissions.class);
    private static final String ALLOW = "allow";
    private static final String ASK = "ask";

    private static final Map<String, String> TOOL_KEYS = Map.ofEntries(
            Map.entry("Read", "read"),
            Map.entry("Write", "edit"),
            Map.entry("Edit", "edit"),
            Map.entry("MultiEdit", "edit"),
            Map.entry("NotebookEdit", "edit"),
            Map.entry("Glob", "glob"),
            Map.entry("Grep", "grep"),
            Map.entry("LS", "list"),
            Map.entry("Bash", "bash"),
            Map.entry("WebFetch", "webfetch"),
            Map.entry("WebSearch", "websearch"),
            Map.entry("Task", "task"),
            Map.entry("Agent", "task"),
            Map.entry("TodoWrite", "todowrite"),
            Map.entry("TodoRead", "todowrite"));

    private OpenCodeSessionPermissions() {
    }

    /**
     * Builds the OpenCode permission block for a session.
     *
     * @param allowedTools the template's resolved allowed tools; may be null
     * @return the permission block (first key {@code "*": "ask"}), or null when there are no non-blank allowed
     *         tools, in which case OpenCode's default permissions apply. When entries exist but none maps to
     *         an OpenCode key, the block is just {@code {"*": "ask"}} so the session fails closed
     */
    public static ObjectNode fromAllowedTools(List<String> allowedTools) {
        if (allowedTools == null) {
            return null;
        }
        ObjectNode permission = JsonNodeFactory.instance.objectNode();
        permission.put("*", ASK);
        boolean any = false;
        for (String raw : allowedTools) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            if (entry.startsWith("mcp__")) {
                String remainder = entry.substring(5);
                any = true;
                if (remainder.isEmpty() || "*".equals(remainder) || remainder.startsWith("__")) {
                    LOG.debugf("Degenerate MCP allowed tool '%s'; skipping", entry);
                    continue;
                }
                String key = !remainder.contains("__") || remainder.endsWith("__*")
                        ? remainder.replace("__*", "") + "_*"
                        : OpenCodePermissionMapper.mapMcpToolName(entry);
                permission.put(key, ALLOW);
                continue;
            }
            String name = entry;
            String pattern = null;
            int open = entry.indexOf('(');
            if (open > 0 && entry.endsWith(")")) {
                name = entry.substring(0, open).trim();
                pattern = entry.substring(open + 1, entry.length() - 1).trim();
            }
            String key = TOOL_KEYS.get(name);
            if (key == null) {
                LOG.debugf("No OpenCode permission key for allowed tool '%s'; skipping", entry);
                any = true;
                continue;
            }
            any = true;
            if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) {
                permission.put(key, ALLOW);
            } else if (!ALLOW.equals(permission.path(key).asText(null))) {
                JsonNode existing = permission.get(key);
                ObjectNode patterns = existing != null && existing.isObject()
                        ? (ObjectNode) existing
                        : permission.putObject(key).put("*", ASK);
                patterns.put(pattern, ALLOW);
            }
        }
        return any ? permission : null;
    }
}
