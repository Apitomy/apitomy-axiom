package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for creating compositions, seeding, saving with revision checks, revisions and restore.
 */
@QuarkusTest
class CompositionServiceTest {

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.delete("name like ?1", "cst-%");
            ActionTypeEntity.list("name like ?1", "cst-%").forEach(e -> e.delete());
            ToolDefinitionEntity.list("name like ?1", "cst-%").forEach(e -> e.delete());
        });
    }

    @Test
    void createsBlankCompositionAndItems() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        assertEquals(CompositionEntity.TURN_IDLE, composition.turnState);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        assertEquals(2, service.items(composition.id).size());
        assertEquals(CompositionItems.revisionOf(service.contentOf(workflow)), workflow.contentRevision);
        assertEquals(1, service.revisions(composition.id, tool.id).size());
        assertTrue(CompositionService.isChanged(tool));
    }

    @Test
    void rejectsSecondWorkflowAndDuplicateNames() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        assertThrows(CompositionConflictException.class,
                () -> service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf2"));
        assertThrows(CompositionConflictException.class,
                () -> service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool"));
        assertThrows(IllegalArgumentException.class,
                () -> service.addBlank(composition.id, ConfigItemType.TOOLSET, "cst-ts"));
    }

    @Test
    void saveChecksBaseRevisionAndRecordsAuthor() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("description", "changed");

        assertThrows(StaleRevisionException.class,
                () -> service.save(composition.id, tool.id, content, "sha256:old", CompositionService.AUTHOR_USER, null));

        CompositionItemEntity saved = service.save(composition.id, tool.id, content, tool.contentRevision,
                CompositionService.AUTHOR_AI, "turn-1");
        assertNotEquals(tool.contentRevision, saved.contentRevision);
        assertEquals("ai", saved.lastAuthor);
        assertEquals("turn-1", saved.lastTurnId);
        List<CompositionRevisionEntity> revisions = service.revisions(composition.id, tool.id);
        assertEquals(2, revisions.size());
        assertEquals("turn-1", revisions.get(0).turnId);
    }

    @Test
    void saveRenamesActionTypesAndToolsFromContent() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-a");
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("name", "cst-b");
        assertEquals("cst-b", service.save(composition.id, tool.id, content, tool.contentRevision,
                CompositionService.AUTHOR_USER, null).name);
        content.remove("name");
        assertThrows(IllegalArgumentException.class, () -> service.save(composition.id, tool.id, content,
                null, CompositionService.AUTHOR_USER, null));
    }

    @Test
    void layoutOnlyWorkflowSaveKeepsRevisionAndAddsNoRevisionRow() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ((ObjectNode) content.path("nodes").get(0)).putObject("position").put("x", 10).put("y", 20);
        CompositionItemEntity saved = service.save(composition.id, workflow.id, content, workflow.contentRevision,
                CompositionService.AUTHOR_USER, null);
        assertEquals(workflow.contentRevision, saved.contentRevision);
        assertEquals(10, service.contentOf(saved).path("nodes").get(0).path("position").path("x").asInt());
        assertEquals(1, service.revisions(composition.id, workflow.id).size());
    }

    @Test
    void restoreCreatesNewUserRevision() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        String original = tool.contentRevision;
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("description", "v2");
        service.save(composition.id, tool.id, content, original, CompositionService.AUTHOR_AI, "t1");
        long firstRevisionId = service.revisions(composition.id, tool.id).get(1).id;

        CompositionItemEntity restored = service.restore(composition.id, tool.id, firstRevisionId);
        assertEquals(original, restored.contentRevision);
        assertEquals("user", restored.lastAuthor);
        assertEquals(3, service.revisions(composition.id, tool.id).size());
    }

    @Test
    void seedsFromExistingWorkflowWithActionTypesAndTools() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolDefinitionEntity tool = new ToolDefinitionEntity();
            tool.name = "cst-seed-tool";
            tool.scriptTemplate = "echo hi";
            tool.persist();
            ActionTypeEntity actionType = new ActionTypeEntity();
            actionType.name = "cst-seed-at";
            actionType.executionMode = "agent";
            actionType.promptTemplate = "Do it";
            actionType.allowedTools = "mcp__axiom-tools__cst-seed-tool";
            actionType.persist();
            WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
            workflow.name = "cst-seed-wf";
            workflow.content = """
                    {"id":"w","name":"cst-seed-wf","nodes":[
                      {"id":"a","type":"action","name":"A","config":{"actionType":"cst-seed-at"}}],"edges":[]}
                    """;
            workflow.createdOn = Instant.now();
            workflow.updatedOn = workflow.createdOn;
            workflow.persist();
        });
        long workflowId = QuarkusTransaction.requiringNew().call(
                () -> WorkflowDefinitionEntity.<WorkflowDefinitionEntity>find("name", "cst-seed-wf").firstResult().id);

        CompositionEntity composition = service.create("seeded", null, null, null, workflowId);
        List<CompositionItemEntity> items = service.items(composition.id);
        assertEquals(List.of("workflow:cst-seed-wf", "action-type:cst-seed-at", "tool:cst-seed-tool"),
                items.stream().map(i -> i.itemType + ":" + i.name).toList());
        CompositionItemEntity actionType = items.get(1);
        assertEquals(actionType.baseContentRevision,
                service.currentBaseRevision(ConfigItemType.ACTION_TYPE, actionType.baseItemId));
        assertFalse(CompositionService.isChanged(actionType));
        JsonNode workflowContent = service.contentOf(items.get(0));
        assertEquals("cst-seed-at", workflowContent.path("nodes").get(0).path("config").path("actionType").asText());
    }

    @Test
    void secondSaveWithSameBaseRevisionIsStale() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        String base = tool.contentRevision;
        ObjectNode first = (ObjectNode) service.contentOf(tool);
        first.put("description", "user edit");
        ObjectNode second = (ObjectNode) service.contentOf(tool);
        second.put("description", "ai edit");

        service.save(composition.id, tool.id, first, base, CompositionService.AUTHOR_USER, null);
        StaleRevisionException stale = assertThrows(StaleRevisionException.class,
                () -> service.save(composition.id, tool.id, second, base, CompositionService.AUTHOR_AI, "t1"));
        assertEquals(CompositionItems.revisionOf(first), stale.currentRevision());
        assertEquals("user edit",
                service.contentOf(service.item(composition.id, tool.id)).path("description").asText());
    }

    @Test
    void concurrentSavesWithSameBaseRevisionAllowExactlyOneWinner() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        String base = tool.contentRevision;
        ObjectNode userContent = (ObjectNode) service.contentOf(tool);
        userContent.put("description", "user edit");
        ObjectNode aiContent = (ObjectNode) service.contentOf(tool);
        aiContent.put("description", "ai edit");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CompositionItemEntity> userSave = executor.submit(() -> {
                start.await();
                return service.save(composition.id, tool.id, userContent, base, CompositionService.AUTHOR_USER, null);
            });
            Future<CompositionItemEntity> aiSave = executor.submit(() -> {
                start.await();
                return service.save(composition.id, tool.id, aiContent, base, CompositionService.AUTHOR_AI, "t1");
            });
            start.countDown();

            int successes = 0;
            int stale = 0;
            for (Future<CompositionItemEntity> future : List.of(userSave, aiSave)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertInstanceOf(StaleRevisionException.class, e.getCause(), "unexpected failure: " + e.getCause());
                    stale++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, stale);
            assertEquals(2, service.revisions(composition.id, tool.id).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unknownCompositionThrowsNotFound() {
        assertThrows(CompositionNotFoundException.class, () -> service.get(999_999L));
        assertThrows(CompositionNotFoundException.class, () -> service.items(999_999L));
    }

    @Test
    void deleteRemovesItemsAndRevisions() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        service.delete(composition.id);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> CompositionItemEntity.count()
                + CompositionRevisionEntity.count()));
    }
}
