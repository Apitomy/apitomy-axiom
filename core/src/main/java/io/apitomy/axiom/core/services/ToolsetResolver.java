package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ToolsetEntity;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves allowed tools strings that may contain toolset references.
 * A toolset reference is prefixed with {@code @} (e.g., {@code @Read-Only Tools}).
 * References are expanded into the toolset's constituent tools. The final
 * list is deduplicated while preserving order.
 *
 * <p>Toolset references can be nested — a toolset can contain {@code @} references
 * to other toolsets, which are recursively expanded.</p>
 */
@ApplicationScoped
public class ToolsetResolver {

    private static final Logger LOG = Logger.getLogger(ToolsetResolver.class);

    /**
     * Resolves a comma-separated allowed tools string, expanding any
     * {@code @ToolsetName} references into their constituent tools.
     *
     * @param allowedTools comma-separated tool strings (may contain @references)
     * @return the flat, deduplicated list of individual tool strings
     */
    public List<String> resolve(String allowedTools) {
        if (allowedTools == null || allowedTools.isBlank()) {
            return List.of();
        }

        return resolve(Arrays.stream(allowedTools.split(",")).toList());
    }

    /**
     * Resolves a list of allowed tool entries, expanding any {@code @ToolsetName}
     * references (recursively, with cycle detection) into their constituent tools.
     * Entries are not split on commas.
     *
     * @param allowedTools the tool entries (may contain @references); may be null
     * @return the flat, deduplicated list of individual tool strings
     */
    public List<String> resolve(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }

        Set<String> resolved = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>(); // cycle detection

        allowedTools.stream()
                .filter(s -> s != null)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(entry -> expandEntry(entry, resolved, visited));

        return new ArrayList<>(resolved);
    }

    /**
     * Expands a single entry — either a direct tool name or a @toolset reference.
     */
    private void expandEntry(String entry, Set<String> resolved, Set<String> visited) {
        if (entry.startsWith("@")) {
            String toolsetName = entry.substring(1);
            if (visited.contains(toolsetName)) {
                LOG.warnf("Circular toolset reference detected: @%s", toolsetName);
                return;
            }
            visited.add(toolsetName);

            ToolsetEntity toolset = ToolsetEntity.find("name", toolsetName).firstResult();
            if (toolset == null || toolset.tools == null) {
                LOG.warnf("Toolset not found: @%s", toolsetName);
                return;
            }

            // Recursively expand the toolset's tools
            List<String> toolsetEntries = Arrays.stream(toolset.tools.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            for (String subEntry : toolsetEntries) {
                expandEntry(subEntry, resolved, visited);
            }
        } else {
            resolved.add(entry);
        }
    }
}
