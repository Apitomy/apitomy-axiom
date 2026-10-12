package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the workbench context block that is sent to the agent ahead of the user's message on every turn.
 * It tells the agent the current workflow content revision, what the user edited since the agent's last turn
 * started, and which nodes/edges the user currently has selected.
 */
@ApplicationScoped
public class TurnPreamble {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CompositionService service;

    /**
     * Builds the preamble text for a turn.
     *
     * @param compositionId the composition id
     * @param selectedNodeIds the ids of the nodes the user has selected
     * @param selectedEdgeIds the ids of the edges the user has selected
     * @return the context text to put before the user's message
     */
    @Transactional
    public String build(long compositionId, List<String> selectedNodeIds, List<String> selectedEdgeIds) {
        CompositionEntity composition = service.get(compositionId);
        List<CompositionItemEntity> items = service.items(compositionId);
        CompositionItemEntity workflow = items.stream()
                .filter(item -> ConfigItemType.WORKFLOW.jsonName().equals(item.itemType))
                .findFirst()
                .orElse(null);

        List<String> lines = new ArrayList<>();
        lines.add("[Workbench context]");
        lines.add(workflow == null ? "No workflow yet." : "Workflow content revision: " + workflow.contentRevision);

        Instant since = composition.lastTurnStartedOn;
        if (since != null) {
            List<String> edits = new ArrayList<>();
            for (CompositionItemEntity item : items) {
                List<CompositionRevisionEntity> userRevisions = CompositionRevisionEntity.list(
                        "itemId = ?1 and author = 'user' and createdOn > ?2", item.id, since);
                if (userRevisions.isEmpty()) {
                    continue;
                }
                edits.add("- " + item.itemType + " '" + item.name + "': edited by the user");
                if (item == workflow) {
                    CompositionRevisionEntity firstUser = userRevisions.stream()
                            .min(java.util.Comparator.comparing((CompositionRevisionEntity r) -> r.createdOn)
                                    .thenComparing(r -> r.id))
                            .orElseThrow();
                    CompositionRevisionEntity base = CompositionRevisionEntity.<CompositionRevisionEntity>find(
                            "itemId = ?1 and (createdOn < ?2 or (createdOn = ?2 and id < ?3))"
                                    + " order by createdOn desc, id desc",
                            item.id, firstUser.createdOn, firstUser.id)
                            .firstResult();
                    if (base != null) {
                        String diff = WorkflowDiffs.summarize(parse(base.content), service.contentOf(item));
                        if (!diff.isEmpty()) {
                            diff.lines().forEach(line -> edits.add("    " + line));
                        }
                    }
                }
            }
            if (edits.isEmpty()) {
                lines.add("User edits since your last turn: none.");
            } else {
                lines.add("User edits since your last turn:");
                lines.addAll(edits);
            }
        }

        if (!selectedNodeIds.isEmpty() || !selectedEdgeIds.isEmpty()) {
            lines.add("The user is looking at: nodes " + selectedNodeIds + " edges " + selectedEdgeIds);
        }
        lines.add("[End of workbench context]");
        return String.join("\n", lines);
    }

    private static JsonNode parse(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored revision content is not valid JSON", e);
        }
    }
}
