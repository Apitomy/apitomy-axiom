package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a structured response against an output schema definition.
 */
@ApplicationScoped
public class InboxResponseValidator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validates a response map against the output schema JSON.
     *
     * @param outputSchemaJson the output schema definition (JSON string)
     * @param response the user's response data
     * @return list of validation error messages (empty if valid)
     */
    public List<String> validate(String outputSchemaJson, Map<String, Object> response) {
        if (outputSchemaJson == null || outputSchemaJson.isBlank()) {
            return List.of();
        }

        List<String> errors = new ArrayList<>();
        try {
            JsonNode schema = objectMapper.readTree(outputSchemaJson);
            JsonNode fields = schema.path("fields");

            if (!fields.isArray()) {
                return List.of();
            }

            for (JsonNode field : fields) {
                String name = field.path("name").asText();
                boolean required = field.path("required").asBoolean(false);
                String type = field.path("type").asText("text");

                Object value = response.get(name);

                if (required && isBlank(value)) {
                    errors.add("Field '" + name + "' is required");
                    continue;
                }

                if (value != null) {
                    validateFieldType(name, type, value, field, errors);
                }
            }
        } catch (Exception e) {
            errors.add("Invalid output schema: " + e.getMessage());
        }

        return errors;
    }

    private void validateFieldType(String name, String type, Object value,
                                   JsonNode field, List<String> errors) {
        switch (type) {
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add("Field '" + name + "' must be a boolean");
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    errors.add("Field '" + name + "' must be a number");
                }
            }
            case "select" -> {
                JsonNode options = field.path("options");
                if (options.isArray()) {
                    List<String> allowedValues = new ArrayList<>();
                    for (JsonNode opt : options) {
                        allowedValues.add(opt.path("value").asText());
                    }
                    if (!allowedValues.contains(value.toString())) {
                        errors.add("Field '" + name + "' must be one of: " + allowedValues);
                    }
                }
            }
            default -> {
                // text and textarea accept any string value
            }
        }
    }

    private boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }
}
