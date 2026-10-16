package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
            EventSubscriptionEntity.delete("name like ?1", "cvt-%");
            WorkflowDefinitionEntity.delete("name like ?1", "cvt-%");
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

    private CompositionItemEntity subscription(long compositionId, String name, String routingJson,
                                               boolean withEnabled) throws Exception {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.SUBSCRIPTION, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("filterExpression", "event.type == 'pr.created'");
        content.set("routing", new ObjectMapper().readTree(routingJson));
        if (withEnabled) {
            content.put("enabled", true);
        }
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    private static List<ConfigProblem> codes(List<ConfigProblem> problems, String code) {
        return problems.stream().filter(p -> p.code().equals(code)).toList();
    }

    /** Creates a composition with one blank workflow item named {@code cvt-wf-<name>}. */
    private CompositionEntity compositionWithWorkflow(String name) {
        CompositionEntity composition = service.create(name, null, null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cvt-wf-" + name);
        return composition;
    }

    @Test
    void createWorkflowMustTargetTheCompositionWorkflowByName() throws Exception {
        CompositionEntity composition = compositionWithWorkflow("cvt-c1");
        String wf = service.items(composition.id).stream()
                .filter(i -> i.itemType.equals("workflow")).findFirst().orElseThrow().name;
        CompositionItemEntity good = subscription(composition.id, "cvt-good",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"" + wf + "\"},"
                        + " {\"type\": \"workflow-dispatch\"}]", false);
        CompositionItemEntity other = subscription(composition.id, "cvt-other",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"someone-else\"}]", false);
        CompositionItemEntity byId = subscription(composition.id, "cvt-id",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionId\": 1}]", false);

        Map<Long, List<ConfigProblem>> all = validator.validateAll(composition.id);
        assertTrue(codes(all.get(good.id), "FOREIGN_WORKFLOW_ROUTE").isEmpty(), all.get(good.id).toString());
        assertEquals(1, codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").size());
        assertEquals("error", codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").get(0).severity());
        assertEquals("routing[0]", codes(all.get(other.id), "FOREIGN_WORKFLOW_ROUTE").get(0).path());
        assertEquals(1, codes(all.get(byId.id), "FOREIGN_WORKFLOW_ROUTE").size());
    }

    @Test
    void enabledInADraftIsIgnoredWithAWarning() throws Exception {
        CompositionEntity composition = compositionWithWorkflow("cvt-c2");
        CompositionItemEntity sub = subscription(composition.id, "cvt-en", "[]", true);
        List<ConfigProblem> problems = codes(validator.validateAll(composition.id).get(sub.id), "ENABLED_IGNORED");
        assertEquals(1, problems.size());
        assertEquals("warning", problems.get(0).severity());
        assertEquals("Subscriptions are published disabled; enable them in the workbench after publishing.",
                problems.get(0).message());
    }

    @Test
    void warnsWhenAnotherEnabledSubscriptionStartsTheWorkflow() throws Exception {
        CompositionEntity composition = compositionWithWorkflow("cvt-c3");
        CompositionItemEntity wfItem = service.items(composition.id).stream()
                .filter(i -> i.itemType.equals("workflow")).findFirst().orElseThrow();
        long definitionId = TestFixtures.createWaitDefinition(wfItem.name);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity s = new EventSubscriptionEntity();
            s.name = "cvt-outside";
            s.enabled = true;
            s.routing = "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId + "}]";
            s.createdOn = Instant.now();
            s.modifiedOn = Instant.now();
            s.persist();
        });
        CompositionItemEntity sub = subscription(composition.id, "cvt-mine",
                "[{\"type\": \"create-workflow\", \"workflowDefinitionName\": \"" + wfItem.name + "\"}]", false);
        List<ConfigProblem> problems = codes(validator.validateAll(composition.id).get(sub.id),
                "OVERLAPPING_SUBSCRIPTION");
        assertEquals(1, problems.size());
        assertEquals("warning", problems.get(0).severity());
        assertTrue(problems.get(0).message().contains("cvt-outside"), problems.get(0).message());
    }
}
