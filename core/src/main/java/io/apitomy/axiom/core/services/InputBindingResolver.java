package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ActionTypeField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Shared input-binding and prompt/script template resolution logic.
 *
 * <p>Both the agent-task path ({@code TaskExecutionService}) and the script path
 * ({@code ScriptExecutionService}) resolve named workflow inputs into
 * {@code {{inputs.NAME}}} placeholders and (for agent tasks) append an output
 * contract describing the declared outputs. Centralising that logic here keeps
 * the two execution paths in lockstep and gives input-binding a single
 * definition with a single set of tests.</p>
 */
public final class InputBindingResolver {

    private static final Logger LOG = Logger.getLogger(InputBindingResolver.class);

    private InputBindingResolver() {
    }

    /**
     * Parses a workflow task's resolved inputs (a JSON object) into a map.
     *
     * @param inputJson the raw resolved-inputs JSON, or {@code null}/blank
     * @param mapper    the JSON mapper to use
     * @return the parsed map, or an empty map when the input is blank or not a JSON object
     */
    public static Map<String, Object> parseInputs(String inputJson, ObjectMapper mapper) {
        if (inputJson == null || inputJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(inputJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Substitutes {@code {{inputs.NAME}}} placeholders in the given template with the
     * corresponding rendered input values.
     *
     * @param template the template to resolve, or {@code null}
     * @param inputs   the named inputs keyed by placeholder name (null/empty means "no binding")
     * @param mapper   the JSON mapper used to render non-string values
     * @return the template with {@code {{inputs.*}}} placeholders substituted
     */
    public static String bindInputs(String template, Map<String, Object> inputs, ObjectMapper mapper) {
        if (template == null || inputs == null || inputs.isEmpty()) {
            return template;
        }
        String resolved = template;
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            resolved = resolved.replace("{{inputs." + e.getKey() + "}}", renderValue(e.getValue(), mapper));
        }
        return resolved;
    }

    /**
     * Renders a single input value to its placeholder string form: {@code null} becomes an
     * empty string, strings are used verbatim, and everything else is serialized to JSON
     * (falling back to {@code String.valueOf} if serialization fails).
     *
     * @param value  the value to render
     * @param mapper the JSON mapper used for non-string values
     * @return the rendered string
     */
    public static String renderValue(Object value, ObjectMapper mapper) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            LOG.debug("Failed to serialize input value to JSON, using toString()");
            return String.valueOf(value);
        }
    }

    /**
     * Builds the instruction block appended to an agent prompt telling it to emit a
     * JSON object keyed by the declared output names as its final result.
     *
     * @param outputs the declared output fields
     * @return the output-contract instruction block
     */
    public static String buildOutputContract(List<ActionTypeField> outputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Required output\n");
        sb.append("When finished, output ONLY a single JSON object (no prose, no code fences) ");
        sb.append("with exactly these keys:\n");
        for (ActionTypeField f : outputs) {
            sb.append("- \"").append(f.name).append("\" (").append(f.type).append(")");
            if (f.required) {
                sb.append(" [required]");
            }
            if (f.description != null && !f.description.isBlank()) {
                sb.append(" — ").append(f.description);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
