package io.apitomy.axiom.app.mcp;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and rewrites references to MCP servers. A server is referenced by name from action types and
 * session templates ({@code mcpServers}), and through {@code mcp__<name>__<tool>} tool names in action
 * type and session template {@code allowedTools} and in toolsets.
 */
@ApplicationScoped
public class McpServerReferences {

    /** Kind of referencing item. */
    public enum ItemType {
        /** An action type. */
        ACTION_TYPE("actionType"),
        /** A user-defined session template. */
        SESSION_TEMPLATE("sessionTemplate"),
        /** A toolset. */
        TOOLSET("toolset");

        private final String value;

        ItemType(String value) {
            this.value = value;
        }

        /**
         * @return the API value
         */
        public String value() {
            return value;
        }
    }

    /**
     * A single reference.
     *
     * @param type the referencing item's type
     * @param id the referencing item's id
     * @param name the referencing item's name
     * @param field {@code mcpServers}, {@code allowedTools} or {@code tools}
     * @param templateId the template id, for session templates (null otherwise)
     */
    public record Usage(ItemType type, Long id, String name, String field, String templateId) {

        /**
         * Creates a usage of an item that is not a session template.
         *
         * @param type the referencing item's type
         * @param id the referencing item's id
         * @param name the referencing item's name
         * @param field the field holding the reference
         */
        public Usage(ItemType type, Long id, String name, String field) {
            this(type, id, name, field, null);
        }
    }

    /**
     * Lists every item that references the named server.
     *
     * @param serverName the server name
     * @return the references
     */
    public List<Usage> usagesOf(String serverName) {
        List<Usage> usages = new ArrayList<>();
        for (ActionTypeEntity at : ActionTypeEntity.<ActionTypeEntity>listAll()) {
            if (at.mcpServers.contains(serverName)) {
                usages.add(new Usage(ItemType.ACTION_TYPE, at.id, at.name, "mcpServers"));
            }
            if (referencesTools(splitCsv(at.allowedTools), serverName)) {
                usages.add(new Usage(ItemType.ACTION_TYPE, at.id, at.name, "allowedTools"));
            }
        }
        for (SessionTemplateEntity t : SessionTemplateEntity.<SessionTemplateEntity>listAll()) {
            if (t.mcpServers.contains(serverName)) {
                usages.add(new Usage(ItemType.SESSION_TEMPLATE, t.id, t.name, "mcpServers", t.templateId));
            }
            if (referencesTools(t.allowedTools, serverName)) {
                usages.add(new Usage(ItemType.SESSION_TEMPLATE, t.id, t.name, "allowedTools", t.templateId));
            }
        }
        for (ToolsetEntity ts : ToolsetEntity.<ToolsetEntity>listAll()) {
            if (referencesTools(splitCsv(ts.tools), serverName)) {
                usages.add(new Usage(ItemType.TOOLSET, ts.id, ts.name, "tools"));
            }
        }
        return usages;
    }

    /**
     * Rewrites every reference from one server name to another. Must run in a transaction.
     *
     * @param oldName the current name
     * @param newName the new name
     */
    public void rename(String oldName, String newName) {
        for (ActionTypeEntity at : ActionTypeEntity.<ActionTypeEntity>listAll()) {
            at.mcpServers.replaceAll(n -> n.equals(oldName) ? newName : n);
            if (at.allowedTools != null) {
                at.allowedTools = renameToolPrefixes(at.allowedTools, oldName, newName);
            }
        }
        for (SessionTemplateEntity t : SessionTemplateEntity.<SessionTemplateEntity>listAll()) {
            t.mcpServers.replaceAll(n -> n.equals(oldName) ? newName : n);
            t.allowedTools.replaceAll(tool -> renameToolPrefixes(tool, oldName, newName));
        }
        for (ToolsetEntity ts : ToolsetEntity.<ToolsetEntity>listAll()) {
            if (ts.tools != null) {
                ts.tools = renameToolPrefixes(ts.tools, oldName, newName);
            }
        }
    }

    /**
     * Replaces {@code mcp__<old>__} prefixes of tool names in a single tool or a comma/space separated list.
     *
     * @param tools the tool name(s)
     * @param oldName the old server name
     * @param newName the new server name
     * @return the rewritten string
     */
    static String renameToolPrefixes(String tools, String oldName, String newName) {
        Pattern p = Pattern.compile("(^|[\\s,])mcp__" + Pattern.quote(oldName) + "__");
        return p.matcher(tools).replaceAll("$1" + Matcher.quoteReplacement("mcp__" + newName + "__"));
    }

    private static boolean referencesTools(List<String> tools, String serverName) {
        return tools.stream().anyMatch(t -> McpServerResolver.serverNameOf(t.trim())
                .filter(serverName::equals).isPresent());
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
