package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test proving Axiom can use Apitomy Flow 3.x change sets and content revisions server-side.
 */
class FlowChangeSetSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKFLOW = """
            {"id":"w","name":"W","nodes":[
              {"id":"s","type":"start","name":"Start","config":{},"position":{"x":0,"y":0}},
              {"id":"e","type":"end","name":"End","config":{}}],
             "edges":[{"id":"e1","source":"s","target":"e","priority":0,"isDefault":true}]}
            """;

    @Test
    void revisionIgnoresLayout() throws Exception {
        ObjectNode workflow = (ObjectNode) MAPPER.readTree(WORKFLOW);
        String before = ContentRevision.of(workflow);
        ((ObjectNode) workflow.path("nodes").get(0).path("position")).put("x", 500);
        assertTrue(before.startsWith("sha256:"));
        assertEquals(before, ContentRevision.of(workflow));
    }

    @Test
    void appliesChangeSetAgainstCurrentRevision() throws Exception {
        JsonNode workflow = MAPPER.readTree(WORKFLOW);
        JsonNode changeSet = MAPPER.readTree("""
                {"id":"cs1","baseRevision":"%s","author":"agent:axiom-workbench","summary":"rename",
                 "ops":[{"op":"updateNode","id":"s","patch":{"name":"Begin"}}]}
                """.formatted(ContentRevision.of(workflow)));
        ChangeSetResult result = ChangeSets.applyChecked(workflow, changeSet);
        ChangeSetResult.Applied applied = assertInstanceOf(ChangeSetResult.Applied.class, result);
        assertEquals("Begin", applied.workflow().path("nodes").get(0).path("name").asText());
    }

    @Test
    void staleChangeSetIsRejected() throws Exception {
        JsonNode changeSet = MAPPER.readTree("""
                {"id":"cs2","baseRevision":"sha256:old","author":"agent:axiom-workbench","summary":"x",
                 "ops":[{"op":"removeNode","id":"e"}]}
                """);
        ChangeSetResult result = ChangeSets.applyChecked(MAPPER.readTree(WORKFLOW), changeSet);
        assertEquals("stale", assertInstanceOf(ChangeSetResult.Rejected.class, result).error().code());
    }
}
