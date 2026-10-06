package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;

/** Resolves {@code workflowDefinitionName} in routing rules to {@code workflowDefinitionId}. */
@ApplicationScoped
public class SubscriptionRoutingResolver {

    private static final String NAME_FIELD = "workflowDefinitionName";
    private static final String ID_FIELD = "workflowDefinitionId";

    /**
     * Returns a copy of the routing array in which every rule with {@code workflowDefinitionName} has
     * {@code workflowDefinitionId} set from the database and the name removed.
     *
     * @param routing routing rules JSON array (may be null)
     * @return resolved array (null if input null)
     * @throws WebApplicationException 400 "Unknown workflow definition name: &lt;name&gt;" if not found
     */
    public JsonNode resolve(JsonNode routing) {
        if (routing == null || routing.isNull()) {
            return null;
        }
        JsonNode copy = routing.deepCopy();
        if (copy instanceof ArrayNode rules) {
            for (JsonNode rule : rules) {
                if (rule instanceof ObjectNode obj && obj.hasNonNull(NAME_FIELD)) {
                    String name = obj.get(NAME_FIELD).asText();
                    WorkflowDefinitionEntity definition = WorkflowDefinitionEntity
                            .<WorkflowDefinitionEntity>find("name", name).firstResult();
                    if (definition == null) {
                        throw new WebApplicationException("Unknown workflow definition name: " + name, 400);
                    }
                    obj.put(ID_FIELD, definition.id);
                    obj.remove(NAME_FIELD);
                }
            }
        }
        return copy;
    }
}
