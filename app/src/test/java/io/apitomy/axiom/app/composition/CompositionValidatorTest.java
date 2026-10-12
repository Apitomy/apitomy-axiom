package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drafts are validated against the database plus the other drafts in the same composition.
 */
@QuarkusTest
class CompositionValidatorTest {

    @Inject
    CompositionService service;

    @Inject
    CompositionValidator validator;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private static boolean hasErrors(List<ConfigProblem> problems) {
        return problems.stream().anyMatch(ConfigProblem::isError);
    }

    /** Makes the workflow call the given action type between Start and End. */
    private ObjectNode workflowUsing(CompositionItemEntity workflow, String actionType) {
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ArrayNode nodes = (ArrayNode) content.path("nodes");
        ObjectNode action = nodes.addObject();
        action.put("id", "act-1");
        action.put("type", "action");
        action.put("name", "Act");
        action.putObject("config").put("actionType", actionType).putObject("inputs");
        ArrayNode edges = content.putArray("edges");
        edges.addObject().put("id", "e1").put("source", "start-1").put("target", "act-1")
                .put("priority", 0).put("isDefault", true);
        edges.addObject().put("id", "e2").put("source", "act-1").put("target", "end-1")
                .put("priority", 0).put("isDefault", true);
        return content;
    }

    @Test
    void workflowMayUseActionTypeThatIsOnlyADraft() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cvt-wf");
        service.addBlank(composition.id, ConfigItemType.ACTION_TYPE, "cvt-draft-at");

        List<ConfigProblem> withDraft = validator.validateContent(composition.id, ConfigItemType.WORKFLOW,
                "cvt-wf", workflowUsing(workflow, "cvt-draft-at"));
        assertFalse(hasErrors(withDraft), withDraft.toString());

        List<ConfigProblem> unknown = validator.validateContent(composition.id, ConfigItemType.WORKFLOW,
                "cvt-wf", workflowUsing(workflow, "cvt-no-such-at"));
        assertTrue(hasErrors(unknown), unknown.toString());
    }

    @Test
    void validateAllReturnsAnEntryForEveryItem() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cvt-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cvt-tool");
        Map<Long, List<ConfigProblem>> problems = validator.validateAll(composition.id);
        assertEquals(List.of(workflow.id, tool.id), List.copyOf(problems.keySet()));
    }
}
