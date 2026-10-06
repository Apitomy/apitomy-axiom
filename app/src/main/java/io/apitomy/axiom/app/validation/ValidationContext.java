package io.apitomy.axiom.app.validation;

import java.util.Map;
import java.util.Set;

/**
 * Names that items may reference: existing database entities plus items pending in the same pack or session.
 * A null set disables the corresponding existence check.
 *
 * <p>{@code actionTypeInputs} maps action type name to declared input name to whether it is required; null
 * disables node input-mapping checks.
 */
public record ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                                Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                                Set<String> eventSourceIds, Set<String> secretNames,
                                Map<String, Map<String, Boolean>> actionTypeInputs) {

    /** @return a context that disables all existence checks (for unit tests without a database) */
    public static ValidationContext unchecked() {
        return new ValidationContext(null, null, null, null, null, null, null, null);
    }
}
