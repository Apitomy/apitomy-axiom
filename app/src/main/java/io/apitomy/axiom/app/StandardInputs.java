package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.flow.model.NodeConfig;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The standard workflow inputs: the only inputs a Start node may declare, and the only values Axiom puts into a
 * new run's context. A run receives exactly the standard inputs its Start node declares.
 */
public final class StandardInputs {

    /** The subject the run is about. */
    public static final String SUBJECT = "subject";
    /** The stream event that started the run. */
    public static final String EVENT = "event";

    /**
     * A standard input.
     *
     * @param name         the input name
     * @param type         the Start input type
     * @param description  what the value is
     * @param availability when Axiom can provide it
     */
    public record StandardInput(String name, String type, String description, String availability) {
    }

    /** The fields of the {@code subject} input, in order. */
    public static final List<String> SUBJECT_FIELDS = List.of("id", "name", "type", "ref", "refSource", "repository");

    /** All standard inputs. */
    public static final List<StandardInput> ALL = List.of(
            new StandardInput(SUBJECT, "object",
                    "The subject the run is about: " + String.join(", ", SUBJECT_FIELDS)
                            + " (id is a number; the others are strings or null)",
                    "always"),
            new StandardInput(EVENT, "object",
                    "The event that started the run: id, type, source, eventSourceId, ref, timestamp, actor, payload",
                    "only when the run was started from a stream event"));

    /**
     * The result of resolving a workflow's declared inputs.
     *
     * @param context         the initial run context (declared and available inputs only)
     * @param missingRequired declared required inputs that are not available, in declaration order
     */
    public record Resolution(Map<String, Object> context, List<String> missingRequired) {
    }

    private StandardInputs() {
    }

    /**
     * Finds a standard input by name.
     *
     * @param name the input name
     * @return the input, or empty when the name is not a standard input
     */
    public static Optional<StandardInput> find(String name) {
        return ALL.stream().filter(i -> i.name().equals(name)).findFirst();
    }

    /**
     * Returns the standard input names.
     *
     * @return the names
     */
    public static Set<String> names() {
        return ALL.stream().map(StandardInput::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Builds the {@code subject} input value: exactly {@link #SUBJECT_FIELDS}, blank strings as null.
     *
     * @param subject the run's subject
     * @return the value (insertion-ordered, may contain nulls)
     */
    public static Map<String, Object> subjectValue(SubjectEntity subject) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", subject.id);
        value.put("name", blankToNull(subject.name));
        value.put("type", blankToNull(subject.type));
        value.put("ref", blankToNull(subject.ref));
        value.put("refSource", blankToNull(subject.refSource));
        value.put("repository", blankToNull(subject.repository));
        return value;
    }

    /**
     * Builds the {@code event} input value: the event envelope, with actor and payload parsed as JSON.
     *
     * @param event  the stream event
     * @param mapper the JSON mapper
     * @return the value
     */
    public static ObjectNode eventValue(StreamEventEntity event, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", event.id.toString());
        node.put("type", event.type);
        node.put("source", event.source);
        node.put("eventSourceId", event.eventSourceId);
        node.put("ref", event.ref);
        node.put("timestamp", event.timestamp != null ? event.timestamp.toString() : null);
        try {
            if (event.actor != null) {
                node.set("actor", mapper.readTree(event.actor));
            }
            if (event.payload != null) {
                node.set("payload", mapper.readTree(event.payload));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON in stream event " + event.id + ": " + e.getMessage(), e);
        }
        return node;
    }

    /**
     * Resolves the Start node's declared inputs for a new run. Undeclared and unknown names are ignored here
     * (publish rejects unknown names).
     *
     * @param workflow the workflow being started
     * @param subject  the run's subject
     * @param event    the {@code event} value when started from a stream event, else null
     * @return the context to start with and any required inputs that are not available
     */
    public static Resolution resolve(Workflow workflow, SubjectEntity subject, JsonNode event) {
        Map<String, Object> context = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (NodeConfig.Field field : startInputs(workflow)) {
            String name = field.name();
            Object value = switch (name == null ? "" : name) {
                case SUBJECT -> subjectValue(subject);
                case EVENT -> event;
                default -> null;
            };
            if (find(name).isEmpty()) {
                continue;
            }
            if (value != null) {
                context.put(name, value);
            } else if (field.required()) {
                missing.add(name);
            }
        }
        return new Resolution(context, missing);
    }

    private static List<NodeConfig.Field> startInputs(Workflow workflow) {
        if (workflow.nodes() == null) {
            return List.of();
        }
        for (WorkflowNode node : workflow.nodes()) {
            if (node.type() == NodeType.START && node.typedConfig() instanceof NodeConfig.Start start) {
                return start.inputs();
            }
        }
        return List.of();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
