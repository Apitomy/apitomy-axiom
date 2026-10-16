package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Undoing an AI turn restores, removes or skips the items it touched.
 */
@QuarkusTest
class TurnUndoTest {

    @Inject
    CompositionService service;

    @Inject
    TurnUndo undo;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private CompositionItemEntity aiSave(long compositionId, CompositionItemEntity item, String description, String turnId) {
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("description", description);
        return service.save(compositionId, item.id, content, null, CompositionService.AUTHOR_AI, turnId);
    }

    @Test
    void restoresChangedRemovesCreatedSkipsLaterEdited() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "tu-wf");
        CompositionItemEntity changed = service.addBlank(composition.id, ConfigItemType.TOOL, "tu-changed");
        CompositionItemEntity edited = service.addBlank(composition.id, ConfigItemType.TOOL, "tu-edited");
        String originalChanged = changed.contentRevision;

        aiSave(composition.id, changed, "by ai", "t1");
        CompositionItemEntity editedAi = aiSave(composition.id, edited, "by ai", "t1");
        service.addBlank(composition.id, ConfigItemType.TOOL, "tu-created", CompositionService.AUTHOR_AI, "t1");
        aiSave(composition.id, workflow, "wf by ai", "t1");
        aiSave(composition.id, editedAi, "by user later", null);

        TurnUndo.Result result = undo.undo(composition.id, "t1");
        assertEquals(List.of("tool/tu-changed"), result.restored());
        assertEquals(List.of("tool/tu-edited"), result.skipped());
        assertEquals(List.of("tool/tu-created"), result.removed());
        assertTrue(result.workflowRestorable());
        assertEquals("", result.workflowBefore().path("description").asText(""));
        CompositionItemEntity restored = service.items(composition.id).stream()
                .filter(i -> "tu-changed".equals(i.name)).findFirst().orElseThrow();
        assertEquals(originalChanged, restored.contentRevision);
    }

    @Test
    void unknownTurnIsNotFound() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        assertThrows(CompositionNotFoundException.class, () -> undo.undo(composition.id, "nope"));
    }
}
