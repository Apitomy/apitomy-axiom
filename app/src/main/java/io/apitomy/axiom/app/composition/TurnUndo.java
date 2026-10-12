package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Undoes an AI turn: restores the items the turn changed, removes the items it created and skips items that
 * were changed again after the turn. The workflow is never changed on the server; the client applies its
 * restored content.
 */
@ApplicationScoped
public class TurnUndo {

    /**
     * The result of undoing a turn. List entries are {@code "<type>/<name>"}.
     *
     * @param restored items restored to their content before the turn
     * @param removed items the turn created, now removed
     * @param skipped items changed after the turn, left as they are
     * @param workflowBefore the workflow content before the turn, or null
     * @param workflowRestorable whether the client should restore {@code workflowBefore}
     */
    public record Result(List<String> restored, List<String> removed, List<String> skipped, JsonNode workflowBefore,
                         boolean workflowRestorable) {
    }

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    /**
     * Undoes the given turn on the composition.
     *
     * @param compositionId the composition id
     * @param turnId the turn id
     * @return what was restored, removed and skipped
     * @throws CompositionConflictException while an AI turn is running
     * @throws CompositionNotFoundException if no revision carries {@code turnId}
     */
    @Transactional
    public Result undo(long compositionId, String turnId) {
        CompositionEntity composition = service.get(compositionId);
        if (CompositionEntity.TURN_AI_WORKING.equals(composition.turnState)) {
            throw new CompositionConflictException("Wait for the AI turn to finish before undoing");
        }
        List<String> restored = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        JsonNode workflowBefore = null;
        boolean workflowRestorable = false;
        boolean found = false;
        for (CompositionItemEntity item : service.items(compositionId)) {
            List<CompositionRevisionEntity> revisions = CompositionRevisionEntity.list(
                    "itemId = ?1 order by createdOn asc, id asc", item.id);
            int firstIndex = -1;
            for (int i = 0; i < revisions.size(); i++) {
                if (Objects.equals(turnId, revisions.get(i).turnId)) {
                    firstIndex = i;
                    break;
                }
            }
            if (firstIndex < 0) {
                continue;
            }
            found = true;
            String label = item.itemType + "/" + item.name;
            CompositionRevisionEntity before = firstIndex > 0 ? revisions.get(firstIndex - 1) : null;
            boolean changedLater = !Objects.equals(turnId, revisions.get(revisions.size() - 1).turnId);
            if (changedLater) {
                skipped.add(label);
            }
            if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
                workflowBefore = before == null ? null : parse(before.content);
                workflowRestorable = !changedLater;
            } else if (!changedLater) {
                if (before != null) {
                    service.save(compositionId, item.id, parse(before.content), null,
                            CompositionService.AUTHOR_USER, null);
                    restored.add(label);
                } else {
                    service.removeItem(compositionId, item.id);
                    removed.add(label);
                }
            }
        }
        if (!found) {
            throw new CompositionNotFoundException("No changes from AI turn " + turnId);
        }
        return new Result(restored, removed, skipped, workflowBefore, workflowRestorable);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Stored revision content is not valid JSON", e);
        }
    }
}
