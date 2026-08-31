package io.apitomy.axiom.core.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a runtime value map against a list of declared action-type fields.
 *
 * <p>Used at execution time to enforce an action type's declared inputs (before the
 * action runs) and outputs (after it completes). Required fields must be present and
 * non-null; present values are best-effort coerced to their declared type before being
 * validated; extra keys are permitted.</p>
 */
public final class ActionTypeIoValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    /**
     * Determines whether the given value satisfies the declared type, applying best-effort
     * coercion per the workflow I/O validation design. A value is accepted when it is already
     * of the declared type <em>or</em> can be losslessly coerced into it.
     */
    private static boolean matchesType(String type, Object value) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case "string" -> matchesString(value);
            case "number" -> matchesNumber(value);
            case "boolean" -> matchesBoolean(value);
            case "object" -> matchesObject(value);
            default -> true;
        };
    }

    private static boolean matchesString(Object value) {
        // Any scalar value can be losslessly rendered as a string via toString().
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static boolean matchesNumber(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            try {
                Double.parseDouble(trimmed);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean matchesBoolean(Object value) {
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            return "true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed);
        }
        return false;
    }

    private static boolean matchesObject(Object value) {
        if (value instanceof Map) {
            return true;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            try {
                return OBJECT_MAPPER.readValue(trimmed, Map.class) != null;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
