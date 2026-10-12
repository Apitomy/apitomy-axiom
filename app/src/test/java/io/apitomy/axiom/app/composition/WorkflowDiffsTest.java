package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the node-level workflow summary used in turn preambles.
 */
class WorkflowDiffsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void reportsAddedRemovedChangedAndEdges() throws Exception {
        JsonNode before = json("""
                {"nodes":[{"id":"a","name":"A"},{"id":"b","name":"B"},{"id":"c","name":"C","position":{"x":1,"y":1}}],
                 "edges":[{"id":"e1","source":"a","target":"b"}]}""");
        JsonNode after = json("""
                {"nodes":[{"id":"a","name":"A2"},{"id":"c","name":"C","position":{"x":9,"y":9}},{"id":"d","name":"D"}],
                 "edges":[{"id":"e2","source":"a","target":"d"}]}""");
        String summary = WorkflowDiffs.summarize(before, after);
        assertTrue(summary.contains("added nodes: d"), summary);
        assertTrue(summary.contains("removed nodes: b"), summary);
        assertTrue(summary.contains("changed nodes: a"), summary);
        assertTrue(summary.contains("edges changed"), summary);
    }

    @Test
    void layoutOnlyIsNoChange() throws Exception {
        JsonNode before = json("{\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":1,\"y\":1}}],\"edges\":[]}");
        JsonNode after = json("{\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":5,\"y\":1}}],\"edges\":[]}");
        assertEquals("", WorkflowDiffs.summarize(before, after));
    }
}
