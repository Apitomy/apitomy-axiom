package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preamble tells the agent the workflow revision, what the user changed since its last turn, and what the
 * user is looking at.
 */
@QuarkusTest
class TurnPreambleTest {

    @Inject
    CompositionService service;

    @Inject
    TurnPreamble preamble;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void summarizesUserEditsSinceLastTurn() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "tp-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "tp-tool");
        Thread.sleep(5);
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionEntity c = CompositionEntity.findById(composition.id);
            c.lastTurnStartedOn = Instant.now();
        });
        Thread.sleep(5);
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ((ObjectNode) content.path("nodes").get(0)).put("name", "Begin");
        service.save(composition.id, workflow.id, content, workflow.contentRevision, CompositionService.AUTHOR_USER, null);

        String text = preamble.build(composition.id, List.of("start-1"), List.of());
        assertTrue(text.startsWith("[Workbench context]"), text);
        assertTrue(text.contains("Workflow content revision: sha256:"), text);
        assertTrue(text.contains("workflow 'tp-wf': edited by the user"), text);
        assertTrue(text.contains("changed nodes: start-1"), text);
        assertTrue(!text.contains("tp-tool"), text);
        assertTrue(text.contains("The user is looking at: nodes [start-1]"), text);
    }

    @Test
    void firstTurnHasNoEditSummary() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        String text = preamble.build(composition.id, List.of(), List.of());
        assertTrue(text.contains("No workflow yet."), text);
        assertTrue(!text.contains("User edits since your last turn"), text);
    }
}
