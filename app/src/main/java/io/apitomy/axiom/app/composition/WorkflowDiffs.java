package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces a short, node-level summary of the differences between two versions of a workflow. Node layout
 * ({@code position}) is ignored.
 */
public final class WorkflowDiffs {

    private WorkflowDiffs() {
    }

    /**
     * Summarizes how a workflow changed.
     *
     * @param before the earlier workflow JSON
     * @param after the later workflow JSON
     * @return newline-separated lines describing added, removed and changed nodes and changed edges, or an
     *         empty string when nothing (other than layout) differs
     */
    public static String summarize(JsonNode before, JsonNode after) {
        Map<String, JsonNode> beforeNodes = indexNodes(before);
        Map<String, JsonNode> afterNodes = indexNodes(after);
        List<String> added = afterNodes.keySet().stream().filter(id -> !beforeNodes.containsKey(id)).toList();
        List<String> removed = beforeNodes.keySet().stream().filter(id -> !afterNodes.containsKey(id)).toList();
        List<String> changed = afterNodes.keySet().stream()
                .filter(beforeNodes::containsKey)
                .filter(id -> !beforeNodes.get(id).equals(afterNodes.get(id)))
                .toList();
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add("added nodes: " + String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            parts.add("removed nodes: " + String.join(", ", removed));
        }
        if (!changed.isEmpty()) {
            parts.add("changed nodes: " + String.join(", ", changed));
        }
        if (!edgeSet(before).equals(edgeSet(after))) {
            parts.add("edges changed");
        }
        return String.join("\n", parts);
    }

    private static Map<String, JsonNode> indexNodes(JsonNode workflow) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        if (workflow == null) {
            return nodes;
        }
        for (JsonNode node : workflow.path("nodes")) {
            JsonNode copy = node.deepCopy();
            if (copy instanceof ObjectNode objectNode) {
                objectNode.remove("position");
            }
            nodes.put(node.path("id").asText(), copy);
        }
        return nodes;
    }

    private static Set<String> edgeSet(JsonNode workflow) {
        Set<String> edges = new HashSet<>();
        if (workflow != null) {
            for (JsonNode edge : workflow.path("edges")) {
                edges.add(edge.toString());
            }
        }
        return edges;
    }
}
