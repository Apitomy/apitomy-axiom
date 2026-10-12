package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Templates for new workflow documents.
 */
public final class WorkflowTemplates {

    private WorkflowTemplates() {
    }

    /**
     * Builds a new workflow with a Start node (declaring the standard subjectId, ref and event inputs), an End
     * node and one default edge between them.
     *
     * @param name the workflow name
     * @param mapper the JSON mapper
     * @return the Flow workflow document
     */
    public static ObjectNode emptyWorkflow(String name, ObjectMapper mapper) {
        ObjectNode workflow = mapper.createObjectNode();
        workflow.put("id", UUID.randomUUID().toString());
        workflow.put("name", name);
        ArrayNode nodes = workflow.putArray("nodes");

        ObjectNode start = nodes.addObject();
        start.put("id", "start-1");
        start.put("type", "start");
        start.put("name", "Start");
        ArrayNode inputs = start.putObject("config").putArray("inputs");
        input(inputs, "subjectId", "number", true, "The Axiom subject ID");
        input(inputs, "ref", "string", true, "Full URL identifying the subject");
        input(inputs, "event", "object", false,
                "The triggering event (present when created by subscription routing)");

        ObjectNode end = nodes.addObject();
        end.put("id", "end-1");
        end.put("type", "end");
        end.put("name", "End");
        end.putObject("config");

        ObjectNode edge = workflow.putArray("edges").addObject();
        edge.put("id", "edge-1");
        edge.put("source", "start-1");
        edge.put("target", "end-1");
        edge.put("priority", 0);
        edge.put("isDefault", true);
        return workflow;
    }

    private static void input(ArrayNode inputs, String name, String type, boolean required, String description) {
        ObjectNode input = inputs.addObject();
        input.put("name", name);
        input.put("type", type);
        input.put("required", required);
        input.put("description", description);
    }
}
