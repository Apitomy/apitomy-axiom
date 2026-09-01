package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.HumanContextDetail;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.axiom.api.beans.OutputSchemaFieldOption;
import io.apitomy.flow.model.HumanTaskInfo;
import io.apitomy.flow.model.OutputDefinition;
import io.apitomy.flow.model.OutputOption;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Apitomy Flow human-task node data ({@link HumanTaskInfo}) onto Axiom inbox beans and coerces a
 * human's submitted answers back to the node's declared semantic types before they are merged into the
 * workflow context.
 */
public final class WorkflowHumanTaskMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TITLE = "Human Task";

    private WorkflowHumanTaskMapper() {
    }

    /**
     * Builds the human-facing context (title, description, display-only details) for an inbox task from
     * a parked human-task node.
     *
     * @param hti the engine's human-task introspection
     * @return a populated {@link HumanContext}
     */
    public static HumanContext toHumanContext(HumanTaskInfo hti) {
        HumanContext ctx = new HumanContext();
        String name = hti.nodeName();
        ctx.setTitle(name != null && !name.isBlank() ? name : DEFAULT_TITLE);
        ctx.setDescription(hti.description());

        if (hti.inputs() != null && !hti.inputs().isEmpty()) {
            List<HumanContextDetail> details = new ArrayList<>();
            for (Map.Entry<String, Object> entry : hti.inputs().entrySet()) {
                HumanContextDetail detail = new HumanContextDetail();
                detail.setLabel(entry.getKey());
                detail.setValue(entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                details.add(detail);
            }
            ctx.setDetails(details);
        }
        return ctx;
    }

    /**
     * Builds the completion form schema from a human-task node's declared outputs.
     *
     * @param outputs the node's output definitions (may be null/empty)
     * @return an {@link OutputSchema}, or {@code null} when there are no outputs (free-form response)
     */
    public static OutputSchema toOutputSchema(List<OutputDefinition> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        List<OutputSchemaField> fields = new ArrayList<>();
        for (OutputDefinition output : outputs) {
            OutputSchemaField field = new OutputSchemaField();
            field.setName(output.name());
            field.setType(widgetToFieldType(output.widget()));
            field.setLabel(output.label() != null ? output.label() : output.name());
            field.setDescription(output.description());
            field.setRequired(output.required());
            field.setDefaultValue(output.defaultValue());
            if (output.options() != null && !output.options().isEmpty()) {
                List<OutputSchemaFieldOption> options = new ArrayList<>();
                for (OutputOption option : output.options()) {
                    OutputSchemaFieldOption o = new OutputSchemaFieldOption();
                    o.setLabel(option.label());
                    o.setValue(option.value());
                    options.add(o);
                }
                field.setOptions(options);
            }
            fields.add(field);
        }
        OutputSchema schema = new OutputSchema();
        schema.setFields(fields);
        return schema;
    }

    /**
     * Coerces a human's submitted answers to the semantic types declared by the node's outputs, so that
     * downstream edge conditions and nodes see real numbers/booleans/objects rather than strings.
     * Missing answers are omitted; undeclared keys pass through unchanged.
     *
     * @param outputs the node's output definitions
     * @param answers the submitted answer map (field name to value)
     * @return a new map with values coerced to declared types
     */
    public static Map<String, Object> coerceAnswers(List<OutputDefinition> outputs,
            Map<String, Object> answers) {
        Map<String, Object> result = new LinkedHashMap<>(answers != null ? answers : Map.of());
        if (outputs == null) {
            return result;
        }
        for (OutputDefinition output : outputs) {
            String name = output.name();
            if (answers == null || !answers.containsKey(name) || answers.get(name) == null) {
                result.remove(name);
                continue;
            }
            result.put(name, coerce(output.type(), answers.get(name)));
        }
        return result;
    }

    private static Object coerce(String type, Object value) {
        String semanticType = type != null ? type : "string";
        return switch (semanticType) {
            case "number" -> coerceNumber(value);
            case "boolean" -> value instanceof Boolean b ? b
                    : value instanceof String s ? Boolean.parseBoolean(s) : value;
            case "object" -> coerceObject(value);
            default -> String.valueOf(value);
        };
    }

    private static Object coerceNumber(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof String s) {
            try {
                if (s.contains(".") || s.contains("e") || s.contains("E")) {
                    return Double.parseDouble(s);
                }
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    private static Object coerceObject(Object value) {
        if (value instanceof Map || value instanceof List) {
            return value;
        }
        if (value instanceof String s) {
            try {
                return MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                return value;
            }
        }
        return value;
    }

    private static OutputSchemaField.Type widgetToFieldType(String widget) {
        String w = widget != null ? widget : "text";
        return switch (w) {
            case "checkbox", "boolean" -> OutputSchemaField.Type.BOOLEAN;
            case "textarea" -> OutputSchemaField.Type.TEXTAREA;
            case "select" -> OutputSchemaField.Type.SELECT;
            case "number" -> OutputSchemaField.Type.NUMBER;
            default -> OutputSchemaField.Type.TEXT;
        };
    }
}
