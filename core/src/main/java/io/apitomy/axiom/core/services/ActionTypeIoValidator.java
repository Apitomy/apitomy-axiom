package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ActionTypeField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a runtime value map against a list of declared action-type fields.
 *
 * <p>Used at execution time to enforce an action type's declared inputs (before the
 * action runs) and outputs (after it completes). Required fields must be present and
 * non-null; present values are best-effort type-checked; extra keys are permitted.</p>
 */
public final class ActionTypeIoValidator {

    private ActionTypeIoValidator() {
    }

    /**
     * Validates the given values against the declared fields.
     *
     * @param declared the declared field contract (null or empty means "no contract" → always valid)
     * @param values   the runtime values keyed by field name (null treated as empty)
     * @return a list of human-readable error messages; empty when valid
     */
    public static List<String> validate(List<ActionTypeField> declared, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        if (declared == null || declared.isEmpty()) {
            return errors;
        }
        Map<String, Object> safeValues = values != null ? values : Map.of();
        for (ActionTypeField field : declared) {
            boolean present = safeValues.containsKey(field.name) && safeValues.get(field.name) != null;
            if (!present) {
                if (field.required) {
                    errors.add("Missing required field '" + field.name + "'.");
                }
                continue;
            }
            Object value = safeValues.get(field.name);
            if (!matchesType(field.type, value)) {
                errors.add("Field '" + field.name + "' expected type " + field.type
                        + " but got " + value.getClass().getSimpleName() + ".");
            }
        }
        return errors;
    }

    private static boolean matchesType(String type, Object value) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }
}
