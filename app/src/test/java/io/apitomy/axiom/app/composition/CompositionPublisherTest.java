package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ArrayNode;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishing selected drafts: dependencies, validation, base conflicts and bookkeeping.
 */
@QuarkusTest
class CompositionPublisherTest {

    @Inject
    CompositionService service;

    @Inject
    CompositionPublisher publisher;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
            ActionTypeEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
            ToolDefinitionEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
        });
    }

    /** A node action type with one output, valid on its own. */
    private CompositionItemEntity validActionType(long compositionId, String name) {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.ACTION_TYPE, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("description", "Test action type");
        ArrayNode outputs = content.putArray("outputs");
        outputs.addObject().put("name", "result").put("type", "string").put("required", true);
        content.put("codeTemplate", "export async function run(inputs, ctx) {\n    return { result: 'ok' };\n}\n");
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    private CompositionItemEntity workflowUsing(long compositionId, String name, String actionType) {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.WORKFLOW, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        ObjectNode action = ((ArrayNode) content.path("nodes")).addObject();
        action.put("id", "act-1").put("type", "action").put("name", "Act");
        action.putObject("config").put("actionType", actionType).putObject("inputs");
        ArrayNode edges = content.putArray("edges");
        edges.addObject().put("id", "e1").put("source", "start-1").put("target", "act-1")
                .put("priority", 0).put("isDefault", true);
        edges.addObject().put("id", "e2").put("source", "act-1").put("target", "end-1")
                .put("priority", 0).put("isDefault", true);
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    @Test
    void publishesWorkflowWithItsDraftActionType() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-at");
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-at");

        CompositionPublisher.Outcome outcome = publisher.publish(composition.id,
                List.of(workflow.id, actionType.id), false);
        assertEquals(2, outcome.created());
        assertEquals(0, outcome.updated());

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.find("name", "cpt-wf").firstResult();
            assertNotNull(definition);
            assertNotNull(definition.currentVersion);
            CompositionItemEntity published = CompositionItemEntity.findById(actionType.id);
            assertEquals(published.contentRevision, published.publishedRevision);
            assertNotNull(published.baseItemId);
            assertFalse(CompositionService.isChanged(published));
        });
    }

    @Test
    void blocksWorkflowWhoseDraftActionTypeIsNotSelected() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        validActionType(composition.id, "cpt-at");
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-at");

        PublishBlockedException blocked = assertThrows(PublishBlockedException.class,
                () -> publisher.publish(composition.id, List.of(workflow.id), false));
        assertTrue(blocked.problems().containsKey("workflow/cpt-wf"), blocked.problems().toString());
        assertEquals("missing-dependency", blocked.problems().get("workflow/cpt-wf").get(0).code());
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> WorkflowDefinitionEntity.count("name", "cpt-wf")));
    }

    @Test
    void blocksInvalidItems() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-nowhere");
        assertThrows(PublishBlockedException.class,
                () -> publisher.publish(composition.id, List.of(workflow.id), false));
    }

    @Test
    void requiresAtLeastOneItem() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(composition.id, List.of(), false));
    }

    @Test
    void baseChangedOutsideWorkbenchNeedsForce() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-at");
        publisher.publish(composition.id, List.of(actionType.id), false);

        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity entity = ActionTypeEntity.find("name", "cpt-at").firstResult();
            entity.description = "edited elsewhere";
        });
        CompositionItemEntity current = QuarkusTransaction.requiringNew().call(
                () -> CompositionItemEntity.<CompositionItemEntity>findById(actionType.id));
        ObjectNode content = (ObjectNode) service.contentOf(current);
        content.put("description", "edited in workbench");
        service.save(composition.id, actionType.id, content, current.contentRevision, CompositionService.AUTHOR_USER, null);

        assertThrows(CompositionConflictException.class,
                () -> publisher.publish(composition.id, List.of(actionType.id), false));
        CompositionPublisher.Outcome forced = publisher.publish(composition.id, List.of(actionType.id), true);
        assertEquals(1, forced.updated());
        assertEquals("edited in workbench", QuarkusTransaction.requiringNew().call(
                () -> ActionTypeEntity.<ActionTypeEntity>find("name", "cpt-at").firstResult().description));
    }

    @Test
    void newDraftMatchingExistingNameNeedsForce() {
        CompositionEntity first = service.create("c1", null, null, null, null);
        CompositionItemEntity original = validActionType(first.id, "cpt-dup");
        publisher.publish(first.id, List.of(original.id), false);

        CompositionEntity second = service.create("c2", null, null, null, null);
        CompositionItemEntity duplicate = validActionType(second.id, "cpt-dup");
        CompositionConflictException conflict = assertThrows(CompositionConflictException.class,
                () -> publisher.publish(second.id, List.of(duplicate.id), false));
        assertTrue(conflict.getMessage().contains("action-type/cpt-dup already exists outside this composition"),
                conflict.getMessage());
        CompositionPublisher.Outcome forced = publisher.publish(second.id, List.of(duplicate.id), true);
        assertEquals(1, forced.updated());
    }

    @Test
    void renamedLoadedItemNeedsForce() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-old");
        publisher.publish(composition.id, List.of(actionType.id), false);

        CompositionItemEntity current = QuarkusTransaction.requiringNew().call(
                () -> CompositionItemEntity.<CompositionItemEntity>findById(actionType.id));
        ObjectNode content = (ObjectNode) service.contentOf(current);
        content.put("name", "cpt-new");
        service.save(composition.id, actionType.id, content, current.contentRevision, CompositionService.AUTHOR_USER, null);

        CompositionConflictException conflict = assertThrows(CompositionConflictException.class,
                () -> publisher.publish(composition.id, List.of(actionType.id), false));
        assertTrue(conflict.getMessage().contains("action-type/cpt-new was renamed from 'cpt-old'; "
                + "publishing creates a new item and keeps 'cpt-old'"), conflict.getMessage());
        CompositionPublisher.Outcome forced = publisher.publish(composition.id, List.of(actionType.id), true);
        assertEquals(1, forced.created());
        assertEquals(2L, QuarkusTransaction.requiringNew().call(
                () -> ActionTypeEntity.count("name in ?1", List.of("cpt-old", "cpt-new"))));
    }

    @Test
    void publishDoesNotOverwriteConcurrentSave() throws Exception {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-race");
        ObjectNode content = (ObjectNode) service.contentOf(actionType);
        content.put("description", "saved concurrently");

        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> saver = CompletableFuture.runAsync(() -> QuarkusTransaction.requiringNew().run(() -> {
            service.save(composition.id, actionType.id, content, actionType.contentRevision,
                    CompositionService.AUTHOR_USER, null);
            saved.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(saved.await(10, TimeUnit.SECONDS));
        CompletableFuture<CompositionPublisher.Outcome> publishing = CompletableFuture.supplyAsync(
                () -> publisher.publish(composition.id, List.of(actionType.id), false));
        Thread.sleep(500);
        release.countDown();
        saver.get(20, TimeUnit.SECONDS);
        publishing.get(20, TimeUnit.SECONDS);

        QuarkusTransaction.requiringNew().run(() -> {
            CompositionItemEntity stored = CompositionItemEntity.findById(actionType.id);
            CompositionRevisionEntity newest = CompositionRevisionEntity
                    .<CompositionRevisionEntity>find("itemId = ?1 order by createdOn desc, id desc", actionType.id)
                    .firstResult();
            assertEquals(newest.contentRevision, stored.contentRevision);
            assertEquals(stored.contentRevision, stored.publishedRevision);
        });
    }
}
